package com.pennywiseai.tracker.data.firefly

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * DAO for the Cashiro Firefly sidecar database. This is the single place the
 * add-on persists Firefly sync state; the app's own entities/DAOs never know
 * Firefly exists.
 */
@Dao
interface FireflySyncDao {

    // ── Transaction state ────────────────────────────────────────────────────

    @Query(
        """
        INSERT INTO firefly_transaction_state (
            localTransactionId, externalId, fireflyTransactionId, fireflyUpdatedAt,
            fireflySourceName, fireflyDestinationName, syncedAt, lastError
        )
        VALUES (:txId, :externalId, :remoteTxId, :updatedAt, :source, :destination, :syncedAt, NULL)
        ON CONFLICT(localTransactionId) DO UPDATE SET
            externalId = :externalId,
            fireflyTransactionId = :remoteTxId,
            fireflyUpdatedAt = :updatedAt,
            -- COALESCE keeps previously recorded names unless the caller
            -- explicitly supplies new ones (only the pull path does).
            fireflySourceName = COALESCE(:source, fireflySourceName),
            fireflyDestinationName = COALESCE(:destination, fireflyDestinationName),
            syncedAt = :syncedAt,
            lastError = NULL
        """
    )
    suspend fun markSynced(
        txId: Long,
        externalId: String?,
        remoteTxId: String?,
        updatedAt: LocalDateTime?,
        source: String? = null,
        destination: String? = null,
        syncedAt: LocalDateTime = LocalDateTime.now()
    )

    /**
     * Records a sync failure for [txId] (H3).
     *
     * Upsert, not a plain UPDATE: the rows that need error bookkeeping the most
     * are the ones that never synced (failed create, inconclusive search), and
     * the old `UPDATE` silently matched zero rows for them — which is why
     * [statesWithError] was always empty.
     *
     * Only `lastError` is written: `syncedAt`, `fireflyTransactionId` and the
     * rest stay as they were, so a row that synced once and then failed an
     * update keeps its remote id and the retry re-issues the update instead of
     * creating a twin. [markSynced] clears `lastError` on the next success.
     */
    @Query(
        """
        INSERT INTO firefly_transaction_state (
            localTransactionId, externalId, fireflyTransactionId, fireflyUpdatedAt,
            fireflySourceName, fireflyDestinationName, syncedAt, lastError
        )
        VALUES (:txId, NULL, NULL, NULL, NULL, NULL, NULL, :error)
        ON CONFLICT(localTransactionId) DO UPDATE SET lastError = :error
        """
    )
    suspend fun markError(txId: Long, error: String)

    @Query(
        """
        UPDATE firefly_transaction_state
        SET externalId = :newExtId
        WHERE localTransactionId = :txId
        """
    )
    suspend fun updateExternalId(txId: Long, newExtId: String)

    @Query("SELECT * FROM firefly_transaction_state WHERE localTransactionId = :txId")
    fun stateFor(txId: Long): Flow<FireflyTransactionStateEntity?>

    @Query("SELECT * FROM firefly_transaction_state WHERE localTransactionId IN (:ids)")
    suspend fun statesFor(ids: List<Long>): List<FireflyTransactionStateEntity>

    @Query("SELECT * FROM firefly_transaction_state WHERE lastError IS NOT NULL")
    fun statesWithError(): Flow<List<FireflyTransactionStateEntity>>

    @Query("SELECT COUNT(*) FROM firefly_transaction_state WHERE lastError IS NOT NULL")
    fun errorCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactionState(state: FireflyTransactionStateEntity)

    // ── Budget state ────────────────────────────────────────────────────────

    @Query("SELECT * FROM firefly_budget_state WHERE localBudgetId = :localBudgetId")
    suspend fun budgetStateFor(localBudgetId: Long): FireflyBudgetStateEntity?

    @Query("SELECT * FROM firefly_budget_state WHERE localBudgetId IN (:ids)")
    suspend fun budgetStatesFor(ids: List<Long>): List<FireflyBudgetStateEntity>

    @Query("SELECT * FROM firefly_budget_state WHERE fireflyBudgetId = :fireflyId LIMIT 1")
    suspend fun budgetByFireflyId(fireflyId: String): FireflyBudgetStateEntity?

