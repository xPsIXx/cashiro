package com.pennywiseai.tracker.data.firefly

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.preferences.dataStore
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * S4 regression: saving an `http://` Firefly base URL used to pass through
 * silently — the Bearer PAT would then travel plaintext to whatever host was
 * configured (FireflyClient.normalizeBaseUrl only https-prefixes scheme-less
 * URLs). The repository now records a persistent warning state next to the
 * URL (surfaced through `insecureBaseUrlWarningFlow`, same pattern as
 * `lastSyncErrorFlow`) that survives restarts and clears itself the moment a
 * safe URL replaces the insecure one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyPreferencesInsecureUrlTest {

    private class WorkingTokenManager(private val ctx: Context) : FireflyTokenManager(ctx) {
        override fun createEncryptedPreferences(): SharedPreferences =
            ctx.getSharedPreferences("firefly_secure_prefs_s4_test", Context.MODE_PRIVATE)
    }

    private val storeCounter = AtomicInteger(0)

    private fun buildRepository(context: Context): Triple<FireflyPreferencesRepository, DataStore<Preferences>, File> {
        val storeFile = File(context.filesDir, "s4_store_${storeCounter.incrementAndGet()}.preferences_pb")
        storeFile.delete()
        val fireflyDataStore = PreferenceDataStoreFactory.create { storeFile }
        val repository = FireflyPreferencesRepository(
            context,
            fireflyDataStore,
            mockk<UserPreferencesRepository>(relaxed = true),
            WorkingTokenManager(context)
        )
        return Triple(repository, fireflyDataStore, storeFile)
    }

    @Test
    fun savingHttpBaseUrlSetsThePersistentWarningAndHttpsClearsIt() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val (repository, store, storeFile) = buildRepository(context)
        try {
            // https first: no warning.
            repository.setBaseUrl("https://ff.example.org")
            assertNull("https must not raise the warning", repository.insecureBaseUrlWarningFlow.first())

            // Explicit http://: warning appears and is stored, not just in-memory.
            repository.setBaseUrl("http://192.168.1.10:8080")
            val warning = repository.insecureBaseUrlWarningFlow.first()
            assertNotNull("http:// must raise the persistent warning", warning!!)
            assertTrue(warning.contains("HTTP"))

            // A scheme-less URL is normalized to https by FireflyClient: no warning.
            repository.setBaseUrl("ff.example.org")
            assertNull("scheme-less URL normalizes to https — no warning", repository.insecureBaseUrlWarningFlow.first())

            // Back to http: warning again (state re-arms on every insecure save).
            repository.setBaseUrl("http://ff.lan")
            assertNotNull(repository.insecureBaseUrlWarningFlow.first())

            // Replacing it with https clears it.
            repository.setBaseUrl("https://ff.example.org")
            assertNull(repository.insecureBaseUrlWarningFlow.first())

            // Removing the URL clears it too.
            repository.setBaseUrl("http://ff.lan")
            repository.setBaseUrl(null)
            assertNull(repository.insecureBaseUrlWarningFlow.first())
        } finally {
            runCatching { store.close() }
            storeFile.delete()
        }
    }

    @Test
    fun warningIsPersistedAcrossDataStoreInstances() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val (repository, store, storeFile) = buildRepository(context)
        repository.setBaseUrl("http://192.168.1.10:8080")
        assertNotNull(repository.insecureBaseUrlWarningFlow.first())
        // DataStore writes durably on edit; close cleanly (one live instance
        // per file), then hand the SAME file to a brand-new repository: the
        // warning must come back from disk, like any persisted preference.
        store.close()

        val freshStore = PreferenceDataStoreFactory.create { storeFile }
        try {
            val freshRepository = FireflyPreferencesRepository(
                context,
                freshStore,
                mockk<UserPreferencesRepository>(relaxed = true),
                WorkingTokenManager(context)
            )
            assertEquals(
                "the warning state must survive a restart exactly like lastSyncErrorFlow does",
                FireflyPreferencesRepository.INSECURE_BASE_URL_WARNING_TEXT,
                freshRepository.insecureBaseUrlWarningFlow.first()
            )
        } finally {
            runCatching { freshStore.close() }
            storeFile.delete()
        }
    }

    @Test
    fun migratedLegacyHttpUrlCarriesTheWarningToo() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val tokenKey = stringPreferencesKey("firefly_access_token")
        context.dataStore.edit { prefs ->
            prefs[tokenKey] = "PAT-S4-TEST-TOKEN-LONG-ENOUGH"
            prefs[stringPreferencesKey("firefly_base_url")] = "http://ff.home.lan"
            prefs[booleanPreferencesKey("firefly_sync_enabled")] = true
        }

        val (repository, store, storeFile) = buildRepository(context)
        try {
            repository.ensureLegacyMigration()
            assertEquals("http://ff.home.lan", repository.baseUrlFlow.first())
            assertNotNull(
                "a migrated http:// URL must raise the same warning a fresh save would",
                repository.insecureBaseUrlWarningFlow.first()
            )
        } finally {
            runCatching { store.close() }
            storeFile.delete()
            context.dataStore.edit { it.remove(tokenKey) }
        }
    }
}
