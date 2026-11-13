package com.pennywiseai.tracker.data.firefly

import android.content.Context
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
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

/**
 * M5 follow-up QA: piggy-banks and recurrences were the last two unpaginated
 * pull floors — a single `?limit=1000` request that Firefly silently clamps to
 * 100 rows/page, making everything past page 1 invisible. Both now run
 * through the shared [paginate] helper; these tests pin that every page is
 * fetched and rows decode identically across page boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyClientPiggyRecurrencePaginationTest {

    private companion object {
        const val BASE_URL = "https://firefly.example"
    }

    private lateinit var client: FireflyClient
    private val requests = mutableListOf<HttpRequestData>()

    private fun jsonHeaders() = Headers.build {
        append(HttpHeaders.ContentType, "application/vnd.api+json")
    }

    private fun metaEnvelope(page: Int, lastPage: Int, dataItems: String): String =
        """{"data":[$dataItems],"meta":{"pagination":{"total":0,"count":0,"per_page":100,""" +
            """"current_page":$page,"last_page":$lastPage,"total_pages":$lastPage,"links":{"next":null}}}}"""

    private fun stub(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
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
        requests.clear()
    }

    private fun piggyItem(n: Int) =
        """{"id":"$n","attributes":{"name":"piggy-$n","target_amount":"$n.00",""" +
            """"current_amount":"2.50","active":true}}"""

    @Test
    fun piggyBanksAcrossTwoPagesAreAllReturnedAndDecoded() = runBlocking {
        stub { request ->
            val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
            val items = when (page) {
                1 -> (1..100).joinToString(",") { piggyItem(it) }
                2 -> (101..130).joinToString(",") { piggyItem(it) }
                else -> ""
            }
            respond(metaEnvelope(page, 2, items), HttpStatusCode.OK, jsonHeaders())
        }

        val piggyBanks = client.getPiggyBanks(BASE_URL, "pat")

        assertEquals("all 130 piggy banks across 2 pages (was 100 max before)", 130, piggyBanks.size)
        assertEquals(2, requests.size)
        requests.forEach { assertTrue("limit=100 requested: ${it.url.encodedQuery}", it.url.encodedQuery.contains("limit=100")) }
        assertEquals(listOf("1", "2"), requests.map { it.url.parameters["page"] })
        assertEquals("piggy-1", piggyBanks.first().name)
        assertEquals("piggy-130", piggyBanks.last().name)
        assertEquals(BigDecimal("130.00"), piggyBanks.last().targetAmount)
    }

    private fun recurrenceItem(n: Int) =
        """{"id":"r$n","attributes":{"title":"sub-$n","type":"withdrawal","active":true,""" +
            """"first_date":"2026-01-15T00:00:00+00:00","updated_at":"2026-02-01T00:00:00+00:00",""" +
            """"repetitions":[{"type":"monthly"}],""" +
            """"transactions":[{"amount":"$n.99","source_name":"Wallet","destination_name":"Netflix"}]}}"""

    @Test
    fun recurrencesAcrossTwoPagesAreAllReturnedAndDecoded() = runBlocking {
        stub { request ->
            val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
            val items = when (page) {
                1 -> (1..100).joinToString(",") { recurrenceItem(it) }
                2 -> (101..112).joinToString(",") { recurrenceItem(it) }
                else -> ""
            }
            respond(metaEnvelope(page, 2, items), HttpStatusCode.OK, jsonHeaders())
        }

        val recurrences = client.getRecurringTransactions(BASE_URL, "pat")

        assertEquals("all 112 recurrences across 2 pages (was 100 max before)", 112, recurrences.size)
        assertEquals(2, requests.size)
        assertEquals(listOf("1", "2"), requests.map { it.url.parameters["page"] })
        val first = recurrences.first()
        val last = recurrences.last()
        assertEquals("sub-1", first.title)
        assertEquals("sub-112", last.title)
        assertEquals(BigDecimal("112.99"), last.amount)
        assertEquals("monthly", last.repeatFrequency)
        assertEquals("Netflix", last.destinationName)
    }

    @Test
    fun emptyRecurrencePageStopsTheLoopImmediately() = runBlocking {
        stub { request ->
            respond(metaEnvelope(1, 1, ""), HttpStatusCode.OK, jsonHeaders())
        }
        assertEquals(0, client.getRecurringTransactions(BASE_URL, "pat").size)
        assertEquals(1, requests.size)
    }
}
