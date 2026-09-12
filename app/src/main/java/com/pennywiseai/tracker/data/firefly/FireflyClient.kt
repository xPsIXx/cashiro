package com.pennywiseai.tracker.data.firefly

import android.content.Context
import android.net.Uri
import com.pennywiseai.tracker.data.database.entity.BudgetEntity
import com.pennywiseai.tracker.data.database.entity.SubscriptionDirection
import com.pennywiseai.tracker.data.database.entity.SubscriptionEntity
import com.pennywiseai.tracker.data.database.entity.SubscriptionState
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionSplitEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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
class FireflyClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val budgetRepository: BudgetRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val transactionRepository: TransactionRepository,
    private val diagnosticLogger: com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
) {

    companion object {
        private const val TAG = "FireflyClient"
        private const val API_PATH = "/api/v1"
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val BATCH_CONCURRENCY = 4
        private const val FALLBACK_BATCH_ENDPOINT_404 = "Firefly batch endpoint not found; falling back to sequential sync"
    }

    // Account info now includes the Firefly id so callers can use id/name interchangeably.
    data class FireflyAccountInfo(
        val id: String,
        val name: String,
        val active: Boolean = true
    )

    data class FireflyBudgetInfo(
        val id: String,
        val name: String,
        val active: Boolean = true,
        val createdAt: Instant? = null,
        val updatedAt: Instant? = null
    )

    /**
     * A resolved view of a Firefly budget combined with its current budget limit.
     */
    data class FireflyBudgetWithLimit(
        val budgetId: String,
        val budgetName: String,
        val active: Boolean,
        val limitId: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val amount: java.math.BigDecimal,
        val currencyCode: String,
        val budgetUpdatedAt: Instant? = null,
        val limitUpdatedAt: Instant? = null
    )

    /**
     * Raw budget limit as returned by Firefly.
     */
    data class FireflyBudgetLimitRemote(
        val id: String,
        val budgetId: String,
        val budgetName: String?,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val amount: java.math.BigDecimal,
        val currencyCode: String,
        val createdAt: Instant? = null,
        val updatedAt: Instant? = null
    )

    data class SyncableTransaction(
        val transaction: TransactionEntity,
        val splits: List<TransactionSplitEntity> = emptyList()
    )

    data class ItemResult(
        val transactionId: Long,
        val externalId: String,
        val status: ItemStatus,
        val fireflyId: String? = null,
        val journalIds: List<String> = emptyList(),
        val remoteUpdatedAt: Instant? = null,
        val message: String? = null
    ) {
        enum class ItemStatus { SUCCESS, SKIPPED, ERROR }
    }

    data class BatchSyncResult(
        val total: Int = 0,
        val created: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val details: List<ItemResult> = emptyList()
    )

    @Serializable
    private data class FireflyAccountsResponse(val data: List<FireflyAccountWrapper>)

    @Serializable
    private data class FireflyAccountWrapper(val id: String? = null, val attributes: FireflyAccountAttributes)

    @Serializable
    private data class FireflyAccountAttributes(val name: String, val active: Boolean = true)

    @Serializable
    private data class FireflyCategoriesResponse(val data: List<FireflyCategoryWrapper>)

    @Serializable
    private data class FireflyCategoryWrapper(val attributes: FireflyCategoryAttributes)

    @Serializable
    private data class FireflyCategoryAttributes(val name: String)

    @Serializable
    private data class FireflyCategoryCreateRequest(
        val name: String
    )

    @Serializable
    private data class FireflyBudgetsResponse(val data: List<FireflyBudgetWrapper>)

    @Serializable
    private data class FireflyBudgetWrapper(val id: String? = null, val attributes: FireflyBudgetAttributes)

    @Serializable
    private data class FireflyBudgetAttributes(
        val name: String,
        val active: Boolean = true,
        val created_at: String? = null,
        val updated_at: String? = null
    )

    @Serializable
    private data class FireflyBudgetLimitsResponse(val data: List<FireflyBudgetLimitWrapper>)

    @Serializable
    private data class FireflyBudgetLimitWrapper(
        val id: String? = null,
        val attributes: FireflyBudgetLimitAttributes
    )

    @Serializable
    private data class FireflyBudgetLimitAttributes(
        val budget_id: String? = null,
        val budget_name: String? = null,
        val start: String? = null,
        val end: String? = null,
        val amount: String? = null,
        val currency_code: String? = null,
        val created_at: String? = null,
        val updated_at: String? = null
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            retryOnExceptionIf { _, cause -> cause is java.io.IOException }
            exponentialDelay()
        }
        // Reasonable timeouts for mobile
        engine {
            connectTimeout = 15_000
            socketTimeout = 30_000
        }
    }

    /**
     * Result of a single-transaction sync attempt.
     */
    sealed class SyncResult {
        data class Success(val fireflyId: String? = null, val journalIds: List<String> = emptyList()) : SyncResult()
        data class Error(val message: String, val isAuthError: Boolean = false) : SyncResult()
        object Skipped : SyncResult()
    }

    /**
     * Tests whether the provided URL + token can reach the Firefly API.
     */
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
                    diagnosticLogger.d(TAG, "Firefly connection test successful")
                    SyncResult.Success()
                } else {
                    val body = response.bodyAsText()
                    diagnosticLogger.w(TAG, "Firefly test failed: ${response.status} $body")
                    SyncResult.Error(
                        "HTTP ${response.status.value}: ${body.take(200)}",
                        isAuthError = response.status == HttpStatusCode.Unauthorized
                    )
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Firefly connection test exception", e)
                SyncResult.Error("Connection failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Fetches asset accounts from Firefly for mapping UI.
     */
    suspend fun getAccounts(baseUrl: String, accessToken: String): List<FireflyAccountInfo> {
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
                val parsed = json.decodeFromString<FireflyAccountsResponse>(body)
                parsed.data
                    .map { item ->
                        FireflyAccountInfo(
                            id = item.id ?: "0",
                            name = item.attributes.name,
                            active = item.attributes.active
                        )
                    }
                    .distinctBy { it.name }
                    .sortedBy { it.name }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to fetch Firefly accounts", e)
                emptyList()
            }
        }
    }

    suspend fun getCategories(baseUrl: String, accessToken: String): List<String> {
        if (baseUrl.isBlank() || accessToken.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.get("$normalizedUrl$API_PATH/categories?limit=1000") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }

                if (!response.status.isSuccess()) return@withContext emptyList()

                val body = response.bodyAsText()
                val parsed = json.decodeFromString<FireflyCategoriesResponse>(body)
                parsed.data
                    .map { it.attributes.name }
                    .distinct()
                    .sorted()
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to fetch Firefly categories", e)
                emptyList()
            }
        }
    }

    /**
     * Ensures that the given PennyWise categories exist in Firefly.
     *
     * Existing Firefly categories are reused; missing ones are created. The returned
     * map can be saved as default category mappings (PennyWise name -> Firefly name).
     */
    suspend fun syncCategories(
        pennywiseCategories: List<String>,
        baseUrl: String,
        accessToken: String
    ): CategorySyncResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            return CategorySyncResult(error = "Firefly not configured")
        }

        return withContext(Dispatchers.IO) {
            try {
                val existing = getCategories(baseUrl, accessToken)
                val normalizedExisting = existing.associateBy { it.lowercase() }
                val toCreate = pennywiseCategories
                    .filter { it.isNotBlank() && it !in listOf("Uncategorized", "Others") }
                    .distinctBy { it.lowercase() }
                    .filter { it.lowercase() !in normalizedExisting }

                var created = 0
                var failed = 0
                val failures = mutableListOf<String>()

                toCreate.forEach { name ->
                    try {
                        val ok = createCategory(baseUrl, accessToken, name)
                        if (ok) created++ else {
                            failed++
                            failures.add(name)
                        }
                    } catch (e: Exception) {
                        diagnosticLogger.e(TAG, "Failed to create category $name in Firefly", e)
                        failed++
                        failures.add(name)
                    }
                }

                val mappings = pennywiseCategories
                    .filter { it.isNotBlank() }
                    .distinct()
                    .associateWith { categoryName ->
                        // Prefer exact case match, fall back to case-insensitive match.
                        if (categoryName in existing) categoryName
                        else normalizedExisting[categoryName.lowercase()]
                            ?: categoryName // If we just created it, it will match exact on next run.
                    }

                CategorySyncResult(
                    created = created,
                    failed = failed,
                    mappings = mappings,
                    failureNames = failures
                )
            } catch (e: Exception) {
                CategorySyncResult(error = e.message)
            }
        }
    }

    private suspend fun createCategory(
        baseUrl: String,
        accessToken: String,
        name: String
    ): Boolean {
        if (baseUrl.isBlank() || accessToken.isBlank() || name.isBlank()) return false

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.post("$normalizedUrl$API_PATH/categories") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(FireflyCategoryCreateRequest(name = name))
                }
                if (!response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    diagnosticLogger.w(TAG, "Failed to create category $name: ${response.status} ${body.take(200)}")
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to create Firefly category $name", e)
                false
            }
        }
    }

    data class CategorySyncResult(
        val created: Int = 0,
        val failed: Int = 0,
        val mappings: Map<String, String> = emptyMap(),
        val failureNames: List<String> = emptyList(),
        val error: String? = null
    )

    /**
     * Fetches existing budgets from Firefly, including timestamps for conflict resolution.
     */
    suspend fun getBudgets(baseUrl: String, accessToken: String): List<FireflyBudgetInfo> {
        if (baseUrl.isBlank() || accessToken.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.get("$normalizedUrl$API_PATH/budgets?limit=1000") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }

                if (!response.status.isSuccess()) return@withContext emptyList()

                val body = response.bodyAsText()
                val parsed = json.decodeFromString<FireflyBudgetsResponse>(body)
                parsed.data
                    .map {
                        FireflyBudgetInfo(
                            id = it.id ?: "0",
                            name = it.attributes.name,
                            active = it.attributes.active,
                            createdAt = parseInstant(it.attributes.created_at),
                            updatedAt = parseInstant(it.attributes.updated_at)
                        )
                    }
                    .distinctBy { it.name }
                    .sortedBy { it.name }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to fetch Firefly budgets", e)
                emptyList()
            }
        }
    }

    /**
     * Fetches budget limits from Firefly. Without date filters it returns the most recent limits.
     */
    suspend fun getBudgetLimits(
        baseUrl: String,
        accessToken: String,
        startDate: String? = null,
        endDate: String? = null
    ): List<FireflyBudgetLimitRemote> {
        if (baseUrl.isBlank() || accessToken.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val params = buildString {
                    append("?limit=1000")
                    if (!startDate.isNullOrBlank()) append("&start=$startDate")
                    if (!endDate.isNullOrBlank()) append("&end=$endDate")
                }
                val response: HttpResponse = client.get("$normalizedUrl$API_PATH/budget-limits$params") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }

                if (!response.status.isSuccess()) return@withContext emptyList()

                val body = response.bodyAsText()
                val parsed = json.decodeFromString<FireflyBudgetLimitsResponse>(body)
                parsed.data.mapNotNull { wrapper ->
                    val attrs = wrapper.attributes
                    val id = wrapper.id ?: return@mapNotNull null
                    val budgetId = attrs.budget_id ?: return@mapNotNull null
                    val start = parseDate(attrs.start) ?: return@mapNotNull null
                    val end = parseDate(attrs.end) ?: return@mapNotNull null
                    val amount = attrs.amount?.toBigDecimalOrNull() ?: return@mapNotNull null
                    val currency = attrs.currency_code ?: return@mapNotNull null
                    FireflyBudgetLimitRemote(
                        id = id,
                        budgetId = budgetId,
                        budgetName = attrs.budget_name,
                        startDate = start,
                        endDate = end,
                        amount = amount,
                        currencyCode = currency,
                        createdAt = parseInstant(attrs.created_at),
                        updatedAt = parseInstant(attrs.updated_at)
                    )
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to fetch Firefly budget limits", e)
                emptyList()
            }
        }
    }

    /**
     * Updates an existing Firefly budget (name / active flag).
     */
    suspend fun updateBudget(
        baseUrl: String,
        accessToken: String,
        budgetId: String,
        name: String,
        active: Boolean
    ): Boolean {
        if (baseUrl.isBlank() || accessToken.isBlank() || budgetId.isBlank()) return false

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.put("$normalizedUrl$API_PATH/budgets/$budgetId") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(FireflyBudgetUpdateRequest(name = name, active = active))
                }
                if (!response.status.isSuccess()) {
                    diagnosticLogger.w(TAG, "Failed to update budget: ${response.status} ${response.bodyAsText().take(200)}")
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to update Firefly budget", e)
                false
            }
        }
    }

    /**
     * Updates an existing Firefly budget limit.
     */
    suspend fun updateBudgetLimit(
        baseUrl: String,
        accessToken: String,
        budgetId: String,
        limitId: String,
        startDate: String,
        endDate: String,
        amount: String,
        currencyCode: String
    ): Boolean {
        if (baseUrl.isBlank() || accessToken.isBlank() || budgetId.isBlank() || limitId.isBlank()) return false

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.put("$normalizedUrl$API_PATH/budget-limits/$limitId") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(
                        FireflyBudgetLimitRequest(
                            budget_id = budgetId,
                            start = startDate,
                            end = endDate,
                            amount = amount,
                            currency_code = currencyCode
                        )
                    )
                }
                if (!response.status.isSuccess()) {
                    diagnosticLogger.w(TAG, "Failed to update budget limit: ${response.status} ${response.bodyAsText().take(200)}")
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to update Firefly budget limit", e)
                false
            }
        }
    }

    /**
     * Creates a budget in Firefly. Returns the created budget id or null on failure.
     */
    suspend fun createBudget(
        baseUrl: String,
        accessToken: String,
        name: String,
        active: Boolean = true
    ): String? {
        if (baseUrl.isBlank() || accessToken.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val payload = FireflyBudgetCreateRequest(
                    name = name,
                    active = active
                )

                val response: HttpResponse = client.post("$normalizedUrl$API_PATH/budgets") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

                if (!response.status.isSuccess()) {
                    diagnosticLogger.w(TAG, "Failed to create budget: ${response.status} ${response.bodyAsText().take(200)}")
                    return@withContext null
                }

                extractCreatedId(response.bodyAsText())
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to create Firefly budget", e)
                null
            }
        }
    }

    /**
     * Creates a budget limit for an existing Firefly budget.
     */
    suspend fun createBudgetLimit(
        baseUrl: String,
        accessToken: String,
        budgetId: String,
        startDate: String,
        endDate: String,
        amount: String,
        currencyCode: String
    ): String? {
        if (baseUrl.isBlank() || accessToken.isBlank() || budgetId.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val payload = FireflyBudgetLimitRequest(
                    budget_id = budgetId,
                    start = startDate,
                    end = endDate,
                    amount = amount,
                    currency_code = currencyCode
                )

                val response: HttpResponse = client.post("$normalizedUrl$API_PATH/budget-limits") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

                if (!response.status.isSuccess()) {
                    diagnosticLogger.w(TAG, "Failed to create budget limit: ${response.status} ${response.bodyAsText().take(200)}")
                    return@withContext null
                }

                extractCreatedId(response.bodyAsText())
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to create Firefly budget limit", e)
                null
            }
        }
    }

    suspend fun syncBudgets(
        budgets: List<BudgetEntity>,
        baseUrl: String,
        accessToken: String
    ): BudgetSyncResult {
        // Cashiro only pushes transactions one-way. Local budgets have no Firefly IDs.
        return BudgetSyncResult(
            created = 0,
            updated = 0,
            failed = 0,
            error = "Budgets stay on the phone. Cashiro only pushes transactions."
        )
    }

    data class BudgetSyncResult(
        val created: Int,
        val updated: Int,
        val failed: Int,
        val error: String? = null
    )

    /**
     * Pushes a single PennyWise transaction to Firefly as a new journal entry.
     * Deprecated single-item path; prefer [syncTransactionsBatch] for bulk operations.
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
        return syncTransaction(
            transaction = transaction,
            splits = emptyList(),
            baseUrl = baseUrl,
            accessToken = accessToken,
            defaultAssetAccount = defaultAssetAccount,
            accountMappings = accountMappings,
            categoryMappings = categoryMappings,
            includeRawSmsInNotes = includeRawSmsInNotes
        )
    }

    /**
     * Single-transaction sync that also supports splits and receipt upload.
     */
    suspend fun syncTransaction(
        transaction: TransactionEntity,
        splits: List<TransactionSplitEntity> = emptyList(),
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?,
        accountMappings: Map<String, String> = emptyMap(),
        categoryMappings: Map<String, String> = emptyMap(),
        includeRawSmsInNotes: Boolean = true,
        uploadReceipt: Boolean = true
    ): SyncResult {
        val result = syncTransactionsBatch(
            syncables = listOf(SyncableTransaction(transaction, splits)),
            baseUrl = baseUrl,
            accessToken = accessToken,
            defaultAssetAccount = defaultAssetAccount,
            accountMappings = accountMappings,
            categoryMappings = categoryMappings,
            includeRawSmsInNotes = includeRawSmsInNotes,
            uploadReceipts = uploadReceipt
        )

        return when {
            result.details.isEmpty() -> SyncResult.Error(result.toString())
            else -> {
                val item = result.details.first()
                when (item.status) {
                    ItemResult.ItemStatus.SUCCESS -> SyncResult.Success(
                        fireflyId = item.fireflyId,
                        journalIds = item.journalIds
                    )
                    ItemResult.ItemStatus.SKIPPED -> SyncResult.Skipped
                    ItemResult.ItemStatus.ERROR -> SyncResult.Error(
                        item.message ?: "Unknown error",
                        isAuthError = item.message?.contains("401") == true
                    )
                }
            }
        }
    }

    /**
     * Bulk sync implementation.
     *
     * First attempts Firefly's dedicated batch endpoint. If the endpoint is not
     * implemented (404), it falls back to concurrent single-transaction posts,
     * limiting in-flight requests to avoid overwhelming the server.
     *
     * Supports:
     *  - Split transactions (mapped to Firefly splits in one transaction group)
     *  - Transfers between mapped asset accounts
     *  - Receipt/attachment upload after successful transaction creation
     */
    suspend fun syncTransactionsBatch(
        syncables: List<SyncableTransaction>,
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?,
        accountMappings: Map<String, String> = emptyMap(),
        categoryMappings: Map<String, String> = emptyMap(),
        includeRawSmsInNotes: Boolean = true,
        uploadReceipts: Boolean = true,
        preferredBatchSize: Int = 50
    ): BatchSyncResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            return BatchSyncResult(
                total = syncables.size,
                failed = syncables.size,
                details = syncables.map {
                    ItemResult(
                        it.transaction.id,
                        computeExternalId(it.transaction),
                        ItemResult.ItemStatus.ERROR,
                        message = "Firefly not configured"
                    )
                }
            )
        }
        if (syncables.isEmpty()) return BatchSyncResult()

        return withContext(Dispatchers.IO) {
            val toCreate = mutableListOf<SyncableTransaction>()
            val toUpdate = mutableListOf<Pair<SyncableTransaction, RemoteTransactionInfo>>()
            val results = mutableListOf<ItemResult>()

            // 1. One-way push: search Firefly by our pennywise-{hash} external_id.
            //    Missing → create. Found AND owned by Cashiro/PennyWise → update.
            //    Found but not ours → skip. Never pull, never touch Firefly-native rows.
            syncables.forEach { syncable ->
                val tx = syncable.transaction
                val extId = computeExternalId(tx)
                val remote = getRemoteTransactionByExternalId(baseUrl, accessToken, extId)

                if (remote == null) {
                    toCreate.add(syncable)
                    return@forEach
                }

                if (!isOwnedByCashiro(remote, extId)) {
                    results.add(
                        ItemResult(
                            transactionId = tx.id,
                            externalId = extId,
                            status = ItemResult.ItemStatus.SKIPPED,
                            fireflyId = remote.fireflyId,
                            remoteUpdatedAt = remote.updatedAt,
                            message = "Firefly-native entry — left untouched"
                        )
                    )
                    return@forEach
                }

                toUpdate.add(syncable to remote)
            }

            // 2. Push local changes to existing Firefly transactions.
            toUpdate.forEach { (syncable, remote) ->
                val tx = syncable.transaction
                val extId = computeExternalId(tx)
                try {
                    val updated = updateTransaction(
                        fireflyId = remote.fireflyId,
                        syncable = syncable,
                        baseUrl = baseUrl,
                        accessToken = accessToken,
                        defaultAssetAccount = defaultAssetAccount,
                        accountMappings = accountMappings,
                        categoryMappings = categoryMappings,
                        includeRawSmsInNotes = includeRawSmsInNotes,
                        journalId = remote.journalIds.firstOrNull()
                    )
                    if (updated != null) {
                        // Best-effort receipt upload: a user may have attached a new receipt
                        // to an already-synced transaction; upload it after the journal update.
                        if (uploadReceipts && !tx.receiptPath.isNullOrBlank()) {
                            val journalId = updated.journalIds.firstOrNull() ?: remote.fireflyId
                            uploadReceipt(
                                baseUrl = baseUrl,
                                accessToken = accessToken,
                                journalId = journalId,
                                receiptPath = tx.receiptPath,
                                title = "${tx.merchantName} receipt".take(100)
                            )
                        }

                        transactionRepository.markFireflySynced(
                            transactionId = tx.id,
                            externalId = extId,
                            transactionIdRemote = updated.fireflyId,
                            fireflyUpdatedAt = updated.updatedAt?.let { instantToLocalDateTime(it) }
                        )
                        results.add(
                            ItemResult(
                                transactionId = tx.id,
                                externalId = extId,
                                status = ItemResult.ItemStatus.SUCCESS,
                                fireflyId = updated.fireflyId,
                                remoteUpdatedAt = updated.updatedAt
                            )
                        )
                    } else {
                        results.add(
                            ItemResult(
                                transactionId = tx.id,
                                externalId = extId,
                                status = ItemResult.ItemStatus.ERROR,
                                fireflyId = remote.fireflyId,
                                message = "Failed to update Firefly transaction"
                            )
                        )
                    }
                } catch (e: Exception) {
                    diagnosticLogger.e(TAG, "Failed to update transaction ${tx.id} in Firefly", e)
                    results.add(
                        ItemResult(
                            transactionId = tx.id,
                            externalId = extId,
                            status = ItemResult.ItemStatus.ERROR,
                            fireflyId = remote.fireflyId,
                            message = e.message ?: "Update error"
                        )
                    )
                }
            }

            // 3. Create brand-new transactions in Firefly.
            val createdDetails = if (toCreate.isEmpty()) {
                emptyList()
            } else {
                val batchResult = tryBatchEndpoint(
                    toCreate = toCreate.map { it to null },
                    baseUrl = baseUrl,
                    accessToken = accessToken,
                    defaultAssetAccount = defaultAssetAccount,
                    accountMappings = accountMappings,
                    categoryMappings = categoryMappings,
                    includeRawSmsInNotes = includeRawSmsInNotes,
                    preferredBatchSize = preferredBatchSize
                )

                if (batchResult != null) {
                    // Native batch path succeeded; upload receipts on this same path.
                    if (uploadReceipts) {
                        uploadReceiptsForBatch(
                            results = batchResult,
                            syncables = toCreate,
                            baseUrl = baseUrl,
                            accessToken = accessToken
                        )
                    }
                    batchResult
                } else {
                    sequentialFallback(
                        toCreate = toCreate.map { it to null },
                        baseUrl = baseUrl,
                        accessToken = accessToken,
                        defaultAssetAccount = defaultAssetAccount,
                        accountMappings = accountMappings,
                        categoryMappings = categoryMappings,
                        includeRawSmsInNotes = includeRawSmsInNotes,
                        uploadReceipts = uploadReceipts
                    )
                }
            }

            // Persist newly-created transactions as synced.
            createdDetails.forEach { item ->
                if (item.status == ItemResult.ItemStatus.SUCCESS && item.remoteUpdatedAt != null) {
                    val tx = syncables.find { it.transaction.id == item.transactionId }?.transaction
                    tx?.let {
                        transactionRepository.markFireflySynced(
                            transactionId = it.id,
                            externalId = item.externalId,
                            transactionIdRemote = item.fireflyId,
                            fireflyUpdatedAt = instantToLocalDateTime(item.remoteUpdatedAt)
                        )
                    }
                }
            }

            val allDetails = results + createdDetails
            BatchSyncResult(
                total = allDetails.size,
                created = allDetails.count { it.status == ItemResult.ItemStatus.SUCCESS },
                skipped = allDetails.count { it.status == ItemResult.ItemStatus.SKIPPED },
                failed = allDetails.count { it.status == ItemResult.ItemStatus.ERROR },
                details = allDetails
            )
        }
    }

    /**
     * Uploads a receipt file to the first journal of a synced transaction.
     *
     * Firefly III requires two steps: create the attachment metadata, then upload the bytes.
     * This method is best-effort; failure to upload an attachment is logged but does not
     * fail the transaction sync.
     */
    suspend fun uploadReceipt(
        baseUrl: String,
        accessToken: String,
        journalId: String,
        receiptPath: String,
        title: String? = null
    ): Boolean {
        if (baseUrl.isBlank() || accessToken.isBlank() || journalId.isBlank() || receiptPath.isBlank()) return false

        val bytes = readFileBytes(receiptPath) ?: run {
            diagnosticLogger.w(TAG, "Could not read receipt file: $receiptPath")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val fileName = File(receiptPath).name.takeIf { it.isNotBlank() } ?: "receipt"

                // 1. Create attachment record
                val attachmentPayload = FireflyAttachmentCreateRequest(
                    filename = fileName,
                    attachable_type = "TransactionJournal",
                    attachable_id = journalId,
                    title = title ?: "Receipt",
                    notes = "Uploaded from PennyWise"
                )

                val createResponse: HttpResponse = client.post("$normalizedUrl$API_PATH/attachments") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(attachmentPayload)
                }

                if (!createResponse.status.isSuccess()) {
                    diagnosticLogger.w(TAG, "Failed to create attachment record: ${createResponse.status} ${createResponse.bodyAsText().take(300)}")
                    return@withContext false
                }

                val attachmentId = extractCreatedId(createResponse.bodyAsText()) ?: return@withContext false

                // 2. Upload bytes
                val contentType = guessContentType(fileName)
                val uploadResponse: HttpResponse = client.submitFormWithBinaryData(
                    url = "$normalizedUrl$API_PATH/attachments/$attachmentId/upload",
                    formData = formData {
                        append(
                            key = "file",
                            value = bytes,
                            headers = Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                append(HttpHeaders.ContentType, contentType)
                            }
                        )
                    }
                ) {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }

                if (uploadResponse.status.isSuccess()) {
                    diagnosticLogger.i(TAG, "Uploaded receipt for journal $journalId")
                    true
                } else {
                    diagnosticLogger.w(TAG, "Failed to upload receipt: ${uploadResponse.status} ${uploadResponse.bodyAsText().take(300)}")
                    false
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Receipt upload failed for journal $journalId", e)
                false
            }
        }
    }

    /**
     * Look up a transaction in Firefly using the stable external_id.
     * Uses search `external_id_is:"…"` — the list endpoint does not filter by this field.
     */
    suspend fun getTransactionIdByExternalId(baseUrl: String, accessToken: String, externalId: String): String? {
        return getRemoteTransactionByExternalId(baseUrl, accessToken, externalId)?.fireflyId
    }

    private fun isOwnedByCashiro(remote: RemoteTransactionInfo, expectedExternalId: String): Boolean {
        if (expectedExternalId.isBlank()) return false
        if (!expectedExternalId.startsWith("pennywise-") && !expectedExternalId.startsWith("cashiro-")) return false
        return remote.externalId == expectedExternalId
    }

    private suspend fun getRemoteTransactionByExternalId(
        baseUrl: String,
        accessToken: String,
        externalId: String
    ): RemoteTransactionInfo? {
        if (baseUrl.isBlank() || accessToken.isBlank() || externalId.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val query = URLEncoder.encode("external_id_is:\"$externalId\"", StandardCharsets.UTF_8.name())
                val response: HttpResponse = client.get(
                    "$normalizedUrl$API_PATH/search/transactions?query=$query&limit=5"
                ) {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }

                if (!response.status.isSuccess()) return@withContext null

                parseRemoteTransaction(response.bodyAsText(), expectedExternalId = externalId)
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to check external_id in Firefly", e)
                null
            }
        }
    }

    private fun parseRemoteTransaction(body: String, expectedExternalId: String? = null): RemoteTransactionInfo? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val dataEl = root["data"] ?: return null
            val objects = when {
                dataEl is kotlinx.serialization.json.JsonArray -> dataEl.mapNotNull {
                    runCatching { it.jsonObject }.getOrNull()
                }
                dataEl is JsonObject -> listOf(dataEl)
                else -> return null
            }
            val parsed = objects.mapNotNull { parseRemoteObject(it) }
            if (expectedExternalId != null) {
                parsed.firstOrNull { it.externalId == expectedExternalId }
            } else {
                parsed.firstOrNull()
            }
        } catch (e: Exception) {
            diagnosticLogger.w(TAG, "Failed to parse remote transaction", e)
            null
        }
    }

    private fun parseRemoteObject(data: JsonObject): RemoteTransactionInfo? {
        val id = data["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val attrs = data["attributes"]?.jsonObject ?: return null
        val updatedAt = parseInstant(attrs["updated_at"]?.jsonPrimitive?.contentOrNull)
        val transactions = attrs["transactions"]?.jsonArray ?: return null
        val first = transactions.firstOrNull()?.jsonObject ?: return null

        val type = first["type"]?.jsonPrimitive?.contentOrNull ?: "withdrawal"
        val amount = first["amount"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val dateStr = first["date"]?.jsonPrimitive?.contentOrNull
        val date = dateStr?.let { parseDate(it) }
        val description = first["description"]?.jsonPrimitive?.contentOrNull
            ?: attrs["title"]?.jsonPrimitive?.contentOrNull
        val category = first["category_name"]?.jsonPrimitive?.contentOrNull
        val sourceName = first["source_name"]?.jsonPrimitive?.contentOrNull
        val destinationName = first["destination_name"]?.jsonPrimitive?.contentOrNull
        val externalId = first["external_id"]?.jsonPrimitive?.contentOrNull
        val originalSource = first["original_source"]?.jsonPrimitive?.contentOrNull
        val tags = first["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        val journalIds = transactions.mapNotNull { txElement ->
            val txObj = txElement.jsonObject
            txObj["transaction_journal_id"]?.jsonPrimitive?.longOrNull?.toString()
                ?: txObj["id"]?.jsonPrimitive?.contentOrNull
        }

        val splits = if (transactions.size > 1) {
            transactions.mapNotNull { txElement ->
                val txObj = txElement.jsonObject
                val splitAmount = txObj["amount"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
                    ?: return@mapNotNull null
                RemoteSplitInfo(
                    amount = splitAmount,
                    category = txObj["category_name"]?.jsonPrimitive?.contentOrNull,
                    description = txObj["description"]?.jsonPrimitive?.contentOrNull
                )
            }
        } else emptyList()

        return RemoteTransactionInfo(
            fireflyId = id,
            updatedAt = updatedAt,
            type = type,
            amount = amount,
            date = date,
            description = description,
            category = category,
            sourceName = sourceName,
            destinationName = destinationName,
            journalIds = journalIds,
            splits = splits,
            externalId = externalId,
            originalSource = originalSource,
            tags = tags
        )
    }

    private data class RemoteTransactionInfo(
        val fireflyId: String,
        val updatedAt: Instant?,
        val type: String,
        val amount: java.math.BigDecimal,
        val date: LocalDate?,
        val description: String?,
        val category: String?,
        val sourceName: String?,
        val destinationName: String?,
        val journalIds: List<String> = emptyList(),
        val splits: List<RemoteSplitInfo>,
        val externalId: String? = null,
        val originalSource: String? = null,
        val tags: List<String> = emptyList()
    )

    private data class RemoteSplitInfo(
        val amount: java.math.BigDecimal,
        val category: String?,
        val description: String?
    )

    /**
     * Updates an existing Firefly transaction group with the current PennyWise data.
     */
    private suspend fun updateTransaction(
        fireflyId: String,
        syncable: SyncableTransaction,
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?,
        accountMappings: Map<String, String>,
        categoryMappings: Map<String, String>,
        includeRawSmsInNotes: Boolean,
        journalId: String? = null
    ): RemoteTransactionInfo? {
        if (fireflyId.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val payload = buildTransactionPayload(
                    syncable,
                    defaultAssetAccount,
                    accountMappings,
                    categoryMappings,
                    includeRawSmsInNotes,
                    forUpdate = true,
                    journalId = journalId
                )
                val response: HttpResponse = client.put("$normalizedUrl$API_PATH/transactions/$fireflyId") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                if (response.status.isSuccess()) {
                    parseRemoteTransaction(response.bodyAsText())
                } else {
                    diagnosticLogger.w(TAG, "Failed to update transaction $fireflyId: ${response.status} ${response.bodyAsText().take(300)}")
                    null
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to update Firefly transaction $fireflyId", e)
                null
            }
        }
    }

    /**
     * Compute a stable external ID for a transaction.
     */
    fun computeExternalId(tx: TransactionEntity): String {
        return if (!tx.transactionHash.isNullOrBlank()) {
            "pennywise-${tx.transactionHash}"
        } else {
            "pennywise-${tx.id}"
        }
    }

    // --------------------------------------------------------------------------
    // Internal helpers
    // --------------------------------------------------------------------------

    private fun normalizeBaseUrl(url: String): String {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        return u
    }

    private fun buildTransactionPayload(
        syncable: SyncableTransaction,
        defaultAsset: String?,
        accountMappings: Map<String, String>,
        categoryMappings: Map<String, String>,
        includeRawSmsInNotes: Boolean,
        forUpdate: Boolean = false,
        journalId: String? = null
    ): FireflyTransactionRequest {
        val tx = syncable.transaction
        val type = mapTransactionType(tx.transactionType)
        val dateStr = tx.dateTime.format(DATE_FORMATTER)

        val (sourceName, destName) = resolveSourceAndDestination(
            tx = tx,
            type = type,
            accountMappings = accountMappings,
            defaultAsset = defaultAsset
        )

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
            appendLine("Synced from Cashiro • bank=${tx.bankName ?: "manual"}")
        }.trim().take(1000)

        val externalId = computeExternalId(tx)

        // If we have splits, create one Firefly split per PennyWise split.
        val tags = buildTags(tx)

        val fireflyTransactions = if (syncable.splits.isNotEmpty()) {
            syncable.splits.mapIndexed { index, split ->
                FireflyTransaction(
                    type = type,
                    date = dateStr,
                    amount = split.amount.abs().toPlainString(),
                    description = if (index == 0) description else "${description} (${split.category})",
                    source_name = sourceName,
                    destination_name = destName,
                    category_name = categoryMappings[split.category] ?: split.category,
                    notes = if (index == 0) notes else null,
                    external_id = if (index == 0) externalId else null,
                    original_source = if (index == 0) "cashiro" else null,
                    transaction_journal_id = if (index == 0) journalId else null,
                    tags = tags
                )
            }
        } else {
            listOf(
                FireflyTransaction(
                    type = type,
                    date = dateStr,
                    amount = tx.amount.abs().toPlainString(),
                    description = description,
                    source_name = sourceName,
                    destination_name = destName,
                    category_name = categoryMappings[tx.category] ?: tx.category.takeIf { it != "Uncategorized" && it.isNotBlank() },
                    notes = notes,
                    external_id = externalId,
                    original_source = "cashiro",
                    transaction_journal_id = journalId,
                    tags = tags
                )
            )
        }

        return FireflyTransactionRequest(
            error_if_duplicate_hash = !forUpdate,
            apply_rules = true,
            fire_webhooks = true,
            transactions = fireflyTransactions
        )
    }

    private fun resolveSourceAndDestination(
        tx: TransactionEntity,
        type: String,
        accountMappings: Map<String, String>,
        defaultAsset: String?
    ): Pair<String, String> {
        // PennyWise stores the account as bankName**last4. Prefer a mapping, fall back to default.
        val accountKey = if (!tx.bankName.isNullOrBlank() && !tx.accountNumber.isNullOrBlank()) {
            "${tx.bankName}**${tx.accountNumber}"
        } else null

        val mappedAccount = accountKey?.let { accountMappings[it] }
        val assetAccount = mappedAccount?.takeIf { it.isNotBlank() }
            ?: defaultAsset?.takeIf { it.isNotBlank() }
            ?: "Checking Account"

        return when (type) {
            "withdrawal" -> assetAccount to resolveDestinationAccount(tx, accountMappings)
            "deposit" -> resolveDestinationAccount(tx, accountMappings) to assetAccount
            "transfer" -> assetAccount to resolveTransferDestination(tx, accountMappings, defaultAsset)
            else -> assetAccount to resolveDestinationAccount(tx, accountMappings)
        }
    }

    /**
     * For transfers, try to resolve the destination account from to_account or merchantName.
     * If to_account matches a mapping key, use the mapped Firefly account name.
     */
    private fun resolveTransferDestination(
        tx: TransactionEntity,
        accountMappings: Map<String, String>,
        defaultAsset: String?
    ): String {
        if (!tx.toAccount.isNullOrBlank()) {
            val mapped = accountMappings[tx.toAccount]
            if (!mapped.isNullOrBlank()) return mapped

            // Also try a reversed "bank**last4" style key
            val normalized = tx.toAccount.replace(Regex("\\s+"), " ").trim()
            val reversedMapped = accountMappings.entries.firstOrNull {
                it.key.equals(normalized, ignoreCase = true) ||
                it.key.endsWith("**$normalized") ||
                it.key.startsWith("$normalized**")
            }?.value
            if (!reversedMapped.isNullOrBlank()) return reversedMapped
        }
        return tx.toAccount?.takeIf { it.isNotBlank() }
            ?: tx.merchantName.takeIf { it.isNotBlank() }?.take(255)
            ?: defaultAsset?.takeIf { it.isNotBlank() }
            ?: "Savings Account"
    }

    private fun resolveDestinationAccount(tx: TransactionEntity, accountMappings: Map<String, String>): String {
        if (!tx.merchantName.isBlank()) return tx.merchantName.take(255)
        return accountMappings.values.firstOrNull() ?: tx.toAccount?.take(255) ?: "Unknown"
    }

    private fun buildTags(tx: TransactionEntity): List<String> {
        return buildList {
            add("pennywise")
            add("cashiro")
            tx.bankName?.takeIf { it.isNotBlank() }?.let {
                add("bank:${it.lowercase().replace(" ", "_")}")
            }
            tx.accountNumber?.takeIf { it.isNotBlank() }?.let {
                add("acct_last4:${it.takeLast(4)}")
            }
            tx.category.takeIf { it.isNotBlank() && it != "Uncategorized" }?.let {
                add("category:${it.lowercase().replace(" ", "_")}")
            }
            add("type:${tx.transactionType.name.lowercase()}")
            if (tx.isRecurring) add("recurring")
            tx.reference?.takeIf { it.isNotBlank() }?.let { add("ref:${it.take(20)}") }
        }
    }

    private fun mapTransactionType(type: TransactionType): String = when (type) {
        TransactionType.INCOME -> "deposit"
        TransactionType.EXPENSE -> "withdrawal"
        TransactionType.CREDIT -> "withdrawal"
        TransactionType.TRANSFER -> "transfer"
        TransactionType.INVESTMENT -> "withdrawal"
    }

    private fun extractCreatedId(body: String): String? {
        return try {
            val element = json.parseToJsonElement(body)
            findIdInJson(element)
        } catch (_: Exception) { null }
    }

    private fun findIdInJson(element: JsonElement): String? {
        return when (element) {
            is JsonObject -> {
                element["data"]?.let { data ->
                    when (data) {
                        is JsonObject -> data["id"]?.jsonPrimitive?.contentOrNull
                        is kotlinx.serialization.json.JsonArray -> data.firstOrNull()?.let { findIdInJson(it) }
                        else -> null
                    }
                } ?: element["id"]?.jsonPrimitive?.contentOrNull
            }
            else -> null
        }
    }

    private fun extractJournalIds(body: String): List<String> {
        return try {
            val ids = mutableListOf<String>()
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject ?: return emptyList()

            // Try relationships.transactions.data[]
            root["data"]?.jsonObject?.get("relationships")?.jsonObject
                ?.get("transactions")?.jsonObject?.get("data")?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                ?.let { ids.addAll(it) }

            // Fall back to attributes.transactions[].transaction_journal_id
            if (ids.isEmpty()) {
                data["attributes"]?.jsonObject?.get("transactions")?.jsonArray
                    ?.mapNotNull { tx ->
                        tx.jsonObject["transaction_journal_id"]?.jsonPrimitive?.longOrNull?.toString()
                            ?: tx.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    }
                    ?.let { ids.addAll(it) }
            }

            ids.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractBatchIds(body: String): List<String> {
        return try {
            val root = json.parseToJsonElement(body)
            val data = root.jsonObject["data"]?.jsonArray ?: return emptyList()
            data.mapNotNull { findIdInJson(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun String.take(max: Int): String = if (length > max) substring(0, max) else this

    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            try {
                LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseDate(value: String?): LocalDate? {
        return try {
            value?.let { LocalDate.parse(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun toInstant(localDateTime: LocalDateTime): Instant {
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant()
    }

    private fun instantToLocalDateTime(instant: Instant): LocalDateTime {
        return instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
    }

    private fun inferPeriodType(start: LocalDate, end: LocalDate): com.pennywiseai.tracker.data.database.entity.BudgetPeriodType {
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
        return when {
            days in 6..8 -> com.pennywiseai.tracker.data.database.entity.BudgetPeriodType.WEEKLY
            days in 28..31 -> com.pennywiseai.tracker.data.database.entity.BudgetPeriodType.MONTHLY
            else -> com.pennywiseai.tracker.data.database.entity.BudgetPeriodType.CUSTOM
        }
    }

    private fun resolveCurrentLimits(
        budgets: List<FireflyBudgetInfo>,
        limits: List<FireflyBudgetLimitRemote>
    ): List<FireflyBudgetWithLimit> {
        val today = LocalDate.now()
        val limitsByBudget = limits.groupBy { it.budgetId }

        return budgets.mapNotNull { budget ->
            val candidates = limitsByBudget[budget.id].orEmpty()
            if (candidates.isEmpty()) return@mapNotNull null

            val current = candidates.firstOrNull {
                !today.isBefore(it.startDate) && !today.isAfter(it.endDate)
            } ?: candidates.maxByOrNull { it.startDate }
            ?: return@mapNotNull null

            FireflyBudgetWithLimit(
                budgetId = budget.id,
                budgetName = budget.name,
                active = budget.active,
                limitId = current.id,
                startDate = current.startDate,
                endDate = current.endDate,
                amount = current.amount,
                currencyCode = current.currencyCode,
                budgetUpdatedAt = budget.updatedAt,
                limitUpdatedAt = current.updatedAt
            )
        }
    }

    /**
     * Attempt to use Firefly's native batch endpoint. Returns null if the endpoint is
     * not available, signalling the caller to fall back to sequential sync.
     */
    private suspend fun tryBatchEndpoint(
        toCreate: List<Pair<SyncableTransaction, String?>>,
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?,
        accountMappings: Map<String, String>,
        categoryMappings: Map<String, String>,
        includeRawSmsInNotes: Boolean,
        preferredBatchSize: Int
    ): List<ItemResult>? {
        // Build a small test chunk first to avoid serializing everything for a 404.
        val testChunk = toCreate.take(preferredBatchSize.coerceAtMost(5))
        val testPayload = testChunk.map { (syncable, _) ->
            buildTransactionPayload(syncable, defaultAssetAccount, accountMappings, categoryMappings, includeRawSmsInNotes)
        }

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.post("$normalizedUrl$API_PATH/transactions/batch") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(FireflyNativeBatchRequest(transactions = testPayload))
                }

                when {
                    response.status == HttpStatusCode.NotFound -> {
                        diagnosticLogger.d(TAG, FALLBACK_BATCH_ENDPOINT_404)
                        return@withContext null
                    }
                    response.status.isSuccess() -> {
                        // Endpoint exists. Process the full list in chunks.
                        val remaining = toCreate.drop(testChunk.size)
                        val testResults = parseNativeBatchResponse(testChunk, response.bodyAsText())
                        val remainingResults = remaining.chunked(preferredBatchSize).flatMap { chunk ->
                            postBatchChunk(chunk, baseUrl, accessToken, defaultAssetAccount, accountMappings, categoryMappings, includeRawSmsInNotes)
                        }
                        testResults + remainingResults
                    }
                    else -> {
                        // Endpoint returned an error we don't know how to parse; fall back.
                        diagnosticLogger.w(TAG, "Batch endpoint error ${response.status}: ${response.bodyAsText().take(300)}")
                        null
                    }
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Batch endpoint call failed", e)
                null
            }
        }
    }

    private suspend fun postBatchChunk(
        chunk: List<Pair<SyncableTransaction, String?>>,
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?,
        accountMappings: Map<String, String>,
        categoryMappings: Map<String, String>,
        includeRawSmsInNotes: Boolean
    ): List<ItemResult> {
        if (chunk.isEmpty()) return emptyList()

        val payload = chunk.map { (syncable, _) ->
            buildTransactionPayload(syncable, defaultAssetAccount, accountMappings, categoryMappings, includeRawSmsInNotes)
        }

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.post("$normalizedUrl$API_PATH/transactions/batch") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(FireflyNativeBatchRequest(transactions = payload))
                }

                if (response.status.isSuccess()) {
                    parseNativeBatchResponse(chunk, response.bodyAsText())
                } else {
                    val body = response.bodyAsText()
                    chunk.map { (syncable, _) ->
                        ItemResult(
                            syncable.transaction.id,
                            computeExternalId(syncable.transaction),
                            ItemResult.ItemStatus.ERROR,
                            message = "HTTP ${response.status.value}: ${body.take(300)}"
                        )
                    }
                }
            } catch (e: Exception) {
                chunk.map { (syncable, _) ->
                    ItemResult(
                        syncable.transaction.id,
                        computeExternalId(syncable.transaction),
                        ItemResult.ItemStatus.ERROR,
                        message = e.message ?: e.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun parseNativeBatchResponse(
        chunk: List<Pair<SyncableTransaction, String?>>,
        body: String
    ): List<ItemResult> {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val dataArray = root["data"]?.jsonArray ?: return emptyList()
            chunk.mapIndexed { index, (syncable, _) ->
                val element = dataArray.getOrNull(index)
                val remoteInfo = element?.let { parseRemoteTransaction("{\"data\":[$it]}") }
                val fireflyId = remoteInfo?.fireflyId ?: extractBatchIds(body).getOrNull(index)
                ItemResult(
                    transactionId = syncable.transaction.id,
                    externalId = computeExternalId(syncable.transaction),
                    status = if (fireflyId != null) ItemResult.ItemStatus.SUCCESS else ItemResult.ItemStatus.ERROR,
                    fireflyId = fireflyId,
                    remoteUpdatedAt = remoteInfo?.updatedAt,
                    message = if (fireflyId == null) "Batch response did not include an id" else null
                )
            }
        } catch (_: Exception) {
            // Fallback to id-only parsing
            val ids = extractBatchIds(body)
            chunk.mapIndexed { index, (syncable, _) ->
                val fireflyId = ids.getOrNull(index)
                ItemResult(
                    transactionId = syncable.transaction.id,
                    externalId = computeExternalId(syncable.transaction),
                    status = if (fireflyId != null) ItemResult.ItemStatus.SUCCESS else ItemResult.ItemStatus.ERROR,
                    fireflyId = fireflyId,
                    message = if (fireflyId == null) "Batch response did not include an id" else null
                )
            }
        }
    }

    private suspend fun sequentialFallback(
        toCreate: List<Pair<SyncableTransaction, String?>>,
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?,
        accountMappings: Map<String, String>,
        categoryMappings: Map<String, String>,
        includeRawSmsInNotes: Boolean,
        uploadReceipts: Boolean
    ): List<ItemResult> {
        val semaphore = Semaphore(BATCH_CONCURRENCY)

        return withContext(Dispatchers.IO) {
            toCreate.map { (syncable, _) ->
                async {
                    semaphore.acquire()
                    try {
                        postSingleTransaction(
                            syncable = syncable,
                            baseUrl = baseUrl,
                            accessToken = accessToken,
                            defaultAssetAccount = defaultAssetAccount,
                            accountMappings = accountMappings,
                            categoryMappings = categoryMappings,
                            includeRawSmsInNotes = includeRawSmsInNotes,
                            uploadReceipt = uploadReceipts
                        )
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun postSingleTransaction(
        syncable: SyncableTransaction,
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?,
        accountMappings: Map<String, String>,
        categoryMappings: Map<String, String>,
        includeRawSmsInNotes: Boolean,
        uploadReceipt: Boolean
    ): ItemResult {
        val tx = syncable.transaction
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val payload = buildTransactionPayload(syncable, defaultAssetAccount, accountMappings, categoryMappings, includeRawSmsInNotes)

                val response: HttpResponse = client.post("$normalizedUrl$API_PATH/transactions") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

                val responseBody = response.bodyAsText()

                when {
                    response.status.isSuccess() -> {
                        val createdId = extractCreatedId(responseBody)
                        val journalIds = extractJournalIds(responseBody)
                        val remoteInfo = parseRemoteTransaction(responseBody)

                        if (uploadReceipt && !tx.receiptPath.isNullOrBlank() && journalIds.isNotEmpty()) {
                            uploadReceipt(
                                baseUrl = baseUrl,
                                accessToken = accessToken,
                                journalId = journalIds.first(),
                                receiptPath = tx.receiptPath,
                                title = "${tx.merchantName} receipt".take(100)
                            )
                        }

                        ItemResult(
                            transactionId = tx.id,
                            externalId = computeExternalId(tx),
                            status = ItemResult.ItemStatus.SUCCESS,
                            fireflyId = createdId,
                            journalIds = journalIds,
                            remoteUpdatedAt = remoteInfo?.updatedAt
                        )
                    }
                    response.status == HttpStatusCode.Unauthorized -> {
                        ItemResult(
                            transactionId = tx.id,
                            externalId = computeExternalId(tx),
                            status = ItemResult.ItemStatus.ERROR,
                            message = "Authentication failed - check your Personal Access Token"
                        )
                    }
                    response.status == HttpStatusCode.UnprocessableEntity -> {
                        ItemResult(
                            transactionId = tx.id,
                            externalId = computeExternalId(tx),
                            status = ItemResult.ItemStatus.ERROR,
                            message = "Validation error: ${responseBody.take(300)}"
                        )
                    }
                    else -> {
                        ItemResult(
                            transactionId = tx.id,
                            externalId = computeExternalId(tx),
                            status = ItemResult.ItemStatus.ERROR,
                            message = "HTTP ${response.status.value}: ${responseBody.take(300)}"
                        )
                    }
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to sync transaction ${tx.id} to Firefly", e)
                ItemResult(
                    transactionId = tx.id,
                    externalId = computeExternalId(tx),
                    status = ItemResult.ItemStatus.ERROR,
                    message = "Network error: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun uploadReceiptsForBatch(
        results: List<ItemResult>,
        syncables: List<SyncableTransaction>,
        baseUrl: String,
        accessToken: String
    ) {
        // Best-effort: failures are logged but do not fail the transaction sync.
        results.forEach { item ->
            if (item.status != ItemResult.ItemStatus.SUCCESS) return@forEach
            val journalId = item.journalIds.firstOrNull() ?: return@forEach
            val receiptPath = syncables.find { it.transaction.id == item.transactionId }
                ?.transaction?.receiptPath?.takeIf { it.isNotBlank() }
                ?: return@forEach

            uploadReceipt(
                baseUrl = baseUrl,
                accessToken = accessToken,
                journalId = journalId,
                receiptPath = receiptPath,
                title = "Receipt".take(100)
            )
        }
    }

    private fun readFileBytes(path: String): ByteArray? {
        return try {
            when {
                path.startsWith("content:") -> {
                    context.contentResolver.openInputStream(Uri.parse(path))?.use { it.readBytes() }
                }
                else -> {
                    File(path).takeIf { it.exists() && it.canRead() }?.readBytes()
                }
            }
        } catch (e: Exception) {
            diagnosticLogger.e(TAG, "Failed to read receipt bytes", e)
            null
        }
    }

    private fun guessContentType(fileName: String): String {
        return when (fileName.lowercase().substringAfterLast('.', "")) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    // --------------------------------------------------------------------------
    // Request/response models
    // --------------------------------------------------------------------------

    @Serializable
    private data class FireflyTransactionRequest(
        val error_if_duplicate_hash: Boolean = false,
        val apply_rules: Boolean = true,
        val fire_webhooks: Boolean = true,
        val transactions: List<FireflyTransaction>
    )

    @Serializable
    private data class FireflyNativeBatchRequest(
        val transactions: List<FireflyTransactionRequest>
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
        val original_source: String? = null,
        val transaction_journal_id: String? = null,
        val tags: List<String>? = null
    )

    @Serializable
    private data class FireflyBudgetCreateRequest(
        val name: String,
        val active: Boolean = true
    )

    @Serializable
    private data class FireflyBudgetUpdateRequest(
        val name: String,
        val active: Boolean = true
    )

    @Serializable
    private data class FireflyBudgetLimitRequest(
        val budget_id: String,
        val start: String,
        val end: String,
        val amount: String,
        val currency_code: String
    )

    @Serializable
    private data class FireflyAttachmentCreateRequest(
        val filename: String,
        val attachable_type: String,
        val attachable_id: String,
        val title: String? = null,
        val notes: String? = null
    )

    // --------------------------------------------------------------------------
    // Piggy Banks (budget TARGET goals -> Firefly piggy banks)
    // --------------------------------------------------------------------------

    data class PiggyBankSyncResult(
        val created: Int,
        val updated: Int,
        val failed: Int
    )

    suspend fun syncBudgetTargetsToPiggyBanks(
        targetBudgets: List<BudgetEntity>,
        baseUrl: String,
        accessToken: String
    ): PiggyBankSyncResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            return PiggyBankSyncResult(created = 0, updated = 0, failed = targetBudgets.size)
        }
        if (targetBudgets.isEmpty()) return PiggyBankSyncResult(0, 0, 0)

        return withContext(Dispatchers.IO) {
            var created = 0
            var updated = 0
            var failed = 0
            try {
                val remote = getPiggyBanks(baseUrl, accessToken)
                val remoteByName = remote.associateBy { it.name.lowercase() }

                targetBudgets.forEach { budget ->
                    try {
                        val name = budget.name
                        val targetAmount = budget.limitAmount.toPlainString()
                        val existing = remoteByName[name.lowercase()]
                        val success = if (existing == null) {
                            createPiggyBank(
                                baseUrl = baseUrl,
                                accessToken = accessToken,
                                name = name,
                                targetAmount = targetAmount
                            )
                        } else {
                            updatePiggyBank(
                                baseUrl = baseUrl,
                                accessToken = accessToken,
                                piggyBankId = existing.id,
                                name = name,
                                targetAmount = targetAmount
                            )
                        }
                        if (success) {
                            if (existing == null) created++ else updated++
                        } else {
                            failed++
                        }
                    } catch (e: Exception) {
                        diagnosticLogger.e(TAG, "Failed to sync piggy bank ${budget.name}", e)
                        failed++
                    }
                }

                PiggyBankSyncResult(created, updated, failed)
            } catch (e: Exception) {
                PiggyBankSyncResult(0, 0, targetBudgets.size)
            }
        }
    }

    private suspend fun getPiggyBanks(baseUrl: String, accessToken: String): List<FireflyPiggyBankInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.get("$normalizedUrl$API_PATH/piggy-banks?limit=1000") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }
                if (!response.status.isSuccess()) return@withContext emptyList()

                val body = response.bodyAsText()
                val parsed = json.decodeFromString<FireflyPiggyBanksResponse>(body)
                parsed.data.map {
                    FireflyPiggyBankInfo(
                        id = it.id ?: "0",
                        name = it.attributes.name,
                        targetAmount = it.attributes.target_amount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
                        currentAmount = it.attributes.current_amount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
                        active = it.attributes.active
                    )
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to fetch Firefly piggy banks", e)
                emptyList()
            }
        }
    }

    private suspend fun createPiggyBank(
        baseUrl: String,
        accessToken: String,
        name: String,
        targetAmount: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.post("$normalizedUrl$API_PATH/piggy-banks") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(
                        FireflyPiggyBankCreateRequest(
                            name = name,
                            target_amount = targetAmount,
                            current_amount = "0",
                            notes = "Synced from PennyWise budget goal"
                        )
                    )
                }
                response.status.isSuccess()
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to create piggy bank", e)
                false
            }
        }
    }

    private suspend fun updatePiggyBank(
        baseUrl: String,
        accessToken: String,
        piggyBankId: String,
        name: String,
        targetAmount: String
    ): Boolean {
        if (piggyBankId.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.put("$normalizedUrl$API_PATH/piggy-banks/$piggyBankId") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(
                        FireflyPiggyBankCreateRequest(
                            name = name,
                            target_amount = targetAmount,
                            current_amount = "0",
                            notes = "Synced from PennyWise budget goal"
                        )
                    )
                }
                response.status.isSuccess()
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to update piggy bank", e)
                false
            }
        }
    }

    private data class FireflyPiggyBankInfo(
        val id: String,
        val name: String,
        val targetAmount: java.math.BigDecimal,
        val currentAmount: java.math.BigDecimal,
        val active: Boolean
    )

    @Serializable
    private data class FireflyPiggyBanksResponse(val data: List<FireflyPiggyBankWrapper>)

    @Serializable
    private data class FireflyPiggyBankWrapper(
        val id: String? = null,
        val attributes: FireflyPiggyBankAttributes
    )

    @Serializable
    private data class FireflyPiggyBankAttributes(
        val name: String,
        val target_amount: String? = null,
        val current_amount: String? = null,
        val active: Boolean = true
    )

    @Serializable
    private data class FireflyPiggyBankCreateRequest(
        val name: String,
        val target_amount: String,
        val current_amount: String,
        val active: Boolean = true,
        val notes: String? = null
    )

    // --------------------------------------------------------------------------
    // Recurring transactions (PennyWise Subscriptions <-> Firefly recurring)
    // --------------------------------------------------------------------------

    data class RecurringSyncResult(
        val created: Int,
        val updated: Int,
        val failed: Int,
        val error: String? = null
    )

    suspend fun syncRecurringTransactions(
        subscriptions: List<SubscriptionEntity>,
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?
    ): RecurringSyncResult {
        // Cashiro only pushes transactions one-way. Subscriptions have no Firefly IDs.
        return RecurringSyncResult(
            created = 0,
            updated = 0,
            failed = 0,
            error = "Subscriptions stay on the phone. Cashiro only pushes transactions."
        )
    }

    private suspend fun getRecurringTransactions(
        baseUrl: String,
        accessToken: String
    ): List<FireflyRecurringInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.get("$normalizedUrl$API_PATH/recurring?limit=1000") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }
                if (!response.status.isSuccess()) return@withContext emptyList()

                val body = response.bodyAsText()
                val parsed = json.decodeFromString<FireflyRecurringResponse>(body)
                parsed.data.mapNotNull { wrapper ->
                    val attrs = wrapper.attributes
                    val id = wrapper.id ?: return@mapNotNull null
                    val type = attrs.type?.lowercase() ?: "withdrawal"
                    val amount = attrs.amount?.toBigDecimalOrNull() ?: return@mapNotNull null
                    val firstDate = parseDate(attrs.first_date) ?: return@mapNotNull null
                    FireflyRecurringInfo(
                        id = id,
                        type = type,
                        title = attrs.title ?: return@mapNotNull null,
                        amount = amount,
                        firstDate = firstDate,
                        repeatFrequency = attrs.repeat_frequency?.lowercase() ?: "monthly",
                        active = attrs.active,
                        updatedAt = parseInstant(attrs.updated_at),
                        sourceName = attrs.transactions?.firstOrNull()?.source_name,
                        destinationName = attrs.transactions?.firstOrNull()?.destination_name
                    )
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to fetch Firefly recurring transactions", e)
                emptyList()
            }
        }
    }

    private suspend fun createRecurringTransaction(
        subscription: SubscriptionEntity,
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val payload = buildRecurringRequestBody(subscription, defaultAssetAccount)
                val response: HttpResponse = client.post("$normalizedUrl$API_PATH/recurring") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                if (!response.status.isSuccess()) {
                    diagnosticLogger.w(TAG, "Failed to create recurring: ${response.status} ${response.bodyAsText().take(300)}")
                    return@withContext null
                }
                extractCreatedId(response.bodyAsText())
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to create Firefly recurring transaction", e)
                null
            }
        }
    }

    private suspend fun updateRecurringTransaction(
        recurringId: String,
        subscription: SubscriptionEntity,
        baseUrl: String,
        accessToken: String,
        defaultAssetAccount: String?
    ): Boolean {
        if (recurringId.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val payload = buildRecurringRequestBody(subscription, defaultAssetAccount)
                val response: HttpResponse = client.put("$normalizedUrl$API_PATH/recurring/$recurringId") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                response.status.isSuccess()
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Failed to update Firefly recurring transaction", e)
                false
            }
        }
    }

    private fun buildRecurringRequestBody(
        subscription: SubscriptionEntity,
        defaultAssetAccount: String?
    ): FireflyRecurringCreateRequest {
        val type = if (subscription.direction == SubscriptionDirection.INCOME) "deposit" else "withdrawal"
        val account = subscription.bankName?.takeIf { it.isNotBlank() }
            ?: defaultAssetAccount?.takeIf { it.isNotBlank() }
            ?: "Checking Account"
        val (source, destination) = when (type) {
            "withdrawal" -> account to subscription.merchantName
            else -> subscription.merchantName to account
        }
        return FireflyRecurringCreateRequest(
            type = type,
            title = subscription.merchantName,
            first_date = subscription.nextPaymentDate?.format(DATE_FORMATTER)
                ?: LocalDate.now().format(DATE_FORMATTER),
            repeat_frequency = mapBillingCycleToFirefly(subscription.billingCycle),
            amount = subscription.amount.abs().toPlainString(),
            notes = "Synced from PennyWise subscription • cycle=${subscription.billingCycle}",
            source_name = source,
            destination_name = destination,
            active = subscription.state == SubscriptionState.ACTIVE,
            apply_rules = true
        )
    }

    private fun mapBillingCycleToFirefly(cycle: String): String {
        return when (cycle.trim().lowercase()) {
            "weekly" -> "weekly"
            "monthly" -> "monthly"
            "quarterly" -> "quarterly"
            "semi-annual", "half-year", "half-yearly", "6 months" -> "half-year"
            "annual", "yearly" -> "yearly"
            else -> "monthly"
        }
    }

    private fun mapFireflyFrequencyToBillingCycle(frequency: String): String {
        return when (frequency.lowercase()) {
            "weekly" -> "Weekly"
            "quarterly" -> "Quarterly"
            "half-year" -> "Semi-Annual"
            "yearly" -> "Annual"
            "monthly" -> "Monthly"
            else -> "Monthly"
        }
    }

    private data class FireflyRecurringInfo(
        val id: String,
        val type: String,
        val title: String,
        val amount: java.math.BigDecimal,
        val firstDate: LocalDate,
        val repeatFrequency: String,
        val active: Boolean = true,
        val updatedAt: Instant? = null,
        val sourceName: String? = null,
        val destinationName: String? = null
    )

    @Serializable
    private data class FireflyRecurringResponse(val data: List<FireflyRecurringWrapper>)

    @Serializable
    private data class FireflyRecurringWrapper(
        val id: String? = null,
        val attributes: FireflyRecurringAttributes
    )

    @Serializable
    private data class FireflyRecurringAttributes(
        val type: String? = null,
        val title: String? = null,
        val notes: String? = null,
        val first_date: String? = null,
        val repeat_frequency: String? = null,
        val amount: String? = null,
        val active: Boolean = true,
        val created_at: String? = null,
        val updated_at: String? = null,
        val transactions: List<FireflyRecurringTransactionTemplate>? = null
    )

    @Serializable
    private data class FireflyRecurringTransactionTemplate(
        val source_name: String? = null,
        val destination_name: String? = null,
        val description: String? = null
    )

    @Serializable
    private data class FireflyRecurringCreateRequest(
        val type: String,
        val title: String,
        val first_date: String,
        val repeat_frequency: String,
        val amount: String,
        val source_name: String? = null,
        val destination_name: String? = null,
        val notes: String? = null,
        val active: Boolean = true,
        val apply_rules: Boolean = true
    )
}
