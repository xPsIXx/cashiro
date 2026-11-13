package com.pennywiseai.tracker.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.database.entity.BudgetGroupType
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.firefly.FireflyClient
import com.pennywiseai.tracker.data.firefly.FireflyLogEntity
import com.pennywiseai.tracker.data.firefly.FireflyPreferencesRepository
import com.pennywiseai.tracker.data.firefly.FireflySyncDao
import com.pennywiseai.tracker.data.firefly.FireflySyncEngine
import com.pennywiseai.tracker.data.firefly.FireflyTokenManager
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.AccountBalanceRepository
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.CategoryRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import com.pennywiseai.tracker.worker.FireflyAutoSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * View model for the Firefly III settings screen. Everything here is backed
 * by the add-on's own preferences repository + sync engine — the app's
 * [com.pennywiseai.tracker.data.preferences.UserPreferencesRepository] and
 * entities no longer know Firefly exists.
 */
@HiltViewModel
class FireflySettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fireflyPreferencesRepository: FireflyPreferencesRepository,
    private val fireflyTokenManager: FireflyTokenManager,
    private val fireflyClient: FireflyClient,
    private val fireflySyncEngine: FireflySyncEngine,
    private val fireflySyncDao: FireflySyncDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val transactionRepository: TransactionRepository,
    private val diagnosticLogger: com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
) : ViewModel() {

    // ── States ────────────────────────────────────────────────────────────────

    val fireflySyncEnabled = fireflyPreferencesRepository.syncEnabledFlow
    val fireflyBaseUrl = fireflyPreferencesRepository.baseUrlFlow
    val fireflyDefaultAssetAccount = fireflyPreferencesRepository.defaultAssetAccountFlow
    val fireflyLastSyncError = fireflyPreferencesRepository.lastSyncErrorFlow
    // S2: secure-storage failure (keystore) — surfaced so the user knows the
    // token is session-only, not silently stored unencrypted.
    val fireflyStorageError = fireflyPreferencesRepository.storageErrorFlow
    // S4: http:// base URL — the PAT travels in cleartext; warn, don't block.
    val fireflyInsecureBaseUrlWarning = fireflyPreferencesRepository.insecureBaseUrlWarningFlow
    val fireflyAutoSyncInterval = fireflyPreferencesRepository.autoSyncIntervalFlow
    val fireflyMigrationRan = fireflyPreferencesRepository.migrationRanFlow

    val fireflyAccountMappings = fireflyPreferencesRepository.accountMappingsFlow
    val fireflyCategoryMappings = fireflyPreferencesRepository.categoryMappingsFlow
    val fireflyIncludeRawSms = fireflyPreferencesRepository.includeRawSmsFlow

    /** Failed sync count (badge on the settings screen). */
    val fireflyFailedSyncCount = fireflySyncEngine.errorCount()

    /** In-app sync log (most recent first). */
    val fireflySyncLog: Flow<List<FireflyLogEntity>> = fireflySyncEngine.recentLogs(50)

    fun clearSyncLog() {
        viewModelScope.launch {
            fireflySyncDao.deleteAllLogs()
        }
    }

    /** Whole log as text for copy/share (most recent first). */
    suspend fun currentLogText(): String {
        return fireflySyncDao.recentLogs(500).first()
            .joinToString("\n") { entry ->
                val whenStr = when (entry.direction) {
                    "push" -> "push"
                    "pull" -> "pull"
                    "resync" -> "resync"
                    "budget" -> "budget"
                    "subscription" -> "subscription"
                    else -> entry.direction
                }
                val statusStr = when (entry.status) {
                    "ok" -> "OK"
                    "error" -> "ERROR"
                    "skip" -> "SKIP"
                    else -> entry.status
                }
                val txPart = entry.localTransactionId?.let { " tx=${it}" } ?: ""
                "${entry.at} [$whenStr/$statusStr]$txPart ${entry.message ?: ""}".trim()
            }
    }

    val allCategoriesForMapping = categoryRepository.getAllCategories()
        .map { categories -> categories.map { it.name }.sorted() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _fireflyAccounts =
        MutableStateFlow<List<FireflyClient.FireflyAccountInfo>>(emptyList())
    val fireflyAccounts: StateFlow<List<FireflyClient.FireflyAccountInfo>> = _fireflyAccounts.asStateFlow()

    private val _fireflyCategories = MutableStateFlow<List<String>>(emptyList())
    val fireflyCategories: StateFlow<List<String>> = _fireflyCategories.asStateFlow()

    private val _isLoadingFireflyAccounts = MutableStateFlow(false)
    val isLoadingFireflyAccounts: StateFlow<Boolean> = _isLoadingFireflyAccounts.asStateFlow()

    val knownAccountsForMapping = accountBalanceRepository.getAllLatestBalances()

    /** Clean list of accounts for the Firefly mapping UI inside the dialog. */
    val fireflyMappingAccounts: Flow<List<AccountMappingItem>> =
        knownAccountsForMapping
            .map { balances ->
                balances
                    .distinctBy { "${it.bankName}**${it.accountLast4}" }
                    .sortedBy { it.bankName }
                    .map { balance ->
                        val key = "${balance.bankName}**${balance.accountLast4}"
                        AccountMappingItem(
                            key = key,
                            displayName = "${balance.bankName} ****${balance.accountLast4}",
                            isCreditCard = balance.isCreditCard
                        )
                    }
            }

    init {
        viewModelScope.launch {
            // One-time legacy prefs migration + worker scheduling from stored pref.
            fireflyPreferencesRepository.ensureLegacyMigration()
            val interval = fireflyPreferencesRepository.autoSyncIntervalFlow.first()
            FireflyAutoSyncWorker.schedule(context, interval)
        }
    }

    // ── Connection ───────────────────────────────────────────────────────────

    suspend fun testFireflyConnection(url: String, token: String): FireflyClient.SyncResult {
        return fireflyClient.testConnection(url, token)
    }

    fun refreshFireflyAccounts() {
        viewModelScope.launch {
            _isLoadingFireflyAccounts.value = true
            try {
                val (url, token) = getFireflySecureCredentials()
                if (!url.isNullOrBlank() && !token.isNullOrBlank()) {
                    val accounts = fireflyClient.getAccounts(url, token)
                    _fireflyAccounts.value = accounts
                    val categories = fireflyClient.getCategories(url, token)
                    _fireflyCategories.value = categories
                } else {
                    _fireflyAccounts.value = emptyList()
                    _fireflyCategories.value = emptyList()
                }
            } finally {
                _isLoadingFireflyAccounts.value = false
            }
        }
    }

    /** Saves Firefly credentials. The token is stored in EncryptedSharedPreferences only. */
    fun saveFireflyCredentials(url: String, token: String, defaultAsset: String?) {
        viewModelScope.launch {
            fireflyPreferencesRepository.setBaseUrl(url)
            fireflyPreferencesRepository.setDefaultAssetAccount(defaultAsset)
            fireflyTokenManager.saveCredentials(url, token)
        }
    }

    /** Returns current secure credentials for initial loading. */
    fun getFireflySecureCredentials(): Pair<String?, String?> {
        return fireflyTokenManager.getBaseUrl() to fireflyTokenManager.getAccessToken()
    }

    fun clearFireflyLastError() {
        viewModelScope.launch {
            fireflyPreferencesRepository.clearLastError()
        }
    }

    // ── Toggles / mappings ────────────────────────────────────────────────────

    fun setFireflySyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            fireflyPreferencesRepository.setSyncEnabled(enabled)
        }
    }

    fun setFireflyAutoSyncInterval(interval: String) {
        viewModelScope.launch {
            fireflyPreferencesRepository.setAutoSyncInterval(interval)
            FireflyAutoSyncWorker.schedule(context, interval)
        }
    }

    fun setFireflyIncludeRawSms(include: Boolean) {
        viewModelScope.launch {
            fireflyPreferencesRepository.setIncludeRawSms(include)
        }
    }

    fun setFireflyAccountMapping(accountKey: String, fireflyAccountName: String) {
        viewModelScope.launch {
            fireflyPreferencesRepository.setAccountMapping(accountKey, fireflyAccountName)
        }
    }

    fun clearFireflyAccountMapping(accountKey: String) {
        viewModelScope.launch {
            fireflyPreferencesRepository.clearAccountMapping(accountKey)
        }
    }

    fun clearAllFireflyAccountMappings() {
        viewModelScope.launch {
            fireflyPreferencesRepository.clearAllAccountMappings()
        }
    }

    fun setFireflyCategoryMapping(pennywiseCategory: String, fireflyCategory: String) {
        viewModelScope.launch {
            fireflyPreferencesRepository.setCategoryMapping(pennywiseCategory, fireflyCategory)
        }
    }

    fun clearFireflyCategoryMapping(pennywiseCategory: String) {
        viewModelScope.launch {
            fireflyPreferencesRepository.clearCategoryMapping(pennywiseCategory)
        }
    }

    // ── Transaction syncs ─────────────────────────────────────────────────────

    // Sync transactions from the last 30 days that haven't been sent to Firefly yet.
    fun syncLast30Days(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val summary = runCatching { fireflySyncEngine.resyncAll(scopeDays = 30) }
                .getOrElse { e ->
                    onResult("Error syncing last 30 days: ${e.message}")
                    return@launch
                }
            onResult(
                "Synced ${summary.created} of ${summary.attempted} transactions from last 30 days" +
                    if (summary.skipped > 0) " (${summary.skipped} already present)" else ""
            )
        }
    }

    // Sync all currently unsynced transactions (those never successfully pushed to Firefly).
    fun syncAllUnsynced(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val summary = runCatching { fireflySyncEngine.resyncAll() }
                .getOrElse { e ->
                    onResult("Error syncing unsynced: ${e.message}")
                    return@launch
                }
            onResult(
                "Synced ${summary.created} of ${summary.attempted} unsynced transactions" +
                    if (summary.skipped > 0) " (${summary.skipped} already present)" else ""
            )
        }
    }

    // Full sync: synchronize everything (unsynced + previously failed, re-push if needed).
    fun fullSyncToFirefly(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val summary = runCatching { fireflySyncEngine.resyncAll() }
                .getOrElse { e ->
                    onResult("Error during full sync: ${e.message}")
                    return@launch
                }
            onResult(
                "Full sync complete. Created: ${summary.created}, Skipped: ${summary.skipped}, Errors: ${summary.failed} (out of ${summary.attempted})"
            )
        }
    }

    // ── Budgets / subscriptions / categories / piggy banks ──────────────────

    fun syncFireflyBudgets(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val summary = runCatching { fireflySyncEngine.syncBudgets() }
                .getOrElse { e ->
                    onResult("Error syncing budgets: ${e.message}")
                    return@launch
                }
            onResult(
                when {
                    summary.failed == 0 -> "Synced budgets: ${summary.created} created, ${summary.updated} updated"
                    else -> "Synced budgets: ${summary.created} created, ${summary.updated} updated, ${summary.failed} failed"
                }
            )
        }
    }

    fun syncFireflyRecurring(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val summary = runCatching { fireflySyncEngine.syncSubscriptions() }
                .getOrElse { e ->
                    onResult("Error syncing recurring transactions: ${e.message}")
                    return@launch
                }
            onResult(
                when {
                    summary.failed == 0 -> "Synced recurring: ${summary.created} created, ${summary.updated} updated"
                    else -> "Recurring sync: ${summary.created} created, ${summary.updated} updated, ${summary.failed} failed"
                }
            )
        }
    }

    // Sync PennyWise categories to Firefly (auto-create missing ones + default mappings).
    fun syncFireflyCategories(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run {
                    onResult("Firefly not configured")
                    return@launch
                }

                val pennywiseCategories = categoryRepository.getAllCategories()
                    .first()
                    .map { it.name }

                if (pennywiseCategories.isEmpty()) {
                    onResult("No categories to sync")
                    return@launch
                }

                val result = fireflyClient.syncCategories(
                    pennywiseCategories = pennywiseCategories,
                    baseUrl = config.url,
                    accessToken = config.token
                )

                if (result.error != null) {
                    onResult("Category sync error: ${result.error}")
                    return@launch
                }

                // Save default exact-name mappings for categories that aren't already mapped.
                result.mappings.forEach { (pennywiseName, fireflyName) ->
                    val current = fireflyPreferencesRepository.categoryMappingsFlow.first()
                    if (!current.containsKey(pennywiseName)) {
                        fireflyPreferencesRepository.setCategoryMapping(pennywiseName, fireflyName)
                    }
                }

                val failurePart = if (result.failureNames.isNotEmpty()) {
                    " Could not create: ${result.failureNames.joinToString(", ")}"
                } else ""
                onResult("Synced categories: ${result.created} created in Firefly$failurePart")
            } catch (e: Exception) {
                onResult("Error syncing categories: ${e.message}")
            }
        }
    }

    // Sync PennyWise TARGET budgets to Firefly piggy banks.
    fun syncFireflyPiggyBanks(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run {
                    onResult("Firefly not configured")
                    return@launch
                }

                val targetBudgets = budgetRepository.getActiveBudgets()
                    .first()
                    .filter { it.groupType == BudgetGroupType.TARGET }

                if (targetBudgets.isEmpty()) {
                    onResult("No target budgets to sync")
                    return@launch
                }

                val result = fireflyClient.syncBudgetTargetsToPiggyBanks(
                    targetBudgets = targetBudgets,
                    baseUrl = config.url,
                    accessToken = config.token,
                    defaultAssetAccount = config.defaultAssetAccount
                )

                onResult(
                    when {
                        result.failed == 0 -> "Synced ${result.created} created, ${result.updated} updated piggy banks"
                        else -> "Piggy banks: ${result.created} created, ${result.updated} updated, ${result.failed} failed"
                    }
                )
            } catch (e: Exception) {
                onResult("Error syncing piggy banks: ${e.message}")
            }
        }
    }

    /**
     * Reconcile local transactions with Firefly after reinstall or data loss.
     * Uses stable hash-based external_id to find existing entries in Firefly and
     * mark matching local rows as synced. State is recorded in the sidecar DB.
     */
    fun reconcileWithFirefly(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run {
                    onResult("Firefly not configured")
                    return@launch
                }

                val all = transactionRepository.getAllTransactionsList()
                val states = fireflySyncDao.statesFor(all.map { it.id }).associateBy { it.localTransactionId }
                val unsynced = all.filter { states[it.id]?.syncedAt == null }

                var reconciled = 0
                unsynced.forEach { tx ->
                    val candidates = mutableListOf<String>()
                    if (!tx.transactionHash.isNullOrBlank()) {
                        candidates.add("pennywise-${tx.transactionHash}")
                    }
                    states[tx.id]?.externalId?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                    for (candidate in candidates.distinct()) {
                        val fireflyId = fireflyClient.getTransactionIdByExternalId(config.url, config.token, candidate)
                        if (fireflyId != null) {
                            // Use the hash version when we have it, else keep the candidate.
                            val hashExt = if (!tx.transactionHash.isNullOrBlank()) "pennywise-${tx.transactionHash}" else candidate
                            fireflySyncDao.markSynced(
                                txId = tx.id,
                                externalId = hashExt,
                                remoteTxId = fireflyId,
                                updatedAt = null
                            )
                            reconciled++
                            break
                        }
                    }
                }

                onResult("Reconciled $reconciled transactions using Firefly as source of truth")
            } catch (e: Exception) {
                onResult("Reconcile error: ${e.message}")
            }
        }
    }

    // Send a test transaction to Firefly.
    fun sendTestTransaction(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run {
                    onResult("Firefly not configured")
                    return@launch
                }

                val testTx = TransactionEntity(
                    id = -1,
                    amount = BigDecimal("123.45"),
                    merchantName = "Test Merchant (PennyWise)",
                    category = "Testing",
                    transactionType = TransactionType.EXPENSE,
                    dateTime = LocalDateTime.now(),
                    bankName = "Test Bank",
                    accountNumber = "0000",
                    currency = config.baseCurrency,
                    transactionHash = "TEST_${System.currentTimeMillis()}"
                )

                val accountKey = "${testTx.bankName}**${testTx.accountNumber}"
                val mappedAccount = config.accountMappings[accountKey]
                val usedAccount = mappedAccount?.takeIf { it.isNotBlank() }
                    ?: config.defaultAssetAccount?.takeIf { it.isNotBlank() }
                    ?: "Checking Account"

                val result = fireflyClient.syncTransaction(
                    transaction = testTx,
                    baseUrl = config.url,
                    accessToken = config.token,
                    defaultAssetAccount = config.defaultAssetAccount,
                    accountMappings = config.accountMappings,
                    categoryMappings = config.categoryMappings,
                    includeRawSmsInNotes = config.includeRawSms
                )

                when (result) {
                    is FireflyClient.SyncResult.Success -> onResult("Test transaction sent successfully using account: $usedAccount")
                    is FireflyClient.SyncResult.Error -> onResult("Failed: ${result.message}")
                    else -> onResult("Skipped")
                }
            } catch (e: Exception) {
                onResult("Error: ${e.message}")
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private data class FireflyConfig(
        val url: String,
        val token: String,
        val defaultAssetAccount: String?,
        val accountMappings: Map<String, String>,
        val categoryMappings: Map<String, String>,
        val includeRawSms: Boolean,
        val baseCurrency: String
    )

    private suspend fun fetchFireflyConfig(): FireflyConfig? {
        if (!fireflySyncEngine.isConfigured()) return null
        val url = fireflyTokenManager.getBaseUrl()?.takeIf { it.isNotBlank() } ?: return null
        val token = fireflyTokenManager.getAccessToken()?.takeIf { it.isNotBlank() } ?: return null
        val accountMappings = fireflyPreferencesRepository.accountMappingsFlow.first()
        val categoryMappings = fireflyPreferencesRepository.categoryMappingsFlow.first()
        val includeRaw = fireflyPreferencesRepository.includeRawSmsFlow.first()

        return FireflyConfig(
            url = url,
            token = token,
            defaultAssetAccount = fireflyPreferencesRepository.defaultAssetAccountFlow.first(),
            accountMappings = accountMappings,
            categoryMappings = categoryMappings,
            includeRawSms = includeRaw,
            baseCurrency = userPreferencesRepository.baseCurrency.first()
        )
    }
}

/** UI model for showing accounts in the Firefly mapping section. */
data class AccountMappingItem(
    val key: String,           // e.g. "HDFC Bank**1234"
    val displayName: String,   // e.g. "HDFC Bank ****1234"
    val isCreditCard: Boolean = false
)
