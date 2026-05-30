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

    /** Saves Firefly credentials securely (token in EncryptedSharedPreferences) */
    fun saveFireflyCredentials(url: String, token: String, defaultAsset: String?) {
        viewModelScope.launch {
            userPreferencesRepository.setFireflyBaseUrl(url)
            if (defaultAsset != null) {
                userPreferencesRepository.setFireflyDefaultAssetAccount(defaultAsset)
            }
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
                val prefs = userPreferencesRepository.userPreferences.first()
                val url = prefs.fireflyBaseUrl?.takeIf { it.isNotBlank() }
                val token = prefs.fireflyAccessToken?.takeIf { it.isNotBlank() }

                if (url == null || token == null) {
                    onResult("Firefly not configured")
                    return@launch
                }

                val thirtyDaysAgo = LocalDateTime.now().minusDays(30)
                val unsynced = transactionRepository.getTransactionsBetweenDatesList(thirtyDaysAgo, LocalDateTime.now())
                    .filter { it.fireflySyncedAt == null && it.fireflyLastError == null }

                if (unsynced.isEmpty()) {
                    onResult("No unsynced transactions in the last 30 days")
                    return@launch
                }

                val accountMappings = userPreferencesRepository.fireflyAccountMappingsFlow.first()
                val categoryMappings = userPreferencesRepository.fireflyCategoryMappingsFlow.first()
                val includeRaw = userPreferencesRepository.fireflyIncludeRawSmsFlow.first()

                var successCount = 0
                unsynced.forEach { tx ->
                    val result = fireflyClient.syncTransaction(
                        transaction = tx,
                        baseUrl = url,
                        accessToken = token,
                        defaultAssetAccount = prefs.fireflyDefaultAssetAccount,
                        accountMappings = accountMappings,
                        categoryMappings = categoryMappings,
                        includeRawSmsInNotes = includeRaw
                    )
                    if (result is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Success) {
                        transactionRepository.markFireflySynced(tx.id, result.fireflyId)
                        successCount++
                    }
                }

                onResult("Synced $successCount of ${unsynced.size} transactions from last 30 days")
            } catch (e: Exception) {
                onResult("Error syncing last 30 days: ${e.message}")
            }
        }
    }

    // Send a test transaction to Firefly
    fun sendTestTransaction(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val prefs = userPreferencesRepository.userPreferences.first()
                val url = prefs.fireflyBaseUrl?.takeIf { it.isNotBlank() }
                val token = prefs.fireflyAccessToken?.takeIf { it.isNotBlank() }

                if (url == null || token == null) {
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
                    currency = prefs.baseCurrency,
                    transactionHash = "TEST_${System.currentTimeMillis()}"
                )

                val accountMappings = userPreferencesRepository.fireflyAccountMappingsFlow.first()
                val categoryMappings = userPreferencesRepository.fireflyCategoryMappingsFlow.first()
                val includeRaw = userPreferencesRepository.fireflyIncludeRawSmsFlow.first()

                val result = fireflyClient.syncTransaction(
                    transaction = testTx,
                    baseUrl = url,
                    accessToken = token,
                    defaultAssetAccount = prefs.fireflyDefaultAssetAccount,
                    accountMappings = accountMappings,
                    categoryMappings = categoryMappings,
                    includeRawSmsInNotes = includeRaw
                )

                when (result) {
                    is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Success -> onResult("Test transaction sent successfully!")
                    is com.pennywiseai.tracker.data.firefly.FireflyClient.SyncResult.Error -> onResult("Failed: ${result.message}")
                    else -> onResult("Skipped")
                }
            } catch (e: Exception) {
                onResult("Error: ${e.message}")
            }
        }
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
                        _importExportMessage.value = "Import successful! Imported ${result.importedTransactions} transactions, ${result.importedCategories} categories. Skipped ${result.skippedDuplicates} duplicates."
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
            userPreferencesRepository.updateBaseCurrency(currency)
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
