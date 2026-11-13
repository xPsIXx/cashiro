package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.room.Room
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.database.entity.TransactionWithSplits
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for H2: `onTransactionSaved` runs inline on the save path while
 * `FireflyAutoSyncWorker` may be resyncing the same row. Before the guard both
 * searched Firefly, both got "not found", and both created the row.
 *
 * The client is stubbed with a stalled `syncTransactionsBatch` so the first
 * attempt is provably still inside its search-then-create section when the
 * second attempt arrives; the second attempt must return SKIPPED without making
 * any HTTP call at all (the stub invocation counter is the proof).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflySyncEngineSingleFlightTest {

    private lateinit var context: Context
    private lateinit var sidecarDb: FireflySyncDatabase
    private lateinit var sidecarDao: FireflySyncDao
    private lateinit var client: FireflyClient
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var engine: FireflySyncEngine

    /** Counts real `syncTransactionsBatch` (i.e. HTTP-doing) invocations. */
    private val clientCalls = AtomicInteger(0)

    /** Completed by the stub when an attempt enters the search-then-create section. */
    private lateinit var entered: CompletableDeferred<Unit>

    /** Released by the test to let the in-flight attempt finish. */
    private lateinit var release: CompletableDeferred<Unit>

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        sidecarDb = Room.inMemoryDatabaseBuilder(context, FireflySyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sidecarDao = sidecarDb.fireflySyncDao()

        entered = CompletableDeferred()
        release = CompletableDeferred()
        clientCalls.set(0)

        client = mockk<FireflyClient>(relaxed = true)
        every { client.computeExternalId(any()) } returns "pennywise-hash"
        coEvery {
            client.syncTransactionsBatch(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            clientCalls.incrementAndGet()
            entered.complete(Unit)
            release.await() // simulate the slow search-then-create window
            val syncables = arg<List<FireflyClient.SyncableTransaction>>(0)
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

        val prefs = mockk<FireflyPreferencesRepository>(relaxed = true)
        every { prefs.syncEnabledFlow } returns flowOf(true)
        every { prefs.getBaseUrl() } returns "https://firefly.example"
        every { prefs.getAccessToken() } returns "pat"
        every { prefs.defaultAssetAccountFlow } returns flowOf("Sign 6671")
        every { prefs.accountMappingsFlow } returns flowOf<Map<String, String>>(emptyMap())
        every { prefs.categoryMappingsFlow } returns flowOf<Map<String, String>>(emptyMap())
        every { prefs.includeRawSmsFlow } returns flowOf(false)

        engine = FireflySyncEngine(
            client,
            sidecarDao,
            prefs,
            transactionRepository,
            mockk<BudgetRepository>(relaxed = true),
            mockk<SubscriptionRepository>(relaxed = true),
            DiagnosticLogger(context),
            context
        )
    }

    @After
    fun tearDown() {
        sidecarDb.close()
    }

    private fun tx(id: Long) = TransactionEntity(
        id = id,
        amount = BigDecimal("12.34"),
        merchantName = "Cashiro single-flight test",
        category = "General",
        transactionType = TransactionType.EXPENSE,
        dateTime = LocalDateTime.of(2026, 1, 1, 12, 0),
        description = "single-flight regression",
        bankName = "Sign 6671",
        transactionHash = "hash-$id",
        currency = "AED",
        updatedAt = LocalDateTime.of(2026, 1, 1, 12, 0)
    )

    private fun logRowCount(status: String): Int =
        sidecarDb.query("SELECT COUNT(*) FROM firefly_log WHERE status = ?", arrayOf<Any?>(status))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Test
    fun concurrentAttemptOnSameTxIdSkipsWithoutAnyHttp() = runBlocking {
        val firstTx = tx(7L)

        // Path 1: the save-hook path (AddTransactionUseCase / SmsTransactionProcessor)
        // claims the id and is parked inside the client's search-then-create.
        val first = async(Dispatchers.IO) { engine.onTransactionSaved(firstTx) }
        withTimeout(10_000) { entered.await() }

        // Path 2: a manual "Sync now" attempt on the very same row.
        val manualResult = withTimeout(10_000) { engine.syncTransaction(firstTx) }

        assertEquals("second attempt must not touch Firefly", 1, clientCalls.get())
        assertTrue(
            "in-flight row must report Skipped, got $manualResult",
            manualResult is FireflyClient.SyncResult.Skipped
        )
        assertEquals("guard skip must be recorded in the sync log", 1, logRowCount("skip"))

        release.complete(Unit)
        withTimeout(20_000) { first.await() }
        assertEquals("still exactly one HTTP attempt for this id", 1, clientCalls.get())
    }

    @Test
    fun resyncBatchSkipsRowsAlreadyInFlightByTheHook() = runBlocking {
        val firstTx = tx(8L)
        coEvery { transactionRepository.getAllTransactionsList() } returns listOf(firstTx)
        coEvery { transactionRepository.getTransactionWithSplitsSync(firstTx.id) } returns
            TransactionWithSplits(firstTx, emptyList())

        // Hook path claims id 8 and is parked inside the client's search-then-create.
        val hook = async(Dispatchers.IO) { engine.onTransactionSaved(firstTx) }
        withTimeout(10_000) { entered.await() }

        // Worker path: full resync candidate list contains the very same row.
        val summary = withTimeout(10_000) { engine.resyncAll() }

        assertEquals(1, summary.attempted)
        assertEquals("resync must not create a second attempt for an in-flight row", 0, summary.created)
        assertEquals(1, summary.skipped)
        assertEquals("no extra HTTP work for the in-flight row", 1, clientCalls.get())

        release.complete(Unit)
        withTimeout(20_000) { hook.await() }
    }

    @Test
    fun twoHookCallsForSameTxIdProduceExactlyOneHttpAttempt() = runBlocking {
        val firstTx = tx(11L)

        val first = async(Dispatchers.IO) { engine.onTransactionSaved(firstTx) }
        withTimeout(10_000) { entered.await() }

        // Second call on the SAME entry point and the same id, on another thread.
        val second = async(Dispatchers.IO) { engine.onTransactionSaved(firstTx) }
        withTimeout(10_000) { second.await() }

        assertEquals("the second hook call must not reach Firefly", 1, clientCalls.get())
        assertEquals("and must be recorded as a skip", 1, logRowCount("skip"))

        release.complete(Unit)
        withTimeout(20_000) { first.await() }
        assertEquals(1, clientCalls.get())
    }

    @Test
    fun claimsAreReleasedEvenWhenTheClientThrows() = runBlocking {
        val failingTx = tx(9L)
        coEvery {
            client.syncTransactionsBatch(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            clientCalls.incrementAndGet()
            if (clientCalls.get() == 1) {
                entered.complete(Unit)
                throw IOException("simulated mid-sync failure")
            }
            val syncables = arg<List<FireflyClient.SyncableTransaction>>(0)
            FireflyClient.BatchSyncResult(
                total = syncables.size,
                created = syncables.size,
                details = syncables.map {
                    FireflyClient.ItemResult(
                        transactionId = it.transaction.id,
                        externalId = "pennywise-hash",
                        status = FireflyClient.ItemResult.ItemStatus.SUCCESS,
                        fireflyId = "ff-2"
                    )
                }
            )
        }

        val first = runCatching { withTimeout(10_000) { engine.syncTransaction(failingTx) } }
        assertTrue("test expects the first attempt to fail", first.isFailure)
        assertEquals(1, clientCalls.get())

        // The id must be claimable again: the failed attempt released its claim.
        val second = withTimeout(10_000) { engine.syncTransaction(failingTx) }
        assertTrue("retry after a failure must reach Firefly again: $second", second is FireflyClient.SyncResult.Success)
        assertEquals(2, clientCalls.get())
    }
}
