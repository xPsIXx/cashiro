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
import com.pennywiseai.tracker.data.firefly.FireflySyncEngine
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * One-time worker that retries Firefly transactions whose previous sync failed.
 *
 * Uses exponential backoff with a bounded number of attempts. After each failure
 * the worker schedules another attempt; after [MAX_RETRY_ATTEMPTS] it gives up and
 * leaves the transaction(s) in the manual retry queue. The sync pass itself is
 * delegated to the add-on engine.
 */
@HiltWorker
class FireflyRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fireflySyncEngine: FireflySyncEngine,
    private val diagnosticLogger: DiagnosticLogger
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
            if (!fireflySyncEngine.isConfigured()) {
                diagnosticLogger.d(TAG, "Firefly sync disabled; skipping retry")
                return Result.success()
            }

            val summary = fireflySyncEngine.retryFailedTxs()

            if (summary.attempted == 0) {
                diagnosticLogger.d(TAG, "No failed Firefly syncs to retry")
                return Result.success()
            }

            if (summary.failed > 0 && attempt + 1 < MAX_RETRY_ATTEMPTS) {
                diagnosticLogger.w(TAG, "${summary.failed} transaction(s) still failing, scheduling retry ${attempt + 1}")
                enqueue(applicationContext, attempt + 1)
            } else if (summary.failed > 0) {
                diagnosticLogger.w(TAG, "Giving up on ${summary.failed} failing transaction(s) after $attempt attempt(s)")
            } else {
                diagnosticLogger.i(TAG, "All failed Firefly syncs retried successfully")
            }
            return Result.success()
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
}
