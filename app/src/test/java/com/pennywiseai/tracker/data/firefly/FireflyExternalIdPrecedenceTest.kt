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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * M3 regression: `computeExternalId` falls back to `pennywise-<autoincrement id>`
 * when `transactionHash` is blank, and any hash-changing edit produced a new
 * id — orphaning the remote row and creating a duplicate on the next search.
 *
 * The engine must now resolve external ids through the sidecar-first
 * precedence chain (persisted `FireflyTransactionStateEntity.externalId` >
 * transactionHash > freshly persisted UUID) and pin the winner into the
 * batch it hands [FireflyClient], so the lookup query, the create/update
 * payload and the sidecar row all carry the same id.
 *
 * Proven against the real client + MockEngine: after a hash-changing edit,
 * pass 2 searches the PERSISTED id (never the recomputed one), updates the
 * existing Firefly group (PUT), and creates nothing new (exactly one POST).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyExternalIdPrecedenceTest {

    private lateinit var context: Context
    private lateinit var sidecarDb: FireflySyncDatabase
    private lateinit var sidecarDao: FireflySyncDao
    private lateinit var client: FireflyClient
    private lateinit var engine: FireflySyncEngine

    private val json = Json { ignoreUnknownKeys = true }
    private val searchQueries = mutableListOf<String>()
    private val postBodies = mutableListOf<JsonObject>()
    private val putBodies = mutableListOf<JsonObject>()

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        sidecarDb = Room.inMemoryDatabaseBuilder(context, FireflySyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sidecarDao = sidecarDb.fireflySyncDao()

        client = FireflyClient(
            context,
            sidecarDao,
            mockk<BudgetRepository>(relaxed = true),
            mockk<SubscriptionRepository>(relaxed = true),
            mockk<TransactionRepository>(relaxed = true),
            DiagnosticLogger(context)
        )

        val prefs = mockk<FireflyPreferencesRepository>(relaxed = true)
        every { prefs.syncEnabledFlow } returns flowOf(true)
        every { prefs.getBaseUrl() } returns "https://firefly.test"
        every { prefs.getAccessToken() } returns "pat"
        every { prefs.defaultAssetAccountFlow } returns flowOf("Test Asset")
        every { prefs.accountMappingsFlow } returns flowOf<Map<String, String>>(emptyMap())
        every { prefs.categoryMappingsFlow } returns flowOf<Map<String, String>>(emptyMap())
        every { prefs.includeRawSmsFlow } returns flowOf(false)

        engine = FireflySyncEngine(
            client,
            sidecarDao,
            prefs,
            mockk<TransactionRepository>(relaxed = true),
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

    private fun tx(id: Long, hash: String) = TransactionEntity(
        id = id,
        amount = BigDecimal("300.00"),
        merchantName = "Cashiro external-id test",
        category = "Groceries",
        transactionType = TransactionType.EXPENSE,
        dateTime = LocalDateTime.of(2026, 2, 1, 12, 0),
        bankName = "Test Bank",
        accountNumber = "1234",
        transactionHash = hash,
        currency = "AED",
        updatedAt = LocalDateTime.of(2026, 2, 1, 12, 0)
    )

    private fun jsonHeaders() = Headers.build { append(HttpHeaders.ContentType, "application/vnd.api+json") }

    private suspend fun parseBody(request: io.ktor.client.request.HttpRequestData): JsonObject =
        json.parseToJsonElement(String(bodyBytes(request.body), Charsets.UTF_8)) as JsonObject

    /** Body of a captured request; `OutgoingContent.toByteArray()` is suspend, so
     *  this runs inside the MockEngine handler where the request was captured. */
    private suspend fun bodyBytes(content: OutgoingContent): ByteArray = content.toByteArray()

    private fun foundJson(groupId: String, externalId: String) =
        """{"data":[{"id":"$groupId","attributes":{"updated_at":"2026-02-01T00:00:00Z","transactions":[{"transaction_journal_id":"7100","type":"withdrawal","amount":"300.00","date":"2026-02-01","description":"Cashiro external-id test","source_name":"Test Asset","external_id":"$externalId"}]}}]}"""

    private fun updatedJson(groupId: String, externalId: String) =
        """{"data":{"id":"$groupId","attributes":{"updated_at":"2026-02-05T00:00:00Z","transactions":[{"transaction_journal_id":"7100","type":"withdrawal","amount":"300.00","date":"2026-02-01","description":"Cashiro external-id test","source_name":"Test Asset","external_id":"$externalId"}]}}}"""

    private fun createdJson(groupId: String) =
        """{"data":{"id":"$groupId","attributes":{"updated_at":"2026-02-01T00:00:00Z","transaction_type":"withdrawal","transactions":[{"transaction_journal_id":"7100"}]}}}"""

    /**
     * First search of a test run answers "not on Firefly" (⇒ create); every
     * later search answers as a FOUND row carrying the id the engine actually
     * posted — so a wrong (recomputed) lookup on pass 2 finds nothing and the
     * test fails with a second POST, which is exactly the duplicate M3 fixes.
     */
    private fun stubFirefly(groupId: String) {
        var persistedExternalId: String? = null
        client.httpClientForTests = HttpClient(
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/search/transactions" -> {
                        searchQueries += request.url.parameters["query"] ?: ""
                        val known = persistedExternalId
                        if (searchQueries.size > 1 && known != null) {
                            respond(foundJson(groupId, known), HttpStatusCode.OK, jsonHeaders())
                        } else {
                            respond("""{"data":[]}""", HttpStatusCode.OK, jsonHeaders())
                        }
                    }
                    request.method == HttpMethod.Post && request.url.encodedPath == "/api/v1/transactions" -> {
                        val body = parseBody(request)
                        postBodies += body
                        persistedExternalId =
                            (body["transactions"] as kotlinx.serialization.json.JsonArray)
                                .let { (it.first() as JsonObject) }["external_id"]?.jsonPrimitive?.content
                        respond(createdJson(groupId), HttpStatusCode.OK, jsonHeaders())
                    }
                    request.method == HttpMethod.Put && request.url.encodedPath.startsWith("/api/v1/transactions/") -> {
                        putBodies += parseBody(request)
                        respond(updatedJson(groupId, persistedExternalId ?: ""), HttpStatusCode.OK, jsonHeaders())
                    }
                    else -> respond("Not Found", HttpStatusCode.NotFound)
                }
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun externalIdOf(body: JsonObject): String =
        (body["transactions"] as kotlinx.serialization.json.JsonArray)
            .let { (it.first() as JsonObject) }["external_id"]?.jsonPrimitive?.content ?: ""

    private fun sidecarExternalIds(txId: Long): List<String> =
        runBlocking { sidecarDao.statesFor(listOf(txId)) }.mapNotNull { it.externalId }

    @Test
    fun hashChangingEditReusesPersistedExternalIdAndUpdatesInsteadOfReCreating() {
        stubFirefly("9101")
        val original = tx(77L, "hash-A")

        val first = runBlocking { engine.syncTransaction(original) }
        assertTrue("pass 1 must create, got $first", first is FireflyClient.SyncResult.Success)
        assertEquals(1, postBodies.size)
        assertEquals("pennywise-hash-A", externalIdOf(postBodies.first()))
        assertEquals("the id used on create must be persisted in the sidecar", listOf("pennywise-hash-A"), sidecarExternalIds(77L))

        // An edit recalculates the transaction hash (the M3 hazard).
        val edited = original.copy(
            transactionHash = "hash-B-recalculated",
            updatedAt = LocalDateTime.now().plusMinutes(10)
        )

        val second = runBlocking { engine.syncTransaction(edited) }
        assertTrue("pass 2 must update the existing group, got $second", second is FireflyClient.SyncResult.Success)

        assertEquals("pass 2 must not create a second Firefly group", 1, postBodies.size)
        assertEquals("pass 2 must push an update", 1, putBodies.size)
        assertEquals("pennywise-hash-A", externalIdOf(putBodies.first()))

        val lastSearch = searchQueries.last()
        assertTrue("lookup must query the PERSISTED id", lastSearch.contains("pennywise-hash-A"))
        assertFalse("lookup must NOT query the freshly recomputed id", lastSearch.contains("hash-B-recalculated"))
        assertEquals("sidecar must still hold exactly one external id for this row", listOf("pennywise-hash-A"), sidecarExternalIds(77L))
    }

    @Test
    fun hashlessTransactionGetsUuidExternalIdPersistedAtFirstPushAndReusedAfterwards() {
        stubFirefly("9178")
        val hashless = tx(78L, "") // no content hash at all ⇒ old code would use pennywise-78

        val first = runBlocking { engine.syncTransaction(hashless) }
        assertTrue("pass 1 must create, got $first", first is FireflyClient.SyncResult.Success)
        assertEquals(1, postBodies.size)

        val persisted = externalIdOf(postBodies.first())
        assertTrue("hash-less rows must get a uuid-based id, got $persisted", persisted.startsWith("pennywise-ff-"))
        assertEquals("the uuid id must be persisted in the sidecar at first push", listOf(persisted), sidecarExternalIds(78L))
        assertTrue("pass 1 lookup already used the uuid id", searchQueries.first().contains(persisted))

        // A later edit assigns a real hash; the persisted sidecar id must win.
        val edited = hashless.copy(
            transactionHash = "brand-new-hash-C",
            updatedAt = LocalDateTime.now().plusMinutes(10)
        )

        val second = runBlocking { engine.syncTransaction(edited) }
        assertTrue("pass 2 must update the existing group, got $second", second is FireflyClient.SyncResult.Success)

        assertEquals("the persisted id must prevent a second create", 1, postBodies.size)
        assertEquals(1, putBodies.size)
        assertEquals(persisted, externalIdOf(putBodies.first()))

        val lastSearch = searchQueries.last()
        assertTrue("lookup must use the sidecar-persisted id", lastSearch.contains(persisted))
        assertFalse("lookup must ignore the newly computed hash", lastSearch.contains("brand-new-hash-C"))
        assertNotNull(sidecarExternalIds(78L).singleOrNull())
    }
}