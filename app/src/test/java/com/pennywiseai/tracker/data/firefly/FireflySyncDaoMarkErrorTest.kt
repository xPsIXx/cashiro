package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * H3 regression: `markError` used to be a plain `UPDATE`, so it silently
 * matched zero rows for the transactions that actually need error bookkeeping
 * (the ones that never synced: failed create, inconclusive search).
 * `statesWithError()`/`errorCount()` therefore stayed empty forever and
 * `FireflyRetryWorker` was a no-op.
 *
 * This pins the upsert semantics on the JVM through the real sidecar database.
 * No schema change is involved: `lastError` already exists in version 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflySyncDaoMarkErrorTest {

    private lateinit var db: FireflySyncDatabase
    private lateinit var dao: FireflySyncDao

    @Before
    fun setUp() {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, FireflySyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.fireflySyncDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun markErrorCreatesStateRowForANeverSyncedTransaction() = runBlocking {
        // The old UPDATE matched nothing here.
        dao.markError(txId = 101L, error = "create failed: HTTP 429")

        val state = dao.stateFor(101L).first()
        assertNotNull("markError must create the sidecar row for an unsynced tx", state)
        assertEquals(101L, state!!.localTransactionId)
        assertNull("an error is not a sync", state.syncedAt)
        assertNull(state.fireflyTransactionId)
        assertEquals("create failed: HTTP 429", state.lastError)

        // The pipeline consumers now actually see something.
        val errored = dao.statesWithError().first()
        assertEquals(1, errored.size)
        assertEquals(101L, errored.single().localTransactionId)
        assertEquals(1, dao.errorCount().first())
    }

    @Test
    fun markErrorPreservesPriorSyncDataSoRetryUpdatesInsteadOfDuplicating() = runBlocking {
        dao.markSynced(
            txId = 202L,
            externalId = "pennywise-hash202",
            remoteTxId = "remote-202",
            updatedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
            source = "Source Bank",
            destination = "Dest Bank",
            syncedAt = LocalDateTime.of(2026, 1, 2, 0, 0)
        )

        dao.markError(txId = 202L, error = "update failed: server rejected update for remote-202")

        val state = dao.stateFor(202L).first()!!
        assertEquals("update failed: server rejected update for remote-202", state.lastError)
        // The remote identity survives the failure: a retry PUTs to the same
        // journal rather than creating a twin.
        assertEquals("remote-202", state.fireflyTransactionId)
        assertEquals("pennywise-hash202", state.externalId)
        assertEquals("Source Bank", state.fireflySourceName)
        assertNotNull("syncedAt must not be cleared by an error", state.syncedAt)
        assertEquals(1, dao.statesWithError().first().size)
    }

    @Test
    fun markSyncedClearsTheErrorAndLeavesOneRowPerTransaction() = runBlocking {
        dao.markError(txId = 303L, error = "search inconclusive: HTTP 429 retryAfter=30")
        assertEquals(1, dao.errorCount().first())

        dao.markSynced(
            txId = 303L,
            externalId = "pennywise-hash303",
            remoteTxId = "remote-303",
            updatedAt = LocalDateTime.of(2026, 3, 1, 0, 0),
            syncedAt = LocalDateTime.of(2026, 3, 2, 0, 0)
        )

        val state = dao.stateFor(303L).first()!!
        assertNull("a successful sync must clear lastError", state.lastError)
        assertNotNull(state.syncedAt)
        assertEquals(0, dao.errorCount().first())
        assertTrue(dao.statesWithError().first().isEmpty())

        // Repeated failures must not fan out into extra rows.
        repeat(3) { dao.markError(txId = 303L, error = "search inconclusive: HTTP 503") }
        val rows = db.query("SELECT COUNT(*) FROM firefly_transaction_state", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals(1, rows)
        assertEquals("search inconclusive: HTTP 503", dao.stateFor(303L).first()!!.lastError)
    }
}
