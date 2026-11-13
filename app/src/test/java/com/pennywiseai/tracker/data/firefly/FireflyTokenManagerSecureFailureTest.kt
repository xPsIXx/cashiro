package com.pennywiseai.tracker.data.firefly

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * S2 regression: `FireflyTokenManager` used to catch every failure of
 * `EncryptedSharedPreferences.create` and silently fall back to a plaintext
 * `SharedPreferences` file — the Personal Access Token landed cleartext-at-rest
 * with no warning on Keystore-broken devices.
 *
 * The fallback file is gone. On forced creation failure the manager must:
 *  - keep the token usable for the current session (in-memory only),
 *  - surface a user-visible error state through `secureStorageErrorFlow`
 *    (mirrored by [FireflyPreferencesRepository.storageErrorFlow], the same
 *    error-state pattern as `lastSyncErrorFlow`),
 *  - write **no** `firefly_secure_prefs*` file to disk.
 *
 * The failure is forced via the `internal open createEncryptedPreferences`
 * seam rather than hoping the host Keystore misbehaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyTokenManagerSecureFailureTest {

    private class FailingTokenManager(context: Context) : FireflyTokenManager(context) {
        override fun createEncryptedPreferences(): SharedPreferences =
            throw SecurityException("simulated Android Keystore / MasterKey failure")
    }

    private fun sharedPrefsDir(context: Context): File? {
        val dataDir = context.applicationInfo?.dataDir
        val base = if (dataDir != null) File(dataDir) else context.filesDir.parentFile
        return base?.let { File(it, "shared_prefs") }
    }

    @Test
    fun forcedKeystoreFailureKeepsTokenInMemoryOnlySurfacesErrorAndWritesNoPlaintextFile() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = FailingTokenManager(context)

        manager.saveCredentials("https://ff.example", "SECRET-PAT-NO-DISK")

        assertNotNull("a user-visible error state must be surfaced", manager.secureStorageErrorFlow.value)
        assertTrue(
            "the error state must name the problem",
            manager.secureStorageErrorFlow.value!!.contains("secure storage", ignoreCase = true)
        )
        assertFalse("secure storage must honestly report itself unavailable", manager.isSecureStorageAvailable())

        // Usable for this session…
        assertEquals("SECRET-PAT-NO-DISK", manager.getAccessToken())
        assertEquals("https://ff.example", manager.getBaseUrl())
        assertTrue(manager.hasCredentials())

        // …but nothing Firefly-related was written to (plaintext) shared prefs.
        val names = sharedPrefsDir(context)?.list()?.toList() ?: emptyList()
        assertTrue(
            "no plaintext (or encrypted) Firefly prefs file may be created, found: $names",
            names.none { it.startsWith("firefly_secure_prefs") }
        )
        assertTrue(
            "the old plaintext fallback store must stay untouched",
            context.getSharedPreferences("${FALLBACK_NAME}", Context.MODE_PRIVATE).all.isEmpty()
        )

        manager.clearCredentials()
        assertNull(manager.getAccessToken())
        assertNotNull("clearing must not erase the user-visible storage error", manager.secureStorageErrorFlow.value)
    }

    @Test
    fun preferencesRepositoryMirrorsTheStorageErrorLikeSyncErrors() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = FailingTokenManager(context)
        val repository = FireflyPreferencesRepository(
            context,
            PreferenceDataStoreFactory.create { File(context.filesDir, "s2_sidecar_store.preferences_pb") },
            mockk<UserPreferencesRepository>(relaxed = true),
            manager
        )

        assertNotNull(
            "the settings layer must be able to observe the storage failure",
            repository.storageErrorFlow.first()
        )
        manager.saveCredentials("https://ff.example", "SECRET-PAT")
        assertEquals(
            "the repository must surface the exact manager error",
            manager.secureStorageErrorFlow.value,
            repository.storageErrorFlow.first()
        )
    }

    @Test
    fun realEncryptedStoragePathStillPersistsWhenKeystoreIsAvailable() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = FireflyTokenManager(context)
        manager.saveCredentials("https://ff.example", "REAL-PAT-12345")

        assumeTrue(
            "Robolectric may not provide an Android Keystore — the durable encrypted path is verified on device/CI",
            manager.isSecureStorageAvailable()
        )

        assertEquals("REAL-PAT-12345", manager.getAccessToken())
        assertTrue(manager.hasCredentials())
        assertNull("no error may be reported on the healthy path", manager.secureStorageErrorFlow.value)

        val names = sharedPrefsDir(context)?.list()?.toList() ?: emptyList()
        assertTrue(
            "the healthy path must still write the encrypted prefs file, found: $names",
            names.any { it.startsWith("firefly_secure_prefs") }
        )
    }

    private companion object {
        const val FALLBACK_NAME = "firefly_secure_prefs_fallback"
    }
}
