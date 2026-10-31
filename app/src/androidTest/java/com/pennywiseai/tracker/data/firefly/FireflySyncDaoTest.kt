package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Sidecar DAO round-trip smoke (verification B): markSynced → stateFor reads
 * it back → markError clears on next markSynced → errorCount updates. Runs as
 * an instrumented test because Room needs a real SQLite driver.
 */
@RunWith(AndroidJUnit4::class)
class FireflySyncDaoTest {

    private lateinit var db: FireflySyncDatabase
    private lateinit var dao: FireflySyncDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
    fun stateRoundTripsThroughMarkSyncedAndMarkError() = runBlocking {
        dao.markSynced(
            txId = 42L,
            externalId = "pennywise-abc",
            remoteTxId = "remote-1",
            updatedAt = LocalDateTime.of(2026, 1, 1, 10, 0),
            source = "Source Bank",
            destination = "Dest Bank"
        )

        val state = dao.stateFor(42L).first()
        assertNotNull(state)
        assertEquals("pennywise-abc", state!!.externalId)
        assertEquals("remote-1", state.fireflyTransactionId)
        assertEquals("Source Bank", state.fireflySourceName)
        assertEquals("Dest Bank", state.fireflyDestinationName)
        assertNull(state.lastError)
        assertEquals(0, dao.errorCount().first())

        // markError shows up in the error view.
        dao.markError(42L, "401 unauthorized")
        assertEquals("401 unauthorized", dao.stateFor(42L).first()!!.lastError)
        assertEquals(1, dao.errorCount().first())
        assertEquals(1, dao.statesWithError().first().size)

        // A successful re-sync clears the error (markSynced semantics).
        dao.markSynced(42L, "pennywise-abc", "remote-1", LocalDateTime.now())
        assertNull(dao.stateFor(42L).first()!!.lastError)
        assertEquals(0, dao.errorCount().first())
    }

    @Test
    fun markSyncedWithoutNamesPreservesExistingNames() = runBlocking {
        dao.markSynced(
            txId = 7L,
            externalId = "e",
            remoteTxId = "r",
            updatedAt = LocalDateTime.now(),
            source = "Originated",
            destination = "Received"
        )
        // Refresh-style update without names must NOT null them out (COALESCE).
        dao.markSynced(txId = 7L, externalId = "e", remoteTxId = "r", updatedAt = LocalDateTime.now())

        val state = dao.stateFor(7L).first()!!
        assertEquals("Originated", state.fireflySourceName)
        assertEquals("Received", state.fireflyDestinationName)
    }

    @Test
    fun budgetAndSubscriptionStateUpsertAndLookup() = runBlocking {
        dao.upsertBudgetState(
            FireflyBudgetStateEntity(
                localBudgetId = 1L,
                fireflyBudgetId = "budget-9",
                fireflyLimitId = "limit-9",
                syncedAt = LocalDateTime.now()
            )
        )
        assertEquals("budget-9", dao.budgetByFireflyId("budget-9")!!.fireflyBudgetId)
        dao.updateBudgetSyncInfo(1L, "budget-10", "limit-10", LocalDateTime.now())
        assertEquals("budget-10", dao.budgetStateFor(1L)!!.fireflyBudgetId)

        dao.upsertSubscriptionState(
            FireflySubscriptionStateEntity(
                localSubscriptionId = 2L,
                fireflyRecurringId = "recurring-1",
                syncedAt = LocalDateTime.now()
            )
        )
        assertEquals("recurring-1", dao.subscriptionByFireflyRecurringId("recurring-1")!!.fireflyRecurringId)
        dao.updateSubscriptionSyncInfo(2L, "recurring-2", LocalDateTime.now())
        assertEquals("recurring-2", dao.subscriptionStateFor(2L)!!.fireflyRecurringId)
    }

    @Test
    fun logInsertAndRecentLogsOrderDesc() = runBlocking {
        dao.insertLog(FireflyLogEntity(direction = "push", status = "ok", message = "one", at = LocalDateTime.now()))
        dao.insertLog(FireflyLogEntity(direction = "pull", status = "error", message = "two", at = LocalDateTime.now().plusSeconds(1)))

        val logs = dao.recentLogs(10).first()
        assertEquals(2, logs.size)
        assertEquals("two", logs[0].message) // most recent first
        assertEquals("pull", logs[0].direction)
    }
}
