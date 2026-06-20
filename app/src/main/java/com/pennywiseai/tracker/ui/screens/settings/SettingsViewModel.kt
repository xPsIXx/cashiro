package com.pennywiseai.tracker.ui.screens.settings

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.pennywiseai.tracker.core.Constants.Links
import com.pennywiseai.tracker.data.repository.ModelRepository
import com.pennywiseai.tracker.data.repository.ModelState
import com.pennywiseai.tracker.data.repository.UnrecognizedSmsRepository
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.backup.BackupExporter
import com.pennywiseai.tracker.data.backup.BackupImporter
import com.pennywiseai.tracker.data.backup.ExportResult
import com.pennywiseai.tracker.data.backup.ImportResult
import com.pennywiseai.tracker.data.backup.ImportStrategy
import com.pennywiseai.tracker.data.repository.TransactionRepository
import com.pennywiseai.tracker.utils.CurrencyUtils
import android.content.Intent
import androidx.core.content.FileProvider
import com.pennywiseai.tracker.core.Constants
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.first
import java.net.URLEncoder
import java.io.File
import com.pennywiseai.tracker.data.firefly.FireflyClient
import com.pennywiseai.tracker.data.firefly.FireflyTokenManager
import com.pennywiseai.tracker.data.repository.AccountBalanceRepository
import com.pennywiseai.tracker.data.repository.CategoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val unrecognizedSmsRepository: UnrecognizedSmsRepository,
    private val transactionRepository: TransactionRepository,
    private val backupExporter: BackupExporter,
    private val backupImporter: BackupImporter,
    private val contactsResolver: com.pennywiseai.tracker.data.contacts.ContactsResolver,
    private val fireflyClient: FireflyClient,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val categoryRepository: CategoryRepository,
    private val fireflyTokenManager: FireflyTokenManager
) : ViewModel() {
    
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    
    // Download state
    private val _downloadState = MutableStateFlow(DownloadState.NOT_DOWNLOADED)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()
    
    private val _downloadedMB = MutableStateFlow(0L)
    val downloadedMB: StateFlow<Long> = _downloadedMB.asStateFlow()
    
    private val _totalMB = MutableStateFlow(0L)
    val totalMB: StateFlow<Long> = _totalMB.asStateFlow()
    
    // Import/Export state
    private val _importExportMessage = MutableStateFlow<String?>(null)
    val importExportMessage: StateFlow<String?> = _importExportMessage.asStateFlow()
    
    private val _exportedBackupFile = MutableStateFlow<File?>(null)
    val exportedBackupFile: StateFlow<File?> = _exportedBackupFile.asStateFlow()
    
    private var currentDownloadId: Long? = null
    
    // Developer mode state
    val isDeveloperModeEnabled = userPreferencesRepository.isDeveloperModeEnabled
    
    // SMS scan period state
    val smsScanMonths = userPreferencesRepository.smsScanMonths
    val smsScanAllTime = userPreferencesRepository.smsScanAllTime

    // Unified Currency Mode
    val unifiedCurrencyMode = userPreferencesRepository.unifiedCurrencyMode
    val displayCurrency = userPreferencesRepository.displayCurrency

    // Replace UPI VPAs with contact names (gated by READ_CONTACTS).
    val useContactsForVpa = userPreferencesRepository.useContactsForVpa

    // Firefly III opt-in sync
    val fireflySyncEnabled = userPreferencesRepository.fireflySyncEnabledFlow
    val fireflyBaseUrl = userPreferencesRepository.fireflyBaseUrlFlow
    val fireflyDefaultAssetAccount = userPreferencesRepository.fireflyDefaultAssetAccountFlow
    val fireflyLastSyncError = userPreferencesRepository.fireflyLastSyncErrorFlow

    // Failed Firefly syncs count (for badge in settings)
    val fireflyFailedSyncCount = transactionRepository.getFailedFireflySyncCount()

    // Firefly account mappings
    val fireflyAccountMappings = userPreferencesRepository.fireflyAccountMappingsFlow

    // Firefly category mappings
    val fireflyCategoryMappings = userPreferencesRepository.fireflyCategoryMappingsFlow

    // Whether to include raw SMS in Firefly notes
    val fireflyIncludeRawSms = userPreferencesRepository.fireflyIncludeRawSmsFlow

    val fireflyAutoSyncInterval = userPreferencesRepository.fireflyAutoSyncIntervalFlow
    val fireflyMigrationRan = userPreferencesRepository.fireflyMigrationRanFlow

    // All user categories for dynamic mapping UI
    val allCategoriesForMapping = categoryRepository.getAllCategories()
        .map { categories ->
            categories.map { it.name }.sorted()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _fireflyAccounts = MutableStateFlow<List<String>>(emptyList())
    val fireflyAccounts: StateFlow<List<String>> = _fireflyAccounts.asStateFlow()

    private val _isLoadingFireflyAccounts = MutableStateFlow(false)
    val isLoadingFireflyAccounts: StateFlow<Boolean> = _isLoadingFireflyAccounts.asStateFlow()

    // Known accounts for mapping (latest balances represent known accounts)
    val knownAccountsForMapping = accountBalanceRepository.getAllLatestBalances()

    /** Clean list of accounts for the Firefly mapping UI inside the dialog */
    val fireflyMappingAccounts: Flow<List<AccountMappingItem>> =
        knownAccountsForMapping
            .map { balances ->
                balances
                    .distinctBy { "${it.bankName}**${it.accountLast4}" }
                    .sortedBy { it.bankName }
                    .map { balance ->
                        val key = "${balance.bankName}**${balance.accountLast4}"
                        AccountMappingItem(
                            key = key,
                            displayName = "${balance.bankName} ••••${balance.accountLast4}",
                            isCreditCard = balance.isCreditCard
                        )
                    }
            }

    val availableCurrencies: StateFlow<List<String>> = transactionRepository.getAllCurrencies()
        .map { transactionCurrencies ->
            val supportedCurrencies = CurrencyUtils.getAllSupportedCurrencies()
            val allCurrencies = (transactionCurrencies + supportedCurrencies).distinct()
            CurrencyUtils.sortCurrencies(allCurrencies)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CurrencyUtils.getAllSupportedCurrencies()
        )
    
    // Base currency state
    val baseCurrency = userPreferencesRepository.baseCurrency
    
    // Unrecognized SMS state
    val unreportedSmsCount = unrecognizedSmsRepository.getUnreportedCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )
    
    init {
        checkDownloadStatus()
        // Also sync with model repository
        modelRepository.checkModelState()
        // Schedule Firefly auto sync based on current preference
        viewModelScope.launch {
            val interval = userPreferencesRepository.fireflyAutoSyncIntervalFlow.first()
            com.pennywiseai.tracker.worker.FireflyAutoSyncWorker.schedule(context, interval)
            // Run legacy migration if needed (one-time)
            migrateLegacyFireflyExternalIds()
        }
    }
    
    private fun checkDownloadStatus() {
        viewModelScope.launch {
            // First check for active download
            val savedDownloadId = userPreferencesRepository.getActiveDownloadId()
            Log.d("SettingsViewModel", "Checking download status, saved ID: $savedDownloadId")
            
            if (savedDownloadId != null) {
                // Query DownloadManager for this ID
                val query = DownloadManager.Query().setFilterById(savedDownloadId)
                val cursor = downloadManager.query(query)
                
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    
                    if (statusIndex != -1) {
                        val status = cursor.getInt(statusIndex)
                        Log.d("SettingsViewModel", "Found active download with status: $status")
                        
                        when (status) {
                            DownloadManager.STATUS_RUNNING,
                            DownloadManager.STATUS_PENDING -> {
                                _downloadState.value = DownloadState.DOWNLOADING
                                currentDownloadId = savedDownloadId
                                // Sync ModelRepository state
                                modelRepository.updateModelState(ModelState.DOWNLOADING)
                                // Get current progress
                                val bytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                if (bytesIndex != -1 && totalIndex != -1) {
                                    val bytes = cursor.getLong(bytesIndex)
                                    val total = cursor.getLong(totalIndex)
                                    _downloadedMB.value = bytes / (1024 * 1024)
                                    _totalMB.value = total / (1024 * 1024)
                                    if (total > 0) {
                                        _downloadProgress.value = (bytes * 100 / total).toInt()
                                    }
                                }
                                monitorDownload(savedDownloadId)
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                _downloadState.value = DownloadState.COMPLETED
                                _downloadProgress.value = 100
                                userPreferencesRepository.clearActiveDownloadId()
                                modelRepository.updateModelState(ModelState.READY)
                            }
                            DownloadManager.STATUS_FAILED -> {
                                _downloadState.value = DownloadState.FAILED
                                userPreferencesRepository.clearActiveDownloadId()
                                // Sync ModelRepository state
                                modelRepository.updateModelState(ModelState.NOT_DOWNLOADED)
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                _downloadState.value = DownloadState.PAUSED
                                currentDownloadId = savedDownloadId
                                // Sync ModelRepository state - still downloading but paused
                                modelRepository.updateModelState(ModelState.DOWNLOADING)
                            }
                        }
                    }
                    cursor.close()
                } else {
                    // Download ID not found in DownloadManager, clear it and check file
                    Log.d("SettingsViewModel", "Download ID not found in DownloadManager, checking file")
                    userPreferencesRepository.clearActiveDownloadId()
                    checkModelFile()
                }
            } else {
                // No active download, check if model file exists
                checkModelFile()
            }
        }
    }
    
    private fun checkModelFile() {
        val modelFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), Constants.ModelDownload.MODEL_FILE_NAME)
        Log.d("SettingsViewModel", "Checking model file at: ${modelFile.absolutePath}")
        Log.d("SettingsViewModel", "Model file exists: ${modelFile.exists()}, size: ${modelFile.length()}, expected: ${Constants.ModelDownload.MODEL_SIZE_BYTES}")
        
        // Check against expected size to ensure it's complete
        // Allow 5% variance in file size as download sizes can vary slightly
        val minSize = (Constants.ModelDownload.MODEL_SIZE_BYTES * 0.95).toLong()
        val maxSize = (Constants.ModelDownload.MODEL_SIZE_BYTES * 1.05).toLong()
        
        if (modelFile.exists() && modelFile.length() in minSize..maxSize) {
            _downloadState.value = DownloadState.COMPLETED
            _totalMB.value = modelFile.length() / (1024 * 1024)
            _downloadedMB.value = _totalMB.value
            _downloadProgress.value = 100
            // Update model repository state
            Log.d("SettingsViewModel", "Model complete (${modelFile.length()} bytes), updating repository state to READY")
            modelRepository.updateModelState(ModelState.READY)
        } else if (modelFile.exists() && modelFile.length() > maxSize) {
            // File is too large, but might still be valid - mark as complete
            _downloadState.value = DownloadState.COMPLETED
            _totalMB.value = modelFile.length() / (1024 * 1024)
            _downloadedMB.value = _totalMB.value
            _downloadProgress.value = 100
            Log.d("SettingsViewModel", "Model file larger than expected (${modelFile.length()} bytes), but marking as complete")
            modelRepository.updateModelState(ModelState.READY)
        } else if (modelFile.exists()) {
            // Partial file exists, delete it
            Log.d("SettingsViewModel", "Partial model file found (${modelFile.length()} bytes), deleting")
            modelFile.delete()
            _downloadState.value = DownloadState.NOT_DOWNLOADED
        } else {
            Log.d("SettingsViewModel", "Model not found")
            _downloadState.value = DownloadState.NOT_DOWNLOADED
        }
    }
    
    fun startModelDownload() {
        viewModelScope.launch {
            // Check if download is already active
            val existingDownloadId = userPreferencesRepository.getActiveDownloadId()
            if (existingDownloadId != null) {
                // Check if this download is still active
                val query = DownloadManager.Query().setFilterById(existingDownloadId)
                val cursor = downloadManager.query(query)
                
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex != -1) {
                        val status = cursor.getInt(statusIndex)
                        if (status == DownloadManager.STATUS_RUNNING || 
                            status == DownloadManager.STATUS_PENDING ||
                            status == DownloadManager.STATUS_PAUSED) {
                            // Download is already active, just monitor it
                            Log.d("SettingsViewModel", "Download already active with ID: $existingDownloadId")
                            cursor.close()
                            _downloadState.value = DownloadState.DOWNLOADING
                            currentDownloadId = existingDownloadId
                            modelRepository.updateModelState(ModelState.DOWNLOADING)
                            monitorDownload(existingDownloadId)
                            return@launch
                        }
                    }
                    cursor.close()
                }
            }
            
            // Check storage space
            val availableSpace = context.filesDir.usableSpace
            if (availableSpace < Constants.ModelDownload.REQUIRED_SPACE_BYTES) {
                _downloadState.value = DownloadState.ERROR_INSUFFICIENT_SPACE
                return@launch
            }
            
            // Validate model URL before attempting download
            val modelUrl = Constants.ModelDownload.MODEL_URL
            if (modelUrl.isBlank() || !modelUrl.startsWith("http")) {
                Log.e("SettingsViewModel", "Invalid MODEL_URL: '$modelUrl'")
                _downloadState.value = DownloadState.FAILED
                return@launch
            }

            // Clean up any stale partial file — DownloadManager stays PENDING if destination exists
            val existingFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), Constants.ModelDownload.MODEL_FILE_NAME)
            if (existingFile.exists()) {
                existingFile.delete()
            }

            try {
                // Create download request
                val request = DownloadManager.Request(Uri.parse(modelUrl))
                    .setTitle("AI Chat Model")
                    .setDescription("Downloading AI chat assistant for PennyWise")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, Constants.ModelDownload.MODEL_FILE_NAME)
                    .setAllowedOverMetered(true) // Allow mobile data downloads
                    .setAllowedOverRoaming(false)

                currentDownloadId = downloadManager.enqueue(request)
                _downloadState.value = DownloadState.DOWNLOADING

                // Sync ModelRepository state
                modelRepository.updateModelState(ModelState.DOWNLOADING)

                // Save download ID
                userPreferencesRepository.saveActiveDownloadId(currentDownloadId!!)
                Log.d("SettingsViewModel", "Started download with ID: $currentDownloadId")

                // Start monitoring progress
                monitorDownload(currentDownloadId!!)
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to start download", e)
                _downloadState.value = DownloadState.FAILED
            }
        }
    }
    
    private fun monitorDownload(downloadId: Long) {
        viewModelScope.launch {
            while (isActive && _downloadState.value == DownloadState.DOWNLOADING) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalBytesColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    
                    if (bytesColumnIndex != -1 && totalBytesColumnIndex != -1) {
                        val bytesDownloaded = cursor.getLong(bytesColumnIndex)
                        val rawBytesTotal = cursor.getLong(totalBytesColumnIndex)

                        // Fallback to known model size when DownloadManager reports 0
                        val bytesTotal = if (rawBytesTotal > 0) rawBytesTotal else Constants.ModelDownload.MODEL_SIZE_BYTES

                        val progress = (bytesDownloaded * 100 / bytesTotal).toInt()

                        _downloadProgress.value = progress
                        _downloadedMB.value = bytesDownloaded / (1024 * 1024)
                        _totalMB.value = bytesTotal / (1024 * 1024)
                    }
                    
                    // Check status
                    if (statusColumnIndex != -1) {
                        when (cursor.getInt(statusColumnIndex)) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                _downloadState.value = DownloadState.COMPLETED
                                _downloadProgress.value = 100
                                // Clear saved download ID
                                userPreferencesRepository.clearActiveDownloadId()
                                // Update model repository state
                                modelRepository.updateModelState(ModelState.READY)
                                Log.d("SettingsViewModel", "Download completed successfully")
                            }
                            DownloadManager.STATUS_FAILED -> {
                                _downloadState.value = DownloadState.FAILED
                                // Clear saved download ID
                                userPreferencesRepository.clearActiveDownloadId()
                                // Sync ModelRepository state
                                modelRepository.updateModelState(ModelState.NOT_DOWNLOADED)
                                Log.d("SettingsViewModel", "Download failed")
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                _downloadState.value = DownloadState.PAUSED
                            }
                        }
                    }
                }
                cursor?.close()
                delay(1000) // Update every second
            }
        }
    }
    
    fun cancelDownload() {
        viewModelScope.launch {
            currentDownloadId?.let {
                downloadManager.remove(it)
                _downloadState.value = DownloadState.NOT_DOWNLOADED
                _downloadProgress.value = 0
                _downloadedMB.value = 0
                _totalMB.value = 0
                
                // Clear saved download ID
                userPreferencesRepository.clearActiveDownloadId()
                
                // Delete partial file
                val modelFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), Constants.ModelDownload.MODEL_FILE_NAME)
                if (modelFile.exists()) {
                    modelFile.delete()
                }
                Log.d("SettingsViewModel", "Download cancelled and cleaned up")
            }
        }
    }
    
    fun deleteModel() {
        viewModelScope.launch {
            val modelFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), Constants.ModelDownload.MODEL_FILE_NAME)
            if (modelFile.exists()) {
                modelFile.delete()
                _downloadState.value = DownloadState.NOT_DOWNLOADED
                _downloadProgress.value = 0
                _downloadedMB.value = 0
                _totalMB.value = 0
                // Clear any saved download ID
                userPreferencesRepository.clearActiveDownloadId()
                // Update model repository state
                modelRepository.updateModelState(ModelState.NOT_DOWNLOADED)
                Log.d("SettingsViewModel", "Model deleted")
            }
        }
    }
    
    fun setUnifiedCurrencyMode(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setUnifiedCurrencyMode(enabled)
            com.pennywiseai.tracker.widget.RecentTransactionsWidgetUpdateWorker.enqueueOneShot(context)
        }
    }

    fun setDisplayCurrency(currency: String) {
        viewModelScope.launch {
            userPreferencesRepository.setDisplayCurrency(currency)
            com.pennywiseai.tracker.widget.RecentTransactionsWidgetUpdateWorker.enqueueOneShot(context)
            com.pennywiseai.tracker.widget.RecentTransactionsWidgetDataStore.clear(context)
        }
    }

    fun setBaseCurrency(currency: String) {
        viewModelScope.launch {
            userPreferencesRepository.setBaseCurrency(currency)
        }
    }

    fun toggleDeveloperMode(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDeveloperModeEnabled(enabled)
        }
    }

    /**
     * Flip the UPI-contact-resolution preference. The screen is responsible
     * for ensuring READ_CONTACTS is granted before passing `true` — this
     * just persists. Toggling either direction wipes the resolver cache so
     * stale results don't leak across the flag flip (and so a re-enable
     * after permission grant picks up the user's contacts immediately).
     */
    fun setUseContactsForVpa(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setUseContactsForVpa(enabled)
            contactsResolver.clearCache()
        }
    }

    // Firefly III integration actions
    fun setFireflySyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflySyncEnabled(enabled)
            if (enabled) {
                val interval = userPreferencesRepository.fireflyAutoSyncIntervalFlow.first()
                com.pennywiseai.tracker.worker.FireflyAutoSyncWorker.schedule(context, interval)
                val migrationRan = userPreferencesRepository.fireflyMigrationRanFlow.first()
                if (!migrationRan) {
                    // One-time migrate legacy + reconcile on first enable after fresh install/reinstall
                    migrateLegacyFireflyExternalIds()
                    reconcileWithFirefly { result ->
                        // The result will be shown via UI toast/log in settings
                        Log.d("SettingsViewModel", "Migration reconcile result: $result")
                    }
                    userPreferencesRepository.setFireflyMigrationRan(true)
                }
            } else {
                com.pennywiseai.tracker.worker.FireflyAutoSyncWorker.cancel(context)
            }
        }
    }

    fun setFireflyBaseUrl(url: String) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyBaseUrl(url)
        }
    }

    fun setFireflyAccessToken(token: String) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyAccessToken(token)
        }
    }

    fun setFireflyDefaultAssetAccount(account: String) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyDefaultAssetAccount(account)
        }
    }

    suspend fun testFireflyConnection(url: String, token: String): FireflyClient.SyncResult {
        return fireflyClient.testConnection(url, token)
    }

    fun clearFireflyLastError() {
        viewModelScope.launch {
            userPreferencesRepository.clearFireflyLastError()
        }
    }

    // Firefly account mapping helpers
    fun setFireflyAccountMapping(accountKey: String, fireflyAccountName: String) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyAccountMapping(accountKey, fireflyAccountName)
        }
    }

    fun clearFireflyAccountMapping(accountKey: String) {
        viewModelScope.launch {
            userPreferencesRepository.clearFireflyAccountMapping(accountKey)
        }
    }

    fun clearAllFireflyAccountMappings() {
        viewModelScope.launch {
            userPreferencesRepository.clearAllFireflyAccountMappings()
        }
    }

    fun refreshFireflyAccounts() {
        viewModelScope.launch {
            _isLoadingFireflyAccounts.value = true
            try {
                val (url, token) = getFireflySecureCredentials()
                if (!url.isNullOrBlank() && !token.isNullOrBlank()) {
                    val accounts = fireflyClient.getAccounts(url, token)
                    _fireflyAccounts.value = accounts
                } else {
                    _fireflyAccounts.value = emptyList()
                }
            } finally {
                _isLoadingFireflyAccounts.value = false
            }
        }
    }

    // Firefly category mapping helpers
    fun setFireflyCategoryMapping(pennywiseCategory: String, fireflyCategory: String) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyCategoryMapping(pennywiseCategory, fireflyCategory)
        }
    }

    fun clearFireflyCategoryMapping(pennywiseCategory: String) {
        viewModelScope.launch {
            userPreferencesRepository.clearFireflyCategoryMapping(pennywiseCategory)
        }
    }

    fun setFireflyIncludeRawSms(include: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyIncludeRawSms(include)
        }
    }

    fun setFireflyAutoSyncInterval(interval: String) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyAutoSyncInterval(interval)
            // Schedule the worker
            com.pennywiseai.tracker.worker.FireflyAutoSyncWorker.schedule(context, interval)
        }
    }

    /** Saves Firefly credentials securely (token in EncryptedSharedPreferences) */
    fun saveFireflyCredentials(url: String, token: String, defaultAsset: String?) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyBaseUrl(url)
            // Always set (blank/empty will clear the fallback)
            userPreferencesRepository.setFireflyDefaultAssetAccount(defaultAsset)
            // Save token securely
            fireflyTokenManager.saveCredentials(url, token)
        }
    }

    /** Returns current secure credentials for initial loading */
    fun getFireflySecureCredentials(): Pair<String?, String?> {
        return fireflyTokenManager.getBaseUrl() to fireflyTokenManager.getAccessToken()
    }

    // Sync transactions from the last 30 days that haven't been sent to Firefly yet
    fun syncLast30Days(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run {
                    onResult("Firefly not configured")
                    return@launch
                }

                val thirtyDaysAgo = LocalDateTime.now().minusDays(30)
                val unsynced = transactionRepository.getTransactionsBetweenDates(thirtyDaysAgo, LocalDateTime.now())
                    .first()
                    .filter { it.fireflySyncedAt == null && it.fireflyLastError == null }

                if (unsynced.isEmpty()) {
                    onResult("No unsynced transactions in the last 30 days")
                    return@launch
                }

                var successCount = 0
                for (tx in unsynced) {
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
                        val extId = fireflyClient.computeExternalId(tx)
                        transactionRepository.markFireflySynced(tx.id, extId)
                        successCount++
                    }
                }

                onResult("Synced $successCount of ${unsynced.size} transactions from last 30 days")
            } catch (e: Exception) {
                onResult("Error syncing last 30 days: ${e.message}")
            }
        }
    }

    // Sync all currently unsynced transactions (those never successfully pushed to Firefly)
    fun syncAllUnsynced(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run {
                    onResult("Firefly not configured")
                    return@launch
                }

                val unsynced = transactionRepository.getAllTransactionsList()
                    .filter { it.fireflySyncedAt == null && it.fireflyLastError == null }

                if (unsynced.isEmpty()) {
                    onResult("No unsynced transactions to sync")
                    return@launch
                }

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
                        val extId = fireflyClient.computeExternalId(tx)
                        transactionRepository.markFireflySynced(tx.id, extId)
                        successCount++
                    } else if (result is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Error) {
                        transactionRepository.markFireflyError(tx.id, result.message)
                    }
                }

                onResult("Synced $successCount of ${unsynced.size} unsynced transactions")
            } catch (e: Exception) {
                onResult("Error syncing unsynced: ${e.message}")
            }
        }
    }

    // Full sync: synchronize everything (unsynced + previously failed, re-push if needed)
    fun fullSyncToFirefly(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run {
                    onResult("Firefly not configured")
                    return@launch
                }

                // Include both never-synced and previously errored
                val toSync = transactionRepository.getAllTransactionsList()
                    .filter { it.fireflySyncedAt == null }  // includes both no success and errored (since errored have syncedAt null)

                if (toSync.isEmpty()) {
                    onResult("All transactions are already synced")
                    return@launch
                }

                var successCount = 0
                var errorCount = 0
                toSync.forEach { tx ->
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
                        val extId = fireflyClient.computeExternalId(tx)
                        transactionRepository.markFireflySynced(tx.id, extId)
                        successCount++
                    } else if (result is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Error) {
                        transactionRepository.markFireflyError(tx.id, result.message)
                        errorCount++
                    }
                }

                onResult("Full sync complete. Success: $successCount, Errors: $errorCount (out of ${toSync.size})")
            } catch (e: Exception) {
                onResult("Error during full sync: ${e.message}")
            }
        }
    }

    /**
     * Reconcile local transactions with Firefly after reinstall or data loss.
     * Uses stable hash-based external_id to find existing entries in Firefly
     * and mark matching local tx as synced. This makes sync status survive reinstalls.
     */
    fun reconcileWithFirefly(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run {
                    onResult("Firefly not configured")
                    return@launch
                }

                val unsynced = transactionRepository.getAllTransactionsList()
                    .filter { it.fireflySyncedAt == null }

                var reconciled = 0
                unsynced.forEach { tx ->
                    val candidates = mutableListOf<String>()
                    if (!tx.transactionHash.isNullOrBlank()) {
                        candidates.add("pennywise-${tx.transactionHash}")
                    }
                    if (!tx.fireflyExternalId.isNullOrBlank()) {
                        candidates.add(tx.fireflyExternalId)  // may be legacy numeric
                    }
                    for (candidate in candidates.distinct()) {
                        val fireflyId = fireflyClient.getTransactionIdByExternalId(config.url, config.token, candidate)
                        if (fireflyId != null) {
                            // Update local to use hash version if we have it
                            val hashExt = if (!tx.transactionHash.isNullOrBlank()) "pennywise-${tx.transactionHash}" else candidate
                            transactionRepository.markFireflySynced(tx.id, hashExt)
                            reconciled++
                            break
                        }
                    }
                }

                onResult("Reconciled $reconciled transactions using Firefly as source of truth")
            } catch (e: Exception) {
                onResult("Reconcile error: ${e.message}")
            }
        }
    }

    /**
     * One-time migration: update local fireflyExternalId from legacy numeric "pennywise-{oldId}"
     * to the stable hash-based "pennywise-{hash}" .
     * Runs on app start / first enable.
     */
    fun migrateLegacyFireflyExternalIds() {
        viewModelScope.launch {
            try {
                val txs = transactionRepository.getAllTransactionsList()
                var migrated = 0
                txs.forEach { tx ->
                    val current = tx.fireflyExternalId
                    if (!current.isNullOrBlank() &&
                        current.startsWith("pennywise-") &&
                        current.removePrefix("pennywise-").all { it.isDigit() } &&
                        !tx.transactionHash.isNullOrBlank()
                    ) {
                        val newExt = "pennywise-${tx.transactionHash}"
                        transactionRepository.updateFireflyExternalId(tx.id, newExt)
                        migrated++
                    }
                }
                if (migrated > 0) {
                    Log.d("SettingsViewModel", "Migrated $migrated legacy Firefly external IDs to hash-based")
                }
                userPreferencesRepository.setFireflyMigrationRan(true)
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Migration error", e)
            }
        }
    }

    // Send a test transaction to Firefly
    fun sendTestTransaction(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = fetchFireflyConfig() ?: run {
                    onResult("Firefly not configured")
                    return@launch
                }

                val testTx = com.pennywiseai.tracker.data.database.entity.TransactionEntity(
                    id = -1,
                    amount = java.math.BigDecimal("123.45"),
                    merchantName = "Test Merchant (PennyWise)",
                    category = "Testing",
                    transactionType = com.pennywiseai.tracker.data.database.entity.TransactionType.EXPENSE,
                    dateTime = java.time.LocalDateTime.now(),
                    bankName = "Test Bank",
                    accountNumber = "0000",
                    currency = config.baseCurrency,
                    transactionHash = "TEST_${System.currentTimeMillis()}"
                )

                val accountKey = "${testTx.bankName}**${testTx.accountNumber}"
                val mappedAccount = config.accountMappings[accountKey]
                val usedAccount = mappedAccount?.takeIf { it.isNotBlank() }
                    ?: config.defaultAssetAccount?.takeIf { it.isNotBlank() }
                    ?: "Checking Account"

                val result = fireflyClient.syncTransaction(
                    transaction = testTx,
                    baseUrl = config.url,
                    accessToken = config.token,
                    defaultAssetAccount = config.defaultAssetAccount,
                    accountMappings = config.accountMappings,
                    categoryMappings = config.categoryMappings,
                    includeRawSmsInNotes = config.includeRawSms
                )

                when (result) {
                    is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Success -> onResult("Test transaction sent successfully using account: $usedAccount")
                    is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Error -> onResult("Failed: ${result.message}")
                    else -> onResult("Skipped")
                }
            } catch (e: Exception) {
                onResult("Error: ${e.message}")
            }
        }
    }

    private data class FireflyConfig(
        val url: String,
        val token: String,
        val defaultAssetAccount: String?,
        val accountMappings: Map<String, String>,
        val categoryMappings: Map<String, String>,
        val includeRawSms: Boolean,
        val baseCurrency: String
    )

    private suspend fun fetchFireflyConfig(): FireflyConfig? {
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
            includeRawSms = includeRaw,
            baseCurrency = prefs.baseCurrency
        )
    }
    
    fun updateSmsScanMonths(months: Int) {
        viewModelScope.launch {
            val currentMonths = userPreferencesRepository.getSmsScanMonths()

            // If increasing scan period, reset scan timestamp to force full scan
            if (months > currentMonths) {
                userPreferencesRepository.setLastScanTimestamp(0L)
                Log.d("SettingsViewModel", "Scan period increased from $currentMonths to $months months - will perform full scan")
            }

            userPreferencesRepository.updateSmsScanMonths(months)
        }
    }

    fun updateSmsScanAllTime(allTime: Boolean) {
        viewModelScope.launch {
            // If enabling all time scanning, reset scan timestamp to force full scan
            if (allTime) {
                userPreferencesRepository.setLastScanTimestamp(0L)
                Log.d("SettingsViewModel", "All time scanning enabled - will perform full scan")
            }

            userPreferencesRepository.updateSmsScanAllTime(allTime)
        }
    }
    
    fun openUnrecognizedSmsReport(context: Context) {
        viewModelScope.launch {
            try {
                val firstUnreported = unrecognizedSmsRepository.getFirstUnreported()
                
                if (firstUnreported != null) {
                    // URL encode the parameters
                    val encodedMessage = URLEncoder.encode(firstUnreported.smsBody, "UTF-8")
                    val encodedSender = URLEncoder.encode(firstUnreported.sender, "UTF-8")
                    
                    // Encrypt device data for verification
                    val encryptedDeviceData = com.pennywiseai.tracker.utils.DeviceEncryption.encryptDeviceData(context)
                    Log.d("SettingsViewModel", "Encrypted device data: ${encryptedDeviceData?.take(50)}... (length: ${encryptedDeviceData?.length})")
                    
                    val encodedDeviceData = if (encryptedDeviceData != null) {
                        URLEncoder.encode(encryptedDeviceData, "UTF-8")
                    } else {
                        ""
                    }
                    Log.d("SettingsViewModel", "Encoded device data: ${encodedDeviceData.take(50)}... (length: ${encodedDeviceData.length})")
                    
                    // Reporting to original service disabled in this fork.
                    // Direct users to the fork's GitHub issues instead.
                    val url = "${Constants.Links.GITHUB_URL}/issues/new"
                    Log.d("SettingsViewModel", "Opening fork issues instead of original parser")
                    
                    // Open in browser
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                    
                    // Mark as reported
                    unrecognizedSmsRepository.markAsReported(listOf(firstUnreported.id))
                    
                    Log.d("SettingsViewModel", "Opened report for unrecognized SMS from: ${firstUnreported.sender}")
                } else {
                    Log.d("SettingsViewModel", "No unreported SMS messages found")
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error opening unrecognized SMS report", e)
            }
        }
    }
    
    fun exportBackup() {
        viewModelScope.launch {
            try {
                val result = backupExporter.exportBackup()
                when (result) {
                    is ExportResult.Success -> {
                        // Store the file for later saving
                        _exportedBackupFile.value = result.file
                        _importExportMessage.value = "Backup created successfully! Choose where to save it."
                    }
                    is ExportResult.Error -> {
                        _importExportMessage.value = "Export failed: ${result.message}"
                        Log.e("SettingsViewModel", "Export failed: ${result.message}")
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                _importExportMessage.value = "Export error: ${e.message}"
                Log.e("SettingsViewModel", "Export error", e)
            }
        }
    }
    
    fun saveBackupToFile(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _exportedBackupFile.value?.let { file ->
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        file.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    _importExportMessage.value = "Backup saved successfully!"
                    _exportedBackupFile.value = null
                }
            } catch (e: Exception) {
                _importExportMessage.value = "Failed to save backup: ${e.message}"
                Log.e("SettingsViewModel", "Error saving backup", e)
            }
        }
    }
    
    fun shareBackup() {
        _exportedBackupFile.value?.let { file ->
            shareBackupFile(file)
        }
    }
    
    private fun shareBackupFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "PennyWise Backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(Intent.createChooser(intent, "Share Backup").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "Error sharing backup file", e)
        }
    }
    
    fun importBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _importExportMessage.value = "Importing backup..."
                val result = backupImporter.importBackup(uri, ImportStrategy.MERGE)
                when (result) {
                    is ImportResult.Success -> {
                        val skipped = if (result.skippedRows > 0) " ${result.skippedRows} rows could not be imported." else ""
                        _importExportMessage.value = "Import successful! Imported ${result.importedTransactions} transactions, ${result.importedCategories} categories. Skipped ${result.skippedDuplicates} duplicates.$skipped"
                    }
                    is ImportResult.Error -> {
                        _importExportMessage.value = "Import failed: ${result.message}"
                        Log.e("SettingsViewModel", "Import failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                _importExportMessage.value = "Import error: ${e.message}"
                Log.e("SettingsViewModel", "Import error", e)
            }
        }
    }
    
    fun clearImportExportMessage() {
        _importExportMessage.value = null
    }
    
    fun updateBaseCurrency(currency: String) {
        viewModelScope.launch {
            userPreferencesRepository.setBaseCurrency(currency)
        }
    }
}

/** UI model for showing accounts in the Firefly mapping section */
data class AccountMappingItem(
    val key: String,           // e.g. "HDFC Bank**1234"
    val displayName: String,   // e.g. "HDFC Bank ••••1234"
    val isCreditCard: Boolean = false
)

enum class DownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    ERROR_INSUFFICIENT_SPACE
}
