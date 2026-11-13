package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pennywiseai.tracker.data.database.PennyWiseDatabase
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fork-era (schemas 47-52) -> 67 migrations stage legacy `firefly_*` state
 * into `firefly_sync_state_staging` in the MAIN database (see
 * [PennyWiseDatabase.rebuildTableAt67]) and nothing consumed it: already-synced
 * transactions would have been re-created on Firefly after an upgrade.
 *
 * Drives both the internal copy/drop seam and the production `runIfNeeded()`
 * entry point against a real in-memory main database (Room's own
 * `SupportSQLiteDatabase`) and a real sidecar [FireflySyncDatabase].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflySyncStagingBackfillTest {

    private companion object {
        /** Mirror of the (class-private) staging table name in [FireflySyncStagingBackfill]. */
        const val STAGING_TABLE = "firefly_sync_state_staging"
    }

    private lateinit var context: Context
    private lateinit var mainDb: PennyWiseDatabase
    private lateinit var sidecarDb: FireflySyncDatabase
    private lateinit var sidecarDao: FireflySyncDao
    private lateinit var backfill: FireflySyncStagingBackfill

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        mainDb = Room.inMemoryDatabaseBuilder(context, PennyWiseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sidecarDb = Room.inMemoryDatabaseBuilder(context, FireflySyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sidecarDao = sidecarDb.fireflySyncDao()
        backfill = FireflySyncStagingBackfill(mainDb, sidecarDao, DiagnosticLogger(context))
    }

    @After
    fun tearDown() {
        runCatching { sidecarDb.close() }
        runCatching { mainDb.close() }
    }

    /** Room's own open connection for the main DB (same object production uses). */
    private fun mainConnection(): SupportSQLiteDatabase = mainDb.openHelper.writableDatabase

    private fun createStagingTable(db: SupportSQLiteDatabase = mainConnection()) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `firefly_sync_state_staging` (" +
                "`transaction_id` INTEGER NOT NULL, `external_id` TEXT, `remote_id` TEXT, " +
                "`synced_at` TEXT, PRIMARY KEY(`transaction_id`))"
        )
    }

    private fun insertStaged(
        txId: Long,
        externalId: String?,
        remoteId: String?,
        syncedAt: String?,
        db: SupportSQLiteDatabase = mainConnection()
    ) {
        db.execSQL(
            "INSERT INTO `firefly_sync_state_staging` " +
                "(`transaction_id`, `external_id`, `remote_id`, `synced_at`) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(txId, externalId, remoteId, syncedAt)
        )
    }

    private fun tableExists(table: String, db: SupportSQLiteDatabase = mainConnection()): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1", arrayOf<Any?>(table))
            .use { it.moveToFirst() }

    private fun state(txId: Long) = runBlocking { sidecarDao.stateFor(txId).first() }

    @Test
    fun copySeamMovesStagedRowsIntoSidecarAndDropsTheTable() {
        createStagingTable()
        insertStaged(11L, "pennywise-h11", "ff-11", "2026-01-05T09:30:00") // ISO (Room's converter format)
        insertStaged(12L, "pennywise-h12", "ff-12", "2026-01-06 10:15:00") // legacy space format
        insertStaged(13L, null, null, null) // unparseable/absent -> fallback

        val copied = runBlocking { backfill.copyStagingTableToSidecar(mainConnection(), sidecarDao) }
        assertEquals("all three staged rows must be copied", 3, copied)

        val first = state(11L)
        assertNotNull("staged row must create sidecar state", first)
        assertEquals("pennywise-h11", first!!.externalId)
        assertEquals("ff-11", first.fireflyTransactionId)
        assertEquals("ISO staged date must parse", LocalDateTime.of(2026, 1, 5, 9, 30), first.fireflyUpdatedAt)
        assertNotNull("row must be marked synced so resync skips it", first.syncedAt)
        assertNull(first.lastError)
        assertNull(first.fireflySourceName) // COALESCE leaves unmapped names unset
        assertNull(first.fireflyDestinationName)

        val second = state(12L)
        assertEquals("space-form staged date must parse", LocalDateTime.of(2026, 1, 6, 10, 15), second!!.fireflyUpdatedAt)
        assertEquals("ff-12", second.fireflyTransactionId)

        val third = state(13L)
        assertNotNull("row without staged metadata must still land in the sidecar", third)
        assertNull(third!!.externalId)
        assertNotNull("unparseable staged date must fall back to a usable timestamp", third.fireflyUpdatedAt)

        assertFalse("staging table must be dropped after a successful copy", tableExists(STAGING_TABLE))

        // One-shot: a second pass has nothing left to do (and must not crash).
        val again = runBlocking { backfill.copyStagingTableToSidecar(mainConnection(), sidecarDao) }
        assertEquals(0, again)
    }

    @Test
    fun runIfNeededBackfillsThroughRoomAndIsIdempotent() {
        createStagingTable()
        insertStaged(21L, "pennywise-h21", "ff-21", "2026-02-01T08:00:00")
        insertStaged(22L, "pennywise-h22", "ff-22", "2026-02-02T08:00:00")

        val copied = runBlocking { backfill.runIfNeeded() }
        assertEquals(2, copied)
        assertEquals("ff-21", state(21L)!!.fireflyTransactionId)
        assertEquals("ff-22", state(22L)!!.fireflyTransactionId)
        assertFalse("migration staging table must be consumed", tableExists(STAGING_TABLE))

        // Advisory + one-shot: a repeat run is a no-op, not an error.
        assertEquals(0, runBlocking { backfill.runIfNeeded() })

        val logMessages = sidecarDb
            .query("SELECT direction, status FROM firefly_log WHERE direction = 'backfill'", null)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add("${cursor.getString(0)}/${cursor.getString(1)}")
                }
            }
        assertEquals(listOf("backfill/ok"), logMessages)
    }

    @Test
    fun runIfNeededIsANoOpWhenNoStagingTableExists() {
        assertEquals("fresh installs have nothing to backfill", 0, runBlocking { backfill.runIfNeeded() })
        assertEquals(0, runBlocking { backfill.runIfNeeded() })
        val count = sidecarDb.query("SELECT COUNT(*) FROM firefly_transaction_state", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        assertEquals(0, count)
    }

    @Test
    fun backfillFailureIsAdvisoryAndKeepsTheStagingTable() {
        createStagingTable()
        insertStaged(31L, "pennywise-h31", "ff-31", "2026-03-01T08:00:00")

        val brokenDao = mockk<FireflySyncDao>()
        coEvery { brokenDao.markSynced(any(), any(), any(), any(), any(), any(), any()) } throws
            IOException("simulated sidecar write failure")
        coEvery { brokenDao.insertLog(any()) } throws IOException("simulated log write failure")
        val failing = FireflySyncStagingBackfill(mainDb, brokenDao, DiagnosticLogger(context))

        assertEquals("a failed backfill must not throw into sync", -1, runBlocking { failing.runIfNeeded() })
        assertTrue("a failed run must keep the staging table for a later retry", tableExists(STAGING_TABLE))
        assertEquals("and must not be marked completed", -1, runBlocking { failing.runIfNeeded() })
    }
}
