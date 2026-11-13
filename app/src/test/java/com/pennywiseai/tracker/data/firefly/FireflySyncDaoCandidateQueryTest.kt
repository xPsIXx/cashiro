package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * M4 regression: the periodic resync used to load the ENTIRE transactions
 * table every run and filter candidates in memory (N+1 loads + a serial search
 * HTTP per unsynced row). The fix selects candidates directly with SQL
 * ([selectSyncCandidates] / [SYNC_CANDIDATE_SQL]) joining the main DB's
 * `transactions` to the sidecar state table.
 *
 * This test pins the eligibility rules against a REAL sidecar Room database
 * file plus a stub main database (minimal `transactions` table):
 *
 *  - id 1: no sidecar state, edited INSIDE the lookback window  -> picked (rule 4)
 *  - id 2: no sidecar state, edited OUTSIDE the window          -> skipped
 *  - id 3: synced, never edited since (updated_at == syncedAt)  -> skipped (clean)
 *  - id 4: synced, edited AFTER the sync (updated_at > syncedAt)-> picked (rule 3, age doesn't matter)
 *  - id 5: sidecar row claimed (externalId) but never synced    -> picked (rule 2, age doesn't matter)
 *  - id 6: last sync errored                                     -> picked (rule 1, age doesn't matter)
 *  - id 7: soft-deleted                                          -> never picked
 *  - id 8: synced clean long ago                                 -> skipped
 *  - id 9: old never-synced row INSIDE a 3650-day window        -> picked only when the window covers it
 *  - id 10: synced clean but edited 2 years ago after syncing   -> picked regardless of window
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflySyncDaoCandidateQueryTest {

    private lateinit var context: Context
    private lateinit var sidecarDb: FireflySyncDatabase
    private lateinit var sidecarDao: FireflySyncDao
    private lateinit var sidecarFile: File
    private lateinit var mainFile: File
    private lateinit var mainHelper: SupportSQLiteOpenHelper

    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private fun now() = LocalDateTime.now().withNano(0)

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        val unique = System.nanoTime()

        // REAL sidecar database, on the exact path the engine will ATTACH
        // (context.getDatabasePath(name)).
        sidecarFile = context.getDatabasePath("candidate_test_$unique.db")
        sidecarFile.parentFile?.mkdirs()
        sidecarFile.delete()
        sidecarDb = Room.databaseBuilder(context, FireflySyncDatabase::class.java, sidecarFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        sidecarDao = sidecarDb.fireflySyncDao()

        // Stub main DB: only the columns the candidate SQL reads.
        mainFile = File(context.filesDir, "candidate_main_$unique.db")
        mainFile.delete()
        // Same handle type selectSyncCandidates consumes in production (Room's
        // RoomOpenHelper is a SupportSQLiteOpenHelper), so the stub main DB has
        // to be one too — a plain android.database.sqlite.SQLiteDatabase is not
        // assignable to SupportSQLiteDatabase.
        mainHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(mainFile.absolutePath)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) = Unit
                    }
                )
                .build()
        )
        mainHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS transactions (" +
                "id INTEGER PRIMARY KEY, updated_at TEXT, is_deleted INTEGER DEFAULT 0)"
        )
    }

    @After
    fun tearDown() {
        sidecarDb.close()
        mainHelper.close()
        sidecarFile.delete()
        mainFile.delete()
    }

    private fun seedTransactions(vararg rows: Triple<Long, LocalDateTime, Boolean>) {
        val db = mainHelper.writableDatabase
        rows.forEach { (id, updatedAt, deleted) ->
            db.execSQL(
                "INSERT INTO transactions (id, updated_at, is_deleted) VALUES (?, ?, ?)",
                arrayOf<Any?>(id, updatedAt.format(iso), if (deleted) 1 else 0)
            )
        }
    }

    private fun seedSidecar() = runBlocking {
        // id 3: clean sync 20 days ago, last local edit 20 days ago (== synced).
        sidecarDao.markSynced(
            txId = 3L, externalId = "pennywise-h3", remoteTxId = "ff-3",
            updatedAt = now().minusDays(20),
            syncedAt = now().minusDays(20).withNano(0)
        )
        // id 4: synced 100 days ago; edited 50 days ago (> syncedAt) -> stale.
        sidecarDao.markSynced(
            txId = 4L, externalId = "pennywise-h4", remoteTxId = "ff-4",
            updatedAt = now().minusDays(100),
            syncedAt = now().minusDays(100).withNano(0)
        )
        // id 8: clean sync 400 days ago, edited at sync time.
        sidecarDao.markSynced(
            txId = 8L, externalId = "pennywise-h8", remoteTxId = "ff-8",
            updatedAt = now().minusDays(400),
            syncedAt = now().minusDays(400).withNano(0)
        )
        // id 5: claimed (external id pre-persisted) but never synced, old.
        sidecarDao.upsertTransactionState(
            FireflyTransactionStateEntity(localTransactionId = 5L, externalId = "pennywise-ff-old5")
        )
        // id 6: errored long ago.
        sidecarDao.markError(6L, "update failed: HTTP 500")
    }

    private fun candidatesFor(lookbackDays: Long?): Set<Long> {
        val cutoff = lookbackDays?.let { LocalDateTime.now().minusDays(it) }
        return selectSyncCandidates(
            mainWritableConnection = mainHelper.writableDatabase,
            sidecarDbFile = sidecarFile,
            lookbackCutoff = cutoff,
            limit = null
        ).map { it.localTransactionId }.toSet()
    }

    @Test
    fun candidateQueryPicksUnsyncedErroredAndStaleRowsAndSkipsCleanRows() {
        seedSidecar()
        seedTransactions(
            Triple(1L, now().minusDays(2), false),    // never seen, inside 30d window
            Triple(2L, now().minusDays(120), false),  // never seen, outside window
            Triple(3L, now().minusDays(20), false),   // clean synced
            Triple(4L, now().minusDays(50), false),   // stale: edited after sync
            Triple(5L, now().minusDays(200), false),  // claimed, never synced
            Triple(6L, now().minusDays(300), false),  // errored
            Triple(7L, now().minusDays(1), true),     // soft-deleted, inside window
            Triple(8L, now().minusDays(400), false)   // clean synced long ago
        )

        val picked = candidatesFor(lookbackDays = 30)

        assertEquals(
            "unsynced-in-window (1), stale (4), claimed-unsynced (5) and errored (6) qualify; " +
                "clean/outside-window/deleted do not",
            setOf(1L, 4L, 5L, 6L),
            picked
        )
    }

    @Test
    fun errorStaleAndClaimedRowsArePickedRegardlessOfWindowSize() {
        seedSidecar() // marks id 4 synced at now-100d
        seedTransactions(
            Triple(1L, now().minusDays(400), false),  // never seen, far outside small window
            Triple(4L, now().minusDays(10), false),   // edited 10d ago, AFTER the -100d sync -> stale
            Triple(5L, now().minusDays(700), false),  // claimed unsynced
            Triple(6L, now().minusDays(700), false)   // errored
        )

        val picked = candidatesFor(lookbackDays = 1)
        assertTrue("rule 1 (error) ignores the window: 6", picked.contains(6L))
        assertTrue("rule 2 (claimed-unsynced) ignores the window: 5", picked.contains(5L))
        assertTrue("rule 3 (stale: updated_at > syncedAt) ignores the window: 4", picked.contains(4L))
        assertFalse("rule 4 (never-seen) IS window-bound: 1", picked.contains(1L))
    }

    @Test
    fun nullCutoffScansNeverSeenRowsAcrossFullHistory() {
        seedSidecar()
        seedTransactions(
            Triple(9L, now().minusDays(1000), false), // ancient never-seen
            Triple(10L, now().minusDays(730), false)  // edited 730d ago, AFTER the -800d sync -> stale
        )
        runBlocking {
            sidecarDao.markSynced(
                txId = 10L, externalId = "pennywise-h10", remoteTxId = "ff-10",
                updatedAt = now().minusDays(800),
                syncedAt = now().minusDays(800).withNano(0)
            )
        }

        // "Sync now" full candidate scan: no cutoff -> even ancient never-seen rows surface.
        val full = candidatesFor(lookbackDays = null)
        assertTrue("full scan must surface ancient never-seen row", full.contains(9L))
        assertTrue("stale row surfaced", full.contains(10L))

        // A 30-day window drops the ancient never-seen row but keeps the stale one.
        val scoped = candidatesFor(lookbackDays = 30)
        assertFalse(scoped.contains(9L))
        assertTrue(scoped.contains(10L))
    }

    @Test
    fun limitCapsTheBatch() {
        seedSidecar()
        seedTransactions(
            Triple(1L, now().minusDays(1), false),
            Triple(2L, now().minusDays(2), false),
            Triple(3L, now().minusDays(3), false),
            Triple(4L, now().minusDays(4), false)
        )
        val capped = selectSyncCandidates(
            mainWritableConnection = mainHelper.writableDatabase,
            sidecarDbFile = sidecarFile,
            lookbackCutoff = LocalDateTime.now().minusDays(30),
            limit = 2L
        )
        assertEquals(2, capped.size)
        assertEquals("ordered by recency (updated_at DESC)", listOf(1L, 2L), capped.map { it.localTransactionId })
    }
}