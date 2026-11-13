package com.pennywiseai.tracker.data.firefly

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.pennywiseai.tracker.data.database.PennyWiseDatabase
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-shot consumer of the migration-side staging table created by the fork-era
 * (schema 47-52) -> 67 migrations (see
 * [com.pennywiseai.tracker.data.database.PennyWiseDatabase.rebuildTableAt67],
 * commit 99bf3cd6).
 *
 * Those migrations copy the legacy per-transaction `firefly_*` sync state into
 * `firefly_sync_state_staging` in the MAIN database before dropping the legacy
 * columns. This class reads that table and upserts every staged row into the
 * Firefly sidecar ([FireflySyncDao.markSynced]) so previously-synced
 * transactions are recognized as synced instead of being re-created on Firefly
 * after the upgrade, then drops the staging table.
 *
 * ## No-race contract (checked against the real androidx.room 2.8.4 artifacts)
 *
 *  - This class never constructs a `SupportSQLiteOpenHelper` and never opens
 *    the main DB file itself. Room owns the file, and only Room runs
 *    `onCreate`/migrations.
 *  - The FIRST touch of the main database is always Room's own public read API,
 *    `RoomDatabase.query(sql, bindArgs)`. That runs on Room's connection
 *    manager, so if the database is not open yet it is opened (and migrated) by
 *    Room, under Room's own lock — never by this class.
 *  - Only after that probe, and only when `RoomDatabase.isOpen` confirms Room
 *    opened it, does this class borrow Room's *already-created* helper
 *    (`RoomDatabase.openHelper` — the same instance Room itself was built with)
 *    to reach `writableDatabase` for the single DDL statement. Calling
 *    `getWritableDatabase()` on an already-open helper returns Room's existing
 *    connection; it does not open a second copy of the file, and it cannot race
 *    Room's migration because Room's migration already ran by definition.
 *  - If the helper is unavailable or the database is somehow closed, the run is
 *    deferred (-1) and the staging table is left in place for a later attempt
 *    rather than forcing an open from this class.
 *  - Why not `openHelper.wrap()`? Room 2.8.4 removed the old
 *    `androidx.room.sqlite.wrapper` package entirely (its `room-runtime-android`
 *    artifact contains no `androidx.room.sqlite.*` class), and its 2.8
 *    replacement (`androidx.room.support.RoomSupportSQLite`) ships in a separate
 *    `room-sqlite-wrapper` artifact that `room-runtime` 2.8.4 does not depend
 *    on. Using it here would mean adding a dependency, so the two steps above
 *    (Room's `query` for reads, Room's own open helper for one DDL) are the
 *    dependency-free way to stay on Room's connection.
 *
 * Every attempt is serialized behind [mutex] and at most one copy phase runs
 * per process; the staging table's own drop is what makes it one-shot across
 * processes.
 *
 * Residual risk (documented, not hidden): the `DROP TABLE` runs outside Room's
 * transaction bookkeeping. With WAL, a contended concurrent write can surface as
 * a busy `SQLiteException`; that lands in the failure path below, keeps the
 * staging table, and the next resync retries.
 *
 * Failure contract: every failure path returns -1 after logging and keeps the
 * staging table for a later attempt. Callers ([FireflySyncEngine.resyncAll])
 * treat the result as advisory — a backfill error never breaks sync.
 */
