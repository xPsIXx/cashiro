package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.room.Room
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionSplitEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.database.entity.TransactionWithSplits
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
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * M1 regression: the `onTransactionSaved` / `syncTransaction` hook path used
 * to push a split transaction as one flat journal (callers pass no splits and
 * the engine defaulted to `emptyList()`), and the row was then marked synced —
 * so the split group was never corrected on Firefly.
 *
 * The hook now builds its `SyncableTransaction` through the SAME group builder
 * `resyncAll` uses (`FireflySyncEngine.buildSyncable`: stored splits are
 * re-loaded from the local database). Proof: with the real [FireflyClient] +
 * MockEngine, the captured CREATE payload for a split row carries one journal
 * per stored split (>1 journal), while a split-less row still produces exactly
 * one journal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflySyncEngineHookSplitTest {

    private lateinit var context: Context
    private lateinit var sidecarDb: FireflySyncDatabase
    private lateinit var client: FireflyClient
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var engine: FireflySyncEngine
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        sidecarDb = Room.inMemoryDatabaseBuilder(context, FireflySyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        transactionRepository = mockk(relaxed = true)

        val prefs = mockk<FireflyPreferencesRepository>(relaxed = true)
        every { prefs.syncEnabledFlow } returns flowOf(true)
        every { prefs.getBaseUrl() } returns "https://firefly.test"
        every { prefs.getAccessToken() } returns "pat"
        every { prefs.defaultAssetAccountFlow } returns flowOf("Test Asset")
        every { prefs.accountMappingsFlow } returns flowOf<Map<String, String>>(emptyMap())
        every { prefs.categoryMappingsFlow } returns flowOf<Map<String, String>>(emptyMap())
        every { prefs.includeRawSmsFlow } returns flowOf(false)

        client = FireflyClient(
            context,
            sidecarDb.fireflySyncDao(),
            mockk<BudgetRepository>(relaxed = true),
            mockk<SubscriptionRepository>(relaxed = true),
            mockk<TransactionRepository>(relaxed = true),
            DiagnosticLogger(context)
        )

        engine = FireflySyncEngine(
            client,
            sidecarDb.fireflySyncDao(),
            prefs,
            transactionRepository,
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
        merchantName = "Cashiro split-hook test",
        category = "Groceries",
        transactionType = TransactionType.EXPENSE,
        dateTime = LocalDateTime.of(2026, 1, 15, 18, 45),
        bankName = "Test Bank",
        accountNumber = "1234",
        transactionHash = hash,
        currency = "AED",
        updatedAt = LocalDateTime.of(2026, 1, 15, 18, 45)
    )

    private fun stubStoredSplits(transaction: TransactionEntity, splits: List<TransactionSplitEntity>) {
        coEvery { transactionRepository.getTransactionWithSplitsSync(transaction.id) } returns
            TransactionWithSplits(transaction, splits)
    }

    private fun jsonHeaders() = Headers.build { append(HttpHeaders.ContentType, "application/vnd.api+json") }

    private companion object {
        const val CREATED_GROUP =
            """{"data":{"id":"9301","attributes":{"updated_at":"2026-01-15T00:00:00Z","transaction_type":"withdrawal","external_id":"pennywise-hook","transactions":[{"transaction_journal_id":"5301"},{"transaction_journal_id":"5302"}]}}}"""
    }

    /** MockEngine stub for the engine's own client: search "not found" → captured create. */
    private fun stubNotFoundThenCreate(capturedCreate: (JsonObject) -> Unit) {
        client.httpClientForTests = HttpClient(
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/search/transactions" ->
                        respond("""{"data":[]}""", HttpStatusCode.OK, jsonHeaders())
                    request.method == HttpMethod.Post && request.url.encodedPath == "/api/v1/transactions" -> {
                        capturedCreate(
                            json.parseToJsonElement(String(request.body.toByteArray(), Charsets.UTF_8)) as JsonObject
                        )
                        respond(CREATED_GROUP, HttpStatusCode.OK, jsonHeaders())
                    }
                    else -> respond("Not Found", HttpStatusCode.NotFound)
                }
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun bodyJournals(body: JsonObject): JsonArray = body["transactions"] as JsonArray

    @Test
    fun hookWithoutSplitArgPushesFireflyGroupWhenSplitsExistLocally() {
        val splitTx = tx(4242L, "hook-split-1")
        stubStoredSplits(
            splitTx,
            listOf(
                TransactionSplitEntity(id = 1L, transactionId = 4242L, category = "Groceries", amount = BigDecimal("180.00")),
                TransactionSplitEntity(id = 2L, transactionId = 4242L, category = "Dining", amount = BigDecimal("120.00"))
            )
        )
        var captured: JsonObject? = null
        stubNotFoundThenCreate { captured = it }

        runBlocking { engine.onTransactionSaved(splitTx) } // callers pass NO splits

        val body = captured
        assertNotNull("hook never reached Firefly with a create", body)
        val journals = bodyJournals(body!!)
        assertEquals("split row must push one journal per stored split", 2, journals.size)
        val first = journals[0] as JsonObject
        val second = journals[1] as JsonObject
        assertEquals("pennywise-hook-split-1", first["external_id"]?.jsonPrimitive?.content)
        assertEquals("180.00", first["amount"]?.jsonPrimitive?.content)
        assertEquals("120.00", second["amount"]?.jsonPrimitive?.content)
        assertNull("only the head journal carries the group external id", second["external_id"])
        assertEquals("hook success must be recorded in the sync log", 1, logRowCount("ok"))
    }

    @Test
    fun manualSyncEntryWithoutSplitArgAlsoCarriesTheGroup() {
        val splitTx = tx(4243L, "hook-split-2")
        stubStoredSplits(
            splitTx,
            listOf(
                TransactionSplitEntity(id = 1L, transactionId = 4243L, category = "Groceries", amount = BigDecimal("300.00")),
                TransactionSplitEntity(id = 2L, transactionId = 4243L, category = "Dining", amount = BigDecimal("1.00"))
            )
        )
        var captured: JsonObject? = null
        stubNotFoundThenCreate { captured = it }

        runBlocking { engine.syncTransaction(splitTx) } // "Sync now" without explicit splits

        val journals = bodyJournals(captured!!)
        assertEquals("syncTransaction must share the hook group builder", 2, journals.size)
    }

    @Test
    fun flatTransactionStillPushesExactlyOneJournal() {
        val flatTx = tx(4244L, "hook-flat")
        stubStoredSplits(flatTx, emptyList())
        var captured: JsonObject? = null
        stubNotFoundThenCreate { captured = it }

        runBlocking { engine.onTransactionSaved(flatTx) }

        val journals = bodyJournals(captured!!)
        assertEquals("no local splits ⇒ single journal (no regression)", 1, journals.size)
        assertEquals(
            "pennywise-hook-flat",
            (journals[0] as JsonObject)["external_id"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun callerSplitsRemainAFallbackWhenDbLookupFails() {
        val splitTx = tx(4245L, "hook-split-fallback")
        coEvery { transactionRepository.getTransactionWithSplitsSync(splitTx.id) } returns null
        var captured: JsonObject? = null
        stubNotFoundThenCreate { captured = it }

        runBlocking {
            engine.onTransactionSaved(
                splitTx,
                splits = listOf(
                    TransactionSplitEntity(id = 1L, transactionId = 4245L, category = "Groceries", amount = BigDecimal("150.00")),
                    TransactionSplitEntity(id = 2L, transactionId = 4245L, category = "Dining", amount = BigDecimal("150.00"))
                )
            )
        }

        val journals = bodyJournals(captured!!)
        assertEquals("caller-supplied splits are the fallback for a failed DB read", 2, journals.size)
    }

    private fun logRowCount(status: String): Int =
        sidecarDb.query("SELECT COUNT(*) FROM firefly_log WHERE status = ?", arrayOf<Any?>(status))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
}
