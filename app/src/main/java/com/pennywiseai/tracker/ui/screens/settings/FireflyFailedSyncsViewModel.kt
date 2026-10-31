package com.pennywiseai.tracker.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.firefly.FireflyClient
import com.pennywiseai.tracker.data.firefly.FireflySyncDao
import com.pennywiseai.tracker.data.firefly.FireflySyncEngine
import com.pennywiseai.tracker.data.repository.TransactionRepository
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lists transactions whose last Firefly sync errored. Failed-sync state lives
 * in the add-on sidecar DB now (firefly_last_error column is gone from the
 * app schema); retries go through [FireflySyncEngine].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FireflyFailedSyncsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fireflySyncDao: FireflySyncDao,
    private val fireflySyncEngine: FireflySyncEngine,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    /** A failed-sync row: the local transaction plus the recorded error text. */
    data class FailedSync(
        val transaction: TransactionEntity,
        val error: String?
    )

    val failedSyncs: StateFlow<List<FailedSync>> =
        fireflySyncDao.statesWithError()
            .flatMapLatest { states ->
                flow {
                    emit(
                        buildList {
                            states.forEach { state ->
                                transactionRepository.getTransactionById(state.localTransactionId)?.let {
                                    add(FailedSync(it, state.lastError))
                                }
                            }
                        }
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val failedCount: StateFlow<Int> =
        fireflySyncEngine.errorCount()
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
                // Preserve splits on retry so they survive the round trip to Firefly.
                val splits = transactionRepository.getTransactionWithSplitsSync(transaction.id)?.splits ?: emptyList()

                val syncResult = fireflySyncEngine.syncTransaction(transaction, splits)
                when (syncResult) {
                    is FireflyClient.SyncResult.Success -> {
                        _message.value = "Synced successfully"
                        diagnosticLogger.d("FailedSyncs", "Retry succeeded for tx ${transaction.id}")
                    }
                    is FireflyClient.SyncResult.Error -> {
                        _message.value = "Retry failed: ${syncResult.message}"
                        diagnosticLogger.w("FailedSyncs", "Retry failed for tx ${transaction.id}: ${syncResult.message}")
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

            currentFailed.forEach { failed ->
                val tx = failed.transaction
                _syncingIds.update { it + tx.id }
                try {
                    val splits = transactionRepository.getTransactionWithSplitsSync(tx.id)?.splits ?: emptyList()
                    when (val result = fireflySyncEngine.syncTransaction(tx, splits)) {
                        is FireflyClient.SyncResult.Success ->
                            diagnosticLogger.d("FailedSyncs", "Retry succeeded for tx ${tx.id}")
                        is FireflyClient.SyncResult.Error ->
                            diagnosticLogger.w("FailedSyncs", "Retry failed for tx ${tx.id}: ${result.message}")
                        FireflyClient.SyncResult.Skipped ->
                            diagnosticLogger.d("FailedSyncs", "Retry skipped for tx ${tx.id}")
                    }
                } catch (e: Exception) {
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
