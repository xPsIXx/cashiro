package com.pennywiseai.tracker.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.firefly.FireflyClient
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FireflyFailedSyncsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val fireflyClient: FireflyClient
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

                val result = fireflyClient.syncTransaction(
                    transaction = transaction,
                    baseUrl = url,
                    accessToken = token,
                    defaultAssetAccount = prefs.fireflyDefaultAssetAccount,
                    accountMappings = accountMappings,
                    categoryMappings = categoryMappings,
                    includeRawSmsInNotes = includeRawSms
                )

                when (result) {
                    is FireflyClient.SyncResult.Success -> {
                        val extId = fireflyClient.computeExternalId(transaction)
                        transactionRepository.markFireflySynced(transaction.id, extId)
                        _message.value = "Synced successfully"
                    }
                    is FireflyClient.SyncResult.Error -> {
                        transactionRepository.markFireflyError(transaction.id, result.message)
                        _message.value = "Retry failed: ${result.message}"
                    }
                    FireflyClient.SyncResult.Skipped -> {
                        _message.value = "Skipped"
                    }
                }
            } catch (e: Exception) {
                _message.value = "Error: ${e.message}"
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

                    val result = fireflyClient.syncTransaction(
                        transaction = tx,
                        baseUrl = url,
                        accessToken = token,
                        defaultAssetAccount = prefs.fireflyDefaultAssetAccount,
                        accountMappings = accountMappings,
                        categoryMappings = categoryMappings,
                        includeRawSmsInNotes = includeRawSms
                    )

                    if (result is FireflyClient.SyncResult.Success) {
                        val extId = fireflyClient.computeExternalId(tx)
                        transactionRepository.markFireflySynced(tx.id, extId)
                    } else if (result is FireflyClient.SyncResult.Error) {
                        transactionRepository.markFireflyError(tx.id, result.message)
                    }
                } catch (e: Exception) {
                    transactionRepository.markFireflyError(tx.id, e.message ?: "Unknown error")
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