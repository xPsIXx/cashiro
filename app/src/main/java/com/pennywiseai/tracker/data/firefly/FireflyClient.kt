package com.pennywiseai.tracker.data.firefly

import android.util.Log
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for interacting with a user's self-hosted Firefly III instance.
 * Used for opt-in one-way sync of parsed SMS (and manual) transactions.
 *
 * Privacy: All sync is user-initiated and goes only to the URL the user configures.
 * No data leaves the device except what the user explicitly enables.
 */
@Singleton
class FireflyClient @Inject constructor() {

    companion object {
        private const val TAG = "FireflyClient"
        private const val API_PATH = "/api/v1"
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        // Reasonable timeouts for mobile
        engine {
            connectTimeout = 15_000
            socketTimeout = 30_000
        }
    }

    /**
     * Result of a sync attempt.
     */
    sealed class SyncResult {
        data class Success(val fireflyId: String? = null) : SyncResult()
        data class Error(val message: String, val isAuthError: Boolean = false) : SyncResult()
        object Skipped : SyncResult()
    }

    /**
     * Tests whether the provided URL + token can reach the Firefly API.
     * Calls a lightweight endpoint (about or users/self).
     */
    /**
     * Fetches asset accounts from Firefly for mapping UI.
     */
    suspend fun getAccounts(baseUrl: String, accessToken: String): List<String> {
        if (baseUrl.isBlank() || accessToken.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.get("$normalizedUrl$API_PATH/accounts?type=asset") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }

                if (!response.status.isSuccess()) return@withContext emptyList()

                val body = response.bodyAsText()
                // Simple parsing for account names in JSON:API format
                val names = mutableListOf<String>()
                Regex(""""name"\s*:\s*"([^"]+)"""").findAll(body).forEach { match ->
                    match.groupValues.getOrNull(1)?.let { names.add(it) }
                }
                names.distinct().sorted()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Firefly accounts", e)
                emptyList()
            }
        }
    }

    suspend fun testConnection(baseUrl: String, accessToken: String): SyncResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            return SyncResult.Error("URL and access token are required")
        }

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.get("$normalizedUrl$API_PATH/about") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }

                if (response.status.isSuccess()) {
                    Log.d(TAG, "Firefly connection test successful")
                    SyncResult.Success()
                } else {
                    val body = response.bodyAsText()
                    Log.w(TAG, "Firefly test failed: ${response.status} $body")
                    SyncResult.Error("HTTP ${response.status.value}: ${body.take(200)}", isAuthError = response.status == HttpStatusCode.Unauthorized)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Firefly connection test exception", e)
                SyncResult.Error("Connection failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Pushes a single PennyWise transaction to Firefly as a new journal entry.
     */
    suspend fun syncTransaction(
        transaction: TransactionEntity,
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?,
        accountMappings: Map<String, String> = emptyMap(),
        categoryMappings: Map<String, String> = emptyMap(),
        includeRawSmsInNotes: Boolean = true
    ): SyncResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            return SyncResult.Error("Firefly not configured")
        }

        // Compute external ID using stable hash (future-proof for reinstalls)
        val externalId = computeExternalId(transaction)

        // Check if already exists in Firefly (resilient to reinstall/reparse)
        val existingId = getTransactionIdByExternalId(baseUrl, accessToken, externalId)
        if (existingId != null) {
            return SyncResult.Success(fireflyId = existingId)
        }

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val payload = buildTransactionPayload(transaction, defaultAssetAccount, accountMappings, categoryMappings, includeRawSmsInNotes)

                val response: HttpResponse = client.post("$normalizedUrl$API_PATH/transactions") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

                val responseBody = response.bodyAsText()

                when {
                    response.status.isSuccess() -> {
                        Log.i(TAG, "Synced tx ${transaction.id} to Firefly successfully")
                        // Try to extract the created id for future reference (best effort)
                        val createdId = extractCreatedId(responseBody)
                        SyncResult.Success(fireflyId = createdId)
                    }
                    response.status == HttpStatusCode.Unauthorized -> {
                        SyncResult.Error("Authentication failed - check your Personal Access Token", isAuthError = true)
                    }
                    response.status == HttpStatusCode.UnprocessableEntity -> {
                        // Often means validation (bad account name, duplicate external_id, etc.)
                        SyncResult.Error("Validation error: ${responseBody.take(300)}")
                    }
                    else -> {
                        SyncResult.Error("HTTP ${response.status.value}: ${responseBody.take(300)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync transaction ${transaction.id} to Firefly", e)
                SyncResult.Error("Network error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun normalizeBaseUrl(url: String): String {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        return u
    }

    private fun buildTransactionPayload(
        tx: TransactionEntity,
        defaultAsset: String?,
        accountMappings: Map<String, String>,
        categoryMappings: Map<String, String>,
        includeRawSmsInNotes: Boolean
    ): FireflyTransactionRequest {
        val type = mapTransactionType(tx.transactionType)
        val dateStr = tx.dateTime.format(DATE_FORMATTER)
        val amountStr = tx.amount.abs().toPlainString()

        // Choose accounts. Prefer per-account mapping, then global default.
        val accountKey = if (!tx.bankName.isNullOrBlank() && !tx.accountNumber.isNullOrBlank()) {
            "${tx.bankName}**${tx.accountNumber}"
        } else null

        val mappedAccount = accountKey?.let { accountMappings[it] }
        val assetAccount = mappedAccount?.takeIf { it.isNotBlank() }
            ?: defaultAsset?.takeIf { it.isNotBlank() }
            ?: "Checking Account"

        val (sourceName, destName) = when (type) {
            "withdrawal" -> assetAccount to (tx.merchantName.take(255))
            "deposit" -> (tx.merchantName.take(255)) to assetAccount
            "transfer" -> assetAccount to (tx.merchantName.take(255))
            else -> assetAccount to (tx.merchantName.take(255))
        }

        val description = buildString {
            append(tx.merchantName)
            tx.description?.takeIf { it.isNotBlank() }?.let {
                append(" — ")
                append(it.take(200))
            }
        }.take(255)

        val notes = buildString {
            tx.description?.let { appendLine(it) }
            if (includeRawSmsInNotes) {
                tx.smsBody?.let {
                    appendLine("SMS: ${it.take(500)}")
                }
            }
            appendLine("Synced from PennyWise • bank=${tx.bankName ?: "manual"}")
        }.trim().take(1000)

        val externalId = computeExternalId(tx)

        val fireflyTx = FireflyTransaction(
            type = type,
            date = dateStr,
            amount = amountStr,
            description = description,
            source_name = sourceName,
            destination_name = destName,
            category_name = categoryMappings[tx.category] ?: tx.category.takeIf { it != "Uncategorized" && it.isNotBlank() },
            notes = notes,
            external_id = externalId,
            tags = listOfNotNull("pennywise", tx.bankName?.lowercase()?.replace(" ", "_"))
        )

        return FireflyTransactionRequest(
            apply_rules = true,
            fire_webhooks = true,
            transactions = listOf(fireflyTx)
        )
    }

    private fun mapTransactionType(type: TransactionType): String = when (type) {
        TransactionType.INCOME -> "deposit"
        TransactionType.EXPENSE -> "withdrawal"
        TransactionType.CREDIT -> "withdrawal"
        TransactionType.TRANSFER -> "transfer"
        TransactionType.INVESTMENT -> "withdrawal"
    }

    private fun extractCreatedId(body: String): String? {
        // Best-effort: look for common patterns in JSON:API response
        return try {
            if (body.contains("\"id\"")) {
                Regex(""""id"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * Check if a transaction with this external_id already exists in Firefly.
     * Used for reinstall resilience and dedup.
     */
    fun computeExternalId(tx: TransactionEntity): String {
        return if (!tx.transactionHash.isNullOrBlank()) {
            "pennywise-${tx.transactionHash}"
        } else {
            "pennywise-${tx.id}"
        }
    }

    suspend fun getTransactionIdByExternalId(baseUrl: String, accessToken: String, externalId: String): String? {
        if (baseUrl.isBlank() || accessToken.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.get("$normalizedUrl$API_PATH/transactions?external_id=$externalId") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }

                if (!response.status.isSuccess()) return@withContext null

                val body = response.bodyAsText()
                extractCreatedId(body)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check external_id in Firefly", e)
                null
            }
        }
    }

    // Minimal request models (JSON:API style wrapper expected by Firefly)
    @Serializable
    private data class FireflyTransactionRequest(
        val apply_rules: Boolean = true,
        val fire_webhooks: Boolean = true,
        val transactions: List<FireflyTransaction>
    )

    @Serializable
    private data class FireflyTransaction(
        val type: String,
        val date: String,
        val amount: String,
        val description: String,
        val source_name: String? = null,
        val destination_name: String? = null,
        val category_name: String? = null,
        val notes: String? = null,
        val external_id: String? = null,
        val tags: List<String>? = null
    )
}