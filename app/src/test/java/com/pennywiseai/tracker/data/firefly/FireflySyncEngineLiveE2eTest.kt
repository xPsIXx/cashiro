package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.pennywiseai.tracker.data.database.PennyWiseDatabase
import com.pennywiseai.tracker.data.database.dao.TransactionDao
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.GoalRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json as ktorJson
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.client.plugins.HttpTimeout
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live end-to-end test of the Firefly add-on's transaction/sync side, run on
 * the JVM (Robolectric) against the user's real Firefly instance:
 *
 *  1. push CREATE (onTransactionSaved path)
 *  2. second sync on an unchanged row -> SKIPPED, no duplicate (idempotency)
 *  3. remote edit via the real API -> next sync PULLS it into the local app row
 *     (the app-data interaction: amount changes in the app's Room DB)
 *
 * Requires a PAT at ~/.firefly-pat (or FIREFLY_PAT env) AND a FIREFLY_BASE_URL
 * env (e.g. https://firefly.example.org). Skipped otherwise — no instance URL
 * is hardcoded in the repo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflySyncEngineLiveE2eTest {

    private lateinit var appDb: PennyWiseDatabase
    private lateinit var sidecarDb: FireflySyncDatabase
    private lateinit var txDao: TransactionDao
    private lateinit var sidecarDao: FireflySyncDao
    private lateinit var fireflyPrefs: FireflyPreferencesRepository
    private lateinit var engine: FireflySyncEngine
    private lateinit var logger: DiagnosticLogger
    private lateinit var raw: HttpClient

    private val json = Json { ignoreUnknownKeys = true }

    private val pat: String = System.getenv("FIREFLY_PAT")
        ?: runCatching { File(System.getProperty("user.home"), ".firefly-pat").readText().trim() }
            .getOrDefault("")

    private val baseUrl: String = System.getenv("FIREFLY_BASE_URL").orEmpty()

    private val merchant = "Cashiro E2E ${DateTimeFormatter.ofPattern("HHmmss").format(LocalDateTime.now())}"
    private var remoteIdToCleanup: String? = null

    @Before
    fun setUp() {
        assumeTrue(
            "FIREFLY_PAT (or ~/.firefly-pat) and FIREFLY_BASE_URL required for live E2E",
            pat.isNotBlank() && baseUrl.isNotBlank(),
        )
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()

        appDb = Room.inMemoryDatabaseBuilder(context, PennyWiseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sidecarDb = Room.inMemoryDatabaseBuilder(context, FireflySyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        txDao = appDb.transactionDao()
        sidecarDao = sidecarDb.fireflySyncDao()

        val userPrefs = UserPreferencesRepository(context)
        val tokenManager = mockk<FireflyTokenManager>(relaxed = true)
        every { tokenManager.getBaseUrl() } returns baseUrl
        every { tokenManager.getAccessToken() } returns pat

        val fireflyDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO)
        ) { File(context.filesDir, "cashiro_firefly_prefs_e2e.preferences_pb") }
        fireflyPrefs = FireflyPreferencesRepository(context, fireflyDataStore, userPrefs, tokenManager)

        val categoryDao = appDb.categoryDao()
        val goalRepo = GoalRepository(appDb.goalDao(), txDao, appDb)
        val txRepo = TransactionRepository(txDao, appDb.transactionSplitDao(), userPrefs, categoryDao, goalRepo)
        val budgetRepo = BudgetRepository(appDb.budgetDao(), txDao, appDb.transactionSplitDao())
        val subscriptionRepo = SubscriptionRepository(appDb.subscriptionDao(), categoryDao)
        logger = DiagnosticLogger(context)

        val e2eJson = json
        raw = HttpClient(OkHttp) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                ktorJson(e2eJson)
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                requestTimeoutMillis = 60_000
            }
        }
        val client = FireflyClient(
            context, sidecarDao, budgetRepo, subscriptionRepo, txRepo, logger
        )
        client.httpClientForTests = raw
        engine = FireflySyncEngine(
            client, sidecarDao, fireflyPrefs, txRepo, budgetRepo, subscriptionRepo, logger, context
        )

        runBlocking {
            fireflyPrefs.setSyncEnabled(true)
            fireflyPrefs.setDefaultAssetAccount("Sign 6671")
        }
    }

    @After
    fun tearDown() {
        if (::sidecarDb.isInitialized && ::raw.isInitialized) {
            runBlocking {
                remoteIdToCleanup?.let { id ->
                    raw.delete("$baseUrl/api/v1/transactions/$id") {
                        header(HttpHeaders.Authorization, "Bearer $pat")
                        header(HttpHeaders.Accept, "application/vnd.api+json")
                    }
                    // Best-effort: remove the payee expense account Firefly
                    // auto-created for this merchant.
                    runCatching {
                        val accounts = raw.get("$baseUrl/api/v1/accounts?type=expense&limit=250") {
                            header(HttpHeaders.Authorization, "Bearer $pat")
                            header(HttpHeaders.Accept, "application/vnd.api+json")
                        }.bodyAsText()
                        val match = json.parseToJsonElement(accounts).jsonObject["data"]!!.jsonArray
                            .firstOrNull {
                                it.jsonObject["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.content == merchant
                            }
                        if (match != null) {
                            raw.delete("$baseUrl/api/v1/accounts/${match.jsonObject["id"]!!.jsonPrimitive.content}") {
                                header(HttpHeaders.Authorization, "Bearer $pat")
                                header(HttpHeaders.Accept, "application/vnd.api+json")
                            }
                        }
                    }
                }
                sidecarDb.close()
                appDb.close()
            }
        }
    }

    private suspend fun insertTestTx(amount: String): TransactionEntity {
        val tx = TransactionEntity(
            amount = BigDecimal(amount),
            merchantName = merchant,
            category = "General",
            transactionType = TransactionType.EXPENSE,
            dateTime = LocalDateTime.now(),
            description = "Cashiro E2E transaction",
            bankName = "Sign 6671",
            transactionHash = "e2e-${System.nanoTime()}",
            currency = "AED"
        )
        val id = txDao.insertTransaction(tx)
        return tx.copy(id = id)
    }

    @Test(timeout = 300_000)
    fun pushCreateThenPullRemoteEditIntoAppRow() {
        val tx = runBlocking { insertTestTx("12.34") }

        // 1) PUSH CREATE.
        val first = runBlocking { engine.syncTransaction(tx) }
        assertTrue("create failed: $first", first is FireflyClient.SyncResult.Success)

        val logs = runBlocking { sidecarDao.recentLogs(20).first() }
            .joinToString { "${it.status}/${it.direction}:${it.message}" }
        println("E2E first-result: $first")
        println("E2E diag-log:\n" + runBlocking { logger.exportToFile().readText() }.takeLast(3000))
        runBlocking {
            val cursor = sidecarDb.query("SELECT * FROM firefly_transaction_state", null)
            val rows = buildList {
                while (cursor.moveToNext()) {
                    add("id=${cursor.getLong(0)} ext=${cursor.getString(1)} remote=${cursor.getString(2)}")
                }
            }
            cursor.close()
            println("E2E state-table rows=[$rows] txId=${tx.id}")
            val all = sidecarDb.query("SELECT localTransactionId, externalId FROM firefly_transaction_state WHERE localTransactionId = ?", arrayOf(tx.id.toString()))
            println("E2E state-by-id rows=${all.count}")
            all.close()
        }
        runBlocking {
            val rawGet = raw.get("$baseUrl/api/v1/transactions/${(first as FireflyClient.SyncResult.Success).fireflyId}") {
                header(HttpHeaders.Authorization, "Bearer $pat")
                header(HttpHeaders.Accept, "application/vnd.api+json")
            }.bodyAsText()
            val obj = json.parseToJsonElement(rawGet).jsonObject
            val attrs = obj["data"]!!.jsonObject["attributes"]!!.jsonObject
            println("E2E raw updated_at=" + attrs["updated_at"])
            println("E2E raw split keys=" + attrs["transactions"]!!.jsonArray[0].jsonObject.keys)
        }
        val state = runBlocking { sidecarDao.stateFor(tx.id).first() }
        if (state == null) throw AssertionError("sidecar state missing; logs=[$logs]")
        if (state.fireflyTransactionId == null) {
            throw AssertionError("fireflyTransactionId null; externalId=${state.externalId}; logs=[$logs]")
        }
        assertNotNull("external id missing in sidecar state", state.externalId)
        assertTrue("unexpected external id prefix", state.externalId!!.startsWith("pennywise-"))
        val remoteId = state.fireflyTransactionId!!
        remoteIdToCleanup = remoteId

        // The push must NOT have mutated the app row: still 12.34 locally.
        assertEquals(BigDecimal("12.34"), runBlocking { txDao.getTransactionById(tx.id) }!!.amount)

        // 2) IDEMPOTENCY: unchanged row -> SKIPPED, no duplicate.
        val second = runBlocking { engine.syncTransaction(tx) }
        assertTrue("expected skip for unchanged row, got $second", second is FireflyClient.SyncResult.Skipped)

        // 3) Remote edit (the app's own update path: reuse the remote's store
        // shape, bump amount) -> next sync must PULL it into the local row.
        runBlocking { updateRemoteAmount(remoteId, "13.45") }

        val third = runBlocking { engine.syncTransaction(tx) }
        assertTrue("pull round failed: $third", third is FireflyClient.SyncResult.Success)

        val pulled = runBlocking { txDao.getTransactionById(tx.id) }!!
        assertEquals(
            "remote edit must reach the app row",
            0, pulled.amount.compareTo(BigDecimal("13.45"))
        )

        // Sidecar log records the pull direction + success.
        val pullLogs = runBlocking { sidecarDao.recentLogs(10).first() }
        assertTrue(
            "expected pull log entry, got ${pullLogs.map { it.direction to it.status }}",
            pullLogs.any { it.direction == "pull" && it.status == "ok" }
        )
    }

    // ---- raw helpers ------------------------------------------------------

    private suspend fun updateRemoteAmount(remoteId: String, amount: String) {
        val get = raw.get("$baseUrl/api/v1/transactions/$remoteId") {
            header(HttpHeaders.Authorization, "Bearer $pat")
            header(HttpHeaders.Accept, "application/vnd.api+json")
        }.bodyAsText()
        val tx0 = json.parseToJsonElement(get).jsonObject["data"]!!.jsonObject["attributes"]!!
            .jsonObject["transactions"]!!.jsonArray[0].jsonObject.toMutableMap()
        tx0["amount"] = json.parseToJsonElement("\"$amount\"").jsonPrimitive
        val body = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("transactions" to JsonArray(listOf(JsonObject(tx0))))))

        val put = raw.put("$baseUrl/api/v1/transactions/$remoteId") {
            header(HttpHeaders.Authorization, "Bearer $pat")
            header(HttpHeaders.Accept, "application/vnd.api+json")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertTrue("PUT remote edit failed: ${put.status}", put.status.value in 200..299)
    }

}
