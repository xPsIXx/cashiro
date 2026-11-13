package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.room.Room
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.repository.BudgetRepository
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
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * H1 + H3 wiring through the real sidecar database: every failure branch of
 * `syncTransactionsBatch` must (a) never create on an inconclusive search and
 * (b) record `lastError` via `markError` so `statesWithError()`/
 * `FireflyRetryWorker`/`FireflyFailedSyncsViewModel` have something to act on.
 *
 * No retry plugin is installed on the stub client: one HTTP request per
 * logical call keeps the request assertions exact. The retry policy itself is
 * covered in [FireflyClientRetryPolicyTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyClientSyncFailureStateTest {

    private lateinit var context: Context
    private lateinit var db: FireflySyncDatabase
    private lateinit var dao: FireflySyncDao
    private lateinit var client: FireflyClient
    private lateinit var engine: MockEngine

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, FireflySyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.fireflySyncDao()
        client = FireflyClient(
            context,
            dao,
            mockk<BudgetRepository>(relaxed = true),
            mockk<SubscriptionRepository>(relaxed = true),
            mockk<TransactionRepository>(relaxed = true),
            DiagnosticLogger(context)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun stubEngine(
        searchStatus: HttpStatusCode = HttpStatusCode.OK,
        searchBody: String = EMPTY_SEARCH,
        searchHeaders: Headers = Headers.Empty,
        writeStatus: HttpStatusCode = HttpStatusCode.InternalServerError
    ) {
        engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/search/transactions") ->
                    respond(searchBody, searchStatus, searchHeaders)

                request.method == HttpMethod.Post || request.method == HttpMethod.Put ->
                    respond("""{"message":"server error"}""", writeStatus)

                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        client.httpClientForTests = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun tx(id: Long, hash: String) = TransactionEntity(
        id = id,
        amount = BigDecimal("12.34"),
        merchantName = "Cashiro H1H3 test",
        category = "General",
        transactionType = TransactionType.EXPENSE,
        dateTime = LocalDateTime.of(2026, 1, 1, 12, 0),
        description = "inconclusive-search regression",
        bankName = "Sign 6671",
        transactionHash = hash,
        currency = "AED",
        updatedAt = LocalDateTime.now()
    )

    private fun runBatch(syncable: FireflyClient.SyncableTransaction, txId: Long) = runBlocking {
        client.syncTransactionsBatch(
            syncables = listOf(syncable),
            baseUrl = BASE_URL,
            accessToken = "pat",
            defaultAssetAccount = "Sign 6671",
            stateByTx = dao.statesFor(listOf(txId)).associateBy { it.localTransactionId }
        )
    }

    private fun requestsOf(method: HttpMethod) = engine.requestHistory.count { it.method == method }

    private fun stateOf(txId: Long) = runBlocking { dao.stateFor(txId).first() }

    // ── H1: inconclusive search must not create ──────────────────────────────

    @Test
    fun rateLimitedSearchDefersTheCreateAndRecordsErrorState() {
        val syncable = FireflyClient.SyncableTransaction(tx(id = 11, hash = "h11"))
        stubEngine(
            searchStatus = HttpStatusCode.TooManyRequests,
            searchBody = "Too Many Requests",
            searchHeaders = Headers.build { append(HttpHeaders.RetryAfter, "12") }
        )

        val result = runBatch(syncable, txId = 11L)

        assertEquals("only the search may run", 1, engine.requestHistory.size)
        assertEquals("an inconclusive search must never POST", 0, requestsOf(HttpMethod.Post))
        assertEquals(1, result.total)
        assertEquals(0, result.created)
        assertEquals(1, result.failed)

        val state = stateOf(11L)
        assertNotNull("H1 deferral must leave an error state behind", state)
        assertNull("a deferred row stays unsynced", state!!.syncedAt)
        assertTrue("lastError=${state.lastError}", state.lastError?.contains("search inconclusive") == true)
        assertTrue("lastError must name the 429", state.lastError?.contains("429") == true)
        assertTrue("Retry-After must be surfaced", state.lastError?.contains("12") == true)
        assertEquals(1, runBlocking { dao.statesWithError().first().size })
    }

    // ── H3: failed create ────────────────────────────────────────────────────

    @Test
    fun failedCreateRecordsErrorStateAndStaysUnsynced() {
        val syncable = FireflyClient.SyncableTransaction(tx(id = 12, hash = "h12"))
        stubEngine(searchBody = EMPTY_SEARCH, writeStatus = HttpStatusCode.InternalServerError)

        val result = runBatch(syncable, txId = 12L)

        assertEquals(1, requestsOf(HttpMethod.Post))
        assertEquals(1, result.failed)

        val state = stateOf(12L)
        assertNotNull("a failed create must leave a sidecar row", state)
        assertNull(state!!.syncedAt)
        assertTrue("lastError=${state.lastError}", state.lastError?.startsWith("create failed:") == true)
        assertTrue("lastError must name the status", state.lastError?.contains("500") == true)
        assertEquals(1, runBlocking { dao.errorCount().first() })
    }

    // ── H3: failed update (the review's dead-code branch) ────────────────────

    @Test
    fun failedUpdateRecordsErrorAndKeepsTheRemoteIdentity() {
        val syncable = FireflyClient.SyncableTransaction(tx(id = 13, hash = "h13"))
        runBlocking {
            dao.markSynced(
                txId = 13L,
                externalId = "pennywise-h13",
                remoteTxId = "9001",
                updatedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                source = "Sign 6671",
                destination = "General",
                syncedAt = LocalDateTime.of(2026, 1, 2, 0, 0)
            )
        }
        // A remote row we own, with no updated_at => local wins => UPDATE path.
        stubEngine(
            searchBody = """{"data":[{"id":"9001","attributes":{"transactions":[{
                "type":"withdrawal","amount":"12.34","date":"2026-01-01",
                "description":"Cashiro H1H3 test","external_id":"pennywise-h13",
                "source_name":"Sign 6671","transaction_journal_id":"5001"}]}}]}""",
            writeStatus = HttpStatusCode.InternalServerError
        )

        val result = runBatch(syncable, txId = 13L)

        assertEquals("the failed branch is the PUT", 1, requestsOf(HttpMethod.Put))
        assertEquals("a failed update must never fall back to POST", 0, requestsOf(HttpMethod.Post))
        assertEquals(1, result.failed)

        val state = stateOf(13L)
        assertNotNull(state)
        assertTrue("lastError=${state!!.lastError}", state.lastError?.contains("update failed") == true)
        assertEquals("9001", state.fireflyTransactionId)
        assertEquals("pennywise-h13", state.externalId)
        assertNotNull("the row is still a synced row, just errored", state.syncedAt)
        assertEquals(1, runBlocking { dao.statesWithError().first().size })
    }

    @Test
    fun retryAfterSuccessClearsTheErrorState() {
        val syncable = FireflyClient.SyncableTransaction(tx(id = 14, hash = "h14"))
        stubEngine(
            searchStatus = HttpStatusCode.TooManyRequests,
            searchBody = "Too Many Requests",
            searchHeaders = Headers.build { append(HttpHeaders.RetryAfter, "5") }
        )
        assertEquals(1, runBatch(syncable, txId = 14L).failed)
        assertNotNull(stateOf(14L)?.lastError)

        // A later run where the search answers cleanly and the create succeeds.
        stubEngine(searchBody = EMPTY_SEARCH, writeStatus = HttpStatusCode.OK)
        // Give the client a body it can read a created id/updatedAt from.
        client.httpClientForTests = HttpClient(
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Get -> respond(EMPTY_SEARCH)
                    else -> respond(
                        """{"data":{"id":"9100","attributes":{"updated_at":"2026-01-01T00:00:00Z",
                            "transactions":[{"type":"withdrawal","amount":"12.34","date":"2026-01-01",
                            "description":"Cashiro H1H3 test","external_id":"pennywise-h14",
                            "transaction_journal_id":"5100"}]}}}"""
                    )
                }
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val result = runBatch(syncable, txId = 14L)

        assertEquals(1, result.created)
        val state = stateOf(14L)!!
        assertNull("a successful re-run must clear lastError", state.lastError)
        assertNotNull(state.syncedAt)
        assertEquals("9100", state.fireflyTransactionId)
        assertEquals(0, runBlocking { dao.errorCount().first() })
    }

    private companion object {
        const val BASE_URL = "https://firefly.test"
        const val EMPTY_SEARCH = """{"data":[]}"""
    }
}