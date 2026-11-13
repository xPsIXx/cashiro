package com.pennywiseai.tracker.data.firefly

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for Firefly III Personal Access Token using EncryptedSharedPreferences.
 * This provides better protection than plain DataStore for sensitive credentials.
 *
 * A [StateFlow] is exposed so consumers that previously observed the DataStore token
 * can now observe secure storage directly without needing a second source of truth.
 *
 * S2: if the EncryptedSharedPreferences file cannot be created (Keystore/MasterKey
 * failure on some OEM builds), the token is **never** written to a plaintext prefs
 * file. The manager degrades to *in-memory-only for the current session* and
 * surfaces a user-visible "secure storage unavailable" error state through
 * [secureStorageErrorFlow] (surfaced to the UI via
 * [FireflyPreferencesRepository.storageErrorFlow]).
 */
@Singleton
open class FireflyTokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "FireflyTokenManager"
        private const val PREFS_FILE_NAME = "firefly_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "firefly_access_token"
        private const val KEY_BASE_URL = "firefly_base_url"

        /** Surfaced (via [secureStorageErrorFlow]) when secure storage failed to open. */
        const val SECURE_STORAGE_UNAVAILABLE =
            "Firefly secure storage unavailable (keystore failure) — the token is kept in memory for this session only and is not persisted"
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * The encrypted prefs file, or null when it could not be created (S2).
     *
     * The old code fell back to a plaintext `SharedPreferences` file on any
     * failure, which persisted the PAT cleartext-at-rest with no warning. That
     * fallback file is gone: a failed creation is recorded in
     * [_secureStorageError] and storage stays null, so every persist path
     * below refuses to write the token to disk unencrypted.
     *
     * `internal open` is the JVM-test seam: tests subclass and force a failure
     * instead of relying on a Keystore being broken in the wild. Production
     * never overrides it.
     */
    private val encryptedPrefs: SharedPreferences? by lazy {
        try {
            createEncryptedPreferences()
        } catch (e: Exception) {
            _secureStorageError.value = SECURE_STORAGE_UNAVAILABLE
            Log.e(TAG, "EncryptedSharedPreferences unavailable — Firefly token will NOT be persisted in plaintext", e)
            null
        }
    }

    internal open fun createEncryptedPreferences(): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    /** Session-only storage used ONLY when [encryptedPrefs] is null (S2). Never touches disk. */
    @Volatile
    private var inMemoryBaseUrl: String? = null

    @Volatile
    private var inMemoryAccessToken: String? = null

    private val _secureStorageError = MutableStateFlow<String?>(null)

    /** Non-null while secure storage is broken; mirrors the `lastSyncErrorFlow` pattern. */
    val secureStorageErrorFlow: StateFlow<String?> = _secureStorageError.asStateFlow()

    /** False once the encrypted file has failed to open — callers must not expect persistence. */
    fun isSecureStorageAvailable(): Boolean = encryptedPrefs != null

    private val _accessTokenFlow = MutableStateFlow(getAccessToken())
    val accessTokenFlow: StateFlow<String?> = _accessTokenFlow.asStateFlow()

    private val _baseUrlFlow = MutableStateFlow(getBaseUrl())
    val baseUrlFlow: StateFlow<String?> = _baseUrlFlow.asStateFlow()

    fun saveCredentials(baseUrl: String?, accessToken: String?) {
        val prefs = encryptedPrefs
        if (prefs != null) {
            prefs.edit().apply {
                if (baseUrl != null) putString(KEY_BASE_URL, baseUrl) else remove(KEY_BASE_URL)
                if (accessToken != null) putString(KEY_ACCESS_TOKEN, accessToken) else remove(KEY_ACCESS_TOKEN)
                apply()
            }
            inMemoryBaseUrl = null
            inMemoryAccessToken = null
        } else {
            // S2: secure storage is unavailable. Persisting to a plaintext
            // SharedPreferences file is refused outright — the credentials
            // live in memory for the lifetime of this process only, and the
            // error state is surfaced so the UI can tell the user.
            if (_secureStorageError.value == null) {
                _secureStorageError.value = SECURE_STORAGE_UNAVAILABLE
            }
            Log.w(TAG, "Saving Firefly credentials in memory only — secure storage unavailable")
            inMemoryBaseUrl = baseUrl
            inMemoryAccessToken = accessToken
        }
        _baseUrlFlow.value = getBaseUrl()
        _accessTokenFlow.value = getAccessToken()
    }

    fun getBaseUrl(): String? = encryptedPrefs?.getString(KEY_BASE_URL, null) ?: inMemoryBaseUrl

    fun getAccessToken(): String? = encryptedPrefs?.getString(KEY_ACCESS_TOKEN, null) ?: inMemoryAccessToken

    fun clearCredentials() {
        encryptedPrefs?.edit()
            ?.remove(KEY_BASE_URL)
            ?.remove(KEY_ACCESS_TOKEN)
            ?.apply()
        inMemoryBaseUrl = null
        inMemoryAccessToken = null
        _baseUrlFlow.value = null
        _accessTokenFlow.value = null
    }

    fun hasCredentials(): Boolean {
        return !getBaseUrl().isNullOrBlank() && !getAccessToken().isNullOrBlank()
    }
}
