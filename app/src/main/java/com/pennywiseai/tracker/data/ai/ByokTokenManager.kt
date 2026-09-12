package com.pennywiseai.tracker.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ByokTokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_FILE_NAME = "byok_secure_prefs"
        private const val KEY_API_KEY = "byok_api_key"
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
        } catch (t: Throwable) {
            context.getSharedPreferences("${PREFS_FILE_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _apiKeyFlow = MutableStateFlow(runCatching { getApiKey() }.getOrNull())
    val apiKeyFlow: StateFlow<String?> = _apiKeyFlow.asStateFlow()

    private val _hasKeyFlow = MutableStateFlow(hasKey())
    val hasKeyFlow: StateFlow<Boolean> = _hasKeyFlow.asStateFlow()

    fun saveApiKey(key: String?) {
        encryptedPrefs.edit().apply {
            if (key.isNullOrBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, key.trim())
            apply()
        }
        _apiKeyFlow.value = getApiKey()
        _hasKeyFlow.value = hasKey()
    }

    fun getApiKey(): String? = encryptedPrefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun hasKey(): Boolean = !getApiKey().isNullOrBlank()

    fun clear() = saveApiKey(null)
}
