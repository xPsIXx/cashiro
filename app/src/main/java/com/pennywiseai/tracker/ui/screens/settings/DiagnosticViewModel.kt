package com.pennywiseai.tracker.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.diagnostic.DiagnosticEntry
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.repository.LlmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    private val diagnosticLogger: DiagnosticLogger,
    private val llmRepository: LlmRepository
) : ViewModel() {

    val entries: StateFlow<List<DiagnosticEntry>> = diagnosticLogger.entries

    private val _summaryState = MutableStateFlow<SummaryState>(SummaryState.Idle)
    val summaryState: StateFlow<SummaryState> = _summaryState.asStateFlow()

    /**
     * Export the current diagnostic log to a temporary shareable file.
     * Must be called from a coroutine scope.
     */
    suspend fun exportLog(): File? {
        return runCatching { diagnosticLogger.exportToFile() }.getOrNull()
    }

    fun clearLog() {
        viewModelScope.launch {
            diagnosticLogger.clear()
        }
    }

    fun clearSummary() {
        _summaryState.value = SummaryState.Idle
    }

    /**
     * Uses the on-device LLM to summarize the current diagnostic log.
     * Secrets/tokens are scrubbed before the log is sent.
     */
    fun summarizeDiagnostics() {
        if (_summaryState.value is SummaryState.Loading) return

        viewModelScope.launch {
            _summaryState.value = SummaryState.Loading
            try {
                val logText = diagnosticLogger.entries.value.joinToString("\n") { it.toString() }
                val result = llmRepository.summarizeDiagnostics(logText)
                _summaryState.value = if (result.isSuccess) {
                    SummaryState.Success(result.getOrNull().orEmpty())
                } else {
                    SummaryState.Error(result.exceptionOrNull()?.message ?: "Summary failed")
                }
            } catch (e: Exception) {
                _summaryState.value = SummaryState.Error(e.message ?: "Summary failed")
            }
        }
    }

    sealed class SummaryState {
        object Idle : SummaryState()
        object Loading : SummaryState()
        data class Success(val text: String) : SummaryState()
        data class Error(val message: String) : SummaryState()
    }
}
