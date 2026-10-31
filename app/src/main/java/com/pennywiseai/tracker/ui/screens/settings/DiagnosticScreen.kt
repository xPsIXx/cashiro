package com.pennywiseai.tracker.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.data.diagnostic.DiagnosticEntry
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLevel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiagnosticViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val summaryState by viewModel.summaryState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf<DiagnosticLevel?>(null) }
    var showSummary by remember { mutableStateOf(false) }

    val filteredEntries = remember(entries, filter) {
        if (filter == null) entries else entries.filter { it.level == filter }
    }

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(filteredEntries.size) {
        if (filteredEntries.isNotEmpty()) {
            listState.animateScrollToItem(filteredEntries.lastIndex)
        }
    }

    LaunchedEffect(summaryState) {
        if (summaryState is DiagnosticViewModel.SummaryState.Success ||
            summaryState is DiagnosticViewModel.SummaryState.Error
        ) {
            showSummary = true
        }
    }

    if (showSummary) {
        SummaryDialog(
            summaryState = summaryState,
            onDismiss = {
                showSummary = false
                viewModel.clearSummary()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.summarizeDiagnostics() },
                        enabled = summaryState !is DiagnosticViewModel.SummaryState.Loading
                    ) {
                        if (summaryState is DiagnosticViewModel.SummaryState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("AI Summary")
                        }
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val file = viewModel.exportLog()
                                if (file != null && file.exists()) {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "Cashiro Diagnostic Log")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(intent, "Share diagnostic log")
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share log")
                    }
                    IconButton(onClick = { viewModel.clearLog() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear log")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "This screen collects diagnostic messages (Firefly sync, SMS parsing, etc.). Share the log when reporting a bug. Tokens and secrets are scrubbed before display or export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            FilterChips(
                selected = filter,
                onSelected = { filter = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredEntries, key = { it.hashCode().toString() + it.timestamp }) { entry ->
                    DiagnosticEntryItem(entry)
                }
            }
        }
    }
}

@Composable
private fun SummaryDialog(
    summaryState: DiagnosticViewModel.SummaryState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("AI Summary") },
        text = {
            when (summaryState) {
                is DiagnosticViewModel.SummaryState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Summarizing with on-device model...")
                    }
                }
                is DiagnosticViewModel.SummaryState.Success -> {
                    Text(
                        text = summaryState.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Default
                    )
                }
                is DiagnosticViewModel.SummaryState.Error -> {
                    Text(
                        text = summaryState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> {
                    Text("No summary available.")
                }
            }
        }
    )
}

@Composable
private fun FilterChips(
    selected: DiagnosticLevel?,
    onSelected: (DiagnosticLevel?) -> Unit
) {
    val levels = listOf(null) + DiagnosticLevel.values()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        levels.forEach { level ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelected(level) },
                label = { Text(level?.name ?: "ALL") }
            )
        }
    }
}

@Composable
private fun DiagnosticEntryItem(entry: DiagnosticEntry) {
    val color = when (entry.level) {
        DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
        DiagnosticLevel.WARN -> MaterialTheme.colorScheme.tertiary
        DiagnosticLevel.INFO -> MaterialTheme.colorScheme.primary
        DiagnosticLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (entry.level) {
                DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                DiagnosticLevel.WARN -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${entry.timestamp.toLocalTime()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(80.dp)
                )
                Text(
                    text = entry.level.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier.width(50.dp)
                )
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            entry.throwable?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
