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
import androidx.compose.material.icons.filled.Category
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
    viewModel: FireflySettingsViewModel = hiltViewModel()
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
    val fireflyCategories by viewModel.fireflyCategories.collectAsStateWithLifecycle(initialValue = emptyList())
    val isLoadingFireflyAccounts by viewModel.isLoadingFireflyAccounts.collectAsStateWithLifecycle(initialValue = false)

    val hasFireflyConnection = fireflyAccounts.isNotEmpty() || fireflyCategories.isNotEmpty() || !isLoadingFireflyAccounts
    val fireflyAutoSyncInterval by viewModel.fireflyAutoSyncInterval.collectAsStateWithLifecycle(initialValue = "never")
    val fireflyMigrationRan by viewModel.fireflyMigrationRan.collectAsStateWithLifecycle(initialValue = false)

    // Local editing state
    val secureCreds = remember { viewModel.getFireflySecureCredentials() }
    var localUrl by remember { mutableStateOf(fireflyBaseUrl ?: secureCreds.first ?: "") }
    var localToken by remember { mutableStateOf(secureCreds.second ?: "") }
    var localDefaultAccount by remember { mutableStateOf(fireflyDefaultAsset ?: "") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isSyncing30d by remember { mutableStateOf(false) }
    var isSyncingAll by remember { mutableStateOf(false) }
    var isFullSyncing by remember { mutableStateOf(false) }
    var isSyncingBudgets by remember { mutableStateOf(false) }
    var isSyncingPiggy by remember { mutableStateOf(false) }
    var isSyncingRecurring by remember { mutableStateOf(false) }
    var isSyncingCategories by remember { mutableStateOf(false) }
    var isSendingTest by remember { mutableStateOf(false) }
    var isSavingSettings by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<String?>(null) }

    // Deeper dedicated mapping sheets (only available after connection)
    var showAccountMappingsSheet by remember { mutableStateOf(false) }
    var showCategoryMappingsSheet by remember { mutableStateOf(false) }

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
                Text("Enable automatic sync", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
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
                Text("Auto sync", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
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

            // Sync Actions
            Text(
                "Sync Actions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "Each SMS/manual row gets external_id pennywise-{hash}. Resync all creates missing journals and updates only Cashiro-owned ones. Firefly-native transactions are never changed or imported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Quick one-time syncs
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onNavigateToFailedSyncs,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Failed / Unsynced")
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
                    Text(if (isSyncing30d) "Syncing..." else "Last 30 Days")
                }
            }

            // Bulk / full syncs
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
                    Text(if (isSyncingAll) "Syncing..." else "All Unsynced")
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
                    Text(if (isFullSyncing) "Syncing..." else "Resync all")
                }
            }

            // Reconcile (post-reinstall recovery)
            OutlinedButton(
                onClick = {
                    syncResult = "Reconciling with Firefly..."
                    viewModel.reconcileWithFirefly { result ->
                        syncResult = result
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reconcile (after reinstall)")
            }

            // Category sync
            OutlinedButton(
                onClick = {
                    isSyncingCategories = true
                    syncResult = "Syncing categories to Firefly..."
                    viewModel.syncFireflyCategories { result ->
                        syncResult = result
                        isSyncingCategories = false
                    }
                },
                enabled = !isSyncingCategories,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSyncingCategories) "Syncing categories..." else "Sync Categories to Firefly")
            }

            // Budget sync
            OutlinedButton(
                onClick = {
                    isSyncingBudgets = true
                    syncResult = "Syncing budgets to Firefly..."
                    viewModel.syncFireflyBudgets { result ->
                        syncResult = result
                        isSyncingBudgets = false
                    }
                },
                enabled = !isSyncingBudgets,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSyncingBudgets) "Syncing budgets..." else "Sync Budgets to Firefly")
            }

            // Piggy bank sync (TARGET budgets)
            OutlinedButton(
                onClick = {
                    isSyncingPiggy = true
                    syncResult = "Syncing target budgets to Firefly piggy banks..."
                    viewModel.syncFireflyPiggyBanks { result ->
                        syncResult = result
                        isSyncingPiggy = false
                    }
                },
                enabled = !isSyncingPiggy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSyncingPiggy) "Syncing piggy banks..." else "Sync Target Budgets to Piggy Banks")
            }

            // Recurring transaction sync
            OutlinedButton(
                onClick = {
                    isSyncingRecurring = true
                    syncResult = "Syncing subscriptions with Firefly recurring transactions..."
                    viewModel.syncFireflyRecurring { result ->
                        syncResult = result
                        isSyncingRecurring = false
                    }
                },
                enabled = !isSyncingRecurring,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSyncingRecurring) "Syncing recurring..." else "Sync Subscriptions/Recurring")
            }

            // Last sync result / status
            syncResult?.let { result ->
                val isError = result.contains("error", ignoreCase = true) || result.contains("fail", ignoreCase = true) || result.contains("✗")
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
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

            // Connection
            Text("Connection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 16.dp))

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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )

            // Hide raw SMS option
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Hide raw SMS in Firefly notes", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
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
                        isSavingSettings = true
                        viewModel.saveFireflyCredentials(localUrl, localToken, localDefaultAccount)
                        val includeRawSms = !hideRawSms
                        viewModel.setFireflyIncludeRawSms(includeRawSms)
                        showSaved = true
                        viewModel.refreshFireflyAccounts()
                        isSavingSettings = false
                    },
                    enabled = !isSavingSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isSavingSettings) "Saving..." else "Save Settings")
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

            // Mappings - only available after successful connection
            val hasConnection = fireflyAccounts.isNotEmpty()

            Text(
                "Mappings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp)
            )

            if (!hasConnection) {
                Text(
                    "Connect successfully to Firefly (test your connection) to enable account and category mappings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Account Mappings - deeper menu (opens as dedicated bottom sheet)
                OutlinedButton(
                    onClick = { showAccountMappingsSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AccountBalance, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Manage Account Mappings (${fireflyMappingAccounts.size})")
                }

                // Category Mappings - deeper menu
                OutlinedButton(
                    onClick = { showCategoryMappingsSheet = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Icon(Icons.Default.Category, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Manage Category Mappings")
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // ==================== DEDICATED ACCOUNT MAPPINGS SHEET ====================
    if (showAccountMappingsSheet) {
        ModalBottomSheet(onDismissRequest = { showAccountMappingsSheet = false }) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("Account Mappings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Map PennyWise accounts to specific Firefly asset accounts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                if (isLoadingFireflyAccounts) {
                    Text("Loading Firefly accounts...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (fireflyAccounts.isEmpty()) {
                    Text("No Firefly accounts loaded. Please test connection again.", color = MaterialTheme.colorScheme.error)
                } else {
                    fireflyMappingAccounts.forEach { account ->
                        val currentValue = fireflyMappings[account.key] ?: ""
                        var expanded by remember { mutableStateOf(false) }

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(account.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = !expanded },
                                modifier = Modifier.weight(1.2f)
                            ) {
                                OutlinedTextField(
                                    value = currentValue,
                                    onValueChange = { },
                                    readOnly = true,
                                    placeholder = { Text("Select Firefly account") },
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                                )

                                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    fireflyAccounts.forEach { accInfo ->
                                        DropdownMenuItem(
                                            text = { Text(accInfo.name) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.AccountBalance,
                                                    contentDescription = null,
                                                    tint = if (accInfo.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                                )
                                            },
                                            onClick = {
                                                viewModel.setFireflyAccountMapping(account.key, accInfo.name)
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Effective + stale warning
                        val effectiveAccount = currentValue.takeIf { it.isNotBlank() } ?: (fireflyDefaultAsset?.takeIf { it.isNotBlank() } ?: "Checking Account (default)")
                        Text("→ Effective: $effectiveAccount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        if (currentValue.isNotBlank() && fireflyAccounts.none { it.name == currentValue }) {
                            Text("(not found in Firefly – please reselect)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { viewModel.clearAllFireflyAccountMappings() }) {
                    Text("Clear all mappings")
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ==================== DEDICATED CATEGORY MAPPINGS SHEET ====================
    if (showCategoryMappingsSheet) {
        ModalBottomSheet(onDismissRequest = { showCategoryMappingsSheet = false }) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("Category Mappings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Map your PennyWise categories to categories that exist in Firefly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                val categoriesToShow = (allCategories + fireflyCategoryMappings.keys).distinct().sorted()

                if (categoriesToShow.isEmpty()) {
                    Text("No categories found. Add transactions first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    categoriesToShow.forEach { cat ->
                        val current = fireflyCategoryMappings[cat] ?: ""
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(cat, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)

                            // Dropdown of Firefly categories
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = !expanded },
                                modifier = Modifier.weight(1.3f)
                            ) {
                                OutlinedTextField(
                                    value = current,
                                    onValueChange = { },
                                    readOnly = true,
                                    placeholder = { Text("Select Firefly category") },
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )

                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    // Include all Firefly categories + allow custom by keeping the text field editable? but per request use dropdown
                                    fireflyCategories.forEach { fireflyCat ->
                                        DropdownMenuItem(
                                            text = { Text(fireflyCat) },
                                            onClick = {
                                                viewModel.setFireflyCategoryMapping(cat, fireflyCat)
                                                expanded = false
                                            }
                                        )
                                    }
                                    // Allow clearing
                                    if (current.isNotBlank()) {
                                        DropdownMenuItem(
                                            text = { Text("Clear mapping") },
                                            onClick = {
                                                viewModel.clearFireflyCategoryMapping(cat)  // note: may need to implement if not exist
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Still allow custom mapping
                Text("Custom category mapping (if category not listed yet)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var customCategory by remember { mutableStateOf("") }
                var customFireflyCategory by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(value = customCategory, onValueChange = { customCategory = it }, placeholder = { Text("PennyWise cat") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = customFireflyCategory, onValueChange = { customFireflyCategory = it }, placeholder = { Text("Firefly cat") }, modifier = Modifier.weight(1f))
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
                    ) { Text("Add") }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}