    @Query(
        """
        UPDATE firefly_budget_state
        SET fireflyBudgetId = :fireflyBudgetId,
            fireflyLimitId = :fireflyLimitId,
            syncedAt = :syncedAt
        WHERE localBudgetId = :localBudgetId
        """
    )
    suspend fun updateBudgetSyncInfo(
        localBudgetId: Long,
        fireflyBudgetId: String?,
        fireflyLimitId: String?,
        syncedAt: LocalDateTime = LocalDateTime.now()
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudgetState(state: FireflyBudgetStateEntity)

    // ── Subscription state ───────────────────────────────────────────────────

    @Query("SELECT * FROM firefly_subscription_state WHERE localSubscriptionId = :localSubscriptionId")
    suspend fun subscriptionStateFor(localSubscriptionId: Long): FireflySubscriptionStateEntity?

    @Query("SELECT * FROM firefly_subscription_state WHERE localSubscriptionId IN (:ids)")
    suspend fun subscriptionStatesFor(ids: List<Long>): List<FireflySubscriptionStateEntity>

    @Query("SELECT * FROM firefly_subscription_state WHERE fireflyRecurringId = :fireflyId LIMIT 1")
    suspend fun subscriptionByFireflyRecurringId(fireflyId: String): FireflySubscriptionStateEntity?

    @Query(
        """
        UPDATE firefly_subscription_state
        SET fireflyRecurringId = :fireflyRecurringId,
            syncedAt = :syncedAt
        WHERE localSubscriptionId = :localSubscriptionId
        """
    )
    suspend fun updateSubscriptionSyncInfo(
        localSubscriptionId: Long,
        fireflyRecurringId: String?,
        syncedAt: LocalDateTime = LocalDateTime.now()
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubscriptionState(state: FireflySubscriptionStateEntity)

    // ── Sync log ─────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertLog(log: FireflyLogEntity): Long

    @Query("SELECT * FROM firefly_log ORDER BY id DESC LIMIT :limit")
    fun recentLogs(limit: Int): Flow<List<FireflyLogEntity>>

    @Query("DELETE FROM firefly_log")
    suspend fun deleteAllLogs()
}

/**
 * M4: one actionable resync candidate row.
 *
 * Produced by [selectSyncCandidates]; describes WHY a `transactions` row is
 * eligible without loading the entity itself. [localTransactionId] is the id
 * the engine fetches through [com.pennywiseai.tracker.data.repository.TransactionRepository].
 */
data class FireflySyncCandidate(
    val localTransactionId: Long,
    /** The transaction's `updated_at` (last local edit), ISO text. */
    val updatedAt: String?,
    /** Sidecar `syncedAt` when the row has ever synced successfully, else null. */
    val syncedAt: String?,
    /** Sidecar `lastError` when the last sync attempt failed, else null. */
    val lastError: String?
)

/**
 * M4 candidate-selector SQL (the eligibility rules, documented rule by rule).
 *
 * A `transactions` row is an actionable resync candidate when ANY of:
 *
 *  1. **Error state** — the sidecar row has `lastError` set. The last attempt
 *     failed (failed create, failed update, inconclusive search), so local and
 *     remote may diverge; it is retried on EVERY run regardless of age.
 *  2. **Claimed but never synced** — a sidecar row exists with
 *     `syncedAt IS NULL` (e.g. `ensureStableExternalIds` pre-persisted an
 *     external id before a push that then died). No age limit: the row is
 *     already known to the add-on and provably unsynced.
 *  3. **Locally edited after the last successful sync** — a sidecar row synced
 *     cleanly but `tx.updated_at > state.synced_at`: the user edited the row
 *     after it was pushed. This is exactly the "possibly stale" comparison the
 *     client already makes against remote `updated_at`, evaluated locally so
 *     stale rows are found WITHOUT a full scan. No age limit: staleness is
 *     defined by the comparison, not by the calendar.
 *  4. **Never-seen rows inside the lookback window** — rows with NO sidecar
 *     row at all (brand-new manual adds whose hook never ran: sync disabled at
 *     save time, a failed hook, a fresh device restore). These cannot be
 *     enumerated from the sidecar, so they are bounded by
 *     `t.updated_at >= :cutoff` to keep the periodic scan off the whole table.
 *     Anything older with no state was either synced before a sidecar wipe or
 *     is outside every window the product promises; the explicit "Sync now"
 *     path (no cutoff) still scans them.
 *
 * Clean rows — synced, no error, `updated_at <= synced_at` — are excluded.
 * Soft-deleted rows never sync. The window compares `updated_at` (edit time),
 * which is a superset of the old `date_time` window: an old row edited
 * yesterday is in scope, which the `dateTime` filter used to miss.
 *
 * String comparison is safe: [com.pennywiseai.tracker.data.database.converter.Converters]
 * and [FireflySyncDao.markSynced] both store `LocalDateTime` as ISO-8601 text
 * (`2026-11-13T15:50:19.123`), which orders lexicographically exactly like
 * chronologically.
 *
 * `ff.` is the ATTACH alias for the sidecar file (see [selectSyncCandidates]);
 * `transactions` is the MAIN database's own table — this SQL runs on the main
 * connection, where it needs no alias. Written for SQLite 3.18 (minSdk 26):
 * plain SELECT/JOIN/WHERE/ORDER/LIMIT only.
 */
internal const val SYNC_CANDIDATE_SQL = """
    SELECT t.id AS candidate_tx_id,
           t.updated_at AS candidate_updated_at,
           ff.syncedAt AS candidate_synced_at,
           ff.lastError AS candidate_last_error
    FROM transactions t
    LEFT JOIN ff.firefly_transaction_state AS ff
        ON ff.localTransactionId = t.id
    WHERE t.is_deleted = 0
      AND (
            ff.localTransactionId IS NULL
            OR ff.lastError IS NOT NULL
            OR ff.syncedAt IS NULL
            OR (ff.syncedAt IS NOT NULL AND t.updated_at > ff.syncedAt)
          )
      AND (
            ff.localTransactionId IS NOT NULL
            OR :cutoff IS NULL
            OR t.updated_at >= :cutoff
          )
    ORDER BY t.updated_at DESC
    LIMIT :limit
"""

/** Returned by [selectSyncCandidates] when `limit` is null: SQLite accepts any 64-bit int. */
internal const val SYNC_CANDIDATE_UNBOUNDED_LIMIT: Long = Long.MAX_VALUE

/**
 * M4: run [SYNC_CANDIDATE_SQL] for one resync pass.
 *
 * Execution model (same dependency-free pattern already proven by
 * [FireflySyncStagingBackfill] against the real androidx.room 2.8.4 artifacts):
 * the query runs on the MAIN database's already-open Room writer connection
 * (`RoomDatabase.openHelper.writableDatabase` — Room owns the connection, this
 * never opens a second copy of the file), which ATTACHes the sidecar database
 * file under the alias `ff` for the duration of the select. Reading the sidecar
 * as an attached database takes a SHARED lock for the (short) SELECT only; the
 * sidecar is not WAL, so it never conflicts with Room's own sidecar
 * connections beyond SQLite's normal lock waits. ATTACH/DETACH are wrapped so a
 * failure can never leave the connection attached.
 *
 * Room's generated `@Query` path cannot express this SQL (the main
 * `transactions` table belongs to a different Room database), which is why the
 * selector lives here as explicit SQL + row mapping instead of an annotated
 * method — the candidate *query* still lives in the add-on's DAO file.
 *
 * @param mainWritableConnection the main database's open writer connection.
 * @param sidecarDbFile the sidecar database file, attached as `ff`.
 * @param lookbackCutoff ISO cutoff for rule 4 (never-seen rows); `null`
 *   disables rule 4's age bound (the explicit "Sync now" full-candidate scan).
 * @param limit caps the batch size; `null` means unbounded.
 */
internal fun selectSyncCandidates(
    mainWritableConnection: SupportSQLiteDatabase,
    sidecarDbFile: java.io.File,
    lookbackCutoff: LocalDateTime?,
    limit: Long?
): List<FireflySyncCandidate> {
    val cutoffIso = lookbackCutoff?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val args = arrayOf<Any?>(
        cutoffIso,
        limit ?: SYNC_CANDIDATE_UNBOUNDED_LIMIT
    )
    mainWritableConnection.execSQL("ATTACH DATABASE '${sidecarDbFile.absolutePath}' AS ff")
    try {
        val out = ArrayList<FireflySyncCandidate>()
        mainWritableConnection.query(SYNC_CANDIDATE_SQL, args).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("candidate_tx_id")
            val updIdx = cursor.getColumnIndexOrThrow("candidate_updated_at")
            val syncedIdx = cursor.getColumnIndexOrThrow("candidate_synced_at")
            val errIdx = cursor.getColumnIndexOrThrow("candidate_last_error")
            while (cursor.moveToNext()) {
                out += FireflySyncCandidate(
                    localTransactionId = cursor.getLong(idIdx),
                    updatedAt = if (cursor.isNull(updIdx)) null else cursor.getString(updIdx),
                    syncedAt = if (cursor.isNull(syncedIdx)) null else cursor.getString(syncedIdx),
                    lastError = if (cursor.isNull(errIdx)) null else cursor.getString(errIdx)
                )
            }
        }
        return out
    } finally {
        runCatching { mainWritableConnection.execSQL("DETACH DATABASE ff") }
    }
}
