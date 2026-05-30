package com.pennywiseai.tracker.data.firefly

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for Firefly III Personal Access Token using EncryptedSharedPreferences.
 * This provides better protection than plain DataStore for sensitive credentials.
 */
@Singleton
class FireflyTokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_FILE_NAME = "firefly_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "firefly_access_token"
        private const val KEY_BASE_URL = "firefly_base_url"
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to regular prefs if encryption fails (e.g. on some devices)
            context.getSharedPreferences("${PREFS_FILE_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    fun saveCredentials(baseUrl: String?, accessToken: String?) {
        encryptedPrefs.edit().apply {
            if (baseUrl != null) putString(KEY_BASE_URL, baseUrl) else remove(KEY_BASE_URL)
            if (accessToken != null) putString(KEY_ACCESS_TOKEN, accessToken) else remove(KEY_ACCESS_TOKEN)
            apply()
        }
    }

    fun getBaseUrl(): String? = encryptedPrefs.getString(KEY_BASE_URL, null)
    fun getAccessToken(): String? = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)

    fun clearCredentials() {
        encryptedPrefs.edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_ACCESS_TOKEN)
            .apply()
    }

    fun hasCredentials(): Boolean {
        return !getBaseUrl().isNullOrBlank() && !getAccessToken().isNullOrBlank()
    }
}