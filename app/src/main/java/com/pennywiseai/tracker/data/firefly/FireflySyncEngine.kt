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
import java.util.concurrent.ConcurrentHashMap
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
 *  - pushing/syncing transactions (hooks + manual sync), guarded so the same
 *    transaction is never synced by two paths at the same time,
 *  - resync of never-synced/failed rows, retry of failed rows,
 *  - one-time consumption of the fork-era migration staging table,
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
    @ApplicationContext private val context: Context,
    /**
     * One-shot consumer of the `firefly_sync_state_staging` table left behind
     * by the fork-era (schemas 47-52) -> 67 migrations. [resyncAll] runs it
     * before it scans rows so sync state staged during the upgrade lands in
     * the sidecar and already-synced transactions are not re-created on
     * Firefly. See [FireflySyncStagingBackfill].
     *
     * Nullable with a default only so hand-constructed engines (the live E2E
     * test builds one directly) keep compiling; Hilt always supplies the
     * singleton in production. When null, resync simply skips the backfill.
     */
    private val stagingBackfill: FireflySyncStagingBackfill? = null
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

    /**
     * Process-wide single-flight guard for transaction sync (H2).
     *
     * `onTransactionSaved` is called synchronously from the save paths
     * (manual add, SMS parse) while `FireflyAutoSyncWorker`/`FireflyRetryWorker`
     * may run `resyncAll()`/`retryFailedTxs()` on another thread at the same
     * moment. Without a guard, both paths search Firefly for the same
     * never-synced row, both get "not found", and both create it — a duplicate
     * transaction group on the user's Firefly.
     *
     * A transaction id is claimed here before that row's search-then-create
     * critical section starts and released in a `finally` when the section
     * ends, so at most one path per id is ever in flight in this process.
     * Every engine entry point (the hook, the manual "Sync now" button, the
     * resync batch and the retry batch) funnels through [runBatch], which is
     * the single place the claim happens — so the hook path and the worker
     * paths share one guard by construction. A second concurrent attempt for
     * the same id performs no HTTP at all and is reported as SKIPPED.
     *
     * Coroutine/thread safety: [ConcurrentHashMap.newKeySet] is a concurrent
     * set whose `add` is an atomic compare-and-insert — it returns true only
     * for the first claimer, so check-and-claim needs no lock and cannot be
     * interleaved by coroutines resumed on different threads (Room,
     * WorkManager and Ktor each dispatch on their own pool). Claiming is a
     * plain (non-suspending) call, so a coroutine can never yield between
     * checking and claiming an id. Claims are per id, so unrelated
     * transactions never block each other.
     */
    private val inFlightTxIds: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>()

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
     *
     * Guarded by [inFlightTxIds]: if a worker is already syncing this exact
     * transaction, the attempt returns a SKIPPED result without touching
     * Firefly (see [runBatch]).
     */
    suspend fun onTransactionSaved(tx: TransactionEntity, splits: List<TransactionSplitEntity> = emptyList()) {
        val config = fetchConfig() ?: return
        syncInternal(listOf(tx to splits), config, direction = LOG_DIRECTION_PUSH)
    }

    /**
     * Manual "Sync now" / retry entry from the transaction detail screen.
     * Returns the per-item result for the UI. A row that another path is
     * already syncing reports [FireflyClient.SyncResult.Skipped].
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
     * **or whose last sync attempt errored** (H3: a row that synced once and
     * then failed an update must not be skipped forever). Restricts to the
     * last [scopeDays] days when non-null.
     *
     * Runs the one-time fork-era staging backfill first: the 47-52 -> 67
     * migrations stage legacy `firefly_*` state into the main database, and
     * consuming it here is what stops already-synced transactions from being
     * re-created on Firefly after an upgrade. The call is advisory — a
     * backfill failure is logged and never breaks the sync.
     */
    suspend fun resyncAll(scopeDays: Int? = null): SyncSummary {
        stagingBackfill?.let { backfill ->
            runCatching { backfill.runIfNeeded() }
                .onFailure { diagnosticLogger.w(TAG, "Staging backfill skipped (non-fatal)", it) }
        }
        val config = fetchConfig() ?: return SyncSummary()
        val all = transactionRepository.getAllTransactionsList()
        val scoped = if (scopeDays == null) {
            all
        } else {
            val cutoff = LocalDateTime.now().minusDays(scopeDays.toLong())
            all.filter { it.dateTime.isAfter(cutoff) }
        }
        val states = fireflySyncDao.statesFor(scoped.map { it.id }).associateBy { it.localTransactionId }
        // Rows with no state, rows that never synced, and rows whose last sync
        // errored are all candidates (H3); guard-skips inside runBatch keep
        // rows another path is currently syncing out of this batch.
        val toSync = scoped.filter { val s = states[it.id]; s == null || s.syncedAt == null || s.lastError != null }
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
        // ── H2 single-flight guard: claim ids BEFORE any HTTP work ────────────
        // See [inFlightTxIds]. The hook path (onTransactionSaved /
        // syncTransaction) and the worker paths (resyncAll / retryFailedTxs)
        // all end up here, so one guard covers every concurrent combination.
        val claimed = ArrayList<FireflyClient.SyncableTransaction>(syncables.size)
        val busy = ArrayList<FireflyClient.SyncableTransaction>()
        for (item in syncables) {
            if (inFlightTxIds.add(item.transaction.id)) claimed += item else busy += item
        }
        val busySkipped = busy.map { item ->
            FireflyClient.ItemResult(
                transactionId = item.transaction.id,
                externalId = fireflyClient.computeExternalId(item.transaction),
                status = FireflyClient.ItemResult.ItemStatus.SKIPPED
            )
        }
        if (busySkipped.isNotEmpty()) {
            diagnosticLogger.w(
                TAG,
                "Single-flight guard: ${busySkipped.size} transaction(s) already in flight on another " +
                    "sync path — skipped without HTTP: ${busySkipped.joinToString { it.transactionId.toString() }}"
            )
            logItemResults(busySkipped, direction, LocalDateTime.now())
        }
        if (claimed.isEmpty()) {
            return FireflyClient.BatchSyncResult(
                total = syncables.size,
                skipped = busySkipped.size,
                details = busySkipped
            )
        }
        val claimedIds = ArrayList<Long>(claimed.size)
        for (item in claimed) claimedIds += item.transaction.id

        val now = LocalDateTime.now()
        try {
            val states = if (stateOverrides.isNotEmpty()) stateOverrides else {
                fireflySyncDao.statesFor(claimedIds).associateBy { it.localTransactionId }
            }

            diagnosticLoggerMessage?.let { diagnosticLogger.i(TAG, it()) }

            val result = fireflyClient.syncTransactionsBatch(
                syncables = claimed,
                baseUrl = config.url,
                accessToken = config.token,
                defaultAssetAccount = config.defaultAssetAccount,
                accountMappings = config.accountMappings,
                categoryMappings = config.categoryMappings,
                includeRawSmsInNotes = config.includeRawSms,
                uploadReceipts = true,
                stateByTx = states
            )

            logItemResults(result.details, direction, now)

            fireflyPreferencesRepository.updateLastSync(
                System.currentTimeMillis(),
                error = if (result.failed > 0) "${result.failed} failed" else null
            )
            if (result.failed > 0) {
                com.pennywiseai.tracker.worker.FireflyRetryWorker.enqueue(context)
            }
            return FireflyClient.BatchSyncResult(
                total = result.total + busySkipped.size,
                created = result.created,
                skipped = result.skipped + busySkipped.size,
                failed = result.failed,
                details = result.details + busySkipped
            )
        } finally {
            // Release the claims even if the client or the DAO throws, so a
            // failed attempt can be retried by the next pass.
            inFlightTxIds.removeAll(claimedIds)
        }
    }

    /** Persist one sync-log row per item result (unchanged semantics). */
    private suspend fun logItemResults(
        details: List<FireflyClient.ItemResult>,
        direction: String,
        at: LocalDateTime
    ) {
        details.forEach { item ->
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
                    at = at
                )
            )
        }
    }
}
