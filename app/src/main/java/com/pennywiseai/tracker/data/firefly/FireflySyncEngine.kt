package com.pennywiseai.tracker.data.firefly

import android.content.Context
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionSplitEntity
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Cashiro Firefly add-on's public surface. Hooks, screens, workers and
 * view models call ONLY this class — never [FireflyClient] or the sidecar DAO
 * directly (except the read-only flows the settings UI exposes).
 *
 * Responsibilities:
 *  - one-time legacy prefs migration,
 *  - configuration check (url + token + enabled),
 *  - pushing/syncing transactions (hooks + manual sync),
 *  - resync of never-synced/failed rows, retry of failed rows,
 *  - budget + subscription two-way sync,
 *  - persisting every action to the in-app sync log.
 */
@Singleton
class FireflySyncEngine @Inject constructor(
    private val fireflyClient: FireflyClient,
    private val fireflySyncDao: FireflySyncDao,
    private val fireflyPreferencesRepository: FireflyPreferencesRepository,
    private val transactionRepository: TransactionRepository,
    private val budgetRepository: BudgetRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val diagnosticLogger: DiagnosticLogger,
    @ApplicationContext private val context: Context
) {

    private companion object {
        const val TAG = "FireflySyncEngine"
        const val LOG_DIRECTION_PUSH = "push"
        const val LOG_DIRECTION_PULL = "pull"
        const val LOG_DIRECTION_RESYNC = "resync"
        const val LOG_DIRECTION_BUDGET = "budget"
        const val LOG_DIRECTION_SUBSCRIPTION = "subscription"
        const val LOG_STATUS_OK = "ok"
        const val LOG_STATUS_ERROR = "error"
        const val LOG_STATUS_SKIP = "skip"
    }

    data class FireflyConfig(
        val url: String,
        val token: String,
        val defaultAssetAccount: String?,
        val accountMappings: Map<String, String>,
        val categoryMappings: Map<String, String>,
        val includeRawSms: Boolean
    )

    /** Counts returned to callers for user-facing summary strings. */
    data class SyncSummary(
        val attempted: Int = 0,
        val created: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0
    )

    data class BudgetSyncSummary(val created: Int = 0, val updated: Int = 0, val failed: Int = 0)

    data class SubscriptionSyncSummary(val created: Int = 0, val updated: Int = 0, val failed: Int = 0)

    /**
     * True when Firefly sync is enabled AND a URL + access token are stored.
     * Runs the one-time legacy prefs migration first so first-launch flows
     * observe the migrated configuration.
     */
    suspend fun isConfigured(): Boolean {
        fireflyPreferencesRepository.ensureLegacyMigration()
        val enabled = fireflyPreferencesRepository.syncEnabledFlow.first()
        if (!enabled) return false
        return !fireflyTokenBaseUrl().isNullOrBlank() && !fireflyToken().isNullOrBlank()
    }

    /** Whether the user enabled Firefly sync (read-only flow for UI). */
    fun syncEnabled(): Flow<Boolean> = fireflyPreferencesRepository.syncEnabledFlow

    /** Read-only passthroughs for the Firefly settings UI. */
    fun stateFor(txId: Long): Flow<FireflyTransactionStateEntity?> = fireflySyncDao.stateFor(txId)

    fun recentLogs(limit: Int): Flow<List<FireflyLogEntity>> = fireflySyncDao.recentLogs(limit)

    fun errorCount(): Flow<Int> = fireflySyncDao.errorCount()

    private fun fireflyTokenBaseUrl(): String? =
        fireflyPreferencesRepository.getBaseUrl()?.takeIf { it.isNotBlank() }

    private fun fireflyToken(): String? =
        fireflyPreferencesRepository.getAccessToken()?.takeIf { it.isNotBlank() }

    private suspend fun fetchConfig(): FireflyConfig? {
        if (!isConfigured()) return null
        return FireflyConfig(
            url = fireflyTokenBaseUrl()!!,
            token = fireflyToken()!!,
            defaultAssetAccount = fireflyPreferencesRepository.defaultAssetAccountFlow.first(),
            accountMappings = fireflyPreferencesRepository.accountMappingsFlow.first(),
            categoryMappings = fireflyPreferencesRepository.categoryMappingsFlow.first(),
            includeRawSms = fireflyPreferencesRepository.includeRawSmsFlow.first()
        )
    }

    /**
     * Hook invoked when a transaction is created/edited locally (SMS parse,
     * manual add). No-op unless the user enabled Firefly sync.
     */
    suspend fun onTransactionSaved(tx: TransactionEntity, splits: List<TransactionSplitEntity> = emptyList()) {
        val config = fetchConfig() ?: return
        syncInternal(listOf(tx to splits), config, direction = LOG_DIRECTION_PUSH)
    }

    /**
     * Manual "Sync now" / retry entry from the transaction detail screen.
     * Returns the per-item result for the UI.
     */
    suspend fun syncTransaction(
        tx: TransactionEntity,
        splits: List<TransactionSplitEntity> = emptyList()
    ): FireflyClient.SyncResult {
        val config = fetchConfig()
            ?: return FireflyClient.SyncResult.Error("Firefly is not configured")
        val results = syncInternal(listOf(tx to splits), config, direction = LOG_DIRECTION_PUSH)
        val item = results.firstOrNull() ?: return FireflyClient.SyncResult.Error("No result")
        return when (item.status) {
            FireflyClient.ItemResult.ItemStatus.SUCCESS -> FireflyClient.SyncResult.Success(
                fireflyId = item.fireflyId,
                journalIds = item.journalIds
            )
            FireflyClient.ItemResult.ItemStatus.SKIPPED -> FireflyClient.SyncResult.Skipped
            FireflyClient.ItemResult.ItemStatus.ERROR -> FireflyClient.SyncResult.Error(
                item.message ?: "Unknown error",
                isAuthError = item.message?.contains("401") == true
            )
        }
    }

    /**
     * Full resync of transactions that never reached a successful sync
     * (including previously failed ones). Restricts to the last [scopeDays]
     * days when non-null.
     */
    suspend fun resyncAll(scopeDays: Int? = null): SyncSummary {
        val config = fetchConfig() ?: return SyncSummary()
        val all = transactionRepository.getAllTransactionsList()
        val scoped = if (scopeDays == null) {
            all
        } else {
            val cutoff = LocalDateTime.now().minusDays(scopeDays.toLong())
            all.filter { it.dateTime.isAfter(cutoff) }
        }
        val states = fireflySyncDao.statesFor(scoped.map { it.id }).associateBy { it.localTransactionId }
        val toSync = scoped.filter { states[it.id]?.syncedAt == null }
        if (toSync.isEmpty()) return SyncSummary()

        val result = runBatch(
            syncablesWithSplits(toSync),
            config,
            diagnosticLoggerMessage = { "Resyncing ${toSync.size} transaction(s)" },
            direction = LOG_DIRECTION_RESYNC
        )
        return SyncSummary(
            attempted = toSync.size,
            created = result.created,
            skipped = result.skipped,
            failed = result.failed
        )
    }

    /** Retry exactly the rows whose last sync errored. One pass; scheduling stays in the worker. */
    suspend fun retryFailedTxs(): SyncSummary {
        val config = fetchConfig() ?: return SyncSummary()
        val failedStates = fireflySyncDao.statesWithError().first()
        if (failedStates.isEmpty()) return SyncSummary()

        val failed = failedStates.mapNotNull { state ->
            transactionRepository.getTransactionById(state.localTransactionId)?.let { it to state }
        }
        if (failed.isEmpty()) return SyncSummary()

        val result = runBatch(
            syncablesWithSplits(failed.map { it.first }),
            config,
            diagnosticLoggerMessage = { "Retrying ${failed.size} failed Firefly sync(s)" },
            direction = LOG_DIRECTION_RESYNC,
            stateOverrides = failed.associate { (tx, state) -> tx.id to state }
        )
        return SyncSummary(
            attempted = failed.size,
            created = result.created,
            skipped = result.skipped,
            failed = result.failed
        )
    }

    /** Two-way sync of every active local budget with Firefly. */
    suspend fun syncBudgets(): BudgetSyncSummary {
        val config = fetchConfig() ?: return BudgetSyncSummary()
        val budgets = budgetRepository.getAllBudgetsList().filter { it.isActive }
        if (budgets.isEmpty()) return BudgetSyncSummary()

        val states = fireflySyncDao.budgetStatesFor(budgets.map { it.id }).associateBy { it.localBudgetId }
        val result = fireflyClient.syncBudgets(
            budgets = budgets,
            baseUrl = config.url,
            accessToken = config.token,
            stateByBudget = states
        )
        fireflySyncDao.insertLog(
            FireflyLogEntity(
                direction = LOG_DIRECTION_BUDGET,
                status = if (result.error != null) LOG_STATUS_ERROR else LOG_STATUS_OK,
                message = result.error ?: "OK: ${result.created} created, ${result.updated} updated, ${result.failed} failed",
                at = LocalDateTime.now()
            )
        )
        fireflyPreferencesRepository.updateLastSync(System.currentTimeMillis())
        diagnosticLogger.i(TAG, "Budget sync: ${result.created} created, ${result.updated} updated, ${result.failed} failed")
        return BudgetSyncSummary(created = result.created, updated = result.updated, failed = result.failed)
    }

    /** Two-way sync of every local subscription with Firefly recurring transactions. */
    suspend fun syncSubscriptions(): SubscriptionSyncSummary {
        val config = fetchConfig() ?: return SubscriptionSyncSummary()
        val subscriptions = subscriptionRepository.getAllSubscriptions().first()
        if (subscriptions.isEmpty()) return SubscriptionSyncSummary()

        val states = fireflySyncDao.subscriptionStatesFor(subscriptions.map { it.id })
            .associateBy { it.localSubscriptionId }
        val result = fireflyClient.syncRecurringTransactions(
            subscriptions = subscriptions,
            baseUrl = config.url,
            accessToken = config.token,
            defaultAssetAccount = config.defaultAssetAccount,
            stateBySubscription = states
        )
        fireflySyncDao.insertLog(
            FireflyLogEntity(
                direction = LOG_DIRECTION_SUBSCRIPTION,
                status = if (result.error != null) LOG_STATUS_ERROR else LOG_STATUS_OK,
                message = result.error ?: "OK: ${result.created} created, ${result.updated} updated, ${result.failed} failed",
                at = LocalDateTime.now()
            )
        )
        fireflyPreferencesRepository.updateLastSync(System.currentTimeMillis())
        diagnosticLogger.i(
            TAG,
            "Subscription sync: ${result.created} created, ${result.updated} updated, ${result.failed} failed"
        )
        return SubscriptionSyncSummary(created = result.created, updated = result.updated, failed = result.failed)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun syncInternal(
        items: List<Pair<TransactionEntity, List<TransactionSplitEntity>>>,
        config: FireflyConfig,
        direction: String
    ): List<FireflyClient.ItemResult> {
        val syncables = items.map { (tx, splits) -> FireflyClient.SyncableTransaction(tx, splits) }
        return runBatch(syncables, config, direction = direction).details
    }

    private suspend fun syncablesWithSplits(transactions: List<TransactionEntity>): List<FireflyClient.SyncableTransaction> {
        return transactions.map { tx ->
            val splits = transactionRepository.getTransactionWithSplitsSync(tx.id)?.splits ?: emptyList()
            FireflyClient.SyncableTransaction(tx, splits)
        }
    }

    private suspend fun runBatch(
        syncables: List<FireflyClient.SyncableTransaction>,
        config: FireflyConfig,
        direction: String,
        diagnosticLoggerMessage: (() -> String)? = null,
        stateOverrides: Map<Long, FireflyTransactionStateEntity> = emptyMap()
    ): FireflyClient.BatchSyncResult {
        val states = if (stateOverrides.isNotEmpty()) stateOverrides else {
            fireflySyncDao.statesFor(syncables.map { it.transaction.id }).associateBy { it.localTransactionId }
        }

        diagnosticLoggerMessage?.let { diagnosticLogger.i(TAG, it()) }

        val result = fireflyClient.syncTransactionsBatch(
            syncables = syncables,
            baseUrl = config.url,
            accessToken = config.token,
            defaultAssetAccount = config.defaultAssetAccount,
            accountMappings = config.accountMappings,
            categoryMappings = config.categoryMappings,
            includeRawSmsInNotes = config.includeRawSms,
            uploadReceipts = true,
            stateByTx = states
        )

        val now = LocalDateTime.now()
        result.details.forEach { item ->
            val status = when (item.status) {
                FireflyClient.ItemResult.ItemStatus.SUCCESS -> LOG_STATUS_OK
                FireflyClient.ItemResult.ItemStatus.SKIPPED -> LOG_STATUS_SKIP
                FireflyClient.ItemResult.ItemStatus.ERROR -> LOG_STATUS_ERROR
            }
            val itemDirection = if (item.pulled) LOG_DIRECTION_PULL else direction
            fireflySyncDao.insertLog(
                FireflyLogEntity(
                    localTransactionId = item.transactionId,
                    direction = itemDirection,
                    status = status,
                    message = when (item.status) {
                        FireflyClient.ItemResult.ItemStatus.ERROR -> item.message ?: "Unknown Firefly error"
                        FireflyClient.ItemResult.ItemStatus.SKIPPED -> null
                        FireflyClient.ItemResult.ItemStatus.SUCCESS ->
                            if (item.pulled) "Pulled remote update into local row"
                            else "Synced to Firefly"
                    },
                    at = now
                )
            )
        }

        fireflyPreferencesRepository.updateLastSync(
            System.currentTimeMillis(),
            error = if (result.failed > 0) "${result.failed} failed" else null
        )
        if (result.failed > 0) {
            com.pennywiseai.tracker.worker.FireflyRetryWorker.enqueue(context)
        }
        return result
    }
}