@Singleton
class FireflySyncStagingBackfill @Inject constructor(
    private val mainDatabase: PennyWiseDatabase,
    private val fireflySyncDao: FireflySyncDao,
    private val diagnosticLogger: DiagnosticLogger,
) {

    private companion object {
        const val TAG = "FireflySyncStagingBackfill"
        const val STAGING_TABLE = "firefly_sync_state_staging"
        const val LOG_DIRECTION = "backfill"
        const val LOG_STATUS_OK = "ok"
        const val LOG_STATUS_ERROR = "error"
        val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val spaceFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    /** Serializes attempts; also keeps the copy phase from overlapping itself. */
    private val mutex = Mutex()

    /** Set only after a run finished without failure (success or nothing-to-do). */
    private val completed = AtomicBoolean(false)

    /**
     * Copies `firefly_sync_state_staging` (main DB) into the sidecar and drops
     * it. At most once per install (the table is dropped on success) and at
     * most once per process (success flag); a no-op when the table does not
     * exist, which is the case for fresh installs and post-firefly installs.
     *
     * @return number of staged rows copied into the sidecar, 0 when there was
     *   nothing to do, or -1 when the run was deferred/failed (table kept for
     *   a later resync pass).
     */
    suspend fun runIfNeeded(): Int = mutex.withLock {
        if (completed.get()) return@withLock 0
        try {
            if (!stagingTableExistsInMain()) {
                completed.set(true)
                diagnosticLogger.d(TAG, "Staging backfill: no $STAGING_TABLE, nothing to do")
                return@withLock 0
            }

            val mainDb = roomWriterConnection()
                ?: run {
                    // Room owns the connection; do not force an open here.
                    diagnosticLogger.w(
                        TAG,
                        "Staging backfill deferred: main database is not open through Room yet"
                    )
                    return@withLock -1
                }

            val copied = copyStagingTableToSidecar(mainDb, fireflySyncDao)
            fireflySyncDao.insertLog(
                FireflyLogEntity(
                    direction = LOG_DIRECTION,
                    status = LOG_STATUS_OK,
                    message = "Backfilled $copied staged Firefly sync state row(s) from the fork-era " +
                        "migration into the sidecar and dropped $STAGING_TABLE",
                    at = LocalDateTime.now()
                )
            )
            diagnosticLogger.i(TAG, "Staging backfill: copied $copied row(s), dropped $STAGING_TABLE")
            completed.set(true)
            copied
        } catch (e: Exception) {
            // Failure-tolerant by contract: log and let sync proceed; the
            // staging table survives so a later resync attempt can retry.
            diagnosticLogger.e(TAG, "Staging backfill failed; leaving $STAGING_TABLE in place", e)
            runCatching {
                fireflySyncDao.insertLog(
                    FireflyLogEntity(
                        direction = LOG_DIRECTION,
                        status = LOG_STATUS_ERROR,
                        message = "Staging backfill failed: ${e.message ?: e.javaClass.simpleName}",
                        at = LocalDateTime.now()
                    )
                )
            }
            -1
        }
    }

    /**
     * Reads every staged row, upserts it into the sidecar state table, then
     * drops the staging table so the whole operation is one-shot.
     *
     * `synced_at` is parsed defensively (Room's [com.pennywiseai.tracker.data.database.converter.Converters]
     * writes ISO, older fork rows may carry the space form); a missing or
     * unparseable value falls back to now.
     *
     * The staged value is stored as `fireflyUpdatedAt` (the closest thing the
     * staging table recorded: when we last reconciled with Firefly) while
     * `syncedAt` is stamped with the backfill time. That combination makes the
     * client treat the row as already synced and unchanged — so the first pass
     * after an upgrade refreshes metadata instead of creating a twin — while a
     * remote that really moved on since the staged sync still wins the PULL.
     * `source`/`destination` were never recorded in staging, so they stay null
     * and [FireflySyncDao.markSynced]'s COALESCE leaves them unset.
     *
     * Internal seam: unit tests drive it with any [SupportSQLiteDatabase].
     */
    internal suspend fun copyStagingTableToSidecar(
        mainDb: SupportSQLiteDatabase,
        sidecarDao: FireflySyncDao,
    ): Int {
        if (!tableExists(mainDb, STAGING_TABLE)) return 0
        var copied = 0
        val cursor = mainDb.query(
            "SELECT `transaction_id`, `external_id`, `remote_id`, `synced_at` FROM `$STAGING_TABLE` " +
                "ORDER BY `transaction_id`"
        )
        try {
            while (cursor.moveToNext()) {
                val txId = cursor.getLong(0)
                sidecarDao.markSynced(
                    txId = txId,
                    externalId = cursor.getString(1),
                    remoteTxId = cursor.getString(2),
                    updatedAt = parseSyncedAt(cursor.getString(3)),
                    source = null,
                    destination = null,
                    syncedAt = LocalDateTime.now()
                )
                copied++
            }
        } finally {
            cursor.close()
        }
        mainDb.execSQL("DROP TABLE IF EXISTS `$STAGING_TABLE`")
        return copied
    }

    /**
     * Existence probe through Room's own public read API — this is the first
     * touch of the main database, so Room performs any open/migration itself.
     */
    private fun stagingTableExistsInMain(): Boolean =
        mainDatabase.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf<Any?>(STAGING_TABLE)
        ).use { cursor -> cursor.moveToFirst() }

    /**
     * Room's already-open writer connection, or null when Room has not opened
     * the database yet (defer, never open it from here).
     */
    private fun roomWriterConnection(): SupportSQLiteDatabase? {
        if (!mainDatabase.isOpen) return null
        val helper: SupportSQLiteOpenHelper? = mainDatabase.openHelper
        return helper?.writableDatabase
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1", arrayOf(table))
            .use { cursor -> cursor.moveToFirst() }

    /** Parses staging `synced_at` text, tolerating both Room datetime formats. */
    private fun parseSyncedAt(raw: String?): LocalDateTime =
        raw?.let { value ->
            runCatching { LocalDateTime.parse(value, isoFormatter) }
                .recoverCatching { LocalDateTime.parse(value, spaceFormatter) }
                .getOrNull()
        } ?: LocalDateTime.now()
}
