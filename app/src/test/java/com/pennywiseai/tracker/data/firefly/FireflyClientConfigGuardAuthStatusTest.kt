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
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * L2 + L3:
 *  - L2: with neither an account mapping nor a default asset, the batch must surface an
 *    actionable "configure your Firefly account" error instead of POSTing to the literal
 *    'Checking Account' fallback and eating an indistinguishable 422.
 *  - L3: auth-error classification must use the HTTP status recorded on the ItemResult,
 *    not message substring matching (a 401 body without the literal "401" used to miss
 *    the token-expired path; any message containing "401" used to mis-trigger it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyClientConfigGuardAuthStatusTest {

    private lateinit var client: FireflyClient
    private lateinit var dao: FireflySyncDao
    private val requests = mutableListOf<HttpRequestData>()

    @Before
    fun setUp() {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        dao = mockk<FireflySyncDao>(relaxed = true)
        client = FireflyClient(
            context,
            dao,
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

    private fun tx(bank: String? = null, last4: String? = null) = TransactionEntity(
        id = 77L,
        amount = BigDecimal("20.00"),
        merchantName = "Corner Store",
        category = "Groceries",
        transactionType = TransactionType.EXPENSE,
        dateTime = LocalDateTime.of(2026, 2, 1, 9, 30),
        transactionHash = "hash-l23",
        bankName = bank,
        accountNumber = last4
    )

    private fun emptySearch() = MockEngine { request ->
        requests += request
        when {
            request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/search/transactions" ->
                respond("""{"data":[]}""", HttpStatusCode.OK, jsonHeaders())
            else -> respond("Not Found", HttpStatusCode.NotFound)
        }
    }

    // L2 ───────────────────────────────────────────────────────────────────────

    @Test
    fun noMappingNoDefaultAsset_yieldsConfigError_andIssuesNoHttp() {
        stub(emptySearch())

        val result = runBlocking {
            client.syncTransaction(
                transaction = tx(),
                baseUrl = "http://firefly.local",
                accessToken = "pat",
                defaultAssetAccount = null,
                accountMappings = emptyMap(),
                uploadReceipt = false
            )
        }

        assertTrue("expected Error, got $result", result is FireflyClient.SyncResult.Error)
        val msg = (result as FireflyClient.SyncResult.Error).message
        assertTrue("message must be actionable: $msg", msg.contains("No Firefly account configured"))
        assertTrue("message must name the mapping key: $msg", msg.contains("map '"))
        assertTrue("guard must short-circuit before any HTTP, saw ${requests.size} requests", requests.isEmpty())
        coVerify { dao.markError(77L, io.mockk.match { it.contains("No Firefly account configured") }) }
    }

    @Test
    fun mappingPresentButNoDefaultAsset_passesGuard() {
        stub(emptySearch())

        val result = runBlocking {
            client.syncTransaction(
                transaction = tx(bank = "Bank", last4 = "1234"),
                baseUrl = "http://firefly.local",
                accessToken = "pat",
                defaultAssetAccount = null,
                accountMappings = mapOf("Bank**1234" to "My Current"),
                uploadReceipt = false
            )
        }

        // Guard passed: we reached the lookup (the empty search -> create path 404s the
        // POST in this stub, i.e. HTTP actually happened).
        assertTrue("expected Error from stubbed create", result is FireflyClient.SyncResult.Error)
        assertTrue("lookup must have run", requests.any { it.method == HttpMethod.Get })
    }

    // L3 ───────────────────────────────────────────────────────────────────────

    @Test
    fun http401OnCreate_setsAuthError_viaStatusNotMessage() {
        stub(MockEngine { request ->
            requests += request
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/search/transactions" ->
                    respond("""{"data":[]}""", HttpStatusCode.OK, jsonHeaders())
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v1/transactions" ->
                    // Body deliberately lacks the literal "401" — the old substring rule missed this.
                    respond("""{"message":"token revoked"}""", HttpStatusCode.Unauthorized, jsonHeaders())
                else -> respond("Not Found", HttpStatusCode.NotFound)
            }
        })

        val result = runBlocking {
            client.syncTransaction(
                transaction = tx(),
                baseUrl = "http://firefly.local",
                accessToken = "pat",
                defaultAssetAccount = "Checking Account",
                uploadReceipt = false
            )
        }

        assertTrue("expected Error", result is FireflyClient.SyncResult.Error)
        val error = result as FireflyClient.SyncResult.Error
        assertTrue("401 status must classify as auth error: ${error.message}", error.isAuthError)
    }

    @Test
    fun http500OnCreate_isNotAuthError() {
        stub(MockEngine { request ->
            requests += request
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/search/transactions" ->
                    respond("""{"data":[]}""", HttpStatusCode.OK, jsonHeaders())
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v1/transactions" ->
                    respond("""{"message":"boom 401 matches in body are irrelevant"}""", HttpStatusCode.InternalServerError, jsonHeaders())
                else -> respond("Not Found", HttpStatusCode.NotFound)
            }
        })

        val result = runBlocking {
            client.syncTransaction(
                transaction = tx(),
                baseUrl = "http://firefly.local",
                accessToken = "pat",
                defaultAssetAccount = "Checking Account",
                uploadReceipt = false
            )
        }

        assertTrue("expected Error", result is FireflyClient.SyncResult.Error)
        val error = result as FireflyClient.SyncResult.Error
        // The old rule would mis-trigger here via the "401" substring in the body.
        assertEquals("500 must NOT classify as auth error: ${error.message}", false, error.isAuthError)
    }
}
