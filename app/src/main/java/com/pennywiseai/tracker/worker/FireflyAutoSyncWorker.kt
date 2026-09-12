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
import com.pennywiseai.tracker.data.firefly.FireflyClient
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

@HiltWorker
class FireflyAutoSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val transactionRepository: TransactionRepository,
    private val fireflyClient: FireflyClient,
    private val diagnosticLogger: com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
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
            userPreferencesRepository.migrateTokenToSecureIfNeeded()

            val enabled = userPreferencesRepository.fireflySyncEnabledFlow.first()
            if (!enabled) return Result.success()

            val config = fetchConfig() ?: return Result.success()

            val unsynced = transactionRepository.getAllTransactionsList()
                .filter { it.fireflySyncedAt == null && it.fireflyLastError == null }

            if (unsynced.isEmpty()) return Result.success()

            val syncables = unsynced.map { tx ->
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
                val tx = unsynced.find { it.id == item.transactionId } ?: return@forEach
                when (item.status) {
                    FireflyClient.ItemResult.ItemStatus.SUCCESS -> {
                        transactionRepository.markFireflySynced(
                            tx.id,
                            item.externalId,
                            item.fireflyId,
                            item.remoteUpdatedAt?.let { java.time.LocalDateTime.ofInstant(it, java.time.ZoneId.systemDefault()) }
                        )
                    }
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
                FireflyRetryWorker.enqueue(applicationContext)
            }

            Result.success()
        } catch (e: Exception) {
            FireflyRetryWorker.enqueue(applicationContext)
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
