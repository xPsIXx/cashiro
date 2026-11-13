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
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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

/**
 * M5: Firefly list endpoints must be walked page by page (`page`+`limit=100`,
 * loop while `meta.pagination.current_page < last_page`, with a `links.next`
 * fallback), exactly like the proven waterfly-iii client (docs/waterfly-reference.md §2).
 *
 * Before this, `getAccounts` had no limit at all and categories/budgets/
 * budget-limits asked for `limit=1000`, which instances silently clamp —
 * users with >50/100 rows got truncated lists and `syncCategories` then
 * re-created rows that already existed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyClientPaginationTest {

    private lateinit var client: FireflyClient
    private val requests = mutableListOf<HttpRequestData>()

    private fun jsonHeaders() = Headers.build {
        append(HttpHeaders.ContentType, "application/vnd.api+json")
    }

    /** Envelope with meta.pagination (the shape v6 actually returns). */
    private fun metaPage(path: String, page: Int, lastPage: Int, names: List<String>): String {
        val data = names.joinToString(",") { """{"id":"$it","attributes":{"name":"$it"}}""" }
        return """{"data":[$data],"meta":{"pagination":{"total":${names.size},"count":${names.size},"""" +
            """"per_page":100,"current_page":$page,"total_pages":$lastPage,"links":{"next":null}}}}"""
    }

    private fun stub(handler: suspend (HttpRequestData) -> io.ktor.client.engine.mock.HttpResponseData) {
        client.httpClientForTests = HttpClient(MockEngine { request ->
            requests += request
            handler(request)
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

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

    private fun pathOf(request: HttpRequestData) = request.url.encodedPath
    private fun queryOf(request: HttpRequestData) = request.url.encodedQuery ?: ""

    // ── meta.pagination loop across 3 pages ─────────────────────────────────

    @Test
    fun categoriesAcrossThreePagesAreAllReturned() = runBlocking {
        stub { request ->
            val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
            val names = when (page) {
                1 -> (1..100).map { "c$it" }
                2 -> (101..200).map { "c$it" }
                3 -> (201..250).map { "c$it" }
                else -> emptyList()
            }
            respond(metaPage("/categories", page, 3, names), HttpStatusCode.OK, jsonHeaders())
        }

        val categories = client.getCategories(BASE_URL, "pat")

        assertEquals("all 250 rows across 3 pages", 250, categories.size)
        assertEquals(3, requests.size)
        requests.forEach { assertTrue("limit=100 requested: ${queryOf(it)}", queryOf(it).contains("limit=100")) }
        assertEquals(listOf("1", "2", "3"), requests.map { it.url.parameters["page"] })
    }

    @Test
    fun accountsPaginationFixedTruncatedList() = runBlocking {
        stub { request ->
            val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
            val names = when (page) {
                1 -> (1..100).map { "acct$it" }
                2 -> (101..200).map { "acct$it" }
                3 -> (201..250).map { "acct$it" }
                else -> emptyList()
            }
            respond(metaPage("/accounts", page, 3, names), HttpStatusCode.OK, jsonHeaders())
        }

        val accounts = client.getAccounts(BASE_URL, "pat")

        assertEquals("previously page 1 only (~50-100 rows); now all 250", 250, accounts.size)
        assertEquals(3, requests.size)
        assertTrue("type filter preserved: ${queryOf(requests.first())}", queryOf(requests.first()).contains("type=asset"))
    }

    @Test
    fun budgetsAcrossPagesReturnEveryRow() = runBlocking {
        stub { request ->
            val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
            val names = when (page) {
                1 -> (1..100).map { "b$it" }
                2 -> (101..130).map { "b$it" }
                else -> emptyList()
            }
            respond(metaPage("/budgets", page, 2, names), HttpStatusCode.OK, jsonHeaders())
        }

        val budgets = client.getBudgets(BASE_URL, "pat")
        assertEquals(130, budgets.size)
        assertEquals(2, requests.size)
    }

    @Test
    fun budgetLimitsKeepDateFiltersOnEveryPage() = runBlocking {
        stub { request ->
            val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
            val data = (1..3).map { i ->
                """{"id":"${page}x$i","attributes":{"budget_id":"b1","start":"2026-01-01",""" +
                    """"end":"2026-01-31","amount":"10.00","currency_code":"EUR"}}"""
            }.joinToString(",")
            val body = """{"data":[$data],"meta":{"pagination":{"current_page":$page,"last_page":2,"total_pages":2}}}"""
            respond(body, HttpStatusCode.OK, jsonHeaders())
        }

        val limits = client.getBudgetLimits(BASE_URL, "pat", startDate = "2026-01-01", endDate = "2026-01-31")

        assertEquals(6, limits.size)
        assertEquals(2, requests.size)
        requests.forEach { q ->
            assertTrue("start kept: $q", q.contains("start=2026-01-01"))
            assertTrue("end kept: $q", q.contains("end=2026-01-31"))
            assertTrue("limit=100: $q", q.contains("limit=100"))
        }
    }

    // ── links.next fallback (no meta.pagination) ────────────────────────────

    @Test
    fun linksNextFallbackFollowsInstanceUrl() = runBlocking {
        stub { request ->
            if (pathOf(request) == "/api/v1/categories") {
                respond(
                    """{"data":[{"id":"p1","attributes":{"name":"A"}}],""" +
                        """"links":{"next":"$BASE_URL/custom/p2?cursor=xyz"}}""",
                    HttpStatusCode.OK, jsonHeaders()
                )
            } else {
                // The followed URL carries no pagination info at all → loop must stop.
                respond("""{"data":[{"id":"p2","attributes":{"name":"B"}}]}""", HttpStatusCode.OK, jsonHeaders())
            }
        }

        val categories = client.getCategories(BASE_URL, "pat")

        assertEquals(listOf("A", "B"), categories)
        assertEquals(2, requests.size)
        assertEquals("/custom/p2", pathOf(requests[1]))
        assertTrue("followed verbatim: ${queryOf(requests[1])}", queryOf(requests[1]).contains("cursor=xyz"))
    }

    // ── failure semantics: never throw, partial beats nothing ───────────────

    @Test
    fun page2FailureKeepsPage1Rows() = runBlocking {
        stub { request ->
            val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
            if (page == 1) {
                respond(metaPage("/categories", 1, 3, listOf("a", "b")), HttpStatusCode.OK, jsonHeaders())
            } else {
                respond("nope", HttpStatusCode.InternalServerError, jsonHeaders())
            }
        }

        val categories = client.getCategories(BASE_URL, "pat")
        assertEquals(listOf("a", "b"), categories)
        assertEquals("stops at the failed page", 2, requests.size)
    }

    @Test
    fun malformedEnvelopeIsNotRetriedForever() = runBlocking {
        stub { respond("""{"nonsense":true}""", HttpStatusCode.OK, jsonHeaders()) }
        val categories = client.getCategories(BASE_URL, "pat")
        assertTrue(categories.isEmpty())
        assertEquals("single page, no loop", 1, requests.size)
    }

    // ── search/transactions pagination with EXACT H1 semantics ──────────────

    private fun searchPage(page: Int, lastPage: Int, externalId: String?): String {
        val data = if (externalId == null) "" else
            """[{"id":"$page","attributes":{"updated_at":"2026-01-0${page}T00:00:00Z","transactions":[{""" +
                """"type":"withdrawal","amount":"1.00","date":"2026-01-01","description":"d$page",""" +
                """"external_id":"$externalId"}]}}]"""
        return """{"data":$data,"meta":{"pagination":{"current_page":$page,"last_page":$last_page,"total_pages":$last_page}}}"""
    }

    private fun lookup(externalId: String) = runBlocking {
        client.lookupRemoteTransactionByExternalId(BASE_URL, "pat", externalId)
    }

    @Test
    fun searchMatchOnLaterPageIsFoundNotJustPage1() {
        // total > 1: the matching row lives on page 2. Old code read page 1 only
        // and wrongly answered NotFound -> duplicate create.
        stub { request ->
            val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
            val body = if (page == 2) searchPage(2, 2, "pennywise-abc") else searchPage(1, 2, "other-1")
            respond(body, HttpStatusCode.OK, jsonHeaders())
        }

        val result = lookup("pennywise-abc")
        assertTrue("page-2 match must be Found, got $result", result is FireflyClient.ExternalIdLookup.Found)
        assertEquals(2, requests.size)
        requests.forEach { assertTrue("search paginated with limit: ${queryOf(it)}", queryOf(it).contains("limit=100")) }
    }

    @Test
    fun emptyButWellFormedSearchPageIsStillNotFound() {
        stub { respond("""{"data":[],"meta":{"pagination":{"current_page":1,"last_page":1,"total_pages":1}}}""", HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(lookup("pennywise-abc") is FireflyClient.ExternalIdLookup.NotFound)
    }

    @Test
    fun searchTransportFailureIsStillTransientAfterPagination() {
        stub { request ->
            val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
            if (page == 1) respond(searchPage(1, 2, "other-1"), HttpStatusCode.OK, jsonHeaders())
            else respond("rate limited", HttpStatusCode.TooManyRequests, Headers.build { append(HttpHeaders.RetryAfter, "12") })
        }
        val result = lookup("pennywise-abc")
        assertTrue("page-2 429 must be Transient, got $result", result is FireflyClient.ExternalIdLookup.Transient)
        val reason = (result as FireflyClient.ExternalIdLookup.Transient).reason
        assertTrue("reason names 429: $reason", reason.contains("429"))
        assertTrue("reason surfaces Retry-After: $reason", reason.contains("12"))
    }

    @Test
    fun search404IsDefinitiveNotFoundUnderPagination() {
        stub { respond("Not Found", HttpStatusCode.NotFound, jsonHeaders()) }
        assertTrue(lookup("pennywise-abc") is FireflyClient.ExternalIdLookup.NotFound)
    }

    private companion object {
        const val BASE_URL = "https://firefly.test"
    }
}
