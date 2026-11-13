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
import io.ktor.client.HttpClientConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import kotlinx.serialization.decodeFromJsonElement
import java.io.File
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
    private val fireflySyncDao: FireflySyncDao,
    private val budgetRepository: BudgetRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val transactionRepository: TransactionRepository,
    private val diagnosticLogger: com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
) {

    private val json = defaultJson()

    /**
     * Test seam: JVM E2E tests swap the Android-engine client for an OkHttp
     * client pointing at the same endpoints. Production never mutates this.
     */
    internal var httpClientForTests: HttpClient? = null
        set(value) {
            field = value
            client = value ?: defaultHttpClient(json)
        }
    private var client: HttpClient = defaultHttpClient(json)

    companion object {
        private const val TAG = "FireflyClient"
        private const val API_PATH = "/api/v1"
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val BATCH_CONCURRENCY = 4

        private fun defaultJson() = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        private fun defaultHttpClient(json: Json): HttpClient = HttpClient(Android) {
            install(ContentNegotiation) {
                json(json)
            }
            // H1: see [installFireflyRetryPolicy] at the bottom of this file for the
            // 429-aware, commit-safe retry policy this replaces.
            installFireflyRetryPolicy()
            // Reasonable timeouts for mobile
            engine {
                connectTimeout = 15_000
                socketTimeout = 30_000
            }
        }
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
        val message: String? = null,
        /** True when the remote (Firefly) row was pulled down into the local row. */
        val pulled: Boolean = false
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
    private data class FireflyAccountWrapper(val id: String? = null, val attributes: FireflyAccountAttributes)

    @Serializable
    private data class FireflyAccountAttributes(val name: String, val active: Boolean = true)

    @Serializable
    private data class FireflyAccountCreateRequest(
        val name: String,
        val type: String
    )

    @Serializable
    private data class FireflyCategoryWrapper(val attributes: FireflyCategoryAttributes)

    @Serializable
    private data class FireflyCategoryAttributes(val name: String)

    @Serializable
    private data class FireflyCategoryCreateRequest(
        val name: String
    )

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

    // --------------------------------------------------------------------------
    // M5: real pagination for Firefly list endpoints
    // --------------------------------------------------------------------------

    /**
     * Page size requested from Firefly on every list call. The instance default
     * is 50 and instances may silently clamp larger values (the old code asked
     * `limit=1000` and only ever got page 1 back), but 100 is a cap every v6
     * instance honours; the loop keeps going until `last_page` either way.
     */
    internal val fireflyPageLimit: Int = 100

    /** Hard stop so a server that never advances `current_page` cannot loop forever. */
    private val maxFireflyPages: Int = 200

    /** One classified page of a paginated GET; mirrors the lookup semantics. */
    internal sealed class FireflyPageOutcome {
        /** A 2xx well-formed JSON:API envelope. */
        data class Page(
            val objects: List<JsonObject>,
            val currentPage: Int,
            val lastPage: Int,
            val nextPageUrl: String?
        ) : FireflyPageOutcome()

        /** HTTP failure or unparseable envelope: stop paging, keep what we have. */
        data class Failure(
            val reason: String,
            /** `Retry-After` recorded client-side: the Ktor engine consumes the
             *  response, so callers that surface it (lookup → Transient) get it
             *  from here instead of re-reading headers. */
            val retryAfterSeconds: Int? = null
        ) : FireflyPageOutcome()
    }

    private fun pageFailureReason(status: Int?, bodySnippet: String?, malformedBody: Boolean = false): String = when {
        status != null -> "HTTP $status ${bodySnippet?.take(200).orEmpty()}"
        malformedBody -> "malformed search response"
        else -> "malformed JSON:API envelope"
    }

    /**
     * Generic `page`+`limit` loop over a Firefly JSON:API list endpoint
     * (reference doc §2 / waterfly `stock.dart`): request page N with
     * `limit=[fireflyPageLimit]`, read `meta.pagination.{current_page,last_page}`
     * and continue while `current_page < last_page`; when the instance omits
     * `meta.pagination`, fall back to `links.next` / `meta.pagination.next_url`.
     *
     * Stops early on an empty page (older instances report pagination poorly),
     * at [maxFireflyPages], or on the first non-2xx/malformed page — partial
     * results are returned rather than throwing, matching the list endpoints'
     * "best page beats nothing" behaviour.
     */
    private suspend fun paginate(
        normalizedUrl: String,
        path: String,
        accessToken: String,
        extraParams: String = "",
        parsePage: (FireflyPageOutcome) -> List<JsonObject>,
        logTag: String = TAG
    ): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var page = 1
        while (page <= maxFireflyPages) {
            val outcome = fetchFireflyPage(normalizedUrl, path, accessToken, page, extraParams)
            items += parsePage(outcome)
            if (outcome is FireflyPageOutcome.Failure) {
                diagnosticLogger.w(logTag, "Firefly pagination for $path stopped: ${outcome.reason}")
                break
            }
            val p = outcome as FireflyPageOutcome.Page
            if (p.objects.isEmpty()) break
            if (p.currentPage <= 0 || p.currentPage >= p.lastPage) break
            val nextUrl = p.nextPageUrl
            if (nextUrl != null && nextUrl.isNotBlank()) {
                // links.next fallback: some instances only advertise the next
                // page through an absolute URL; follow it verbatim once.
                val raw = runCatching {
                    client.get(nextUrl) {
                        header("Authorization", "Bearer $accessToken")
                        header("Accept", "application/vnd.api+json")
                    }
                }
                val nextOutcome = if (raw.isFailure) {
                    diagnosticLogger.w(logTag, "Firefly links.next request failed for $path: ${raw.exceptionOrNull()?.message}")
                    break
                } else {
                    classifyFireflyPage(raw.getOrThrow(), "$path links.next")
                }
                items += parsePage(nextOutcome)
                if (nextOutcome is FireflyPageOutcome.Failure) {
                    diagnosticLogger.w(logTag, "Firefly pagination for $path stopped: ${nextOutcome.reason}")
                    break
                }
                val np = nextOutcome as FireflyPageOutcome.Page
                if (np.objects.isEmpty() || np.currentPage <= p.currentPage) break
                page = np.currentPage + 1
            } else {
                page = p.currentPage + 1
            }
        }
        return items
    }

    private suspend fun fetchFireflyPage(
        normalizedUrl: String,
        path: String,
        accessToken: String,
        page: Int,
        extraParams: String
    ): FireflyPageOutcome {
        val url = buildString {
            append(normalizedUrl).append(API_PATH).append(path)
            append("?page=").append(page).append("&limit=").append(fireflyPageLimit)
            if (extraParams.isNotBlank()) append('&').append(extraParams)
        }
        val response: HttpResponse = try {
            client.get(url) {
                header("Authorization", "Bearer $accessToken")
                header("Accept", "application/vnd.api+json")
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Cancellation is never a transport failure: let it propagate so the
            // caller's job actually stops mid-pagination.
            throw e
        } catch (e: Exception) {
            return FireflyPageOutcome.Failure("network: ${e.message ?: e.javaClass.simpleName}")
        }
        return classifyFireflyPage(response, path)
    }

    private suspend fun classifyFireflyPage(response: HttpResponse, path: String): FireflyPageOutcome {
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            return FireflyPageOutcome.Failure(
                reason = pageFailureReason(response.status.value, body),
                retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toIntOrNull()
            )
        }
        val body = runCatching { response.bodyAsText() }.getOrNull()
            ?: return FireflyPageOutcome.Failure(pageFailureReason(null, null, malformedBody = true))
        return parseFireflyPageEnvelope(body)
            ?: FireflyPageOutcome.Failure(pageFailureReason(null, body, malformedBody = true))
    }

    /**
     * Parses one JSON:API list envelope into [FireflyPageOutcome.Page], reading
     * `meta.pagination.{current_page,last_page,next_url}` and the `links.next`
     * fallback. `null` when the envelope cannot be interpreted at all.
     */
    private fun parseFireflyPageEnvelope(body: String): FireflyPageOutcome? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val dataEl = root["data"] ?: return null
        val objects = (dataEl as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            ?: (dataEl as? JsonObject)?.let { listOf(it) }
            ?: return null

        var current = 1
        var last = 1
        var nextUrl: String? = null
        val metaObj = root["meta"] as? JsonObject
        val pagination = metaObj?.get("pagination") as? JsonObject
        if (pagination != null) {
            pagination["current_page"]?.jsonPrimitive?.intOrNull?.let { current = it }
            pagination["last_page"]?.jsonPrimitive?.intOrNull?.let { last = it }
            (pagination["next_url"] ?: pagination["next_page_url"])?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }?.let { nextUrl = it }
        }
        val links = root["links"] as? JsonObject
        (links?.get("next") ?: metaObj?.get("next") ?: pagination?.get("next"))?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }?.let { nextUrl = it }
        FireflyPageOutcome.Page(objects, current, last, nextUrl)
    } catch (e: Exception) {
        null
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
    suspend fun getAccounts(
        baseUrl: String,
        accessToken: String,
        accountType: String = "asset"
    ): List<FireflyAccountInfo> {
        if (baseUrl.isBlank() || accessToken.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                // M5: paginate instead of taking whatever one default page returns.
                val items = paginate(
                    normalizedUrl = normalizedUrl,
                    path = "/accounts",
                    accessToken = accessToken,
                    extraParams = "type=$accountType",
                    parsePage = { outcome ->
                        if (outcome is FireflyPageOutcome.Page) outcome.objects else emptyList()
                    }
                )
                items
                    .mapNotNull { wrapperEl ->
                        runCatching {
                            val item = json.decodeFromJsonElement<FireflyAccountWrapper>(wrapperEl)
                            FireflyAccountInfo(
                                id = item.id ?: "0",
                                name = item.attributes.name,
                                active = item.attributes.active
                            )
                        }.getOrNull()
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
                // M5: paginate — a single call only ever returned the first page.
                val items = paginate(
                    normalizedUrl = normalizedUrl,
                    path = "/categories",
                    accessToken = accessToken,
                    parsePage = { outcome ->
                        if (outcome is FireflyPageOutcome.Page) outcome.objects else emptyList()
                    }
                )
                items
                    .mapNotNull { wrapperEl ->
                        runCatching {
                            json.decodeFromJsonElement<FireflyCategoryWrapper>(wrapperEl).attributes.name
                        }.getOrNull()
                    }
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
                // M5: paginate (limit=1000 was silently clamped to one page).
                val items = paginate(
                    normalizedUrl = normalizedUrl,
                    path = "/budgets",
                    accessToken = accessToken,
                    parsePage = { outcome ->
                        if (outcome is FireflyPageOutcome.Page) outcome.objects else emptyList()
                    }
                )
                items
                    .mapNotNull { wrapperEl ->
                        runCatching {
                            val it = json.decodeFromJsonElement<FireflyBudgetWrapper>(wrapperEl)
                            FireflyBudgetInfo(
                                id = it.id ?: "0",
                                name = it.attributes.name,
                                active = it.attributes.active,
                                createdAt = parseInstant(it.attributes.created_at),
                                updatedAt = parseInstant(it.attributes.updated_at)
                            )
                        }.getOrNull()
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
                // M5: paginate (limit=1000 was silently clamped to one page).
                val extra = buildString {
                    if (!startDate.isNullOrBlank()) append("start=$startDate&")
                    if (!endDate.isNullOrBlank()) append("end=$endDate")
                }.trimEnd('&')
                val items = paginate(
                    normalizedUrl = normalizedUrl,
                    path = "/budget-limits",
                    accessToken = accessToken,
                    extraParams = extra,
                    parsePage = { outcome ->
                        if (outcome is FireflyPageOutcome.Page) outcome.objects else emptyList()
                    }
                )
                items.mapNotNull { wrapperEl ->
                    val wrapper = runCatching {
                        json.decodeFromJsonElement<FireflyBudgetLimitWrapper>(wrapperEl)
                    }.getOrNull() ?: return@mapNotNull null
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

    /**
     * Two-way syncs PennyWise budgets with Firefly budgets + budget limits.
     *
     * Conflict resolution uses last-modified timestamps:
     *  - If the local budget changed since the last sync and the remote did not, push local.
     *  - If the remote changed since the last sync and the local did not, pull remote.
     *  - If both changed, the newer timestamp wins (remote wins ties).
     *  - Unmapped remote budgets are imported as new local budgets.
     *  - Unmapped local budgets are created remotely.
     */
    suspend fun syncBudgets(
        budgets: List<BudgetEntity>,
        baseUrl: String,
        accessToken: String,
        /** Last-known sidecar sync state per local budget id (from [FireflySyncEngine]). */
        stateByBudget: Map<Long, FireflyBudgetStateEntity> = emptyMap()
    ): BudgetSyncResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            return BudgetSyncResult(created = 0, updated = 0, failed = budgets.size, error = "Firefly not configured")
        }

        return withContext(Dispatchers.IO) {
            try {
                val remoteBudgets = getBudgets(baseUrl, accessToken)
                // Spec: GET /budget-limits requires start and end. A wide window
                // around today still matches the current-period limits that
                // resolveCurrentLimits() picks.
                val windowStart = LocalDate.now().withDayOfYear(1).format(DATE_FORMATTER)
                val windowEnd = LocalDate.now().plusYears(1).withDayOfYear(1).minusDays(1).format(DATE_FORMATTER)
                val remoteLimits = getBudgetLimits(baseUrl, accessToken, startDate = windowStart, endDate = windowEnd)
                val remoteCombined = resolveCurrentLimits(remoteBudgets, remoteLimits)

                var created = 0
                var updated = 0
                var failed = 0
                val now = LocalDateTime.now()
                val processedRemoteIds = mutableSetOf<String>()

                // 1. Push / pull for every local budget
                budgets.forEach { local ->
                    try {
                        val state = stateByBudget[local.id]
                        val remote = state?.fireflyBudgetId?.let { id ->
                            remoteCombined.find { it.budgetId == id }
                        } ?: remoteCombined.find { it.budgetName.equals(local.name, ignoreCase = true) }

                        if (remote == null) {
                            // No matching remote budget -> create it
                            val newBudgetId = createBudget(
                                baseUrl = baseUrl,
                                accessToken = accessToken,
                                name = local.name,
                                active = local.isActive
                            )
                            if (newBudgetId.isNullOrBlank()) {
                                failed++
                                return@forEach
                            }
                            val newLimitId = createBudgetLimit(
                                baseUrl = baseUrl,
                                accessToken = accessToken,
                                budgetId = newBudgetId,
                                startDate = local.startDate.format(DATE_FORMATTER),
                                endDate = local.endDate.format(DATE_FORMATTER),
                                amount = local.limitAmount.toPlainString(),
                                currencyCode = local.currency
                            )
                            if (newLimitId.isNullOrBlank()) {
                                failed++
                                return@forEach
                            }
                            fireflySyncDao.upsertBudgetState(
                                FireflyBudgetStateEntity(
                                    localBudgetId = local.id,
                                    fireflyBudgetId = newBudgetId,
                                    fireflyLimitId = newLimitId,
                                    syncedAt = now
                                )
                            )
                            created++
                            return@forEach
                        }

                        processedRemoteIds.add(remote.budgetId)

                        val localInstant = toInstant(local.updatedAt)
                        val lastSyncInstant = state?.syncedAt?.let { toInstant(it) }
                        val remoteInstant = listOfNotNull(remote.budgetUpdatedAt, remote.limitUpdatedAt).maxOrNull()

                        val localChanged = lastSyncInstant == null || localInstant.isAfter(lastSyncInstant)
                        val remoteChanged = remoteInstant != null && (lastSyncInstant == null || remoteInstant.isAfter(lastSyncInstant))

                        when {
                            !localChanged && !remoteChanged -> {
                                // Nothing changed, just refresh sync metadata
                                fireflySyncDao.updateBudgetSyncInfo(
                                    localBudgetId = local.id,
                                    fireflyBudgetId = remote.budgetId,
                                    fireflyLimitId = remote.limitId,
                                    syncedAt = now
                                )
                            }
                            localChanged && !remoteChanged -> {
                                // Push local to Firefly
                                val budgetOk = updateBudget(
                                    baseUrl = baseUrl,
                                    accessToken = accessToken,
                                    budgetId = remote.budgetId,
                                    name = local.name,
                                    active = local.isActive
                                )
                                val limitOk = if (remote.limitId.isNotBlank()) {
                                    updateBudgetLimit(
                                        baseUrl = baseUrl,
                                        accessToken = accessToken,
                                        budgetId = remote.budgetId,
                                        limitId = remote.limitId,
                                        startDate = local.startDate.format(DATE_FORMATTER),
                                        endDate = local.endDate.format(DATE_FORMATTER),
                                        amount = local.limitAmount.toPlainString(),
                                        currencyCode = local.currency
                                    )
                                } else {
                                    createBudgetLimit(
                                        baseUrl = baseUrl,
                                        accessToken = accessToken,
                                        budgetId = remote.budgetId,
                                        startDate = local.startDate.format(DATE_FORMATTER),
                                        endDate = local.endDate.format(DATE_FORMATTER),
                                        amount = local.limitAmount.toPlainString(),
                                        currencyCode = local.currency
                                    ) != null
                                }
                                if (budgetOk && limitOk) {
                                    fireflySyncDao.updateBudgetSyncInfo(
                                        localBudgetId = local.id,
                                        fireflyBudgetId = remote.budgetId,
                                        fireflyLimitId = remote.limitId,
                                        syncedAt = now
                                    )
                                    updated++
                                } else {
                                    failed++
                                }
                            }
                            else -> {
                                // Pull remote into local (remote wins, including tie)
                                budgetRepository.updateBudgetFromSync(
                                    budgetId = local.id,
                                    name = remote.budgetName,
                                    limitAmount = remote.amount,
                                    startDate = remote.startDate,
                                    endDate = remote.endDate,
                                    currency = remote.currencyCode,
                                    periodType = inferPeriodType(remote.startDate, remote.endDate),
                                    isActive = remote.active
                                )
                                fireflySyncDao.updateBudgetSyncInfo(
                                    localBudgetId = local.id,
                                    fireflyBudgetId = remote.budgetId,
                                    fireflyLimitId = remote.limitId,
                                    syncedAt = now
                                )
                                updated++
                            }
                        }
                    } catch (e: Exception) {
                        diagnosticLogger.e(TAG, "Failed to sync budget ${local.name}", e)
                        failed++
                    }
                }

                // 2. Import remote budgets that have no local counterpart
                remoteCombined.forEach { remote ->
                    if (remote.budgetId in processedRemoteIds) return@forEach
                    try {
                        val localId = budgetRepository.createBudget(
                            name = remote.budgetName,
                            limitAmount = remote.amount,
                            periodType = inferPeriodType(remote.startDate, remote.endDate),
                            startDate = remote.startDate,
                            endDate = remote.endDate,
                            currency = remote.currencyCode,
                            includeAllCategories = true,
                            categories = emptyList(),
                            color = "#1565C0"
                        )
                        fireflySyncDao.upsertBudgetState(
                            FireflyBudgetStateEntity(
                                localBudgetId = localId,
                                fireflyBudgetId = remote.budgetId,
                                fireflyLimitId = remote.limitId,
                                syncedAt = now
                            )
                        )
                        created++
                    } catch (e: Exception) {
                        diagnosticLogger.e(TAG, "Failed to import remote budget ${remote.budgetName}", e)
                        failed++
                    }
                }

                BudgetSyncResult(created = created, updated = updated, failed = failed)
            } catch (e: Exception) {
                BudgetSyncResult(created = 0, updated = 0, failed = budgets.size, error = e.message)
            }
        }
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
        preferredBatchSize: Int = 50,
        /** Last-known sidecar sync state per local transaction id (from [FireflySyncEngine]). */
        stateByTx: Map<Long, FireflyTransactionStateEntity> = emptyMap()
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
            val now = LocalDateTime.now()
            val toCreate = mutableListOf<SyncableTransaction>()
            val toUpdate = mutableListOf<Pair<SyncableTransaction, RemoteTransactionInfo>>()
            val results = mutableListOf<ItemResult>()

            // 1. Check each transaction against Firefly and decide create / update / pull.
            syncables.forEach { syncable ->
                val tx = syncable.transaction
                val extId = computeExternalId(tx)
                val lookup = lookupRemoteTransactionByExternalId(baseUrl, accessToken, extId)

                // H1: an inconclusive search (429/5xx/network) is NOT evidence of
                // absence. Creating here is what turned rate limits into duplicate
                // journals at scale; deferring costs one retry cycle, a duplicate
                // costs the user's financial data. Leave the row unsynced and
                // record the error so the retry pipeline picks it up later.
                if (lookup is ExternalIdLookup.Transient) {
                    diagnosticLogger.w(
                        TAG,
                        "${describe(tx)} → DEFER (search inconclusive: ${lookup.reason})"
                    )
                    fireflySyncDao.markError(
                        txId = tx.id,
                        error = "search inconclusive: ${lookup.reason}".take(300)
                    )
                    results.add(
                        ItemResult(
                            transactionId = tx.id,
                            externalId = extId,
                            status = ItemResult.ItemStatus.ERROR,
                            message = "Search inconclusive (${lookup.reason}) — create skipped to avoid a duplicate"
                        )
                    )
                    return@forEach
                }

                if (lookup is ExternalIdLookup.NotFound) {
                    diagnosticLogger.i(TAG, "${describe(tx)} → CREATE (not on Firefly)")
                    toCreate.add(syncable)
                    return@forEach
                }

                val remote = (lookup as ExternalIdLookup.Found).remote

                // Ours-only: a Firefly-native row (different/absent external_id)
                // is never updated and never pulled into the phone.
                if (!isOwnedByUs(remote, extId)) {
                    diagnosticLogger.i(
                        TAG,
                        "${describe(tx)} → SKIP native ff=${remote.fireflyId} remoteExt=${remote.externalId ?: "(none)"}"
                    )
                    fireflySyncDao.markSynced(
                        txId = tx.id,
                        externalId = extId,
                        remoteTxId = remote.fireflyId,
                        updatedAt = remote.updatedAt?.let { instantToLocalDateTime(it) }
                    )
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

                val localInstant = toInstant(tx.updatedAt)
                // Last-sync timestamps now come from the sidecar state row.
                val state = stateByTx[tx.id]
                val lastSyncInstant = state?.syncedAt?.let { toInstant(it) }
                val remoteKnownInstant = state?.fireflyUpdatedAt?.let { toInstant(it) }
                val localChanged = lastSyncInstant == null || localInstant.isAfter(lastSyncInstant)
                val remoteChanged = remote.updatedAt != null && (remoteKnownInstant == null || remote.updatedAt.isAfter(remoteKnownInstant))

                when {
                    !localChanged && !remoteChanged -> {
                        // Nothing changed since last sync; just refresh metadata.
                        diagnosticLogger.i(TAG, "${describe(tx)} → SKIP (unchanged, ff=${remote.fireflyId})")
                        fireflySyncDao.markSynced(
                            txId = tx.id,
                            externalId = extId,
                            remoteTxId = remote.fireflyId,
                            updatedAt = remote.updatedAt?.let { instantToLocalDateTime(it) }
                        )
                        results.add(
                            ItemResult(
                                transactionId = tx.id,
                                externalId = extId,
                                status = ItemResult.ItemStatus.SKIPPED,
                                fireflyId = remote.fireflyId,
                                remoteUpdatedAt = remote.updatedAt
                            )
                        )
                    }
                    localChanged && !remoteChanged -> {
                        // Local is newer → push update to Firefly.
                        diagnosticLogger.i(TAG, "${describe(tx)} → UPDATE (local wins, ff=${remote.fireflyId})")
                        toUpdate.add(syncable to remote)
                    }
                    else -> {
                        // Remote is newer, or both changed and remote wins ties.
                        diagnosticLogger.i(TAG, "${describe(tx)} → PULL (remote wins, ff=${remote.fireflyId})")
                        pullRemoteTransactionIntoLocal(syncable, remote, now)
                        fireflySyncDao.markSynced(
                            txId = tx.id,
                            externalId = extId,
                            remoteTxId = remote.fireflyId,
                            updatedAt = remote.updatedAt?.let { instantToLocalDateTime(it) },
                            source = remote.sourceName,
                            destination = remote.destinationName,
                            syncedAt = now
                        )
                        results.add(
                            ItemResult(
                                transactionId = tx.id,
                                externalId = extId,
                                status = ItemResult.ItemStatus.SUCCESS,
                                fireflyId = remote.fireflyId,
                                remoteUpdatedAt = remote.updatedAt,
                                pulled = true
                            )
                        )
                    }
                }
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
                        includeRawSmsInNotes = includeRawSmsInNotes
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

                        fireflySyncDao.markSynced(
                            txId = tx.id,
                            externalId = extId,
                            remoteTxId = updated.fireflyId,
                            updatedAt = updated.updatedAt?.let { instantToLocalDateTime(it) }
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
                        // H3: record the failure. `updated == null` is a failed
                        // update, and before this the sidecar state was left
                        // untouched — so the row stayed "synced" forever, was
                        // skipped by resyncAll, and statesWithError()/
                        // FireflyRetryWorker had nothing to act on. The previous
                        // successful sync data is kept, so a retry re-issues the
                        // update instead of creating a twin.
                        fireflySyncDao.markError(
                            txId = tx.id,
                            error = "update failed: server rejected update for ${remote.fireflyId}".take(300)
                        )
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
                    fireflySyncDao.markError(
                        txId = tx.id,
                        error = "update failed: ${(e.message ?: e.javaClass.simpleName).take(300)}"
                    )
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
            // Spec-bounded: Firefly's API has no /transactions/batch endpoint
            // (see api-docs.firefly-iii.org), so creates always go through the
            // concurrent single-transaction path (receipts upload inline).
            val createdDetails = if (toCreate.isEmpty()) {
                emptyList()
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

            // Persist newly-created transactions as synced; record failed
            // creates as errors so the retry pipeline has them (H3).
            createdDetails.forEach { item ->
                val tx = syncables.find { it.transaction.id == item.transactionId }?.transaction
                when (item.status) {
                    ItemResult.ItemStatus.SUCCESS -> {
                        if (item.remoteUpdatedAt != null) {
                            tx?.let {
                                fireflySyncDao.markSynced(
                                    txId = it.id,
                                    externalId = item.externalId,
                                    remoteTxId = item.fireflyId,
                                    updatedAt = instantToLocalDateTime(item.remoteUpdatedAt)
                                )
                            }
                        }
                    }

                    ItemResult.ItemStatus.ERROR -> tx?.let {
                        // H3: a failed create used to leave no trace at all.
                        // `markError` keeps syncedAt NULL, so resyncAll still
                        // re-tries this row and `retryFailedTxs` now sees it too.
                        fireflySyncDao.markError(
                            txId = it.id,
                            error = "create failed: ${(item.message ?: "unknown error").take(300)}"
                        )
                    }

                    ItemResult.ItemStatus.SKIPPED -> Unit
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
     * M6 / reference doc §1 (waterfly `attachments.dart`): the real v6 flow is
     *  1. `POST /api/v1/attachments` with `{title, notes?, attachable_type:
     *     "transaction_journal", attachable_id: <JOURNAL id>}` (the journal id,
     *     not the transaction-group id),
     *  2. stream the raw bytes (`application/octet-stream`) to the response's
     *     `data.attributes.upload_url` with the same Authorization headers.
     *
     * The old code POSTed multipart to `/attachments/{id}/upload` — a route the
     * v6 API does not have, so every upload 404'd. That URL is gone.
     *
     * Still best-effort: a failed upload is logged (with the parsed Firefly
     * error envelope) but never fails the transaction sync.
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

                // 1. Create the attachment record (AttachmentStore shape).
                val attachmentPayload = FireflyAttachmentCreateRequest(
                    title = (title ?: fileName).take(255),
                    attachable_type = "transaction_journal",
                    attachable_id = journalId,
                    notes = "Uploaded from PennyWise"
                )

                val createResponse: HttpResponse = client.post("$normalizedUrl$API_PATH/attachments") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                    contentType(ContentType.Application.Json)
                    setBody(attachmentPayload)
                }

                val createBody = createResponse.bodyAsText()
                if (!createResponse.status.isSuccess()) {
                    diagnosticLogger.w(
                        TAG,
                        "Failed to create attachment record for journal $journalId: " +
                            "HTTP ${createResponse.status.value} ${fireflyErrorEnvelope(createBody) ?: createBody.take(300)}"
                    )
                    return@withContext false
                }

                // 2. Resolve the upload URL: attributes.upload_url is the real
                // target; attributes.url is a last-resort fallback only —
                // embed_url is the viewer link and must NEVER be uploaded to.
                val uploadUrl = extractAttachmentUploadUrl(createBody, normalizedUrl) ?: run {
                    diagnosticLogger.w(
                        TAG,
                        "Attachment create for journal $journalId returned no upload_url: ${fireflyErrorEnvelope(createBody) ?: createBody.take(300)}"
                    )
                    return@withContext false
                }

                // 3. Stream the raw bytes to upload_url (waterfly uses POST with
                // application/octet-stream + the same Authorization headers).
                val uploadResponse: HttpResponse = client.post(uploadUrl) {
                    header("Authorization", "Bearer $accessToken")
                    contentType(ContentType.Application.OctetStream)
                    setBody(bytes)
                }

                if (uploadResponse.status.isSuccess()) {
                    diagnosticLogger.i(TAG, "Uploaded receipt for journal $journalId")
                    true
                } else {
                    val uploadBody = uploadResponse.bodyAsText()
                    diagnosticLogger.w(
                        TAG,
                        "Failed to upload receipt for journal $journalId: " +
                            "HTTP ${uploadResponse.status.value} ${fireflyErrorEnvelope(uploadBody) ?: uploadBody.take(300)}"
                    )
                    false
                }
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Receipt upload failed for journal $journalId", e)
                false
            }
        }
    }

    /**
     * Pulls the step-2 upload target out of an attachment-create response
     * (reference doc §1: `data.attributes.upload_url`). Falls back to
     * `attributes.url` only when `upload_url` is absent entirely; never `embed_url`.
     * Relative targets are resolved against the instance base URL.
     */
    private fun extractAttachmentUploadUrl(body: String, normalizedUrl: String): String? = try {
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonObject ?: return null
        val attributes = data["attributes"] as? JsonObject
        val candidates = listOfNotNull(
            attributes?.get("upload_url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            data["upload_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            attributes?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        )
        candidates.firstOrNull()?.let { url ->
            if (url.startsWith("http://") || url.startsWith("https://")) url else "$normalizedUrl${if (url.startsWith("/")) "" else "/"}$url"
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Parses a Firefly error envelope `{ "errors": { "<field>": ["msg", …] },
     * "message": "…" }` (reference doc §4) into a compact single-line summary,
     * so validation failures show the offending field instead of a raw blob.
     * `null` when the body is not an error envelope.
     */
    private fun fireflyErrorEnvelope(body: String): String? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val errors = root["errors"] as? JsonObject
        val message = root["message"]?.jsonPrimitive?.contentOrNull
        val fields = errors?.entries?.joinToString("; ") { (field, value) ->
            val messages = (value as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.joinToString(", ")
                ?: value.toString()
            "$field: $messages"
        }
        when {
            fields != null && !fields.isBlank() && !message.isNullOrBlank() -> "$message ($fields)"
            fields != null && !fields.isBlank() -> fields
            !message.isNullOrBlank() -> message
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Look up a transaction id in Firefly using the stable external_id.
     *
     * `null` covers both "not on Firefly" and "could not tell"; callers that
     * must not confuse the two use [lookupRemoteTransactionByExternalId].
     */
    suspend fun getTransactionIdByExternalId(baseUrl: String, accessToken: String, externalId: String): String? {
        return when (val lookup = lookupRemoteTransactionByExternalId(baseUrl, accessToken, externalId)) {
            is ExternalIdLookup.Found -> lookup.remote.fireflyId
            is ExternalIdLookup.NotFound -> null
            is ExternalIdLookup.Transient -> {
                diagnosticLogger.w(TAG, "External id $externalId lookup inconclusive: ${lookup.reason}")
                null
            }
        }
    }

    /**
     * Outcome of one `external_id` lookup (H1).
     *
     * The old `RemoteTransactionInfo?` API collapsed "Firefly answered and has
     * no such row" and "Firefly could not be asked (429/5xx/network)" into the
     * same `null`, and the sync loop read that `null` as *absent* — so every
     * merely rate-limited search ended up creating a duplicate journal. Only
     * [NotFound] may justify a create; [Transient] means the remote state is
     * unknown and the row must stay unsynced for a later run.
     */
    internal sealed class ExternalIdLookup {
        /** Firefly answered and has a row carrying exactly this external id. */
        data class Found(val remote: RemoteTransactionInfo) : ExternalIdLookup()

        /** Firefly answered cleanly and no row carries this external id. */
        data object NotFound : ExternalIdLookup()

        /** 429 / 5xx / non-404 4xx / network failure: the remote state is unknown. */
        data class Transient(val reason: String) : ExternalIdLookup()
    }

    /**
     * Full remote transaction lookup for two-way sync (#7).
     *
     * Uses the search endpoint with `external_id_is:"…"` — the plain list
     * endpoint does not filter by that field. Search results are deduped by the
     * exact external id.
     *
     * Reports [ExternalIdLookup.NotFound] only when the server answered with a
     * well-formed search response (or a definitive 404).
     */
    internal suspend fun lookupRemoteTransactionByExternalId(
        baseUrl: String,
        accessToken: String,
        externalId: String
    ): ExternalIdLookup = withContext<ExternalIdLookup>(Dispatchers.IO) {
        // Nothing to ask about — keep the pre-H1 behaviour (the batch path
        // already rejects an unconfigured client before it reaches here).
        if (baseUrl.isBlank() || accessToken.isBlank() || externalId.isBlank()) {
            return@withContext ExternalIdLookup.NotFound
        }

        val normalizedUrl = normalizeBaseUrl(baseUrl)
        val query = URLEncoder.encode("external_id_is:\"$externalId\"", StandardCharsets.UTF_8.name())

        // M5: a `total > 1` search must not silently stop at page 1. Loop the
        // search exactly like the list endpoints (meta.pagination / links.next),
        // but keep the H1 semantics intact: only a well-formed search page (or a
        // definitive 404) may produce NotFound; any transport failure is
        // Transient so the row is never mistaken for "absent" and re-created.
        val rows = mutableListOf<RemoteTransactionInfo>()
        var failureReason: String? = null
        var retryAfter: String? = null
        var page = 1
        var paging = true
        while (paging && page <= maxFireflyPages) {
            val outcome = fetchFireflyPage(
                normalizedUrl = normalizedUrl,
                path = "/search/transactions",
                accessToken = accessToken,
                page = page,
                extraParams = "query=$query"
            )
            when (outcome) {
                is FireflyPageOutcome.Page -> {
                    rows += outcome.objects.mapNotNull { row ->
                        runCatching { json.encodeToString(JsonObject.serializer(), row) }
                            .getOrNull()
                            ?.let { parseRemoteSearchRows(it) }
                            ?.firstOrNull()
                    }
                    paging = outcome.objects.isNotEmpty() &&
                        outcome.currentPage > 0 &&
                        outcome.currentPage < outcome.lastPage
                    if (paging) page = outcome.currentPage + 1
                }
                is FireflyPageOutcome.Failure -> {
                    failureReason = outcome.reason
                    retryAfter = outcome.retryAfterSeconds?.toString()
                    paging = false
                }
            }
        }

        if (failureReason != null) {
            // A 404 is definitive: nothing carries this external id.
            if (failureReason.startsWith("HTTP ${HttpStatusCode.NotFound.value}")) {
                diagnosticLogger.w(TAG, "Search $externalId HTTP 404 → NotFound")
            } else {
                // 429/5xx/401/403/… — inconclusive. Never report NotFound here:
                // that is precisely the misclassification that produced duplicate
                // creates.
                diagnosticLogger.w(TAG, "Search $externalId $failureReason → inconclusive")
                return@withContext ExternalIdLookup.Transient(
                    retryAfter?.let { "$failureReason retryAfter=$it" } ?: failureReason
                )
            }
        }

        rows.firstOrNull { it.externalId == externalId }?.let { ExternalIdLookup.Found(it) }
            ?: ExternalIdLookup.NotFound
    }

    /**
     * Parses a Firefly search/list body into remote rows.
     *
     * Returns an empty list for a well-formed response with no matches and
     * `null` only when the body cannot be interpreted at all — the lookup needs
     * that difference to tell "not found" from "inconclusive".
     */
    private fun parseRemoteSearchRows(body: String): List<RemoteTransactionInfo>? {
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
            objects.mapNotNull { parseRemoteObject(it) }
        } catch (e: Exception) {
            diagnosticLogger.w(TAG, "Failed to parse remote transaction", e)
            null
        }
    }

    private fun parseRemoteTransaction(body: String, expectedExternalId: String? = null): RemoteTransactionInfo? {
        val rows = parseRemoteSearchRows(body) ?: return null
        return if (expectedExternalId != null) {
            rows.firstOrNull { it.externalId == expectedExternalId }
        } else {
            rows.firstOrNull()
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

        // Collect journal IDs from all transaction journals; needed for receipt uploads.
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
            externalId = externalId
        )
    }

    /**
     * Ours-only guard: a remote row is ours only when its external_id exactly
     * matches the id we derived for it AND that id uses one of our prefixes
     * (both `pennywise-` — this repo's rows — and `cashiro-` — rows created by
     * the former cashiro fork — are owned by us). Firefly-native rows never
     * match and are left untouched.
     */
    private fun isOwnedByUs(remote: RemoteTransactionInfo, expectedExternalId: String): Boolean {
        if (expectedExternalId.isBlank()) return false
        if (!expectedExternalId.startsWith("pennywise-") && !expectedExternalId.startsWith("cashiro-")) return false
        return remote.externalId == expectedExternalId
    }

    private fun describe(tx: TransactionEntity): String {
        val merchant = tx.merchantName.take(40)
        return "tx #${tx.id} $merchant ${tx.currency} ${tx.amount} ${computeExternalId(tx)}"
    }

    // internal (not private) because [ExternalIdLookup.Found] exposes it, and the
    // JVM classification tests assert on it.
    internal data class RemoteTransactionInfo(
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
        val externalId: String? = null
    )

    internal data class RemoteSplitInfo(
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
        includeRawSmsInNotes: Boolean
    ): RemoteTransactionInfo? {
        if (fireflyId.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val payload = buildTransactionPayload(syncable, defaultAssetAccount, accountMappings, categoryMappings, includeRawSmsInNotes)
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
        includeRawSmsInNotes: Boolean
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
            appendLine("Synced from PennyWise • bank=${tx.bankName ?: "manual"}")
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
                    tags = tags
                )
            )
        }

        // L1 / reference doc §6: apply_rules/fire_webhooks default TRUE in Firefly
        // on POST/PUT. Pushing with them true lets the user's server-side rules
        // silently rename/recategorize/move the row right after our sync, and the
        // next PULL copies that rule-mutated state back into local rows. Push
        // with both false: synced journals stay exactly as entered, and rules
        // keep firing for edits the user makes on the Firefly web side.
        return FireflyTransactionRequest(
            apply_rules = false,
            fire_webhooks = false,
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

    private fun mapRemoteTypeToLocal(type: String): TransactionType {
        return when (type.lowercase()) {
            "deposit" -> TransactionType.INCOME
            "transfer" -> TransactionType.TRANSFER
            else -> TransactionType.EXPENSE
        }
    }

    private suspend fun pullRemoteTransactionIntoLocal(
        syncable: SyncableTransaction,
        remote: RemoteTransactionInfo,
        syncedAt: LocalDateTime
    ) {
        val tx = syncable.transaction
        val amount = remote.amount.abs()
        val merchantName = remote.description?.takeIf { it.isNotBlank() } ?: tx.merchantName
        val category = remote.category?.takeIf { it.isNotBlank() } ?: tx.category
        val dateTime = remote.date?.atStartOfDay() ?: tx.dateTime

        // Local-data update only — sync state (incl. source/destination names
        // and updated-at) is recorded in the sidecar DB by the caller.
        transactionRepository.updateTransactionFromFirefly(
            transactionId = tx.id,
            amount = amount,
            merchantName = merchantName,
            category = category,
            description = remote.description,
            dateTime = dateTime,
            transactionType = mapRemoteTypeToLocal(remote.type),
            syncedAt = syncedAt
        )

        if (remote.splits.isNotEmpty()) {
            val localSplits = remote.splits.map { split ->
                TransactionSplitEntity(
                    transactionId = tx.id,
                    category = split.category?.takeIf { it.isNotBlank() } ?: category,
                    amount = split.amount.abs()
                )
            }
            transactionRepository.saveSplits(tx.id, localSplits)
        }
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
                        diagnosticLogger.i(
                            TAG,
                            "Created Firefly tx $createdId (parsed updatedAt=${remoteInfo?.updatedAt}, " +
                                "externalId=${remoteInfo?.externalId}, body=${responseBody.take(100)})"
                        )

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

    @Serializable
    private data class FireflyTransactionRequest(
        // L1: push paths disable both — see buildTransactionPayload for why.
        val apply_rules: Boolean = false,
        val fire_webhooks: Boolean = false,
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

    /**
     * Firefly `AttachmentStore` (reference doc §1): title is the display name
     * (the receipt filename here); attachable_type must be the snake-case
     * `transaction_journal` with the JOURNAL id.
     */
    @Serializable
    private data class FireflyAttachmentCreateRequest(
        val title: String,
        val attachable_type: String,
        val attachable_id: String,
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
        accessToken: String,
        defaultAssetAccount: String? = null
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
                // Spec: PiggyBankStore requires account_id (and start_date on create).
                // Resolve the target account once: prefer the user's default asset
                // account, else the first asset account.
                val accounts = getAccounts(baseUrl, accessToken)
                val accountId = accounts.firstOrNull {
                    it.name.equals(defaultAssetAccount, ignoreCase = true)
                }?.id ?: accounts.firstOrNull { it.active }?.id
                    ?: accounts.firstOrNull()?.id
                if (accountId == null) {
                    diagnosticLogger.w(TAG, "Piggy-bank sync: no asset account to attach piggy banks to")
                    return@withContext PiggyBankSyncResult(0, 0, targetBudgets.size)
                }
                val startDate = LocalDate.now().format(DATE_FORMATTER)

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
                                targetAmount = targetAmount,
                                accountId = accountId,
                                startDate = startDate
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
        targetAmount: String,
        accountId: String,
        startDate: String
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
                            account_id = accountId,
                            target_amount = targetAmount,
                            start_date = startDate,
                            notes = "Synced from Cashiro budget goal"
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
                        // Update never touches current_amount (spec: it is a
                        // separate, history-backed value) — sending "0" here would
                        // wipe saved progress on every sync.
                        FireflyPiggyBankUpdateRequest(
                            name = name,
                            target_amount = targetAmount,
                            notes = "Synced from Cashiro budget goal"
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
        val account_id: String,
        val target_amount: String,
        val start_date: String,
        val active: Boolean = true,
        val notes: String? = null
    )

    @Serializable
    private data class FireflyPiggyBankUpdateRequest(
        val name: String,
        val target_amount: String,
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
        defaultAssetAccount: String?,
        /** Last-known sidecar sync state per local subscription id (from [FireflySyncEngine]). */
        stateBySubscription: Map<Long, FireflySubscriptionStateEntity> = emptyMap()
    ): RecurringSyncResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            return RecurringSyncResult(created = 0, updated = 0, failed = subscriptions.size, error = "Firefly not configured")
        }

        return withContext(Dispatchers.IO) {
            try {
                val remoteRecurrings = getRecurringTransactions(baseUrl, accessToken)
                // Firefly's recurrence storage requires source_id/destination_id;
                // resolve asset + payee accounts once per sync run.
                val assetAccounts = getAccounts(baseUrl, accessToken, "asset")
                val expenseAccounts = getAccounts(baseUrl, accessToken, "expense")
                var created = 0
                var updated = 0
                var failed = 0
                val now = LocalDateTime.now()
                val processedRemoteIds = mutableSetOf<String>()

                subscriptions.forEach { local ->
                    try {
                        val state = stateBySubscription[local.id]
                        val remote = state?.fireflyRecurringId?.let { id ->
                            remoteRecurrings.find { it.id == id }
                        } ?: remoteRecurrings.find { it.title.equals(local.merchantName, ignoreCase = true) }

                        if (remote == null) {
                            // Create remote recurring transaction
                            val newId = createRecurringTransaction(
                                local, baseUrl, accessToken, defaultAssetAccount, assetAccounts, expenseAccounts
                            )
                            if (newId.isNullOrBlank()) {
                                failed++
                                return@forEach
                            }
                            fireflySyncDao.upsertSubscriptionState(
                                FireflySubscriptionStateEntity(
                                    localSubscriptionId = local.id,
                                    fireflyRecurringId = newId,
                                    syncedAt = now
                                )
                            )
                            created++
                            return@forEach
                        }

                        processedRemoteIds.add(remote.id)

                        val localInstant = toInstant(local.updatedAt)
                        val lastSyncInstant = state?.syncedAt?.let { toInstant(it) }
                        val remoteInstant = remote.updatedAt

                        val localChanged = lastSyncInstant == null || localInstant.isAfter(lastSyncInstant)
                        val remoteChanged = remoteInstant != null && (lastSyncInstant == null || remoteInstant.isAfter(lastSyncInstant))

                        when {
                            !localChanged && !remoteChanged -> {
                                fireflySyncDao.updateSubscriptionSyncInfo(local.id, remote.id, now)
                            }
                            localChanged && !remoteChanged -> {
                                val ok = updateRecurringTransaction(
                                    recurringId = remote.id,
                                    subscription = local,
                                    baseUrl = baseUrl,
                                    accessToken = accessToken,
                                    defaultAssetAccount = defaultAssetAccount,
                                    assetAccounts = assetAccounts,
                                    expenseAccounts = expenseAccounts
                                )
                                if (ok) {
                                    fireflySyncDao.updateSubscriptionSyncInfo(local.id, remote.id, now)
                                    updated++
                                } else failed++
                            }
                            else -> {
                                // Pull remote values into local subscription
                                subscriptionRepository.updateSubscriptionFromSync(
                                    subscriptionId = local.id,
                                    merchantName = remote.title,
                                    amount = remote.amount,
                                    nextPaymentDate = remote.firstDate,
                                    billingCycle = mapFireflyFrequencyToBillingCycle(remote.repeatFrequency),
                                    currency = local.currency,
                                    direction = if (remote.type == "deposit") SubscriptionDirection.INCOME else SubscriptionDirection.EXPENSE
                                )
                                fireflySyncDao.updateSubscriptionSyncInfo(local.id, remote.id, now)
                                updated++
                            }
                        }
                    } catch (e: Exception) {
                        diagnosticLogger.e(TAG, "Failed to sync recurring ${local.merchantName}", e)
                        failed++
                    }
                }

                // Import remote recurrences that have no local match as new subscriptions
                remoteRecurrings.forEach { remote ->
                    if (remote.id in processedRemoteIds) return@forEach
                    try {
                        val insertId = subscriptionRepository.insertSubscription(
                            SubscriptionEntity(
                                merchantName = remote.title,
                                amount = remote.amount,
                                nextPaymentDate = remote.firstDate,
                                state = if (remote.active) SubscriptionState.ACTIVE else SubscriptionState.ENDED,
                                category = null,
                                currency = "INR", // Firefly recurring doesn't expose a currency directly; default to INR
                                direction = if (remote.type == "deposit") SubscriptionDirection.INCOME else SubscriptionDirection.EXPENSE,
                                billingCycle = mapFireflyFrequencyToBillingCycle(remote.repeatFrequency)
                            )
                        )
                        fireflySyncDao.upsertSubscriptionState(
                            FireflySubscriptionStateEntity(
                                localSubscriptionId = insertId,
                                fireflyRecurringId = remote.id,
                                syncedAt = now
                            )
                        )
                        created++
                    } catch (e: Exception) {
                        diagnosticLogger.e(TAG, "Failed to import recurring ${remote.title}", e)
                        failed++
                    }
                }

                RecurringSyncResult(created = created, updated = updated, failed = failed)
            } catch (e: Exception) {
                RecurringSyncResult(created = 0, updated = 0, failed = subscriptions.size, error = e.message)
            }
        }
    }

    private suspend fun getRecurringTransactions(
        baseUrl: String,
        accessToken: String
    ): List<FireflyRecurringInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val response: HttpResponse = client.get("$normalizedUrl$API_PATH/recurrences?limit=1000") {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/vnd.api+json")
                }
                if (!response.status.isSuccess()) return@withContext emptyList()

                val body = response.bodyAsText()
                val parsed = json.decodeFromString<FireflyRecurringResponse>(body)
                parsed.data.mapNotNull { wrapper ->
                    val attrs = wrapper.attributes
                    val id = wrapper.id ?: return@mapNotNull null
                    // Cadence comes from the first repetition; the amount and
                    // accounts from the first transaction (spec: Recurrence).
                    val firstTx = attrs.transactions?.firstOrNull()
                    val type = attrs.type?.lowercase() ?: "withdrawal"
                    val amount = firstTx?.amount?.toBigDecimalOrNull() ?: return@mapNotNull null
                    val firstDate = parseDate(attrs.first_date) ?: return@mapNotNull null
                    FireflyRecurringInfo(
                        id = id,
                        type = type,
                        title = attrs.title ?: return@mapNotNull null,
                        amount = amount,
                        firstDate = firstDate,
                        repeatFrequency = attrs.repetitions?.firstOrNull()?.type?.lowercase() ?: "monthly",
                        active = attrs.active,
                        updatedAt = parseInstant(attrs.updated_at),
                        sourceName = firstTx?.source_name,
                        destinationName = firstTx?.destination_name
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
        defaultAssetAccount: String?,
        assetAccounts: List<FireflyAccountInfo>,
        expenseAccounts: List<FireflyAccountInfo>
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val payload = buildRecurringRequestBody(
                    subscription, defaultAssetAccount, baseUrl, accessToken, assetAccounts, expenseAccounts
                )
                val response: HttpResponse = client.post("$normalizedUrl$API_PATH/recurrences") {
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
        defaultAssetAccount: String?,
        assetAccounts: List<FireflyAccountInfo>,
        expenseAccounts: List<FireflyAccountInfo>
    ): Boolean {
        if (recurringId.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)
                val payload = buildRecurringRequestBody(
                    subscription, defaultAssetAccount, baseUrl, accessToken, assetAccounts, expenseAccounts
                )
                val response: HttpResponse = client.put("$normalizedUrl$API_PATH/recurrences/$recurringId") {
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

    private suspend fun buildRecurringRequestBody(
        subscription: SubscriptionEntity,
        defaultAssetAccount: String?,
        baseUrl: String,
        accessToken: String,
        assetAccounts: List<FireflyAccountInfo>,
        expenseAccounts: List<FireflyAccountInfo>
    ): FireflyRecurringCreateRequest {
        val type = if (subscription.direction == SubscriptionDirection.INCOME) "deposit" else "withdrawal"
        val account = subscription.bankName?.takeIf { it.isNotBlank() }
            ?: defaultAssetAccount?.takeIf { it.isNotBlank() }
            ?: "Checking Account"
        val (source, destination) = when (type) {
            "withdrawal" -> account to subscription.merchantName
            else -> subscription.merchantName to account
        }
        // Firefly's recurrence storage requires the id keys: resolve the asset
        // account id and the payee's expense-account id (creating the payee
        // account on first contact), and send both id+name per account.
        val assetId = assetAccounts.firstOrNull { it.name.equals(source, ignoreCase = true) }?.id
            ?: assetAccounts.firstOrNull()?.id
        val payeeId = resolvePayeeAccountId(baseUrl, accessToken, expenseAccounts, subscription.merchantName)
        val (sourceId, destinationId) = when (type) {
            "withdrawal" -> assetId to payeeId
            else -> payeeId to assetId
        }
        // Spec: first_date "Must be after today" — never send today or the past.
        val firstDate = (subscription.nextPaymentDate ?: LocalDate.now())
            .let { if (it.isAfter(LocalDate.now())) it else LocalDate.now().plusDays(1) }
        return FireflyRecurringCreateRequest(
            type = type,
            title = subscription.merchantName,
            first_date = firstDate.format(DATE_FORMATTER),
            // Spec: repeat_until is required; a far horizon keeps the recurrence
            // active without an end date (use it or nr_of_repetitions, not both).
            repeat_until = firstDate.plusYears(10).format(DATE_FORMATTER),
            repetitions = listOf(
                FireflyRecurrenceRepetitionStore(
                    type = mapBillingCycleToFirefly(subscription.billingCycle),
                    moment = "1"
                )
            ),
            transactions = listOf(
                FireflyRecurringTransactionStore(
                    amount = subscription.amount.abs().toPlainString(),
                    source_id = sourceId,
                    source_name = source,
                    destination_id = destinationId,
                    destination_name = destination,
                    description = "Synced from Cashiro subscription • cycle=${subscription.billingCycle}".take(255)
                )
            ),
            notes = "Synced from Cashiro subscription • cycle=${subscription.billingCycle}",
            active = subscription.state == SubscriptionState.ACTIVE,
            // L1: same rule as the transaction push paths — recurring pushes
            // must not let server rules mutate the synced template.
            apply_rules = false
        )
    }

    /**
     * Returns the id of the expense/payee account for [merchantName], creating
     * it (spec: AccountStore) on first contact so both recurrence and future
     * transaction creates have a stable id they can reference.
     */
    private suspend fun resolvePayeeAccountId(
        baseUrl: String,
        accessToken: String,
        expenseAccounts: List<FireflyAccountInfo>,
        merchantName: String
    ): String? {
        expenseAccounts.firstOrNull { it.name.equals(merchantName, ignoreCase = true) }?.let { return it.id }
        return try {
            val normalizedUrl = normalizeBaseUrl(baseUrl)
            val response: HttpResponse = client.post("$normalizedUrl$API_PATH/accounts") {
                header("Authorization", "Bearer $accessToken")
                header("Accept", "application/vnd.api+json")
                contentType(ContentType.Application.Json)
                setBody(FireflyAccountCreateRequest(name = merchantName, type = "expense"))
            }
            if (response.status.isSuccess()) {
                extractCreatedId(response.bodyAsText())
            } else {
                diagnosticLogger.w(TAG, "Failed to create payee account $merchantName: ${response.status}")
                null
            }
        } catch (e: Exception) {
            diagnosticLogger.e(TAG, "Failed to create payee account $merchantName", e)
            null
        }
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
        val active: Boolean = true,
        val created_at: String? = null,
        val updated_at: String? = null,
        // Cadence lives on the first repetition, amount/accounts on the first
        // transaction (spec: Recurrence) — the recurrence object itself has neither.
        val repetitions: List<FireflyRecurrenceRepetition>? = null,
        val transactions: List<FireflyRecurringTransactionTemplate>? = null
    )

    @Serializable
    private data class FireflyRecurrenceRepetition(
        val type: String? = null,
        val moment: String? = null,
        val skip: Int? = null,
        val weekend: Int? = null
    )

    @Serializable
    private data class FireflyRecurringTransactionTemplate(
        val amount: String? = null,
        val source_name: String? = null,
        val destination_name: String? = null,
        val description: String? = null
    )

    @Serializable
    private data class FireflyRecurringCreateRequest(
        val type: String,
        val title: String,
        val first_date: String,
        val repeat_until: String,
        val repetitions: List<FireflyRecurrenceRepetitionStore>,
        val transactions: List<FireflyRecurringTransactionStore>,
        val notes: String? = null,
        val active: Boolean = true,
        // L1: recurring push paths keep server rules inert (see buildRecurringRequestBody).
        val apply_rules: Boolean = false
    )

    @Serializable
    private data class FireflyRecurrenceRepetitionStore(
        /** daily, weekly, monthly, quarterly, half-year or yearly. */
        val type: String,
        /** Moment of the repetition: day-of-month for monthly, day-of-week for weekly. */
        val moment: String = "1"
    )

    @Serializable
    private data class FireflyRecurringTransactionStore(
        val amount: String,
        // Firefly <= 6.x recurrence storage indexes these id keys directly and
        // 500s ("Undefined array key source_id/destination_id") when only names
        // are sent — resolve and send both, names included for consistency.
        val source_id: String? = null,
        val source_name: String? = null,
        val destination_id: String? = null,
        val destination_name: String? = null,
        val description: String? = null
    )
}

// ---------------------------------------------------------------------------
// H1: HTTP retry policy
// ---------------------------------------------------------------------------

/** Ceiling for a single Firefly request: 1 attempt + [FIREFLY_RETRY_MAX_ATTEMPTS] retries. */
internal const val FIREFLY_RETRY_MAX_ATTEMPTS = 3

/**
 * The Firefly retry policy, extracted so the production client and the JVM
 * tests install exactly the same one.
 *
 * What the previous blanket policy (`retryOnServerErrors` + retry on any
 * `IOException`) got wrong:
 *
 *  - **429 was never retried.** Firefly rate-limits `/search/transactions`, so
 *    a rate-limited search fell through as "not on Firefly" and straight into
 *    the create path — duplicate journals at scale. 429 is now retried with
 *    exponential backoff, and `exponentialDelay(respectRetryAfterHeader = true)`
 *    raises the wait to the server's `Retry-After` seconds whenever the header
 *    is present (Ktor 2.3.13 `HttpRequestRetry.delayMillis` honours it).
 *    A 429 is safe to repeat for *any* method: the rate limiter rejects the
 *    request before Firefly touches a journal.
 *  - **5xx/IOException were retried regardless of method**, which re-POSTed a
 *    create that may already have committed server-side before a read timeout.
 *    Firefly does not dedupe `external_id` on POST `/transactions`, so that
 *    duplicated user data. Idempotent reads (GET/HEAD/DELETE) retry freely on
 *    any `IOException`; POST/PUT retry only on failures that provably happened
 *    before the request reached the server (DNS/connect phase) — never after a
 *    response, and never on a read-phase timeout. A create that fails that way
 *    is left to the state/retry pipeline (see `markError`) instead of being
 *    blindly re-sent.
 *
 * @param maxRetries retry ceiling.
 * @param sleeper the wait implementation; tests inject a recorder so they can
 *                assert the computed backoff without actually sleeping.
 */
internal fun HttpClientConfig<*>.installFireflyRetryPolicy(
    maxRetries: Int = FIREFLY_RETRY_MAX_ATTEMPTS,
    sleeper: suspend (Long) -> Unit = { millis -> kotlinx.coroutines.delay(millis) }
) {
    install(HttpRequestRetry) {
        this.maxRetries = maxRetries

        // A response came back: decide by status + method.
        retryIf { request, response ->
            when (response.status.value) {
                HttpStatusCode.TooManyRequests.value -> true
                in 500..599 -> isIdempotentFireflyMethod(request.method)
                else -> false
            }
        }

        // No response came back: decide by method + where the failure happened.
        retryOnExceptionIf { request, cause ->
            when {
                isIdempotentFireflyMethod(request.method) -> cause is java.io.IOException
                else -> isPreCommitFailure(cause)
            }
        }

        exponentialDelay(
            base = 2.0,
            maxDelayMs = 30_000L,
            randomizationMs = 1_000L,
            respectRetryAfterHeader = true
        )
        delay(sleeper)
    }
}

/** Reads can be re-issued without changing server state; writes cannot. */
internal fun isIdempotentFireflyMethod(method: HttpMethod): Boolean =
    method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Delete

/**
 * True only for failures that provably happened before the request reached
 * Firefly, so repeating a POST/PUT cannot duplicate a committed journal.
 * Read-phase timeouts deliberately return `false`: the create may already exist
 * remotely and the state pipeline handles it.
 */
internal fun isPreCommitFailure(cause: Throwable): Boolean = when {
    cause is java.net.ConnectException -> true
    cause is java.net.UnknownHostException -> true
    cause is java.net.NoRouteToHostException -> true
    cause is java.net.SocketTimeoutException ->
        cause.message?.contains("connect", ignoreCase = true) == true

    else -> false
}
