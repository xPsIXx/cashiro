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

    /** S4 warning text stored under [Keys.INSECURE_BASE_URL_WARNING]. */
    internal companion object {
        const val INSECURE_BASE_URL_WARNING_TEXT =
            "Firefly base URL uses plain HTTP: the Bearer access token travels unencrypted to this host. " +
                "Accept only for a trusted LAN self-host; prefer https:// everywhere else."
    }

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
        val INSECURE_BASE_URL_WARNING = stringPreferencesKey("firefly_insecure_base_url_warning")

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

    /**
     * S2: user-visible error state for the credential store, mirroring how
     * `lastSyncErrorFlow` surfaces sync failures to the settings UI. Non-null
     * while [FireflyTokenManager] could not open EncryptedSharedPreferences —
     * in that state the token is session-in-memory only and is deliberately
     * NOT persisted anywhere.
     */
    val storageErrorFlow: Flow<String?> = fireflyTokenManager.secureStorageErrorFlow

    val defaultAssetAccountFlow: Flow<String?> = fireflyDataStore.data
        .map { it[Keys.DEFAULT_ASSET_ACCOUNT] }

    val lastSyncTimestampFlow: Flow<Long?> = fireflyDataStore.data
        .map { it[Keys.LAST_SYNC_TIMESTAMP] }

    val lastSyncErrorFlow: Flow<String?> = fireflyDataStore.data
        .map { it[Keys.LAST_SYNC_ERROR] }

    /**
     * S4: persistent warning state for an `http://` Firefly base URL, surfaced
     * exactly like [lastSyncErrorFlow] (the settings UI renders both cards).
     * Non-null while the stored URL uses plain HTTP: the Bearer PAT would
     * travel unencrypted to whatever host is configured, so the user must
     * acknowledge the exposure (a LAN-only self-host is a legitimate choice,
     * which is why this warns instead of blocking).
     *
     * Set by [setBaseUrl] at save time, so the warning survives app restarts;
     * cleared automatically when the URL is replaced with https:// or removed.
     */
    val insecureBaseUrlWarningFlow: Flow<String?> = fireflyDataStore.data
        .map { it[Keys.INSECURE_BASE_URL_WARNING] }

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
     *
     * S1: once the token is verifiably in secure storage, the plaintext
     * `firefly_access_token` key is **removed from the app DataStore** so the
     * PAT no longer lingers cleartext-at-rest after the upgrade. Removal only
     * ever happens when secure storage provably holds a token (freshly
     * migrated or already present); the secure copy itself is never touched,
     * and the plaintext key is kept if the secure copy failed (better to keep
     * one working copy than to destroy the user's only one). If a previous
     * launch crashed between "copied" and "removed", the already-migrated
     * early-return path still performs the cleanup.
     */
    suspend fun ensureLegacyMigration() {
        val alreadyDone = fireflyDataStore.data.first()[Keys.LEGACY_MIGRATED] ?: false
        val legacyTokenKey = stringPreferencesKey("firefly_access_token")
        val appPrefs = context.dataStore.data.first()
        val legacyToken = appPrefs[legacyTokenKey]

        if (alreadyDone) {
            // S1 cleanup pass: migration completed earlier, but the cleartext
            // PAT must not outlive it. Only when secure storage is durably
            // available — an in-memory-only session copy (S2) must not be
            // allowed to destroy the user's only durable credential.
            if (
                !legacyToken.isNullOrBlank() &&
                !fireflyTokenManager.getAccessToken().isNullOrBlank() &&
                fireflyTokenManager.isSecureStorageAvailable()
            ) {
                context.dataStore.edit { it.remove(legacyTokenKey) }
            }
            return
        }

        val legacy = legacyFireflyValues(appPrefs)

        // Token first (best effort): port a legacy plain-text DataStore token
        // into secure storage, mirroring the old
        // `UserPreferencesRepository.migrateTokenToSecureIfNeeded()`.
        val legacyUrl = appPrefs[stringPreferencesKey("firefly_base_url")]
        if (fireflyTokenManager.getAccessToken().isNullOrBlank() && !legacyToken.isNullOrBlank()) {
            fireflyTokenManager.saveCredentials(legacyUrl, legacyToken)
        }

        fireflyDataStore.edit { preferences ->
            legacy.syncEnabled?.let { preferences[Keys.SYNC_ENABLED] = it }
            legacy.baseUrl?.let { preferences[Keys.BASE_URL] = it }
            // S4: a migrated http:// URL carries the same warning a fresh
            // save would produce.
            if (legacy.baseUrl?.startsWith("http://") == true) {
                preferences[Keys.INSECURE_BASE_URL_WARNING] = INSECURE_BASE_URL_WARNING_TEXT
            }
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

        // S1: delete the cleartext PAT from the app DataStore — but only after
        // confirming secure storage actually holds a token DURABLY. If the
        // secure store is unavailable (S2 in-memory fallback) the plaintext
        // key is kept: the next launch retries the migration, and the user's
        // credential is never destroyed.
        if (
            !legacyToken.isNullOrBlank() &&
            !fireflyTokenManager.getAccessToken().isNullOrBlank() &&
            fireflyTokenManager.isSecureStorageAvailable()
        ) {
            context.dataStore.edit { it.remove(legacyTokenKey) }
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
        val stored = url?.trim()?.trimEnd('/')
        fireflyDataStore.edit { preferences ->
            if (stored.isNullOrBlank()) {
                preferences.remove(Keys.BASE_URL)
                preferences.remove(Keys.INSECURE_BASE_URL_WARNING)
            } else {
                preferences[Keys.BASE_URL] = stored
                // S4: an explicit http:// base URL stores the persistent
                // warning alongside it; https:// (or a scheme-less URL, which
                // FireflyClient normalizes to https://) clears it.
                if (stored.startsWith("http://")) {
                    preferences[Keys.INSECURE_BASE_URL_WARNING] = INSECURE_BASE_URL_WARNING_TEXT
                } else {
                    preferences.remove(Keys.INSECURE_BASE_URL_WARNING)
                }
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
