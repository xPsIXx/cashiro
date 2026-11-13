package com.pennywiseai.tracker.data.firefly

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.pennywiseai.tracker.data.database.PennyWiseDatabase
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.database.entity.TransactionWithSplits
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import com.pennywiseai.tracker.worker.FireflyAutoSyncWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M4 engine-level regression: the periodic resync must select candidates via
 * the DAO candidate query instead of loading the whole transactions table,
 * and must degrade to the old full-scan filter path when the candidate query
 * cannot run — never to a *lossier* path.
 *
 * The engine is wired with a mockk'd `PennyWiseDatabase` whose `isOpen` /
 * `openHelper` hand out a stub main connection (minimal `transactions` table),
 * exactly the seam `findSyncCandidates` uses; the sidecar is a REAL Room file
 * database at the exact path the engine ATTACHes. The client is stubbed, so
 * the syncables it receives *are* the engine's candidate set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflySyncEngineCandidateResyncTest {

    private lateinit var context: Context
    private lateinit var sidecarDb: FireflySyncDatabase
    private lateinit var sidecarDao: FireflySyncDao
    private lateinit var mainFile: File
    private lateinit var mainHelper: SQLiteOpenHelper
    private lateinit var stubSupportHelper: SupportSQLiteOpenHelper
    private lateinit var client: FireflyClient
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var engine: FireflySyncEngine
    private lateinit var prefs: FireflyPreferencesRepository
    private lateinit var mainDbMock: PennyWiseDatabase

    private val lastBatch = AtomicReference<List<FireflyClient.SyncableTransaction>>(emptyList())
    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private fun now() = LocalDateTime.now().withNano(0)

    private fun tx(id: Long, updatedAt: LocalDateTime) = TransactionEntity(
        id = id,
        amount = BigDecimal("12.34"),
        merchantName = "Cashiro candidate test",
        category = "General",
        transactionType = TransactionType.EXPENSE,
        dateTime = updatedAt.minusDays(1),
        description = "candidate regression",
        bankName = "Sign 6671",
        transactionHash = "hash-$id",
        currency = "AED",
        updatedAt = updatedAt.withNano(0)
    )

    /** 1 recent never-seen | 2 old never-seen | 3 clean synced | 4 stale (edited after sync) |
     *  5 claimed unsynced | 6 errored | 7 soft-deleted | 8 clean synced old */
    private val seeded = mapOf(
        1L to tx(1L, now().minusDays(2)),
        2L to tx(2L, now().minusDays(120)),
        3L to tx(3L, now().minusDays(20)),
        4L to tx(4L, now().minusDays(50)),
        5L to tx(5L, now().minusDays(200)),
        6L to tx(6L, now().minusDays(300)),
        7L to tx(7L, now().minusDays(1)),
        8L to tx(8L, now().minusDays(400))
    )

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()

        // Real sidecar file at the EXACT path findSyncCandidates ATTACHes.
        val sidecarFile = context.getDatabasePath(FireflySyncDatabase.DATABASE_NAME)
        sidecarFile.parentFile?.mkdirs()
        sidecarFile.delete()
        sidecarDb = Room.databaseBuilder(context, FireflySyncDatabase::class.java, sidecarFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        sidecarDao = sidecarDb.fireflySyncDao()

        mainFile = File(context.filesDir, "engine_candidate_main.db").also { it.delete() }
        mainHelper = object : SQLiteOpenHelper(context, mainFile.absolutePath, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE transactions (" +
                        "id INTEGER PRIMARY KEY, updated_at TEXT, is_deleted INTEGER DEFAULT 0)"
                )
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        seeded.values.forEach { t ->
            mainHelper.writableDatabase.execSQL(
                "INSERT INTO transactions (id, updated_at, is_deleted) VALUES (?, ?, ?)",
                arrayOf<Any?>(t.id, t.updatedAt.format(iso), if (t.id == 7L) 1 else 0)
            )
        }

        // SupportSQLite view of the SAME main file: what Room's openHelper
        // would hand the engine in production.
        stubSupportHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(mainFile.absolutePath)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) = Unit
                    }
                )
                .build()
        )
        // Ensure the table exists through this handle too (idempotent).
        stubSupportHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS transactions (" +
                "id INTEGER PRIMARY KEY, updated_at TEXT, is_deleted INTEGER DEFAULT 0)"
        )

        client = mockk<FireflyClient>(relaxed = true)
        every { client.computeExternalId(any()) } returns "pennywise-hash"
        coEvery {
            client.syncTransactionsBatch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val syncables = arg<List<FireflyClient.SyncableTransaction>>(0)
            lastBatch.set(syncables)
            FireflyClient.BatchSyncResult(
                total = syncables.size,
                created = syncables.size,
                details = syncables.map {
                    FireflyClient.ItemResult(
                        transactionId = it.transaction.id,
                        externalId = "pennywise-hash",
                        status = FireflyClient.ItemResult.ItemStatus.SUCCESS,
                        fireflyId = "ff-1"
                    )
                }
            )
        }

        transactionRepository = mockk<TransactionRepository>(relaxed = true)
        seeded.values.forEach { t ->
            coEvery { transactionRepository.getTransactionById(t.id) } returns t
            coEvery { transactionRepository.getTransactionWithSplitsSync(t.id) } returns
                TransactionWithSplits(t, emptyList())
        }

        prefs = mockk<FireflyPreferencesRepository>(relaxed = true)
        every { prefs.syncEnabledFlow } returns flowOf(true)
        every { prefs.getBaseUrl() } returns "https://firefly.example"
        every { prefs.getAccessToken() } returns "pat"
        every { prefs.defaultAssetAccountFlow } returns flowOf("Sign 6671")
        every { prefs.accountMappingsFlow } returns flowOf<Map<String, String>>(emptyMap())
        every { prefs.categoryMappingsFlow } returns flowOf<Map<String, String>>(emptyMap())
        every { prefs.includeRawSmsFlow } returns flowOf(false)

        mainDbMock = mockk<PennyWiseDatabase>(relaxed = true)

        engine = FireflySyncEngine(
            client,
            sidecarDao,
            prefs,
            transactionRepository,
            mockk<BudgetRepository>(relaxed = true),
            mockk<SubscriptionRepository>(relaxed = true),
            DiagnosticLogger(context),
            context,
            null,
            mainDbMock
        )
    }

    @After
    fun tearDown() {
        sidecarDb.close()
        mainHelper.close()
        runCatching { stubSupportHelper.close() }
        mainFile.delete()
        context.getDatabasePath(FireflySyncDatabase.DATABASE_NAME).delete()
    }

    private fun seedSidecar() = runBlocking {
        sidecarDao.markSynced(
            txId = 3L, externalId = "pennywise-h3", remoteTxId = "ff-3",
            updatedAt = now().minusDays(20), syncedAt = now().minusDays(20)
        )
        sidecarDao.markSynced(
            txId = 4L, externalId = "pennywise-h4", remoteTxId = "ff-4",
            updatedAt = now().minusDays(100), syncedAt = now().minusDays(100)
        )
        sidecarDao.markSynced(
            txId = 8L, externalId = "pennywise-h8", remoteTxId = "ff-8",
            updatedAt = now().minusDays(400), syncedAt = now().minusDays(400)
        )
        sidecarDao.upsertTransactionState(
            FireflyTransactionStateEntity(localTransactionId = 5L, externalId = "pennywise-ff-old5")
        )
        sidecarDao.markError(6L, "update failed: HTTP 500")
    }

    @Test
    fun scopedResyncSyncsOnlyCandidatesAndNeverLoadsTheFullTable() = runBlocking {
        seedSidecar()
        every { mainDbMock.isOpen } returns true
        every { mainDbMock.openHelper } returns stubSupportHelper

        val summary = engine.resyncAll(scopeDays = FireflyAutoSyncWorker.PERIODIC_LOOKBACK_DAYS)

        assertEquals("candidates only: never-seen-in-window, stale, claimed-unsynced, errored", 4, summary.attempted)
        assertEquals(setOf(1L, 4L, 5L, 6L), lastBatch.get().map { it.transaction.id }.toSet())
        // never loaded the full table
        coVerify(atLeast = 0, atMost = 0) { transactionRepository.getAllTransactionsList() }
    }

    @Test
    fun fullCandidateScanWhenNoWindowGiven() = runBlocking {
        seedSidecar()
        every { mainDbMock.isOpen } returns true
        every { mainDbMock.openHelper } returns stubSupportHelper

        val summary = engine.resyncAll() // explicit "Sync now" keeps the full candidate scan

        assertEquals(5, summary.attempted)
        assertEquals(setOf(1L, 2L, 4L, 5L, 6L), lastBatch.get().map { it.transaction.id }.toSet())
    }

    @Test
    fun engineFallsBackToFullScanFilterWhenMainDbNotOpen() = runBlocking {
        seedSidecar()
        every { mainDbMock.isOpen } returns false
        coEvery { transactionRepository.getAllTransactionsList() } returns seeded.values.filter { it.id != 7L }

        val summary = engine.resyncAll()

        // Old full-scan path: with the candidate filter applied in memory it
        // picks 1,2 (no state), 4 (stale), 5 (claimed unsynced), 6 (error) —
        // never the clean rows 3/8, never the deleted 7.
        assertEquals(5, summary.attempted)
        assertEquals(setOf(1L, 2L, 4L, 5L, 6L), lastBatch.get().map { it.transaction.id }.toSet())
        coVerify(atLeast = 1) { transactionRepository.getAllTransactionsList() }
    }

    @Test
    fun stalenessRuleAlsoAppliesOnTheFallbackPath() = runBlocking {
        // Row 8 synced cleanly then was edited afterwards: the OLD filter would
        // have skipped it (syncedAt != null, no error); the M4 filter must
        // pick it. Exercise the fallback path to prove the in-memory filter
        // matches the SQL rules.
        seedSidecar()
        val editedLate = tx(8L, now().minusDays(50)) // edited 50d ago; synced 400d ago
        val rebuilt = seeded.toMutableMap().apply { put(8L, editedLate) }
        rebuilt.values.forEach { t ->
            coEvery { transactionRepository.getTransactionById(t.id) } returns t
            coEvery { transactionRepository.getTransactionWithSplitsSync(t.id) } returns
                TransactionWithSplits(t, emptyList())
        }
        stubSupportHelper.writableDatabase.execSQL(
            "UPDATE transactions SET updated_at = ? WHERE id = 8",
            arrayOf<Any?>(editedLate.updatedAt.format(iso))
        )
        every { mainDbMock.isOpen } returns false
        coEvery { transactionRepository.getAllTransactionsList() } returns rebuilt.values.filter { it.id != 7L }

        val summary = engine.resyncAll()

        assertEquals(setOf(1L, 2L, 4L, 5L, 6L, 8L), lastBatch.get().map { it.transaction.id }.toSet())
        assertEquals(6, summary.attempted)
    }

    @Test
    fun workerPassesBoundedWindowConstant() {
        // The periodic worker's contract: a bounded lookback for never-seen
        // rows. (Scheduling semantics are WorkManager's; this pins the value.)
        assertEquals(30, FireflyAutoSyncWorker.PERIODIC_LOOKBACK_DAYS)
    }
}