package com.pennywiseai.tracker.data.firefly

import android.content.Context
import com.pennywiseai.tracker.data.database.dao.CategoryDao
import com.pennywiseai.tracker.data.database.dao.TransactionDao
import com.pennywiseai.tracker.data.database.dao.TransactionSplitDao
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.GoalRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * M2 regression: the remote-wins PULL path used to (a) reset the local row's
 * time-of-day to 00:00 (Firefly stores date-only), (b) downgrade a local
 * CREDIT/INVESTMENT row to EXPENSE because `mapRemoteTypeToLocal` collapses
 * every Firefly `withdrawal` to EXPENSE, and (c) stamp the row's `updatedAt`
 * with the sync wall-clock, conflating edit time with sync time.
 *
 * Exercises the REAL pull branch of [FireflyClient.syncTransactionsBatch]
 * (MockEngine returns a remote row newer than the sidecar state) down through
 * the real [TransactionRepository.updateTransactionFromFirefly] into a mocked
 * [TransactionDao], and asserts the values the DAO would actually write:
 *
 *  - remote DATE + preserved local TIME-of-day,
 *  - local CREDIT kept for a withdrawal pull (direction-equivalent), remote
 *    INCOME applied only when the direction genuinely differs (deposit pull),
 *  - `updatedAt` stays the row's own edit time — never `syncedAt`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyPullPreservationTest {

    private lateinit var context: Context
    private lateinit var transactionDao: TransactionDao
    private lateinit var client: FireflyClient

    private lateinit var capturedDateTime: CapturingSlot<LocalDateTime>
    private lateinit var capturedType: CapturingSlot<String>
    private lateinit var capturedUpdatedAt: CapturingSlot<LocalDateTime>

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        transactionDao = mockk(relaxed = true)

        capturedDateTime = slot()
        capturedType = slot()
        capturedUpdatedAt = slot()
        coEvery {
            transactionDao.updateTransactionFromFirefly(
                any(), any(), any(), any(), any(),
                capture(capturedDateTime), capture(capturedType), capture(capturedUpdatedAt)
            )
        } just Runs

        val transactionRepository = TransactionRepository(
            transactionDao,
            mockk<TransactionSplitDao>(relaxed = true),
            mockk<UserPreferencesRepository>(relaxed = true),
            mockk<CategoryDao>(relaxed = true),
            mockk<GoalRepository>(relaxed = true)
        )

        client = FireflyClient(
            context,
            mockk<FireflySyncDao>(relaxed = true),
            mockk<BudgetRepository>(relaxed = true),
            mockk<SubscriptionRepository>(relaxed = true),
            transactionRepository,
            DiagnosticLogger(context)
        )
    }

    private fun localRow(id: Long, hash: String, type: TransactionType, stamp: LocalDateTime) = TransactionEntity(
        id = id,
        amount = BigDecimal("40.00"),
        merchantName = "Local Shop",
        category = "Groceries",
        transactionType = type,
        dateTime = stamp,
        transactionHash = hash,
        bankName = "Local Bank",
        currency = "AED",
        updatedAt = stamp
    )

    /** Sidecar state: synced earlier; remote untouched since `fireflyUpdatedAt`. */
    private fun syncedState(txId: Long, externalId: String, fireflyId: String) = FireflyTransactionStateEntity(
        localTransactionId = txId,
        externalId = externalId,
        fireflyTransactionId = fireflyId,
        fireflyUpdatedAt = LocalDateTime.of(2026, 1, 15, 0, 0),
        syncedAt = LocalDateTime.of(2026, 1, 20, 0, 0)
    )

    private fun stubRemote(remoteJson: String) {
        client.httpClientForTests = HttpClient(
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/search/transactions" ->
                        respond(remoteJson, HttpStatusCode.OK, jsonHeaders())
                    else -> respond("Not Found", HttpStatusCode.NotFound)
                }
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun jsonHeaders() = Headers.build { append(HttpHeaders.ContentType, "application/vnd.api+json") }

    private fun runPull(syncableTx: TransactionEntity, state: FireflyTransactionStateEntity) {
        runBlocking {
            client.syncTransactionsBatch(
                syncables = listOf(FireflyClient.SyncableTransaction(syncableTx)),
                baseUrl = "https://firefly.test",
                accessToken = "pat",
                defaultAssetAccount = "Test Asset",
                uploadReceipts = false,
                stateByTx = mapOf(syncableTx.id to state)
            )
        }
    }

    private companion object {
        // Remote newer than state.fireflyUpdatedAt (2026-01-15) ⇒ remote wins ⇒ PULL.
        // Single raw strings: splitting these across raw-string segments used to emit
        // doubled quotes (`,""date":…`) which is not valid JSON at all.
        const val REMOTE_WITHDRAWAL =
            """{"data":[{"id":"9055","attributes":{"updated_at":"2026-02-01T00:00:00Z","transactions":[{"transaction_journal_id":"7001","type":"withdrawal","amount":"42.50","date":"2026-01-20","description":"Remote edit","source_name":"Wallet 1234","external_id":"pennywise-pull-1"}]}}]}"""
        const val REMOTE_DEPOSIT =
            """{"data":[{"id":"9057","attributes":{"updated_at":"2026-02-01T00:00:00Z","transactions":[{"transaction_journal_id":"7003","type":"deposit","amount":"500.00","date":"2026-01-20","description":"Payday","destination_name":"Wallet 1234","external_id":"pennywise-pull-3"}]}}]}"""
    }

    @Test
    fun pullKeepsRemoteDateLocalTimeOfDayLocalTypeAndOwnUpdatedAt() {
        val row = localRow(55L, "pull-1", TransactionType.CREDIT, LocalDateTime.of(2026, 1, 10, 14, 30))
        coEvery { transactionDao.getTransactionById(55L) } returns row
        stubRemote(REMOTE_WITHDRAWAL)

        runPull(row, syncedState(55L, "pennywise-pull-1", "9055"))

        assertEquals(
            "remote date must win but the local 14:30 time-of-day must survive",
            LocalDateTime.of(2026, 1, 20, 14, 30),
            capturedDateTime.captured
        )
        assertEquals(
            "withdrawal pull must NOT downgrade a local CREDIT row to EXPENSE",
            "CREDIT",
            capturedType.captured
        )
        assertEquals(
            "updatedAt must stay the row's own edit time, never the sync timestamp",
            row.updatedAt,
            capturedUpdatedAt.captured
        )
    }

    @Test
    fun pullDefaultsToMidnightOnlyWhenLocalRowIsGoneAndStillRemapsGenuinelyDifferentType() {
        val row = localRow(56L, "pull-2", TransactionType.CREDIT, LocalDateTime.of(2026, 1, 10, 23, 59))
        coEvery { transactionDao.getTransactionById(56L) } returns null // local row unreadable: time unknown
        stubRemote(REMOTE_WITHDRAWAL.replace("pennywise-pull-1", "pennywise-pull-2").replace("9055", "9056"))

        runPull(row, syncedState(56L, "pennywise-pull-2", "9056"))

        assertEquals(
            "no local row ⇒ midnight fallback on the remote date",
            LocalDateTime.of(2026, 1, 20, 0, 0),
            capturedDateTime.captured
        )
        assertEquals(
            "with no local type to protect, the remote withdrawal maps to EXPENSE",
            "EXPENSE",
            capturedType.captured
        )
        assertTrue(
            "updatedAt falls back to the sync time only when the row itself is gone",
            capturedUpdatedAt.captured.isAfter(LocalDateTime.of(2026, 1, 21, 0, 0))
        )
    }

    @Test
    fun depositPullWinsBecauseDirectionGenuinelyDiffersButTimeOfDayStillSurvives() {
        val row = localRow(57L, "pull-3", TransactionType.CREDIT, LocalDateTime.of(2026, 1, 10, 9, 15))
        coEvery { transactionDao.getTransactionById(57L) } returns row
        stubRemote(REMOTE_DEPOSIT)

        runPull(row, syncedState(57L, "pennywise-pull-3", "9057"))

        assertEquals(
            "deposit (inflow) genuinely differs from CREDIT (outflow) ⇒ remote type applies",
            "INCOME",
            capturedType.captured
        )
        assertEquals(
            "time-of-day preservation applies to every pull, not just equivalent-type ones",
            LocalDateTime.of(2026, 1, 20, 9, 15),
            capturedDateTime.captured
        )
        assertEquals(
            "updatedAt stays the row's own edit time",
            row.updatedAt,
            capturedUpdatedAt.captured
        )
    }
}