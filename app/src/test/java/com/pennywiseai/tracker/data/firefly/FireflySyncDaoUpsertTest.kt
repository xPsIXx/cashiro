package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * Regression: `markSynced` used to be UPDATE-only, so the FIRST sync of a
 * transaction never created a state row and all sync/error bookkeeping was
 * dead. This pins the upsert semantics on the JVM (no emulator needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflySyncDaoUpsertTest {

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
    fun markSyncedCreatesThenUpdatesSidecarState() = runBlocking {
        dao.markSynced(
            txId = 42L,
            externalId = "pennywise-first",
            remoteTxId = "remote-1",
            updatedAt = LocalDateTime.of(2026, 1, 1, 10, 0),
            source = "Source Bank",
            destination = "Dest Bank"
        )

        // First sync must CREATE the row (was broken before the upsert fix).
        val first = dao.stateFor(42L).first()
        assertNotNull("first markSynced must create the state row", first)
        assertEquals("pennywise-first", first!!.externalId)
        assertEquals("remote-1", first.fireflyTransactionId)
        assertEquals("Source Bank", first.fireflySourceName)
        assertEquals("Dest Bank", first.fireflyDestinationName)
        assertNull(first.lastError)

        // A later sync must UPDATE the same row (and clear any prior error).
        dao.markError(42L, "boom")
        assertEquals("boom", dao.stateFor(42L).first()!!.lastError)

        dao.markSynced(
            txId = 42L,
            externalId = "pennywise-first",
            remoteTxId = "remote-2",
            updatedAt = LocalDateTime.of(2026, 1, 2, 10, 0)
        )
        val second = dao.stateFor(42L).first()!!
        assertEquals(1, db.query("SELECT COUNT(*) FROM firefly_transaction_state", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        })
        assertEquals("remote-2", second.fireflyTransactionId)
        assertEquals("Source Bank", second.fireflySourceName) // COALESCE keeps names
        assertNull(second.lastError)
    }
}
