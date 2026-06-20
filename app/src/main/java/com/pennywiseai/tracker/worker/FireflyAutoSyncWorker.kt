package com.pennywiseai.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pennywiseai.tracker.data.firefly.FireflyClient
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

@HiltWorker
class FireflyAutoSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val transactionRepository: TransactionRepository,
    private val fireflyClient: FireflyClient
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val WORK_NAME_PERIODIC = "firefly_auto_sync_periodic"

        fun schedule(context: Context, interval: String) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)

            if (interval == "never") return

            val repeatInterval = when (interval) {
                "daily" -> 1L to TimeUnit.DAYS
                "weekly" -> 7L to TimeUnit.DAYS
                else -> return
            }

            val request = PeriodicWorkRequestBuilder<FireflyAutoSyncWorker>(
                repeatInterval.first, repeatInterval.second
            ).build()

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
            val enabled = userPreferencesRepository.fireflySyncEnabledFlow.first()
            if (!enabled) return Result.success()

            val config = fetchConfig() ?: return Result.success()

            val unsynced = transactionRepository.getAllTransactionsList()
                .filter { it.fireflySyncedAt == null && it.fireflyLastError == null }

            if (unsynced.isEmpty()) return Result.success()

            var successCount = 0
            unsynced.forEach { tx ->
                val result = fireflyClient.syncTransaction(
                    transaction = tx,
                    baseUrl = config.url,
                    accessToken = config.token,
                    defaultAssetAccount = config.defaultAssetAccount,
                    accountMappings = config.accountMappings,
                    categoryMappings = config.categoryMappings,
                    includeRawSmsInNotes = config.includeRawSms
                )
                if (result is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Success) {
                    transactionRepository.markFireflySynced(tx.id, result.fireflyId)
                    successCount++
                } else if (result is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Error) {
                    transactionRepository.markFireflyError(tx.id, result.message)
                }
                delay(200) // throttle
            }

            userPreferencesRepository.updateFireflyLastSync(
                System.currentTimeMillis(),
                error = if (successCount < unsynced.size) "Some failed" else null
            )

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun fetchConfig(): FireflyConfig? {
        val prefs = userPreferencesRepository.userPreferences.first()
        val url = prefs.fireflyBaseUrl?.takeIf { it.isNotBlank() }
        val token = prefs.fireflyAccessToken?.takeIf { it.isNotBlank() } ?: return null
        if (url == null) return null

        val accountMappings = userPreferencesRepository.fireflyAccountMappingsFlow.first()
        val categoryMappings = userPreferencesRepository.fireflyCategoryMappingsFlow.first()
        val includeRaw = userPreferencesRepository.fireflyIncludeRawSmsFlow.first()

        return FireflyConfig(
            url = url,
            token = token,
            defaultAssetAccount = prefs.fireflyDefaultAssetAccount,
            accountMappings = accountMappings,
            categoryMappings = categoryMappings,
            includeRawSms = includeRaw
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