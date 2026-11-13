package com.pennywiseai.tracker.data.firefly

import android.content.Context
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
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException

/**
 * H1 classification test: `lookupRemoteTransactionByExternalId` must separate
 * "Firefly answered and has no such row" ([FireflyClient.ExternalIdLookup.NotFound])
 * from "Firefly could not be asked" ([FireflyClient.ExternalIdLookup.Transient]).
 *
 * Before H1 both cases were the same `null`, and the sync loop read `null` as
 * "absent" -> a rate-limited search created a duplicate journal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyClientExternalIdLookupTest {

    private lateinit var client: FireflyClient

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

    private fun stubEngine(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = EMPTY_SEARCH,
        headers: Headers = Headers.Empty,
        failWith: Throwable? = null
    ) {
        client.httpClientForTests = HttpClient(MockEngine {
            if (failWith != null) throw failWith
            respond(body, status, headers)
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun lookup(externalId: String = "pennywise-abc") = runBlocking {
        client.lookupRemoteTransactionByExternalId(BASE_URL, "pat", externalId)
    }

    // ── answered, no match => NotFound (a create is legitimate) ──────────────

    @Test
    fun wellFormedEmptySearchIsNotFound() {
        stubEngine(body = EMPTY_SEARCH)
        assertTrue(
            "empty search result must be NotFound, got ${lookup()}",
            lookup() is FireflyClient.ExternalIdLookup.NotFound
        )
    }

    @Test
    fun http404IsNotFound() {
        stubEngine(status = HttpStatusCode.NotFound, body = "Not Found")
        assertTrue("404 must be NotFound", lookup() is FireflyClient.ExternalIdLookup.NotFound)
    }

    @Test
    fun foreignExternalIdRowIsNotFoundForUs() {
        // Search matched a Firefly-native row; it is not ours, so for our
        // external id the answer is still "no match" (pre-H1 semantics kept).
        stubEngine(
            body = """{"data":[{"id":"77","attributes":{"transactions":[{
                "type":"withdrawal","amount":"5.00","date":"2026-01-01",
                "description":"native","external_id":"someone-else-9"}]}}]}"""
        )
        assertTrue("non-matching external id must be NotFound", lookup() is FireflyClient.ExternalIdLookup.NotFound)
    }

    // ── could not ask => Transient (never a reason to create) ────────────────

    @Test
    fun http429IsTransientAndCarriesRetryAfter() {
        stubEngine(
            status = HttpStatusCode.TooManyRequests,
            body = "Too Many Requests",
            headers = Headers.build { append(HttpHeaders.RetryAfter, "30") }
        )
        val result = lookup()
        assertTrue("429 must be Transient, got $result")
        val reason = (result as FireflyClient.ExternalIdLookup.Transient).reason
        assertTrue("reason must name the status: $reason", reason.contains("429"))
        assertTrue("reason must surface Retry-After: $reason", reason.contains("30"))
    }

    @Test
    fun http503IsTransient() {
        stubEngine(status = HttpStatusCode.ServiceUnavailable, body = "unavailable")
        val result = lookup()
        assertTrue("5xx must be Transient, got $result")
        assertTrue((result as FireflyClient.ExternalIdLookup.Transient).reason.contains("503"))
    }

    @Test
    fun http401IsTransientNotNotFound() {
        // An auth failure must never be read as "not on Firefly", or every
        // unsynced row would be created twice once the token works again.
        stubEngine(status = HttpStatusCode.Unauthorized, body = "Unauthorized")
        assertTrue("401 must be Transient", lookup() is FireflyClient.ExternalIdLookup.Transient)
    }

    @Test
    fun networkFailureIsTransient() {
        stubEngine(failWith = SocketTimeoutException("Read timed out"))
        val result = lookup()
        assertTrue("network failure must be Transient, got $result")
        assertTrue((result as FireflyClient.ExternalIdLookup.Transient).reason.startsWith("network:"))
    }

    @Test
    fun malformedSearchBodyIsTransient() {
        stubEngine(body = """{"nonsense":true}""")
        val result = lookup()
        assertTrue("unparseable 200 body must be Transient, got $result")
        assertTrue(
            (result as FireflyClient.ExternalIdLookup.Transient).reason.contains("malformed")
        )
    }

    // ── answered, match => Found ─────────────────────────────────────────────

    @Test
    fun matchingExternalIdIsFoundWithRemoteData() {
        stubEngine(
            body = """{"data":[{"id":"9001","attributes":{"updated_at":"2026-01-01T00:00:00Z",
                "transactions":[{
                    "type":"withdrawal","amount":"12.34","date":"2026-01-01",
                    "description":"Cashiro journal","external_id":"pennywise-abc",
                    "source_name":"Sign 6671","transaction_journal_id":"5001"}]}}]}"""
        )
        val result = lookup()
        assertTrue("matching row must be Found, got $result")
        val remote = (result as FireflyClient.ExternalIdLookup.Found).remote
        assertEquals("9001", remote.fireflyId)
        assertEquals("pennywise-abc", remote.externalId)
        assertEquals(0, remote.amount.compareTo(java.math.BigDecimal("12.34")))
        assertNotNull(remote.updatedAt)
    }

    private companion object {
        const val BASE_URL = "https://firefly.test"
        const val EMPTY_SEARCH = """{"data":[]}"""
    }
}
