package com.pennywiseai.tracker.data.firefly

import android.content.Context
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M6: the receipt upload must follow the real Firefly III v6 attachment flow
 * (docs/waterfly-reference.md §1, waterfly `attachments.dart`):
 *
 *  1. POST /api/v1/attachments  body {title, notes?, attachable_type:
 *     "transaction_journal", attachable_id: <journal id>}
 *  2. stream raw bytes (application/octet-stream + Authorization) to
 *     data.attributes.upload_url
 *
 * The old code POSTed multipart to /attachments/{id}/upload — a route that
 * does not exist in v6, so every upload 404'd. These tests pin the new flow
 * and assert the dead route is never called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyClientAttachmentUploadTest {

    private lateinit var client: FireflyClient
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableMapOf<String, ByteArray>()
    private lateinit var receiptFile: File

    private fun stub(
        createStatus: HttpStatusCode = HttpStatusCode.Created,
        createBody: String = ATTACHMENT_CREATED,
        uploadStatus: HttpStatusCode = HttpStatusCode.OK
    ) {
        client.httpClientForTests = HttpClient(MockEngine { request ->
            requests += request
            bodies[request.url.encodedPath] = request.body.toByteArray()
            when {
                request.url.encodedPath == "/api/v1/attachments" -> respond(
                    createBody, createStatus,
                    Headers.build { append(HttpHeaders.ContentType, "application/vnd.api+json") }
                )
                // Step 2 must answer whichever target the create response points
                // at: the absolute UPLOAD_PATH or a relative upload_url resolved
                // against the instance (the stub used to 404 every relative one).
                request.url.encodedPath.startsWith("/api/v1/attachments/55/") -> respond("", uploadStatus)
                else -> respond("Not Found", HttpStatusCode.NotFound)
            }
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun upload() = runBlocking {
        client.uploadReceipt(BASE_URL, "pat", JOURNAL_ID, receiptFile.absolutePath, title = "Shop receipt")
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
        receiptFile = File.createTempFile("receipt", ".png").apply {
            writeBytes(RECEIPT_BYTES)
            deleteOnExit()
        }
    }

    private suspend fun createBodyJson(): JsonObject {
        val createReq = requests.first { it.url.encodedPath == "/api/v1/attachments" }
        return Json { ignoreUnknownKeys = true }
            .parseToJsonElement(String(bodyBytes(createReq.body), Charsets.UTF_8)) as JsonObject
    }

    /** Body of a captured request; `OutgoingContent.toByteArray()` is suspend, so
     *  this runs inside the MockEngine handler where the request was captured. */
    private suspend fun bodyBytes(content: OutgoingContent): ByteArray = content.toByteArray()

    // ── happy path: two-step flow, correct body, upload_url targeted ────────

    @Test
    fun twoStepFlowCreatesRecordThenUploadsToUploadUrl() {
        stub()
        assertTrue("upload must succeed", upload())

        assertEquals(2, requests.size)
        val createReq = requests[0]
        val uploadReq = requests[1]

        assertEquals("/api/v1/attachments", createReq.url.encodedPath)
        assertEquals(HttpMethod.Post, createReq.method)

        val body = runBlocking { createBodyJson() }
        assertEquals("transaction_journal", body["attachable_type"]?.jsonPrimitive?.content)
        assertEquals(JOURNAL_ID, body["attachable_id"]?.jsonPrimitive?.content)
        assertEquals("Shop receipt", body["title"]?.jsonPrimitive?.content)

        // Step 2 goes to the upload_url from the create response, raw bytes.
        assertEquals(UPLOAD_PATH, uploadReq.url.encodedPath)
        assertEquals(HttpMethod.Post, uploadReq.method)
        // Ktor carries a request body's content type on OutgoingContent, not in
        // HttpRequestData.headers — the MockEngine capture shows the upload body as
        // application/octet-stream while its headers map has no Content-Type (same
        // for the JSON create). Real engines render exactly this as the wire
        // Content-Type header, so pin the contract where Ktor actually models it.
        assertEquals(
            "application/octet-stream",
            uploadReq.body.contentType?.toString()
        )
        assertEquals("Bearer pat", uploadReq.headers[HttpHeaders.Authorization])
        assertEquals(RECEIPT_BYTES.size, bodies.getValue(UPLOAD_PATH).size)
        assertTrue(
            "raw bytes streamed",
            bodies.getValue(UPLOAD_PATH).contentEquals(RECEIPT_BYTES)
        )
    }

    @Test
    fun deadUploadRouteIsNeverCalled() {
        stub()
        upload()
        assertTrue(
            "no /attachments/{id}/upload may be requested: ${requests.map { it.url.encodedPath }}",
            requests.none { it.url.encodedPath.matches(Regex("/api/v1/attachments/\\d+/upload")) }
        )
    }

    @Test
    fun relativeUploadUrlIsResolvedAgainstInstance() {
        stub(
            createBody = """{"data":{"id":"55","attributes":{""" +
                """"upload_url":"/api/v1/attachments/55/files/abcdef"}}}"""
        )
        assertTrue(upload())
        assertEquals("/api/v1/attachments/55/files/abcdef", requests[1].url.encodedPath)
    }

    // ── failure paths: still best-effort false, error envelope surfaced ─────

    @Test
    fun createValidationFailureIsFalseAndEnvelopeParsed() {
        stub(
            createStatus = HttpStatusCode.UnprocessableEntity,
            createBody = """{"errors":{"attachable_id":["Journal does not exist"]},"message":"The given data was invalid."}"""
        )
        assertFalse("best-effort: a failed create must not throw or block the sync", upload())
        assertEquals("no step-2 when there is no upload_url", 1, requests.size)
    }

    @Test
    fun missingUploadUrlMeansNoUploadAttempt() {
        stub(createBody = """{"data":{"id":"55","attributes":{"embed_url":"https://firefly.test/attachment/55"}}}""")
        assertFalse(upload())
        assertEquals(1, requests.size)
        assertTrue("embed_url must never be used as an upload target", requests.none { it.url.encodedPath.contains("/attachment/") })
    }

    @Test
    fun uploadStepFailureIsBestEffortFalse() {
        stub(uploadStatus = HttpStatusCode.InternalServerError)
        assertFalse(upload())
        assertEquals(2, requests.size)
    }

    @Test
    fun attributesUrlIsLastResortFallback() {
        stub(
            createBody = """{"data":{"id":"55","attributes":{"url":"$BASE_URL$UPLOAD_PATH"}}}"""
        )
        assertTrue(upload())
        assertEquals(UPLOAD_PATH, requests[1].url.encodedPath)
    }

    private companion object {
        const val BASE_URL = "https://firefly.test"
        const val JOURNAL_ID = "777"
        const val UPLOAD_PATH = "/api/v1/attachments/55/upload-target"
        // PNG magic: 0x89 is > Byte.MAX_VALUE, so it needs an explicit narrowing.
        val RECEIPT_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val ATTACHMENT_CREATED =
            """{"data":{"id":"55","attributes":{"title":"Shop receipt",""" +
                """"upload_url":"https://firefly.test/api/v1/attachments/55/upload-target",""" +
                """"embed_url":"https://firefly.test/attachment/55"}}}"""
    }
}
