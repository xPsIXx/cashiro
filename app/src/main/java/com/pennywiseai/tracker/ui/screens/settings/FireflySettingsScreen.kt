package com.pennywiseai.tracker.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
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
    val allCategories by viewModel.allCategoriesForMapping.collectAsStateWithLifecycle(initialValue = emptyList())
    val fireflyIncludeRawSms by viewModel.fireflyIncludeRawSms.collectAsStateWithLifecycle(initialValue = true)
    val fireflyAccounts by viewModel.fireflyAccounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val isLoadingFireflyAccounts by viewModel.isLoadingFireflyAccounts.collectAsStateWithLifecycle(initialValue = false)
    val fireflyAutoSyncInterval by viewModel.fireflyAutoSyncInterval.collectAsStateWithLifecycle(initialValue = "never")
    val fireflyMigrationRan by viewModel.fireflyMigrationRan.collectAsStateWithLifecycle(initialValue = false)

    // Local editing state
    val secureCreds = remember { viewModel.getFireflySecureCredentials() }
    var localUrl by remember { mutableStateOf(fireflyBaseUrl ?: secureCreds.first ?: "") }
    var localToken by remember { mutableStateOf("") }
    var localDefaultAccount by remember { mutableStateOf(fireflyDefaultAsset ?: "") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isSyncing30d by remember { mutableStateOf(false) }
    var isSyncingAll by remember { mutableStateOf(false) }
    var isFullSyncing by remember { mutableStateOf(false) }
    var isSendingTest by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<String?>(null) }

    // "Hide raw SMS in notes" toggle (UI label)
    // We store the inverse in prefs as "includeRawSms"
    var hideRawSms by remember(fireflyIncludeRawSms) {
        mutableStateOf(!fireflyIncludeRawSms)
    }

    // Keep local UI state in sync if preference changes externally
    LaunchedEffect(fireflyIncludeRawSms) {
        hideRawSms = !fireflyIncludeRawSms
    }

    // Automatic test connection (debounced) when URL or token is entered/changed
    // This fulfills the "automatic connection attempt when entering a url or api key" request
    LaunchedEffect(localUrl, localToken) {
        // Clear previous result when user starts editing, to avoid stale messages
        testResult = null
        if (localUrl.isNotBlank() && localToken.isNotBlank() && !isTesting && !isSendingTest) {
            delay(800) // debounce typing
            if (localUrl.isNotBlank() && localToken.isNotBlank() && !isTesting) {
                isTesting = true
                try {
                    val result = viewModel.testFireflyConnection(localUrl, localToken)
                    testResult = when (result) {
                        is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Success -> {
                            viewModel.refreshFireflyAccounts()
                            "✓ Connection successful (auto)"
                        }
                        is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Error -> "✗ ${result.message.take(100)}"
                        else -> "Skipped"
                    }
                } finally {
                    isTesting = false
                }
            }
        }
    }

    val scrollState = rememberScrollState()

    // Auto-refresh Firefly accounts list when credentials are present (for selection in mappings)
    LaunchedEffect(localUrl, localToken) {
        if (localUrl.isNotBlank() && localToken.isNotBlank()) {
            viewModel.refreshFireflyAccounts()
        }
    }

    // Show toast/log when migration/reconcile has run (on first enable after fresh install)
    LaunchedEffect(fireflyMigrationRan) {
        if (fireflyMigrationRan) {
            syncResult = "Migration & reconcile with Firefly completed (legacy IDs updated to hash-based for reinstall resilience)"
        }
    }

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

            // Auto sync interval (configurable daily/weekly/never)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Auto sync", modifier = Modifier.weight(1f))
                val intervalLabel = when (fireflyAutoSyncInterval) {
                    "daily" -> "Daily"
                    "weekly" -> "Weekly"
                    else -> "Never"
                }
                Button(
                    onClick = {
                        val next = when (fireflyAutoSyncInterval) {
                            "never" -> "daily"
                            "daily" -> "weekly"
                            else -> "never"
                        }
                        viewModel.setFireflyAutoSyncInterval(next)
                    }
                ) {
                    Text(intervalLabel)
                }
            }

            // Sync controls
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onNavigateToFailedSyncs,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sync Failed / Unsynced")
                }

            OutlinedButton(
                    onClick = {
                        isSyncing30d = true
                        syncResult = "Syncing last 30 days..."
                        viewModel.syncLast30Days { result ->
                            syncResult = result
                            isSyncing30d = false
                        }
                    },
                    enabled = !isSyncing30d,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isSyncing30d) "Syncing..." else "Sync Last 30 Days")
                }

            syncResult?.let {
                val isError = it.contains("error", ignoreCase = true) || it.contains("fail", ignoreCase = true) || it.contains("✗")
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            }

            // Additional bulk sync options (Stage 2/3)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        isSyncingAll = true
                        syncResult = "Syncing all unsynced..."
                        viewModel.syncAllUnsynced { result ->
                            syncResult = result
                            isSyncingAll = false
                        }
                    },
                    enabled = !isSyncingAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isSyncingAll) "Syncing..." else "Sync All Unsynced")
                }

                OutlinedButton(
                    onClick = {
                        isFullSyncing = true
                        syncResult = "Full sync in progress..."
                        viewModel.fullSyncToFirefly { result ->
                            syncResult = result
                            isFullSyncing = false
                        }
                    },
                    enabled = !isFullSyncing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isFullSyncing) "Syncing..." else "Full Sync Everything")
                }
            }

            // Reconcile after reinstall (uses stable hash-based external IDs)
            Button(
                onClick = {
                    syncResult = "Reconciling with Firefly..."
                    viewModel.reconcileWithFirefly { result ->
                        syncResult = result
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reconcile with Firefly (after reinstall)")
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

            // Last global sync error (quick win for error display)
            val lastSyncError = fireflyLastError
            if (lastSyncError != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Last sync error:",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            lastSyncError,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            onClick = { viewModel.clearFireflyLastError() },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Dismiss")
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
                trailingIcon = {
                    if (localDefaultAccount.isNotBlank()) {
                        IconButton(onClick = { localDefaultAccount = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear fallback account")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Used only if no matching account mapping is found for the transaction's bank account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
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
            if (fireflyMappings.isNotEmpty() || fireflyDefaultAsset?.isNotBlank() == true) {
                Text(
                    "Mappings and default will be used for syncs and tests.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val coroutineScope = rememberCoroutineScope()

                OutlinedButton(
                    onClick = {
                        if (localUrl.isNotBlank() && localToken.isNotBlank()) {
                            // Manual test takes precedence over any pending auto-test
                            isTesting = true
                            testResult = null

                            coroutineScope.launch {
                                try {
                                    val result = viewModel.testFireflyConnection(localUrl, localToken)
                                    testResult = when (result) {
                                        is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Success -> {
                                            viewModel.refreshFireflyAccounts()
                                            "✓ Connection successful (manual)"
                                        }
                                        is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Error -> "✗ ${result.message.take(100)}"
                                        else -> "Skipped"
                                    }
                                } finally {
                                    isTesting = false
                                }
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
                        // hideRawSms = true means do NOT include raw SMS
                        val includeRawSms = !hideRawSms
                        viewModel.setFireflyIncludeRawSms(includeRawSms)
                        showSaved = true
                        viewModel.refreshFireflyAccounts()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save Settings")
                }

                if (showSaved) {
                    Text("Saved!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            LaunchedEffect(showSaved) {
                if (showSaved) {
                    delay(1500)
                    showSaved = false
                }
            }

            testResult?.let {
                val isError = it.startsWith("✗")
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            // Also show last error near test if present (for visibility)
            val lastError = fireflyLastError
            if (testResult == null && lastError != null) {
                Text(
                    "Last error: $lastError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Test Transaction button
            var testMessage by remember { mutableStateOf<String?>(null) }

            OutlinedButton(
                onClick = {
                    isSendingTest = true
                    testMessage = "Sending test transaction..."
                    viewModel.sendTestTransaction { result ->
                        testMessage = result
                        isSendingTest = false
                    }
                },
                enabled = !isSendingTest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isSendingTest) "Sending..." else "Send Test Transaction")
            }

            testMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            // Account Mappings
            if (fireflyMappingAccounts.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Account Mappings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.refreshFireflyAccounts() }, enabled = !isLoadingFireflyAccounts) {
                        if (isLoadingFireflyAccounts) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Firefly accounts")
                        }
                    }
                    TextButton(onClick = { viewModel.clearAllFireflyAccountMappings() }) {
                        Text("Clear all", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    "Map specific accounts to Firefly asset accounts (default fallback used otherwise)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                val mappedCount = fireflyMappings.size
                val totalPennywiseAccounts = fireflyMappingAccounts.size
                if (totalPennywiseAccounts > 0) {
                    Text(
                        "$mappedCount of $totalPennywiseAccounts PennyWise accounts have specific Firefly mappings (rest use default).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Column(modifier = Modifier.heightIn(max = 280.dp)) {
                    if (isLoadingFireflyAccounts) {
                        Text(
                            "Loading Firefly accounts...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    } else if (fireflyAccounts.isEmpty()) {
                        Text(
                            "⚠ Unable to load accounts from Firefly (test your connection first). Manual entry is disabled until accounts can be fetched.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    fireflyMappingAccounts.forEach { account ->
                        val currentValue = fireflyMappings[account.key] ?: ""
                        var expanded by remember { mutableStateOf(false) }

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(account.displayName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))

                            Column(modifier = Modifier.weight(1.1f)) {
                                if (fireflyAccounts.isEmpty()) {
                                    // Fallback plain field when fetch failed – no point in manual if we can't match Firefly
                                    OutlinedTextField(
                                        value = currentValue,
                                        onValueChange = { viewModel.setFireflyAccountMapping(account.key, it) },
                                        singleLine = true,
                                        placeholder = { Text("Firefly name (load list first)") },
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = false,
                                        trailingIcon = {
                                            if (currentValue.isNotBlank()) {
                                                IconButton(onClick = { viewModel.clearFireflyAccountMapping(account.key) }) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Clear mapping")
                                                }
                                            }
                                        }
                                    )
                                } else {
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = !expanded }
                                    ) {
                                        OutlinedTextField(
                                            value = currentValue,
                                            onValueChange = { /* only allow via dropdown to enforce real Firefly names */ },
                                            singleLine = true,
                                            readOnly = true,
                                            placeholder = { Text("Select Firefly account") },
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                                            trailingIcon = {
                                                if (currentValue.isNotBlank()) {
                                                    IconButton(onClick = { viewModel.clearFireflyAccountMapping(account.key) }) {
                                                        Icon(Icons.Default.Clear, contentDescription = "Clear mapping")
                                                    }
                                                } else {
                                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                                }
                                            }
                                        )

                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            // Only real Firefly asset accounts are allowed (no manual/custom when list is loaded)
                                            fireflyAccounts.forEach { fireflyAccount ->
                                                DropdownMenuItem(
                                                    text = { Text(fireflyAccount) },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.AccountBalance, contentDescription = null)
                                                    },
                                                    onClick = {
                                                        viewModel.setFireflyAccountMapping(account.key, fireflyAccount)
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Show effective account and warning for stale
                                val effectiveAccount = currentValue.takeIf { it.isNotBlank() } ?: (fireflyDefaultAsset?.takeIf { it.isNotBlank() } ?: "Checking Account (default)")
                                Text(
                                    "→ Effective: $effectiveAccount",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )

                                if (fireflyAccounts.isNotEmpty() && currentValue.isNotBlank() && !fireflyAccounts.contains(currentValue)) {
                                    Text(
                                        " (not found in Firefly – please reselect)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
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

            if (allCategories.isNotEmpty() || fireflyCategoryMappings.isNotEmpty()) {
                // Include DB categories + any custom mappings the user has already set
                val categoriesToShow = (allCategories + fireflyCategoryMappings.keys).distinct().sorted()
                Column {
                    categoriesToShow.forEach { cat ->
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
                }
            } else {
                Text(
                    "No categories found yet. Add some transactions to populate the list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Custom category mapping (allows mapping categories not yet in your DB)
            Text(
                "Add custom category mapping",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )
            var customCategory by remember { mutableStateOf("") }
            var customFireflyCategory by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customCategory,
                    onValueChange = { customCategory = it },
                    singleLine = true,
                    placeholder = { Text("PennyWise category") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = customFireflyCategory,
                    onValueChange = { customFireflyCategory = it },
                    singleLine = true,
                    placeholder = { Text("Firefly category") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (customCategory.isNotBlank() && customFireflyCategory.isNotBlank()) {
                            viewModel.setFireflyCategoryMapping(customCategory.trim(), customFireflyCategory.trim())
                            customCategory = ""
                            customFireflyCategory = ""
                        }
                    },
                    enabled = customCategory.isNotBlank() && customFireflyCategory.isNotBlank()
                ) {
                    Text("Map", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}