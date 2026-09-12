package com.pennywiseai.tracker.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.firefly.FireflyClient
import com.pennywiseai.tracker.data.firefly.FireflyTokenManager
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.AccountBalanceRepository
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.CategoryRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
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
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class FireflySettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val transactionRepository: TransactionRepository,
    private val fireflyClient: FireflyClient,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val fireflyTokenManager: FireflyTokenManager,
    private val diagnosticLogger: com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
) : ViewModel() {

    val fireflySyncEnabled = userPreferencesRepository.fireflySyncEnabledFlow
    val fireflyBaseUrl = userPreferencesRepository.fireflyBaseUrlFlow
    val fireflyDefaultAssetAccount = userPreferencesRepository.fireflyDefaultAssetAccountFlow
    val fireflyLastSyncError = userPreferencesRepository.fireflyLastSyncErrorFlow
    val fireflyFailedSyncCount = transactionRepository.getFailedFireflySyncCount()
    val fireflyAccountMappings = userPreferencesRepository.fireflyAccountMappingsFlow
    val fireflyCategoryMappings = userPreferencesRepository.fireflyCategoryMappingsFlow
    val fireflyIncludeRawSms = userPreferencesRepository.fireflyIncludeRawSmsFlow
    val fireflyAutoSyncInterval = userPreferencesRepository.fireflyAutoSyncIntervalFlow
    val fireflyMigrationRan = userPreferencesRepository.fireflyMigrationRanFlow

    val allCategoriesForMapping = categoryRepository.getAllCategories()
        .map { categories -> categories.map { it.name }.sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _fireflyAccounts = MutableStateFlow<List<FireflyClient.FireflyAccountInfo>>(emptyList())
    val fireflyAccounts: StateFlow<List<FireflyClient.FireflyAccountInfo>> = _fireflyAccounts.asStateFlow()

    private val _fireflyCategories = MutableStateFlow<List<String>>(emptyList())
    val fireflyCategories: StateFlow<List<String>> = _fireflyCategories.asStateFlow()

    private val _isLoadingFireflyAccounts = MutableStateFlow(false)
    val isLoadingFireflyAccounts: StateFlow<Boolean> = _isLoadingFireflyAccounts.asStateFlow()

    val knownAccountsForMapping = accountBalanceRepository.getAllLatestBalances()

    val fireflyMappingAccounts: Flow<List<AccountMappingItem>> =
        knownAccountsForMapping.map { balances ->
            balances
                .distinctBy { "${it.bankName}**${it.accountLast4}" }
                .sortedBy { it.bankName }
                .map { balance ->
                    AccountMappingItem(
                        key = "${balance.bankName}**${balance.accountLast4}",
                        displayName = "${balance.bankName} ****${balance.accountLast4}",
                        isCreditCard = balance.isCreditCard
                    )
                }
        }

    fun setFireflySyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflySyncEnabled(enabled)
            if (enabled) {
                val interval = userPreferencesRepository.fireflyAutoSyncIntervalFlow.first()
                com.pennywiseai.tracker.worker.FireflyAutoSyncWorker.schedule(context, interval)
                val migrationRan = userPreferencesRepository.fireflyMigrationRanFlow.first()
                if (!migrationRan) {
                    migrateLegacyFireflyExternalIds()
                    reconcileWithFirefly { result ->
                        diagnosticLogger.d("FireflySettings", "Migration reconcile result: $result")
                    }
                    userPreferencesRepository.setFireflyMigrationRan(true)
                }
            } else {
                com.pennywiseai.tracker.worker.FireflyAutoSyncWorker.cancel(context)
            }
        }
    }

    fun setFireflyBaseUrl(url: String) {
        viewModelScope.launch { userPreferencesRepository.setFireflyBaseUrl(url) }
    }

    fun setFireflyDefaultAssetAccount(account: String) {
        viewModelScope.launch { userPreferencesRepository.setFireflyDefaultAssetAccount(account) }
    }

    suspend fun testFireflyConnection(url: String, token: String): FireflyClient.SyncResult {
        return fireflyClient.testConnection(url, token)
    }

    fun clearFireflyLastError() {
        viewModelScope.launch { userPreferencesRepository.clearFireflyLastError() }
    }

    fun setFireflyAccountMapping(accountKey: String, fireflyAccountName: String) {
        viewModelScope.launch { userPreferencesRepository.setFireflyAccountMapping(accountKey, fireflyAccountName) }
    }

    fun clearFireflyAccountMapping(accountKey: String) {
        viewModelScope.launch { userPreferencesRepository.clearFireflyAccountMapping(accountKey) }
    }

    fun clearAllFireflyAccountMappings() {
        viewModelScope.launch { userPreferencesRepository.clearAllFireflyAccountMappings() }
    }

    fun refreshFireflyAccounts() {
        viewModelScope.launch {
            _isLoadingFireflyAccounts.value = true
            try {
                val (url, token) = getFireflySecureCredentials()
                if (!url.isNullOrBlank() && !token.isNullOrBlank()) {
                    _fireflyAccounts.value = fireflyClient.getAccounts(url, token)
                    _fireflyCategories.value = fireflyClient.getCategories(url, token)
                } else {
                    _fireflyAccounts.value = emptyList()
                    _fireflyCategories.value = emptyList()
                }
            } finally {
                _isLoadingFireflyAccounts.value = false
            }
        }
    }

    fun setFireflyCategoryMapping(pennywiseCategory: String, fireflyCategory: String) {
        viewModelScope.launch { userPreferencesRepository.setFireflyCategoryMapping(pennywiseCategory, fireflyCategory) }
    }

    fun clearFireflyCategoryMapping(pennywiseCategory: String) {
        viewModelScope.launch { userPreferencesRepository.clearFireflyCategoryMapping(pennywiseCategory) }
    }

    fun setFireflyIncludeRawSms(include: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setFireflyIncludeRawSms(include) }
    }

    fun setFireflyAutoSyncInterval(interval: String) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyAutoSyncInterval(interval)
            com.pennywiseai.tracker.worker.FireflyAutoSyncWorker.schedule(context, interval)
        }
    }

    fun saveFireflyCredentials(url: String, token: String, defaultAsset: String?) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyBaseUrl(url)
            userPreferencesRepository.setFireflyDefaultAssetAccount(defaultAsset)
            fireflyTokenManager.saveCredentials(url, token)
        }
    }

    fun getFireflySecureCredentials(): Pair<String?, String?> {
        return fireflyTokenManager.getBaseUrl() to fireflyTokenManager.getAccessToken()
    }

    fun syncLast30Days(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run { onResult("Firefly not configured"); return@launch }
                val thirtyDaysAgo = LocalDateTime.now().minusDays(30)
                val unsynced = transactionRepository.getTransactionsBetweenDates(thirtyDaysAgo, LocalDateTime.now())
                    .first()
                    .filter { it.fireflySyncedAt == null && it.fireflyLastError == null }
                if (unsynced.isEmpty()) {
                    onResult("No unsynced transactions in the last 30 days")
                    return@launch
                }
                val result = syncTransactions(unsynced, config)
                onResult(
                    "Synced ${result.created} of ${unsynced.size} transactions from last 30 days" +
                        if (result.skipped > 0) " (${result.skipped} already present)" else ""
                )
            } catch (e: Exception) {
                onResult("Error syncing last 30 days: ${e.message}")
            }
        }
    }

    fun syncAllUnsynced(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run { onResult("Firefly not configured"); return@launch }
                val unsynced = transactionRepository.getAllTransactionsList()
                    .filter { it.fireflySyncedAt == null && it.fireflyLastError == null }
                if (unsynced.isEmpty()) {
                    onResult("No unsynced transactions to sync")
                    return@launch
                }
                val result = syncTransactions(unsynced, config)
                onResult(
                    "Synced ${result.created} of ${unsynced.size} unsynced transactions" +
                        if (result.skipped > 0) " (${result.skipped} already present)" else ""
                )
            } catch (e: Exception) {
                onResult("Error syncing unsynced: ${e.message}")
            }
        }
    }

    fun fullSyncToFirefly(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run { onResult("Firefly not configured"); return@launch }
                val toSync = transactionRepository.getAllTransactionsList()
                if (toSync.isEmpty()) {
                    onResult("No transactions to send")
                    return@launch
                }
                val result = syncTransactions(toSync, config)
                onResult(
                    "Resync complete. Created: ${result.created}, Updated: ${result.details.count { it.status == FireflyClient.ItemResult.ItemStatus.SUCCESS && it.message == null }}, Skipped: ${result.skipped}, Errors: ${result.failed} (out of ${toSync.size}). Owned Cashiro entries were updated; Firefly-native rows were left alone."
                )
            } catch (e: Exception) {
                onResult("Error during full sync: ${e.message}")
            }
        }
    }

    private suspend fun syncTransactions(
        transactions: List<TransactionEntity>,
        config: FireflyConfig
    ): FireflyClient.BatchSyncResult {
        val syncables = transactions.map { tx ->
            val splits = transactionRepository.getTransactionWithSplitsSync(tx.id)?.splits ?: emptyList()
            FireflyClient.SyncableTransaction(tx, splits)
        }
        val result = fireflyClient.syncTransactionsBatch(
            syncables = syncables,
            baseUrl = config.url,
            accessToken = config.token,
            defaultAssetAccount = config.defaultAssetAccount,
            accountMappings = config.accountMappings,
            categoryMappings = config.categoryMappings,
            includeRawSmsInNotes = config.includeRawSms,
            uploadReceipts = true
        )
        result.details.forEach { item ->
            val tx = transactions.find { it.id == item.transactionId } ?: return@forEach
            when (item.status) {
                FireflyClient.ItemResult.ItemStatus.SUCCESS,
                FireflyClient.ItemResult.ItemStatus.SKIPPED -> {
                    transactionRepository.markFireflySynced(
                        tx.id,
                        item.externalId,
                        item.fireflyId,
                        item.remoteUpdatedAt?.let { java.time.LocalDateTime.ofInstant(it, java.time.ZoneId.systemDefault()) }
                    )
                }
                FireflyClient.ItemResult.ItemStatus.ERROR -> {
                    transactionRepository.markFireflyError(tx.id, item.message ?: "Unknown Firefly error")
                }
            }
        }
        userPreferencesRepository.updateFireflyLastSync(
            System.currentTimeMillis(),
            error = if (result.failed > 0) "${result.failed} failed" else null
        )
        if (result.failed > 0) {
            com.pennywiseai.tracker.worker.FireflyRetryWorker.enqueue(context)
        }
        return result
    }

    fun syncFireflyCategories(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run { onResult("Firefly not configured"); return@launch }
                val pennywiseCategories = categoryRepository.getAllCategories().first().map { it.name }
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
                result.mappings.forEach { (pennywiseName, fireflyName) ->
                    val current = userPreferencesRepository.fireflyCategoryMappingsFlow.first()
                    if (!current.containsKey(pennywiseName)) {
                        userPreferencesRepository.setFireflyCategoryMapping(pennywiseName, fireflyName)
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

    fun syncFireflyPiggyBanks(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run { onResult("Firefly not configured"); return@launch }
                val targetBudgets = budgetRepository.getActiveBudgets().first()
                    .filter { it.groupType == com.pennywiseai.tracker.data.database.entity.BudgetGroupType.TARGET }
                if (targetBudgets.isEmpty()) {
                    onResult("No target budgets to sync")
                    return@launch
                }
                val result = fireflyClient.syncBudgetTargetsToPiggyBanks(
                    targetBudgets = targetBudgets,
                    baseUrl = config.url,
                    accessToken = config.token
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

    fun syncFireflyRecurring(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run { onResult("Firefly not configured"); return@launch }
                val subscriptions = subscriptionRepository.getActiveSubscriptions().first() +
                    subscriptionRepository.getEndedSubscriptions().first()
                if (subscriptions.isEmpty()) {
                    onResult("No subscriptions to sync")
                    return@launch
                }
                val result = fireflyClient.syncRecurringTransactions(
                    subscriptions = subscriptions,
                    baseUrl = config.url,
                    accessToken = config.token,
                    defaultAssetAccount = config.defaultAssetAccount
                )
                onResult(
                    when {
                        result.error != null -> "Recurring sync error: ${result.error}"
                        result.failed == 0 -> "Synced recurring: ${result.created} created, ${result.updated} updated"
                        else -> "Recurring sync: ${result.created} created, ${result.updated} updated, ${result.failed} failed"
                    }
                )
            } catch (e: Exception) {
                onResult("Error syncing recurring transactions: ${e.message}")
            }
        }
    }

    fun syncFireflyBudgets(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run { onResult("Firefly not configured"); return@launch }
                val budgets = budgetRepository.getActiveBudgets().first()
                if (budgets.isEmpty()) {
                    onResult("No active budgets to sync")
                    return@launch
                }
                val result = fireflyClient.syncBudgets(
                    budgets = budgets,
                    baseUrl = config.url,
                    accessToken = config.token
                )
                onResult(
                    when {
                        result.error != null -> "Budget sync error: ${result.error}"
                        result.failed == 0 -> "Synced budgets: ${result.created} created, ${result.updated} updated"
                        else -> "Synced budgets: ${result.created} created, ${result.updated} updated, ${result.failed} failed"
                    }
                )
            } catch (e: Exception) {
                onResult("Error syncing budgets: ${e.message}")
            }
        }
    }

    fun reconcileWithFirefly(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run { onResult("Firefly not configured"); return@launch }
                val unsynced = transactionRepository.getAllTransactionsList()
                    .filter { it.fireflySyncedAt == null }
                var reconciled = 0
                unsynced.forEach { tx ->
                    val candidates = mutableListOf<String>()
                    if (tx.transactionHash.isNotBlank()) {
                        candidates.add("pennywise-${tx.transactionHash}")
                    }
                    if (!tx.fireflyExternalId.isNullOrBlank()) {
                        candidates.add(tx.fireflyExternalId)
                    }
                    for (candidate in candidates.distinct()) {
                        val fireflyId = fireflyClient.getTransactionIdByExternalId(config.url, config.token, candidate)
                        if (fireflyId != null) {
                            val hashExt = if (tx.transactionHash.isNotBlank()) "pennywise-${tx.transactionHash}" else candidate
                            transactionRepository.markFireflySynced(tx.id, hashExt)
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

    fun migrateLegacyFireflyExternalIds() {
        viewModelScope.launch {
            try {
                val txs = transactionRepository.getAllTransactionsList()
                var migrated = 0
                txs.forEach { tx ->
                    val current = tx.fireflyExternalId
                    if (!current.isNullOrBlank() &&
                        current.startsWith("pennywise-") &&
                        current.removePrefix("pennywise-").all { it.isDigit() } &&
                        tx.transactionHash.isNotBlank()
                    ) {
                        transactionRepository.updateFireflyExternalId(tx.id, "pennywise-${tx.transactionHash}")
                        migrated++
                    }
                }
                if (migrated > 0) {
                    diagnosticLogger.d("FireflySettings", "Migrated $migrated legacy Firefly external IDs to hash-based")
                }
                userPreferencesRepository.setFireflyMigrationRan(true)
            } catch (e: Exception) {
                diagnosticLogger.e("FireflySettings", "Migration error", e)
            }
        }
    }

    fun sendTestTransaction(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run { onResult("Firefly not configured"); return@launch }
                val testTx = TransactionEntity(
                    id = -1,
                    amount = java.math.BigDecimal("123.45"),
                    merchantName = "Test Merchant (Cashiro)",
                    category = "Testing",
                    transactionType = com.pennywiseai.tracker.data.database.entity.TransactionType.EXPENSE,
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
        val prefs = userPreferencesRepository.userPreferences.first()
        val url = prefs.fireflyBaseUrl?.takeIf { it.isNotBlank() }
        val token = prefs.fireflyAccessToken?.takeIf { it.isNotBlank() } ?: return null
        if (url == null) return null
        return FireflyConfig(
            url = url,
            token = token,
            defaultAssetAccount = prefs.fireflyDefaultAssetAccount,
            accountMappings = userPreferencesRepository.fireflyAccountMappingsFlow.first(),
            categoryMappings = userPreferencesRepository.fireflyCategoryMappingsFlow.first(),
            includeRawSms = userPreferencesRepository.fireflyIncludeRawSmsFlow.first(),
            baseCurrency = prefs.baseCurrency
        )
    }
}

data class AccountMappingItem(
    val key: String,
    val displayName: String,
    val isCreditCard: Boolean = false
)
