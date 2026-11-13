package com.pennywiseai.tracker.data.firefly

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1 retry policy: pins the *production* policy ([installFireflyRetryPolicy],
 * installed by `FireflyClient.defaultHttpClient`) against a MockEngine, so the
 * 429 handling and the "never blindly re-POST a create" rule are covered
 * without network or emulator.
 *
 * The `sleeper` seam records the backoff Ktor computed instead of sleeping, so
 * `Retry-After` and exponential growth are asserted directly.
 */
class FireflyClientRetryPolicyTest {

    private val delays = mutableListOf<Long>()

    /**
     * Attempt log taken from inside the handler: `MockEngine.requestHistory`
     * only records requests whose handler *returned*, so counting there would
     * silently read 0 for every throwing case.
     */
    private val attempted = mutableListOf<HttpMethod>()

    private fun buildClient(handler: MockRequestHandler): HttpClient {
        val engine = MockEngine { request ->
            attempted += request.method
            handler(request)
        }
        return HttpClient(engine) {
            installFireflyRetryPolicy(sleeper = { millis -> delays.add(millis) })
        }
    }

    private fun retryAfter(seconds: Int): Headers =
        Headers.build { append(HttpHeaders.RetryAfter, seconds.toString()) }

    private fun attempts(method: HttpMethod) = attempted.count { it == method }

    @Test
    fun rateLimitedSearchIsRetriedAndHonoursRetryAfter() = runBlocking {
        var first = true
        val client = buildClient {
            if (first) {
                first = false
                respond("Too Many Requests", HttpStatusCode.TooManyRequests, retryAfter(30))
            } else {
                respond("""{"data":[]}""")
            }
        }

        val response = client.get("$BASE/search/transactions?query=external_id_is")
        response.bodyAsText()

        assertEquals("429 must be retried until it succeeds", HttpStatusCode.OK, response.status)
        assertEquals("one retry expected", 2, attempts(HttpMethod.Get))
        assertEquals("one backoff expected, got $delays", 1, delays.size)
        assertTrue("Retry-After: 30 must raise the wait to >=30s, got $delays", delays[0] >= 30_000L)
    }

    @Test
    fun serverErrorOnGetIsRetriedWithExponentialBackoff() = runBlocking {
        val client = buildClient { respond("unavailable", HttpStatusCode.ServiceUnavailable) }
        val response = client.get("$BASE/search/transactions?query=external_id_is")

        assertEquals("the last response is surfaced once retries are spent", HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(1 + FIREFLY_RETRY_MAX_ATTEMPTS, attempts(HttpMethod.Get))
        assertEquals(FIREFLY_RETRY_MAX_ATTEMPTS, delays.size)
        assertTrue("backoff must grow: $delays", delays[0] < delays[1] && delays[1] < delays[2])
    }

    @Test
    fun ioExceptionOnGetIsRetried() = runBlocking {
        val client = buildClient { throw java.io.IOException("connection reset") }
        runCatching { client.get("$BASE/search/transactions?query=x") }
        assertEquals("reads may always be re-issued", 1 + FIREFLY_RETRY_MAX_ATTEMPTS, attempts(HttpMethod.Get))
    }

    @Test
    fun serverErrorOnPostIsNotRetriedBecauseTheCreateMayHaveCommitted() = runBlocking {
        val client = buildClient { respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError) }
        runCatching { client.post("$BASE/transactions") { setBody("{}") } }

        assertEquals("a 5xx create must be attempted exactly once", 1, attempts(HttpMethod.Post))
        assertTrue("no retry delay expected, got $delays", delays.isEmpty())
    }

    @Test
    fun readTimeoutOnPostIsNotRetried() = runBlocking {
        val client = buildClient { throw java.net.SocketTimeoutException("Read timed out") }
        runCatching { client.post("$BASE/transactions") { setBody("{}") } }

        assertEquals("a timed-out create must not be re-POSTed", 1, attempts(HttpMethod.Post))
        assertTrue("no retry delay expected, got $delays", delays.isEmpty())
    }

    @Test
    fun connectFailureOnPostIsRetriedBecauseNothingReachedTheServer() = runBlocking {
        val client = buildClient { throw java.net.ConnectException("Connection refused") }
        runCatching { client.post("$BASE/transactions") { setBody("{}") } }

        assertEquals("pre-commit failures are safe to repeat", 1 + FIREFLY_RETRY_MAX_ATTEMPTS, attempts(HttpMethod.Post))
        assertEquals(FIREFLY_RETRY_MAX_ATTEMPTS, delays.size)
    }

    @Test
    fun rateLimitedCreateIsRetriedBecauseItWasNeverProcessed() = runBlocking {
        val client = buildClient { respond("slow down", HttpStatusCode.TooManyRequests, retryAfter(1)) }
        runCatching { client.post("$BASE/transactions") { setBody("{}") } }

        assertEquals("429 means the journal was never written", 1 + FIREFLY_RETRY_MAX_ATTEMPTS, attempts(HttpMethod.Post))
    }

    @Test
    fun unknownRouteIsNotRetried() = runBlocking {
        val client = buildClient { respondError(HttpStatusCode.NotFound) }
        val response = client.get("$BASE/search/transactions?query=x")

        assertEquals("404 is definitive, not transient", HttpStatusCode.NotFound, response.status)
        assertEquals(1, attempts(HttpMethod.Get))
        assertTrue("no retry delay expected, got $delays", delays.isEmpty())
    }

    private companion object {
        const val BASE = "https://firefly.test/api/v1"
    }
}
