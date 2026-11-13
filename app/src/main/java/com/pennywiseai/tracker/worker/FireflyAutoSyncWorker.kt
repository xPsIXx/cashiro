package com.pennywiseai.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pennywiseai.tracker.data.firefly.FireflySyncEngine
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that resyncs never-synced/failed transactions with Firefly.
 * All Firefly logic lives in the add-on engine; this file only schedules and
 * hands over control.
 */
@HiltWorker
class FireflyAutoSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fireflySyncEngine: FireflySyncEngine,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "FireflyAutoSyncWorker"
        private const val WORK_NAME_PERIODIC = "firefly_auto_sync_periodic"

        /**
         * M4: the periodic run only passes a bounded CANDIDATE window. The
         * engine's candidate query (see `SYNC_CANDIDATE_SQL` in FireflySyncDao)
         * still picks up EVERY error row, every claimed-but-never-synced row
         * and every row edited after its last sync regardless of age —
         * [PERIODIC_LOOKBACK_DAYS] bounds only never-seen (no sidecar state)
         * rows, which replaces the old unscoped full table + per-row scan.
         */
        const val PERIODIC_LOOKBACK_DAYS = 30

        fun schedule(context: Context, interval: String) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)

            if (interval == "never") return

            val repeatInterval = when (interval) {
                "daily" -> 1L to TimeUnit.DAYS
                "weekly" -> 7L to TimeUnit.DAYS
                else -> return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<FireflyAutoSyncWorker>(
                repeatInterval.first, repeatInterval.second
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME_PERIODIC,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            if (!fireflySyncEngine.isConfigured()) return Result.success()

            // M4: bounded candidate window — never-seen rows only from the
            // last PERIODIC_LOOKBACK_DAYS; error/stale/unsynced-with-state
            // rows are always included by the candidate query itself.
            val summary = fireflySyncEngine.resyncAll(scopeDays = PERIODIC_LOOKBACK_DAYS)

            if (summary.failed > 0) {
                FireflyRetryWorker.enqueue(applicationContext)
            }

            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.e(TAG, "Auto sync crashed", e)
            FireflyRetryWorker.enqueue(applicationContext)
            Result.retry()
        }
    }
}
