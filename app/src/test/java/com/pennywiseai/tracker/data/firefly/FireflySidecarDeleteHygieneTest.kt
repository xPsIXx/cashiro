package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Sidecar delete-hygiene QA: sidecar state is keyed by plain rowids
 * (`transactions.id` etc.), and SQLite reuses the id of the highest deleted
 * row for the next insert. A state row that outlives a HARD delete would
 * therefore mis-pin a brand-new, unrelated row onto the old row's remote
 * journal (and M3's persisted-external-id precedence would PUT over that
 * foreign journal). These tests pin the two-layer defense:
 *
 *  1. immediate cleanup — DAO deletes wired into every known hard-delete
 *     path (TransactionRepository / BudgetRepository /
 *     SubscriptionRepository / BackupImporter.replaceAllData);
 *  2. the safety net — [pruneOrphanedSidecarState], run at the start of every
 *     resync, which drops state whose main-database row no longer exists.
 *     Soft-deleted rows KEEP their state on purpose: undo/undelete relies on
 *     it, and the candidate SQL already excludes `is_deleted` rows.
 *
 * Also pins: the log bound ([trimLogs]) and the error-first candidate
 * ordering that stops a bounded batch from starving old failed rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflySidecarDeleteHygieneTest {

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

        sidecarFile = context.getDatabasePath("hygiene_test_$unique.db")
        sidecarFile.parentFile?.mkdirs()
        sidecarFile.delete()
        sidecarDb = Room.databaseBuilder(context, FireflySyncDatabase::class.java, sidecarFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        sidecarDao = sidecarDb.fireflySyncDao()

        // Stub main DB with the three tables the prune deletes reference —
        // same SupportSQLiteOpenHelper handle type production uses.
        mainFile = File(context.filesDir, "hygiene_main_$unique.db")
        mainFile.delete()
        mainHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(mainFile.absolutePath)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE transactions (id INTEGER PRIMARY KEY, updated_at TEXT, is_deleted INTEGER DEFAULT 0)"
                            )
                            db.execSQL("CREATE TABLE budgets (id INTEGER PRIMARY KEY)")
                            db.execSQL("CREATE TABLE subscriptions (id INTEGER PRIMARY KEY)")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) = Unit
                    }
                )
                .build()
        )
    }

    @After
    fun tearDown() {
        sidecarDb.close()
        mainHelper.close()
        sidecarFile.delete()
        mainFile.delete()
    }

    private fun seedMain(table: String, vararg ids: Long, deleted: List<Long> = emptyList()) {
        val db = mainHelper.writableDatabase
        ids.forEach { id ->
            if (table == "transactions") {
                db.execSQL(
                    "INSERT INTO transactions (id, updated_at, is_deleted) VALUES (?, ?, ?)",
                    arrayOf<Any?>(id, now().format(iso), if (deleted.contains(id)) 1 else 0)
                )
            } else {
                db.execSQL("INSERT INTO $table (id) VALUES (?)", arrayOf<Any?>(id))
            }
        }
    }

    // ---- layer 1: immediate hard-delete cleanup ------------------------------

    @Test
    fun hardDeleteRemovesTransactionStateAndSurvivingRowsKeepTheirs() = runBlocking {
        seedMain("transactions", 1L, 2L, 3L)
        listOf(1L, 2L, 3L).forEach {
            sidecarDao.upsertTransactionState(
                FireflyTransactionStateEntity(
                    localTransactionId = it,
                    externalId = "pennywise-ff-x$it",
                    fireflyTransactionId = "journal-$it",
                    syncedAt = now()
                )
            )
        }

        sidecarDao.deleteTransactionState(2L)

        val remaining = sidecarDao.statesFor(listOf(1L, 2L, 3L))
        assertEquals(listOf(1L, 3L), remaining.map { it.localTransactionId }.sorted())
    }

    @Test
    fun bulkDeleteAndClearMatchTheRepositoryHardDeletePaths() = runBlocking {
        listOf(10L, 11L, 12L).forEach {
            sidecarDao.upsertTransactionState(FireflyTransactionStateEntity(localTransactionId = it, syncedAt = now()))
        }
        // deleteRebuildableSmsTransactions-style bulk wipe of an id set:
        sidecarDao.deleteTransactionStates(listOf(10L, 11L))
        assertEquals(listOf(12L), sidecarDao.statesFor(listOf(10L, 11L, 12L)).map { it.localTransactionId })

        // BackupImporter.replaceAllData-style total wipe:
        sidecarDao.clearTransactionStates()
        assertTrue(sidecarDao.statesFor(listOf(10L, 11L, 12L)).isEmpty())
    }

    @Test
    fun budgetAndSubscriptionCleanupTracksTheirHardDeletes() = runBlocking {
        seedMain("budgets", 5L)
        seedMain("subscriptions", 7L)
        sidecarDao.upsertBudgetState(FireflyBudgetStateEntity(localBudgetId = 5L, fireflyBudgetId = "b-5"))
        sidecarDao.upsertSubscriptionState(FireflySubscriptionStateEntity(localSubscriptionId = 7L, fireflyRecurringId = "r-7"))

        sidecarDao.deleteBudgetState(5L)
        sidecarDao.deleteSubscriptionState(7L)
        assertNull(sidecarDao.budgetStateFor(5L))
        assertNull(sidecarDao.subscriptionStateFor(7L))

        sidecarDao.upsertBudgetState(FireflyBudgetStateEntity(localBudgetId = 6L, fireflyBudgetId = "b-6"))
        sidecarDao.clearBudgetStates()
        sidecarDao.upsertSubscriptionState(FireflySubscriptionStateEntity(localSubscriptionId = 8L, fireflyRecurringId = "r-8"))
        sidecarDao.clearSubscriptionStates()
        assertNull(sidecarDao.budgetStateFor(6L))
        assertNull(sidecarDao.subscriptionStateFor(8L))
    }

    // ---- layer 2: the prune safety net ---------------------------------------

    @Test
    fun pruneDropsOrphansAcrossAllThreeTablesButKeepsLiveAndSoftDeletedRows() {
        seedMain("transactions", 1L, 2L, deleted = listOf(2L)) // 1 live, 2 soft-deleted, 99 never existed
        seedMain("budgets", 5L)
        seedMain("subscriptions", 7L)

        runBlocking {
            sidecarDao.upsertTransactionState(FireflyTransactionStateEntity(localTransactionId = 1L, fireflyTransactionId = "j-1", syncedAt = now()))
            sidecarDao.upsertTransactionState(FireflyTransactionStateEntity(localTransactionId = 2L, fireflyTransactionId = "j-2", syncedAt = now()))
            sidecarDao.upsertTransactionState(FireflyTransactionStateEntity(localTransactionId = 99L, externalId = "pennywise-ff-dead", fireflyTransactionId = "j-99", syncedAt = now()))
            sidecarDao.upsertBudgetState(FireflyBudgetStateEntity(localBudgetId = 5L, fireflyBudgetId = "b-5"))
            sidecarDao.upsertBudgetState(FireflyBudgetStateEntity(localBudgetId = 98L, fireflyBudgetId = "b-98"))
            sidecarDao.upsertSubscriptionState(FireflySubscriptionStateEntity(localSubscriptionId = 7L, fireflyRecurringId = "r-7"))
            sidecarDao.upsertSubscriptionState(FireflySubscriptionStateEntity(localSubscriptionId = 97L, fireflyRecurringId = "r-97"))
        }

        val removed = pruneOrphanedSidecarState(mainHelper.writableDatabase, sidecarFile)
        assertEquals(3, removed) // tx 99 + budget 98 + subscription 97

        runBlocking {
            val live = sidecarDao.statesFor(listOf(1L, 2L, 99L))
            // Soft-deleted tx 2 keeps its mapping (undo/undelete relies on it).
            assertEquals(listOf(1L, 2L), live.map { it.localTransactionId }.sorted())
            assertEquals("b-5", sidecarDao.budgetStateFor(5L)?.fireflyBudgetId)
            assertNull(sidecarDao.budgetStateFor(98L))
            assertEquals("r-7", sidecarDao.subscriptionStateFor(7L)?.fireflyRecurringId)
            assertNull(sidecarDao.subscriptionStateFor(97L))
        }
    }

    @Test
    fun pruneIsIdempotentAndDetachesTheSidecar() {
        seedMain("transactions", 1L)
        runBlocking {
            sidecarDao.upsertTransactionState(FireflyTransactionStateEntity(localTransactionId = 1L, fireflyTransactionId = "j-1", syncedAt = now()))
            sidecarDao.upsertTransactionState(FireflyTransactionStateEntity(localTransactionId = 99L, fireflyTransactionId = "j-99", syncedAt = now()))
        }
        assertEquals(1, pruneOrphanedSidecarState(mainHelper.writableDatabase, sidecarFile))
        // Second pass finds nothing — and proves the connection is not left
        // attached (a leaked ATTACH would make this throw "already attached").
        assertEquals(0, pruneOrphanedSidecarState(mainHelper.writableDatabase, sidecarFile))

        val cursor = mainHelper.writableDatabase.query("PRAGMA database_list")
        val attached = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) attached.add(it.getString(1))
        }
        assertTrue("sidecar must not remain attached", attached.none { it == "ff" })
    }

    // ---- log bound ------------------------------------------------------------

    @Test
    fun trimLogsKeepsTheNewestRowsOnly() = runBlocking {
        repeat(5) { i ->
            sidecarDao.insertLog(FireflyLogEntity(direction = "push", status = "ok", message = "entry-$i", at = now().plusDays(i.toLong())))
        }
        sidecarDao.trimLogs(3)

        val cursor = sidecarDb.openHelper.readableDatabase.query("SELECT message FROM firefly_log ORDER BY id DESC")
        val messages = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) messages.add(it.getString(0))
        }
        assertEquals(listOf("entry-4", "entry-3", "entry-2"), messages)
    }

    // ---- error-first candidate ordering ----------------------------------------

    @Test
    fun errorRowsAreSelectedBeforeNewerRowsSoABoundedBatchCannotStarveThem() {
        val base = now()
        val db = mainHelper.writableDatabase
        // 100: edited recently after syncing (would sort first by recency)
        //  90: ancient row whose last attempt ERRORED (must come first anyway)
        //  80: never seen, inside an unbounded scan
        db.execSQL("INSERT INTO transactions (id, updated_at, is_deleted) VALUES (100, ?, 0)", arrayOf<Any?>(base.format(iso)))
        db.execSQL("INSERT INTO transactions (id, updated_at, is_deleted) VALUES (90, ?, 0)", arrayOf<Any?>((base.minusDays(30)).format(iso)))
        db.execSQL("INSERT INTO transactions (id, updated_at, is_deleted) VALUES (80, ?, 0)", arrayOf<Any?>((base.minusDays(60)).format(iso)))

        runBlocking {
            sidecarDao.upsertTransactionState(FireflyTransactionStateEntity(localTransactionId = 100L, fireflyTransactionId = "j-100", syncedAt = base.minusDays(1)))
            sidecarDao.upsertTransactionState(FireflyTransactionStateEntity(localTransactionId = 90L, fireflyTransactionId = "j-90", syncedAt = base.minusDays(31)))
            sidecarDao.markError(90L, "update failed: HTTP 500")
        }

        val candidates = selectSyncCandidates(db, sidecarFile, lookbackCutoff = null, limit = 500L)
        assertEquals(listOf(90L, 100L, 80L), candidates.map { it.localTransactionId })

        // And under a batch cap of 1, the errored row — not the newest edit — wins the slot.
        val capped = selectSyncCandidates(db, sidecarFile, lookbackCutoff = null, limit = 1L)
        assertEquals(listOf(90L), capped.map { it.localTransactionId })
    }
}
