package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.pennywiseai.tracker.data.database.PennyWiseDatabase
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionSplitEntity
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.UUID
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
    private val stagingBackfill: FireflySyncStagingBackfill? = null,
    /**
     * M4: the MAIN app database, used ONLY to run the candidate-selector query
     * ([selectSyncCandidates]) so scoped resyncs never load the whole
     * transactions table. Nullable with a default (like [stagingBackfill]) so
     * hand-constructed engines (existing JVM tests) keep compiling; Hilt
     * supplies the singleton in production. When null — or when the candidate
     * query fails for any reason — [resyncAll] falls back to the previous
     * full-table scan with the in-memory candidate filter, so candidate
     * discovery is an optimization that can never lose rows.
     */
    private val syncDatabase: PennyWiseDatabase? = null
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
        /** Cap on one candidate batch: protects WorkManager runs from giant backlogs. */
        const val MAX_CANDIDATE_BATCH = 500L
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
     * M1: splits are re-loaded locally via [buildSyncable] — the same builder
     * [resyncAll] uses — even when the caller passes none (which both save
     * paths do), so a split transaction is pushed as a real Firefly group
     * instead of one flat journal.
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
     * already syncing reports [FireflyClient.SyncResult.Skipped]. Passed-in
     * [splits] are only a fallback: the locally stored splits win (M1, see
     * [buildSyncable]).
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
                // L3: same status-first rule as FireflyClient's single-sync wrapper.
                isAuthError = item.httpStatus == 401 || item.httpStatus == 403 ||
                    item.message?.contains("HTTP 401") == true ||
                    item.message?.contains("HTTP 403") == true ||
                    item.message?.contains("Authentication failed") == true
            )
        }
    }

    /**
     * Resync of transactions that never reached a successful sync
     * **or whose last sync attempt errored** (H3: a row that synced once and
     * then failed an update must not be skipped forever), **plus rows locally
     * edited after their last successful sync** (M4 staleness rule).
     *
     * M4: candidates are selected by the DAO candidate query
     * ([selectSyncCandidates], rules documented on [SYNC_CANDIDATE_SQL])
     * instead of loading the whole transactions table:
     *  - a non-null [scopeDays] bounds ONLY never-seen rows (rows with no
     *    sidecar state) to `updated_at >= now - scopeDays`; error rows,
     *    claimed-but-unsynced rows and stale-edited rows are always in scope,
     *    no matter how old — correctness semantics are unchanged;
     *  - a null [scopeDays] (explicit "Sync now") keeps the full candidate
     *    scan of history, still candidate-filtered and staleness-aware.
     * If the candidate query cannot run (engine built without the main DB in
     * tests, DB not open yet, query error) the method degrades to the previous
     * full-scan + in-memory filter after one diagnostic log — a slower path,
     * never a lossier one.
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

        val lookbackCutoff = scopeDays?.let { LocalDateTime.now().minusDays(it.toLong()) }
        val candidates = findSyncCandidates(lookbackCutoff)
        if (candidates != null) {
            if (candidates.isEmpty()) return SyncSummary()
            val transactions = candidates.mapNotNull {
                transactionRepository.getTransactionById(it.localTransactionId)
            }
            if (transactions.isEmpty()) return SyncSummary()
            val result = runBatch(
                syncablesWithSplits(transactions),
                config,
                diagnosticLoggerMessage = {
                    "Resyncing ${transactions.size} candidate transaction(s) " +
                        "(window=${scopeDays?.toString() ?: "full history"})"
                },
                direction = LOG_DIRECTION_RESYNC
            )
            return SyncSummary(
                attempted = transactions.size,
                created = result.created,
                skipped = result.skipped,
                failed = result.failed
            )
        }

        // Candidate discovery unavailable: previous full-scan behaviour.
        val all = transactionRepository.getAllTransactionsList()
        val scoped = if (lookbackCutoff == null) {
            all
        } else {
            all.filter { it.dateTime.isAfter(lookbackCutoff) }
        }
        val states = fireflySyncDao.statesFor(scoped.map { it.id }).associateBy { it.localTransactionId }
        // Rows with no state, rows that never synced, rows whose last sync
        // errored, and rows edited after their last sync are all candidates
        // (H3 + M4 staleness rule); guard-skips inside runBatch keep rows
        // another path is currently syncing out of this batch.
        val toSync = scoped.filter { tx ->
            val s = states[tx.id]
            s == null || s.syncedAt == null || s.lastError != null ||
                (s.syncedAt != null && tx.updatedAt.isAfter(s.syncedAt!!))
        }
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

    /**
     * M4: candidate discovery via [selectSyncCandidates] on the main DB's
     * Room-owned writer connection. Returns null ONLY when the candidate path
     * is unavailable (no main DB injected, Room has not opened it yet, or the
     * query threw) — the caller then uses the full-scan fallback. Never throws.
     */
    private suspend fun findSyncCandidates(lookbackCutoff: LocalDateTime?): List<FireflySyncCandidate>? {
        val database = syncDatabase ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!database.isOpen) return@runCatching null
                val helper: SupportSQLiteOpenHelper = database.openHelper ?: return@runCatching null
                selectSyncCandidates(
                    mainWritableConnection = helper.writableDatabase,
                    // Same resolution Room's own builder uses for the sidecar
                    // file (both are built from the application context).
                    sidecarDbFile = context.getDatabasePath(FireflySyncDatabase.DATABASE_NAME),
                    lookbackCutoff = lookbackCutoff,
                    limit = MAX_CANDIDATE_BATCH
                )
            }.getOrElse {
                diagnosticLogger.w(
                    TAG,
                    "Candidate query unavailable — this resync falls back to the full-scan filter path",
                    it
                )
                null
            }
        }
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
        // M1: the hook path builds its SyncableTransactions through the exact
        // same builder the resync/retry batches use — one shared group shape,
        // so a split transaction can never be pushed as a flat single journal.
        val syncables = items.map { (tx, splits) -> buildSyncable(tx, splits) }
        return runBatch(syncables, config, direction = direction).details
    }

    /**
     * THE group builder of the engine (M1). Every path that sends a
     * transaction to Firefly funnels through here, so hook pushes and
     * resync/retry pushes produce identical transaction groups:
     *
     *  - splits are re-loaded from the local database for **every** item,
     *    including the `onTransactionSaved` hook, whose callers
     *    (`AddTransactionUseCase`, `SmsTransactionProcessor`) pass no splits
     *    at all. Before this, the hook pushed the total as one flat journal
     *    and the row was then marked synced, so the split structure was never
     *    corrected on Firefly.
     *  - caller-provided splits are only a fallback for the case where the
     *    database has none (e.g. a read failure); the stored rows win.
     */
    private suspend fun buildSyncable(
        tx: TransactionEntity,
        providedSplits: List<TransactionSplitEntity> = emptyList()
    ): FireflyClient.SyncableTransaction {
        val storedSplits = runCatching {
            transactionRepository.getTransactionWithSplitsSync(tx.id)?.splits
        }.getOrNull().orEmpty()
        return FireflyClient.SyncableTransaction(tx, storedSplits.ifEmpty { providedSplits })
    }

    private suspend fun syncablesWithSplits(transactions: List<TransactionEntity>): List<FireflyClient.SyncableTransaction> {
        return transactions.map { buildSyncable(it) }
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
        // M3: sidecar state is loaded up-front — the single-flight guard's
        // skip results and the external-id stabilization below both read it.
        val allIds = ArrayList<Long>(syncables.size)
        for (item in syncables) allIds += item.transaction.id
        val states: Map<Long, FireflyTransactionStateEntity> = when {
            stateOverrides.isNotEmpty() -> stateOverrides
            allIds.isEmpty() -> emptyMap()
            else -> fireflySyncDao.statesFor(allIds).associateBy { it.localTransactionId }
        }

        val claimed = ArrayList<FireflyClient.SyncableTransaction>(syncables.size)
        val busy = ArrayList<FireflyClient.SyncableTransaction>()
        for (item in syncables) {
            if (inFlightTxIds.add(item.transaction.id)) claimed += item else busy += item
        }
        val busySkipped = busy.map { item ->
            FireflyClient.ItemResult(
                transactionId = item.transaction.id,
                // M3 precedence: report the SAME id the sync would use —
                // the persisted sidecar id wins over a freshly derived one.
                externalId = states[item.transaction.id]?.externalId?.takeIf { it.isNotBlank() }
                    ?: fireflyClient.computeExternalId(item.transaction),
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
            diagnosticLoggerMessage?.let { diagnosticLogger.i(TAG, it()) }

            // M3: every claimed row gets its external id resolved through the
            // sidecar-first precedence chain (see [ensureStableExternalIds])
            // BEFORE any HTTP happens, so search, payload and persisted state
            // all use the same id.
            val stabilized = ensureStableExternalIds(claimed, states)

            val result = fireflyClient.syncTransactionsBatch(
                syncables = stabilized,
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

    /**
     * M3: resolve one stable external id per claimed transaction and pin it so
     * every Firefly interaction (search lookup, create/update payload, sidecar
     * bookkeeping) uses the SAME id.
     *
     * Precedence chain (first match wins):
     *  1. persisted sidecar id — `FireflyTransactionStateEntity.externalId`
     *     wins over ANY recomputed value, so hash-changing edits, restores or
     *     hash recalculation can never orphan the remote row;
     *  2. a non-blank `transactionHash` → `pennywise-<hash>` (SMS/autopay rows
     *     already have a content-stable hash);
     *  3. hash-less transactions (manual adds whose hash was cleared or never
     *     set) → a fresh UUID `pennywise-ff-<uuid>` that is **persisted into
     *     the sidecar row at first push**, so from that point on rule 1 makes
     *     it stable forever. The id is persisted BEFORE the HTTP push: if the
     *     create actually committed server-side but the response was lost, the
     *     next run searches the same id and updates instead of re-creating.
     *
     * Pinning mechanism: [FireflyClient.computeExternalId] derives the id it
     * uses everywhere from the entity's `transactionHash` (or PK fallback).
     * The client's external-id resolution is out of this task's ownership, so
     * the engine carries the winning id in the hash field of the
     * [FireflyClient.SyncableTransaction]'s entity copy — the client then
     * computes exactly the sidecar id for search, payload and markSynced. Only
     * the in-memory copy sent to the client is rewritten; the local database
     * row's `transactionHash` is never touched.
     *
     * Ids that do not start with the `pennywise-` prefix cannot be expressed
     * through the hash pinning and are left to the client's own derivation.
     */
    private suspend fun ensureStableExternalIds(
        items: List<FireflyClient.SyncableTransaction>,
        states: Map<Long, FireflyTransactionStateEntity>
    ): List<FireflyClient.SyncableTransaction> {
        return items.map { item ->
            val tx = item.transaction
            val persisted = states[tx.id]?.externalId?.takeIf { it.isNotBlank() }
            val externalId: String = persisted
                ?: tx.transactionHash?.takeIf { it.isNotBlank() }?.let { "pennywise-$it" }
                ?: "pennywise-ff-${UUID.randomUUID()}"

            if (persisted == null) {
                val existingState = states[tx.id]
                runCatching {
                    if (existingState == null) {
                        fireflySyncDao.upsertTransactionState(
                            FireflyTransactionStateEntity(
                                localTransactionId = tx.id,
                                externalId = externalId
                            )
                        )
                    } else {
                        fireflySyncDao.updateExternalId(tx.id, externalId)
                    }
                }.onFailure {
                    diagnosticLogger.w(
                        TAG,
                        "Could not pre-persist external id for tx ${tx.id} — this run uses it in-memory only",
                        it
                    )
                }
            }

            if (externalId.startsWith("pennywise-")) {
                val pinnedHash = externalId.removePrefix("pennywise-")
                if (tx.transactionHash != pinnedHash) {
                    item.copy(transaction = tx.copy(transactionHash = pinnedHash))
                } else {
                    item
                }
            } else {
                item
            }
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
