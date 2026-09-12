package com.pennywiseai.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pennywiseai.tracker.data.firefly.FireflyClient
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * One-time worker that retries Firefly transactions whose previous sync failed.
 *
 * Uses exponential backoff with a bounded number of attempts. After each failure
 * the worker schedules another attempt; after [MAX_RETRY_ATTEMPTS] it gives up and
 * leaves the transaction(s) in the manual retry queue.
 */
@HiltWorker
class FireflyRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val transactionRepository: TransactionRepository,
    private val fireflyClient: FireflyClient,
    private val diagnosticLogger: com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "FireflyRetryWorker"
        private const val WORK_NAME_RETRY = "firefly_retry"
        private const val KEY_RETRY_ATTEMPT = "firefly_retry_attempt"
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val INITIAL_RETRY_DELAY_MINUTES = 15L

        /**
         * Enqueue a retry run. If a retry is already pending, it is replaced so the
         * delay/attempt count are refreshed.
         */
        fun enqueue(context: Context, attempt: Int = 0) {
            if (attempt >= MAX_RETRY_ATTEMPTS) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val delayMinutes = INITIAL_RETRY_DELAY_MINUTES * (1 shl attempt.coerceAtMost(5))

            val inputData = Data.Builder()
                .putInt(KEY_RETRY_ATTEMPT, attempt)
                .build()

            val request = OneTimeWorkRequestBuilder<FireflyRetryWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_RETRY,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_RETRY)
        }
    }

    override suspend fun doWork(): Result {
        val attempt = inputData.getInt(KEY_RETRY_ATTEMPT, 0)

        try {
            userPreferencesRepository.migrateTokenToSecureIfNeeded()

            val enabled = userPreferencesRepository.fireflySyncEnabledFlow.first()
            if (!enabled) {
                diagnosticLogger.d(TAG, "Firefly sync disabled; skipping retry")
                return Result.success()
            }

            val config = fetchConfig() ?: return Result.failure()

            val failed = transactionRepository.getFailedFireflySyncs().first()
            if (failed.isEmpty()) {
                diagnosticLogger.d(TAG, "No failed Firefly syncs to retry")
                return Result.success()
            }

            diagnosticLogger.i(TAG, "Retrying ${failed.size} failed Firefly sync(s), attempt $attempt")

            val syncables = failed.map { tx ->
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
                val tx = failed.find { it.id == item.transactionId } ?: return@forEach
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
                error = if (result.failed > 0) "${result.failed} retry failures" else null
            )

            return if (result.failed > 0) {
                val remaining = transactionRepository.getFailedFireflySyncs().first().size
                if (remaining > 0 && attempt + 1 < MAX_RETRY_ATTEMPTS) {
                    diagnosticLogger.w(
                        TAG,
                        "${result.failed} transaction(s) still failing, scheduling retry ${attempt + 1}"
                    )
                    enqueue(applicationContext, attempt + 1)
                    Result.success()
                } else {
                    diagnosticLogger.w(
                        TAG,
                        "Giving up on $remaining failing transaction(s) after $attempt attempt(s)"
                    )
                    Result.success()
                }
            } else {
                diagnosticLogger.i(TAG, "All failed Firefly syncs retried successfully")
                Result.success()
            }
        } catch (e: Exception) {
            diagnosticLogger.e(TAG, "Retry worker crashed on attempt $attempt", e)
            return if (attempt + 1 < MAX_RETRY_ATTEMPTS) {
                enqueue(applicationContext, attempt + 1)
                Result.success()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun fetchConfig(): FireflyConfig? {
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
            includeRawSms = userPreferencesRepository.fireflyIncludeRawSmsFlow.first()
        )
    }

    private data class FireflyConfig(
        val url: String,
        val token: String,
        val defaultAssetAccount: String?,
        val accountMappings: Map<String, String>,
        val categoryMappings: Map<String, String>,
        val includeRawSms: Boolean
    )
}
