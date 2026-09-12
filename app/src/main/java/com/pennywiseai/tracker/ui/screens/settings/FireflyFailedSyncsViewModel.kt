package com.pennywiseai.tracker.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.firefly.FireflyClient
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FireflyFailedSyncsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val fireflyClient: FireflyClient,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    val failedSyncs: StateFlow<List<TransactionEntity>> =
        transactionRepository.getFailedFireflySyncs()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val failedCount: StateFlow<Int> =
        transactionRepository.getFailedFireflySyncCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = 0
            )

    private val _syncingIds = MutableStateFlow<Set<Long>>(emptySet())
    val syncingIds: StateFlow<Set<Long>> = _syncingIds.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun retrySync(transaction: TransactionEntity) {
        viewModelScope.launch {
            _syncingIds.update { it + transaction.id }

            try {
                val prefs = userPreferencesRepository.userPreferences.first()
                val url = prefs.fireflyBaseUrl?.takeIf { it.isNotBlank() }
                val token = prefs.fireflyAccessToken?.takeIf { it.isNotBlank() }

                if (url == null || token == null) {
                    _message.value = "Firefly is not configured"
                    return@launch
                }

                val accountMappings = userPreferencesRepository.fireflyAccountMappingsFlow.first()
                val categoryMappings = userPreferencesRepository.fireflyCategoryMappingsFlow.first()
                val includeRawSms = userPreferencesRepository.fireflyIncludeRawSmsFlow.first()

                // Preserve splits on retry so they survive the round trip to Firefly.
                val splits = transactionRepository.getTransactionWithSplitsSync(transaction.id)?.splits ?: emptyList()

                val result = fireflyClient.syncTransaction(
                    transaction = transaction,
                    splits = splits,
                    baseUrl = url,
                    accessToken = token,
                    defaultAssetAccount = prefs.fireflyDefaultAssetAccount,
                    accountMappings = accountMappings,
                    categoryMappings = categoryMappings,
                    includeRawSmsInNotes = includeRawSms,
                    uploadReceipt = true
                )

                when (result) {
                    is FireflyClient.SyncResult.Success -> {
                        val extId = fireflyClient.computeExternalId(transaction)
                        transactionRepository.markFireflySynced(transaction.id, extId)
                        _message.value = "Synced successfully"
                        diagnosticLogger.d("FailedSyncs", "Retry succeeded for tx ${transaction.id}")
                    }
                    is FireflyClient.SyncResult.Error -> {
                        transactionRepository.markFireflyError(transaction.id, result.message)
                        _message.value = "Retry failed: ${result.message}"
                        diagnosticLogger.w("FailedSyncs", "Retry failed for tx ${transaction.id}: ${result.message}")
                    }
                    FireflyClient.SyncResult.Skipped -> {
                        _message.value = "Skipped"
                        diagnosticLogger.d("FailedSyncs", "Retry skipped for tx ${transaction.id}")
                    }
                }
            } catch (e: Exception) {
                _message.value = "Error: ${e.message}"
                diagnosticLogger.e("FailedSyncs", "Retry exception for tx ${transaction.id}", e)
            } finally {
                _syncingIds.update { it - transaction.id }
            }
        }
    }

    fun retryAll() {
        viewModelScope.launch {
            val currentFailed = failedSyncs.value
            if (currentFailed.isEmpty()) return@launch

            val prefs = userPreferencesRepository.userPreferences.first()
            val url = prefs.fireflyBaseUrl?.takeIf { it.isNotBlank() }
            val token = prefs.fireflyAccessToken?.takeIf { it.isNotBlank() }

            if (url == null || token == null) {
                _message.value = "Firefly is not configured"
                return@launch
            }

            currentFailed.forEach { tx ->
                _syncingIds.update { it + tx.id }

                try {
                    val accountMappings = userPreferencesRepository.fireflyAccountMappingsFlow.first()
                    val categoryMappings = userPreferencesRepository.fireflyCategoryMappingsFlow.first()
                    val includeRawSms = userPreferencesRepository.fireflyIncludeRawSmsFlow.first()

                    // Preserve splits on retry so they survive the round trip to Firefly.
                    val splits = transactionRepository.getTransactionWithSplitsSync(tx.id)?.splits ?: emptyList()

                    val result = fireflyClient.syncTransaction(
                        transaction = tx,
                        splits = splits,
                        baseUrl = url,
                        accessToken = token,
                        defaultAssetAccount = prefs.fireflyDefaultAssetAccount,
                        accountMappings = accountMappings,
                        categoryMappings = categoryMappings,
                        includeRawSmsInNotes = includeRawSms,
                        uploadReceipt = true
                    )

                    if (result is FireflyClient.SyncResult.Success) {
                        val extId = fireflyClient.computeExternalId(tx)
                        transactionRepository.markFireflySynced(tx.id, extId)
                        diagnosticLogger.d("FailedSyncs", "Retry succeeded for tx ${tx.id}")
                    } else if (result is FireflyClient.SyncResult.Error) {
                        transactionRepository.markFireflyError(tx.id, result.message)
                        diagnosticLogger.w("FailedSyncs", "Retry failed for tx ${tx.id}: ${result.message}")
                    }
                } catch (e: Exception) {
                    transactionRepository.markFireflyError(tx.id, e.message ?: "Unknown error")
                    diagnosticLogger.e("FailedSyncs", "Retry exception for tx ${tx.id}", e)
                } finally {
                    _syncingIds.update { it - tx.id }
                }
            }

            _message.value = "Retry completed"
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
