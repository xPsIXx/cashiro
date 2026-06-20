package com.pennywiseai.tracker.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import com.pennywiseai.tracker.ui.components.CustomTitleTopAppBar
import com.pennywiseai.tracker.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FireflySettingsScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit,
    onNavigateToFailedSyncs: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val fireflySyncEnabled by viewModel.fireflySyncEnabled.collectAsStateWithLifecycle(initialValue = false)
    val fireflyBaseUrl by viewModel.fireflyBaseUrl.collectAsStateWithLifecycle(initialValue = null)
    val fireflyDefaultAsset by viewModel.fireflyDefaultAssetAccount.collectAsStateWithLifecycle(initialValue = null)
    val fireflyLastError by viewModel.fireflyLastSyncError.collectAsStateWithLifecycle(initialValue = null)
    val fireflyFailedCount by viewModel.fireflyFailedSyncCount.collectAsStateWithLifecycle(initialValue = 0)
    val fireflyMappings by viewModel.fireflyAccountMappings.collectAsStateWithLifecycle(initialValue = emptyMap())
    val fireflyMappingAccounts by viewModel.fireflyMappingAccounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val fireflyCategoryMappings by viewModel.fireflyCategoryMappings.collectAsStateWithLifecycle(initialValue = emptyMap())
    val fireflyIncludeRawSms by viewModel.fireflyIncludeRawSms.collectAsStateWithLifecycle(initialValue = true)

    // Local editing state
    val secureCreds = remember { viewModel.getFireflySecureCredentials() }
    var localUrl by remember { mutableStateOf(fireflyBaseUrl ?: secureCreds.first ?: "") }
    var localToken by remember { mutableStateOf("") }
    var localDefaultAccount by remember { mutableStateOf(fireflyDefaultAsset ?: "") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var hideRawSms by remember { mutableStateOf(!fireflyIncludeRawSms) } // default on

    // Sync local toggle with saved preference (after declare)
    LaunchedEffect(fireflyIncludeRawSms) {
        hideRawSms = !fireflyIncludeRawSms
    }

    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            val scrollBehaviorSmall = TopAppBarDefaults.pinnedScrollBehavior()
            val scrollBehaviorLarge = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
            CustomTitleTopAppBar(
                scrollBehaviorSmall = scrollBehaviorSmall,
                scrollBehaviorLarge = scrollBehaviorLarge,
                title = "Firefly III Sync",
                hasBackButton = true,
                navigationContent = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Enable toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable automatic sync", modifier = Modifier.weight(1f))
                Switch(
                    checked = fireflySyncEnabled,
                    onCheckedChange = { viewModel.setFireflySyncEnabled(it) }
                )
            }

            // Sync controls
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onNavigateToFailedSyncs,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sync Failed / Unsynced")
                }
                var syncResult by remember { mutableStateOf<String?>(null) }

            OutlinedButton(
                    onClick = {
                        syncResult = "Syncing last 30 days..."
                        viewModel.syncLast30Days { result ->
                            syncResult = result
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sync Last 30 Days")
                }

            syncResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            }

            // Failed syncs quick access
            if (fireflyFailedCount > 0) {
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$fireflyFailedCount failed sync(s)",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onNavigateToFailedSyncs) {
                            Text("View & Retry")
                        }
                    }
                }
            }

            // Connection settings
            OutlinedTextField(
                value = localUrl,
                onValueChange = { localUrl = it },
                label = { Text("Firefly URL") },
                placeholder = { Text("https://firefly.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = localToken,
                onValueChange = { localToken = it },
                label = { Text("Personal Access Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = localDefaultAccount,
                onValueChange = { localDefaultAccount = it },
                label = { Text("Default Asset Account (fallback)") },
                placeholder = { Text("Checking Account") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Hide raw SMS option
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Hide raw SMS in Firefly notes", modifier = Modifier.weight(1f))
                Switch(
                    checked = hideRawSms,
                    onCheckedChange = { hideRawSms = it }
                )
            }

            // Test + Save buttons
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val coroutineScope = rememberCoroutineScope()

                OutlinedButton(
                    onClick = {
                        if (localUrl.isNotBlank() && localToken.isNotBlank()) {
                            isTesting = true
                            testResult = null

                            coroutineScope.launch {
                                val result = viewModel.testFireflyConnection(localUrl, localToken)
                                testResult = when (result) {
                                    is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Success -> "✓ Connection successful"
                                    is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Error -> "✗ ${result.message.take(100)}"
                                    else -> "Skipped"
                                }
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting && localUrl.isNotBlank() && localToken.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isTesting) "Testing..." else "Test Connection")
                }

                Button(
                    onClick = {
                        viewModel.saveFireflyCredentials(localUrl, localToken, localDefaultAccount)
                        viewModel.setFireflyIncludeRawSms(!hideRawSms) // note: toggle is inverted in UI
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save Settings")
                }
            }

            // Test Transaction button
            var testMessage by remember { mutableStateOf<String?>(null) }

            OutlinedButton(
                onClick = {
                    testMessage = "Sending test transaction..."
                    viewModel.sendTestTransaction { result ->
                        testMessage = result
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send Test Transaction")
            }

            testMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            // Account Mappings
            if (fireflyMappingAccounts.isNotEmpty()) {
                Text("Account Mappings", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Map specific accounts to Firefly asset accounts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Column(modifier = Modifier.heightIn(max = 220.dp)) {
                    fireflyMappingAccounts.take(6).forEach { account ->
                        val currentValue = fireflyMappings[account.key] ?: ""
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(account.displayName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = currentValue,
                                onValueChange = { viewModel.setFireflyAccountMapping(account.key, it) },
                                singleLine = true,
                                placeholder = { Text("Firefly name") },
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1.1f)
                            )
                        }
                    }
                }
            }

            // Category Mappings
            Text("Category Mappings", style = MaterialTheme.typography.titleMedium)
            Text(
                "Map PennyWise categories to Firefly categories",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            // Simple editor for common categories (expandable in future)
            val commonCategories = listOf("Food", "Transport", "Shopping", "Bills", "Entertainment", "Salary", "Investment")
            commonCategories.forEach { cat ->
                val current = fireflyCategoryMappings[cat] ?: ""
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cat, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = current,
                        onValueChange = { viewModel.setFireflyCategoryMapping(cat, it) },
                        singleLine = true,
                        placeholder = { Text("Firefly category") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1.2f)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}