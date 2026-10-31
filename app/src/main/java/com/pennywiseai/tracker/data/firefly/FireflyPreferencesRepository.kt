package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.preferences.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences store OWNED by the Cashiro Firefly add-on.
 *
 * Everything Firefly-related moves out of the app's
 * [UserPreferencesRepository] into this dedicated DataStore
 * (`cashiro_firefly_prefs`), so the app prefs stay pristine upstream files.
 *
 * Key names mirror the legacy app-DataStore keys (still prefixed `firefly_`)
 * so the one-time legacy migration is a raw-name copy and external tooling
 * keeps the same semantics. The access token is NOT stored here; it lives in
 * [FireflyTokenManager]'s EncryptedSharedPreferences file (as before).
 */
@Singleton
class FireflyPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fireflyDataStore: DataStore<Preferences>,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val fireflyTokenManager: FireflyTokenManager
) {

    private object Keys {
        // Sidecar store keys (same raw names as the legacy app-DataStore keys).
        val SYNC_ENABLED = booleanPreferencesKey("firefly_sync_enabled")
        val BASE_URL = stringPreferencesKey("firefly_base_url")
        val DEFAULT_ASSET_ACCOUNT = stringPreferencesKey("firefly_default_asset_account")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("firefly_last_sync_timestamp")
        val LAST_SYNC_ERROR = stringPreferencesKey("firefly_last_sync_error")
        val ACCOUNT_MAPPINGS = stringPreferencesKey("firefly_account_mappings")
        val CATEGORY_MAPPINGS = stringPreferencesKey("firefly_category_mappings")
        val INCLUDE_RAW_SMS = booleanPreferencesKey("firefly_include_raw_sms")
        val AUTO_SYNC_INTERVAL = stringPreferencesKey("firefly_auto_sync_interval")
        val MIGRATION_RAN = booleanPreferencesKey("firefly_migration_ran")

        // Add-on flag: legacy app-DataStore keys have been copied here once.
        val LEGACY_MIGRATED = booleanPreferencesKey("cashiro_firefly_legacy_migrated")
    }

    // ── Flows ─────────────────────────────────────────────────────────────────

    val syncEnabledFlow: Flow<Boolean> = fireflyDataStore.data
        .map { it[Keys.SYNC_ENABLED] ?: false }

    val baseUrlFlow: Flow<String?> = fireflyDataStore.data
        .map { it[Keys.BASE_URL] }

    /** Token lives in encrypted storage, not DataStore. */
    val accessTokenFlow: Flow<String?> = fireflyTokenManager.accessTokenFlow

    val defaultAssetAccountFlow: Flow<String?> = fireflyDataStore.data
        .map { it[Keys.DEFAULT_ASSET_ACCOUNT] }

    val lastSyncTimestampFlow: Flow<Long?> = fireflyDataStore.data
        .map { it[Keys.LAST_SYNC_TIMESTAMP] }

    val lastSyncErrorFlow: Flow<String?> = fireflyDataStore.data
        .map { it[Keys.LAST_SYNC_ERROR] }

    val autoSyncIntervalFlow: Flow<String> = fireflyDataStore.data
        .map { it[Keys.AUTO_SYNC_INTERVAL] ?: "never" }

    val migrationRanFlow: Flow<Boolean> = fireflyDataStore.data
        .map { it[Keys.MIGRATION_RAN] ?: false }

    // ── One-time legacy migration ─────────────────────────────────────────────

    /**
     * Copies the legacy `firefly_*` keys (name for name) from the APP DataStore
     * into the sidecar store, then sets [Keys.LEGACY_MIGRATED]. Also ports a
     * legacy plain-text DataStore access token into secure storage, mirroring
     * the old `UserPreferencesRepository.migrateTokenToSecureIfNeeded()`.
     *
     * Idempotent and safe to call on every launch; keys the user never wrote
     * are simply absent. DataStore keeps stale keys on disk after they are
     * removed from code, so the raw-name copy always finds them.
     */
    suspend fun ensureLegacyMigration() {
        val alreadyDone = fireflyDataStore.data.first()[Keys.LEGACY_MIGRATED] ?: false
        if (alreadyDone) return

        val appPrefs = context.dataStore.data.first()
        val legacy = legacyFireflyValues(appPrefs)

        // Token first (best effort): port a legacy plain-text DataStore token
        // into secure storage, mirroring the old
        // `UserPreferencesRepository.migrateTokenToSecureIfNeeded()`.
        val legacyToken = appPrefs[stringPreferencesKey("firefly_access_token")]
        val legacyUrl = appPrefs[stringPreferencesKey("firefly_base_url")]
        if (fireflyTokenManager.getAccessToken().isNullOrBlank() && !legacyToken.isNullOrBlank()) {
            fireflyTokenManager.saveCredentials(legacyUrl, legacyToken)
        }

        fireflyDataStore.edit { preferences ->
            legacy.syncEnabled?.let { preferences[Keys.SYNC_ENABLED] = it }
            legacy.baseUrl?.let { preferences[Keys.BASE_URL] = it }
            legacy.defaultAssetAccount?.let { preferences[Keys.DEFAULT_ASSET_ACCOUNT] = it }
            legacy.lastSyncTimestamp?.let { preferences[Keys.LAST_SYNC_TIMESTAMP] = it }
            legacy.lastSyncError?.let { preferences[Keys.LAST_SYNC_ERROR] = it }
            legacy.accountMappings?.let { preferences[Keys.ACCOUNT_MAPPINGS] = it }
            legacy.categoryMappings?.let { preferences[Keys.CATEGORY_MAPPINGS] = it }
            legacy.includeRawSms?.let { preferences[Keys.INCLUDE_RAW_SMS] = it }
            legacy.autoSyncInterval?.let { preferences[Keys.AUTO_SYNC_INTERVAL] = it }
            legacy.migrationRan?.let { preferences[Keys.MIGRATION_RAN] = it }
            preferences[Keys.LEGACY_MIGRATED] = true
        }
    }

    // ── Setters ──────────────────────────────────────────────────────────────

    suspend fun setSyncEnabled(enabled: Boolean) {
        fireflyDataStore.edit { preferences ->
            preferences[Keys.SYNC_ENABLED] = enabled
        }
        if (!enabled) {
            // Clear sensitive credentials from encrypted storage when disabling.
            fireflyTokenManager.clearCredentials()
        }
    }

    suspend fun setBaseUrl(url: String?) {
        fireflyDataStore.edit { preferences ->
            if (url.isNullOrBlank()) {
                preferences.remove(Keys.BASE_URL)
            } else {
                preferences[Keys.BASE_URL] = url.trim().trimEnd('/')
            }
        }
    }

    suspend fun setDefaultAssetAccount(account: String?) {
        fireflyDataStore.edit { preferences ->
            if (account.isNullOrBlank()) {
                preferences.remove(Keys.DEFAULT_ASSET_ACCOUNT)
            } else {
                preferences[Keys.DEFAULT_ASSET_ACCOUNT] = account.trim()
            }
        }
    }

    suspend fun updateLastSync(timestamp: Long, error: String? = null) {
        fireflyDataStore.edit { preferences ->
            preferences[Keys.LAST_SYNC_TIMESTAMP] = timestamp
            if (error != null) {
                preferences[Keys.LAST_SYNC_ERROR] = error
            } else {
                preferences.remove(Keys.LAST_SYNC_ERROR)
            }
        }
    }

    suspend fun clearLastError() {
        fireflyDataStore.edit { preferences ->
            preferences.remove(Keys.LAST_SYNC_ERROR)
        }
    }

    suspend fun setAutoSyncInterval(interval: String) {
        fireflyDataStore.edit { preferences ->
            preferences[Keys.AUTO_SYNC_INTERVAL] = interval
        }
    }

    suspend fun setMigrationRan(ran: Boolean) {
        fireflyDataStore.edit { preferences ->
            preferences[Keys.MIGRATION_RAN] = ran
        }
    }

    // ── Account mappings (BANK**LAST4 -> Firefly asset account name) ────────

    val accountMappingsFlow: Flow<Map<String, String>> = fireflyDataStore.data
        .map { preferences -> parseMappingJson(preferences[Keys.ACCOUNT_MAPPINGS] ?: "{}") }

    suspend fun setAccountMapping(accountKey: String, fireflyAccountName: String) {
        fireflyDataStore.edit { preferences ->
            val current = preferences[Keys.ACCOUNT_MAPPINGS]?.let {
                try {
                    it.trim('{', '}').split(",").associate { p ->
                        val parts = p.split(":", limit = 2)
                        parts[0].trim('"') to parts[1].trim('"')
                    }
                } catch (_: Exception) { emptyMap() }
            } ?: emptyMap()

            val updated = current.toMutableMap().apply {
                if (fireflyAccountName.isBlank()) remove(accountKey) else put(accountKey, fireflyAccountName.trim())
            }

            preferences[Keys.ACCOUNT_MAPPINGS] = toMappingJson(updated)
        }
    }

    suspend fun clearAccountMapping(accountKey: String) {
        setAccountMapping(accountKey, "")
    }

    suspend fun clearAllAccountMappings() {
        fireflyDataStore.edit { preferences ->
            preferences.remove(Keys.ACCOUNT_MAPPINGS)
        }
    }

    // ── Category mappings (PennyWise Category -> Firefly Category Name) ────

    val categoryMappingsFlow: Flow<Map<String, String>> = fireflyDataStore.data
        .map { preferences -> parseMappingJson(preferences[Keys.CATEGORY_MAPPINGS] ?: "{}") }

    suspend fun setCategoryMapping(pennywiseCategory: String, fireflyCategory: String) {
        fireflyDataStore.edit { preferences ->
            val current = preferences[Keys.CATEGORY_MAPPINGS]?.let {
                try {
                    it.trim('{', '}').split(",").associate { p ->
                        val parts = p.split(":", limit = 2)
                        parts[0].trim('"') to parts[1].trim('"')
                    }
                } catch (_: Exception) { emptyMap() }
            } ?: emptyMap()

            val updated = current.toMutableMap().apply {
                if (fireflyCategory.isBlank()) remove(pennywiseCategory)
                else put(pennywiseCategory.trim(), fireflyCategory.trim())
            }

            preferences[Keys.CATEGORY_MAPPINGS] = toMappingJson(updated)
        }
    }

    suspend fun clearCategoryMapping(pennywiseCategory: String) {
        setCategoryMapping(pennywiseCategory, "")
    }

    // ── Raw SMS in Firefly notes ────────────────────────────────────────────

    val includeRawSmsFlow: Flow<Boolean> = fireflyDataStore.data
        .map { it[Keys.INCLUDE_RAW_SMS] ?: true }

    suspend fun setIncludeRawSms(include: Boolean) {
        fireflyDataStore.edit { preferences ->
            preferences[Keys.INCLUDE_RAW_SMS] = include
        }
    }

    // ── Token passthrough (encrypted storage, not DataStore) ─────────────────

    suspend fun saveCredentials(url: String, token: String) {
        fireflyTokenManager.saveCredentials(url, token)
    }

    fun getBaseUrl(): String? = fireflyTokenManager.getBaseUrl()

    fun getAccessToken(): String? = fireflyTokenManager.getAccessToken()

    private fun parseMappingJson(json: String): Map<String, String> {
        return try {
            if (json == "{}") emptyMap()
            else json.trim('{', '}')
                .split(",")
                .mapNotNull { pair ->
                    val parts = pair.split(":", limit = 2)
                    if (parts.size == 2) {
                        val k = parts[0].trim().trim('"')
                        val v = parts[1].trim().trim('"')
                        if (k.isNotBlank() && v.isNotBlank()) k to v else null
                    } else null
                }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun toMappingJson(map: Map<String, String>): String {
        return if (map.isEmpty()) "{}"
        else map.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
    }
}

/**
 * Pure, Context-free mapping of the old app-DataStore `firefly_*` keys into
 * typed values. Extracted so the legacy migration can be unit-tested without
 * an Android Context.
 * @see FireflyPreferencesRepository.ensureLegacyMigration
 */
internal data class LegacyFireflyValues(
    val syncEnabled: Boolean? = null,
    val baseUrl: String? = null,
    val defaultAssetAccount: String? = null,
    val lastSyncTimestamp: Long? = null,
    val lastSyncError: String? = null,
    val accountMappings: String? = null,
    val categoryMappings: String? = null,
    val includeRawSms: Boolean? = null,
    val autoSyncInterval: String? = null,
    val migrationRan: Boolean? = null
)

internal fun legacyFireflyValues(appPrefs: Preferences): LegacyFireflyValues {
    return LegacyFireflyValues(
        syncEnabled = appPrefs[booleanPreferencesKey("firefly_sync_enabled")],
        baseUrl = appPrefs[stringPreferencesKey("firefly_base_url")],
        defaultAssetAccount = appPrefs[stringPreferencesKey("firefly_default_asset_account")],
        lastSyncTimestamp = appPrefs[longPreferencesKey("firefly_last_sync_timestamp")],
        lastSyncError = appPrefs[stringPreferencesKey("firefly_last_sync_error")],
        accountMappings = appPrefs[stringPreferencesKey("firefly_account_mappings")],
        categoryMappings = appPrefs[stringPreferencesKey("firefly_category_mappings")],
        includeRawSms = appPrefs[booleanPreferencesKey("firefly_include_raw_sms")],
        autoSyncInterval = appPrefs[stringPreferencesKey("firefly_auto_sync_interval")],
        migrationRan = appPrefs[booleanPreferencesKey("firefly_migration_ran")]
    )
}
