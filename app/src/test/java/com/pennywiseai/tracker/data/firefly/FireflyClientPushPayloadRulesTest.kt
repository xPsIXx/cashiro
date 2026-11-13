package com.pennywiseai.tracker.data.firefly

import android.content.Context
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
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * L1: push payloads must send `apply_rules=false` and `fire_webhooks=false`
 * (docs/waterfly-reference.md §6). With the Firefly defaults (true), the user's
 * server rules mutate the journal we just synced and the next PULL writes the
 * rule-mutated description/category back into local rows — silent divergence.
 * Rules must keep firing only for edits the user makes on the Firefly web side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyClientPushPayloadRulesTest {

    private lateinit var client: FireflyClient
    private val requests = mutableListOf<HttpRequestData>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        client = FireflyClient(
            context,
            mockk<FireflySyncDao>(relaxed = true),
            mockk<BudgetRepository>(relaxed = true),
            mockk<SubscriptionRepository>(relaxed = true),
            mockk<TransactionRepository>(relaxed = true),
            DiagnosticLogger(context)
        )
    }

    private fun stub(engine: MockEngine) {
        client.httpClientForTests = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun jsonHeaders() = Headers.build { append(HttpHeaders.ContentType, "application/vnd.api+json") }

    private fun sampleTx() = TransactionEntity(
        id = 42L,
        amount = BigDecimal("12.34"),
        merchantName = "Test Shop",
        category = "Groceries",
        transactionType = TransactionType.EXPENSE,
        dateTime = LocalDateTime.of(2026, 1, 15, 12, 0),
        transactionHash = "hash-l1"
    )

    private fun bodyOf(request: HttpRequestData): JsonObject =
        json.parseToJsonElement(String(request.body.toByteArray(), Charsets.UTF_8)) as JsonObject

    private fun flag(root: JsonObject, name: String): Boolean? = root[name]?.jsonPrimitive?.booleanOrNull

    private fun assertPushFlags(body: JsonObject, label: String) {
        assertEquals("$label: apply_rules must be false", false, flag(body, "apply_rules"))
        assertEquals("$label: fire_webhooks must be false", false, flag(body, "fire_webhooks"))
    }

    // ── CREATE payload (POST /transactions) ──────────────────────────────────

    @Test
    fun createPayloadCarriesApplyRulesFalseAndFireWebhooksFalse() {
        var capturedCreate: JsonObject? = null
        stub(MockEngine { request ->
            requests += request
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/search/transactions" ->
                    respond("""{"data":[]}""", HttpStatusCode.OK, jsonHeaders())
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v1/transactions" -> {
                    capturedCreate = bodyOf(request)
                    respond(CREATED_TX, HttpStatusCode.OK, jsonHeaders())
                }
                else -> respond("Not Found", HttpStatusCode.NotFound)
            }
        })

        val result = runBlocking {
            client.syncTransaction(
                transaction = sampleTx(),
                baseUrl = BASE_URL,
                accessToken = "pat",
                defaultAssetAccount = "Checking Account",
                uploadReceipt = false
            )
        }

        assertTrue("sync must succeed, got $result", result is FireflyClient.SyncResult.Success)
        val body = capturedCreate
        assertNotNull("create payload was never captured", body)
        assertPushFlags(body!!, "create")
        // sanity: this really is our row
        val journalObj = (body["transactions"] as JsonArray).first() as JsonObject
        assertEquals("pennywise-hash-l1", journalObj["external_id"]?.jsonPrimitive?.content)
    }

    // ── UPDATE payload (PUT /transactions/{id}) ──────────────────────────────

    @Test
    fun updatePayloadCarriesApplyRulesFalseAndFireWebhooksFalse() {
        var capturedUpdate: JsonObject? = null
        stub(MockEngine { request ->
            requests += request
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/search/transactions" ->
                    respond(REMOTE_OURS, HttpStatusCode.OK, jsonHeaders())
                request.method == HttpMethod.Put && request.url.encodedPath == "/api/v1/transactions/9001" -> {
                    capturedUpdate = bodyOf(request)
                    respond(REMOTE_OURS_UPDATED, HttpStatusCode.OK, jsonHeaders())
                }
                else -> respond("Not Found", HttpStatusCode.NotFound)
            }
        })

        val tx = sampleTx().copy(updatedAt = LocalDateTime.of(2026, 3, 1, 0, 0))
        // Local is newer than the last sync; remote unchanged since last sync
        // → the batch takes the PUSH-UPDATE branch (PUT /transactions/9001).
        val state = FireflyTransactionStateEntity(
            localTransactionId = tx.id,
            externalId = "pennywise-hash-l1",
            fireflyTransactionId = "9001",
            fireflyUpdatedAt = LocalDateTime.of(2026, 2, 1, 0, 0),
            syncedAt = LocalDateTime.of(2026, 2, 15, 0, 0)
        )

        val result = runBlocking {
            client.syncTransactionsBatch(
                syncables = listOf(FireflyClient.SyncableTransaction(tx)),
                baseUrl = BASE_URL,
                accessToken = "pat",
                defaultAssetAccount = "Checking Account",
                uploadReceipts = false,
                stateByTx = mapOf(tx.id to state)
            )
        }

        assertEquals(1, result.details.size)
        assertTrue("expected a push update, got ${result.details.first().status}",
            result.details.first().status == FireflyClient.ItemResult.ItemStatus.SUCCESS)
        val body = capturedUpdate
        assertNotNull("update payload was never captured", body)
        assertPushFlags(body!!, "update")
    }

    private companion object {
        const val BASE_URL = "https://firefly.test"
        const val CREATED_TX = """{"data":{"id":"9001","attributes":{"updated_at":"2026-01-15T00:00:00Z",""" +
            """"transaction_type":"withdrawal","external_id":"pennywise-hash-l1",""" +
            """"transactions":[{"transaction_journal_id":"5001"}]}}}"""
        const val REMOTE_OURS = """{"data":[{"id":"9001","attributes":{"updated_at":"2026-02-01T00:00:00Z",""" +
            """"transactions":[{"type":"withdrawal","amount":"12.34","date":"2026-01-15",""" +
            """"description":"Test Shop","external_id":"pennywise-hash-l1"}]}}]}"""
        const val REMOTE_OURS_UPDATED = """{"data":{"id":"9001","attributes":{"updated_at":"2026-03-02T00:00:00Z",""" +
            """"transactions":[{"type":"withdrawal","amount":"12.34","date":"2026-01-15",""" +
            """"description":"Test Shop","external_id":"pennywise-hash-l1"}]}}}"""
    }
}
