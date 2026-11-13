package com.pennywiseai.tracker.data.firefly

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.preferences.dataStore
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * S1 regression: `ensureLegacyMigration()` copied the legacy plaintext
 * `firefly_access_token` from the app DataStore into secure storage but never
 * removed the cleartext key — the PAT lingered cleartext-at-rest forever.
 *
 * After the fix the key must be gone from the app DataStore once (and only
 * once) the token is durably stored, migration stays idempotent, the secure
 * copy is never destroyed, and a leftover plaintext token on an already
 * migrated install (copy-then-crash) is still cleaned up.
 *
 * The token manager uses a test subclass with a deterministic working
 * "secure" store: the Robolectric sandbox does not guarantee an Android
 * Keystore, and this test is about the DataStore cleanup semantics, not the
 * cipher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyLegacyTokenDeletionTest {

    // Deliberately an inner class that reaches the Application through
    // RuntimeEnvironment instead of its own constructor property:
    // FireflyTokenManager seeds _accessTokenFlow/_baseUrlFlow in property
    // initialisers, which run BEFORE a subclass's constructor state is assigned
    // (a classic this-escape). Reading a subclass `val ctx` from the overridden
    // seam at that moment NPEs, the lazy swallows it and the manager permanently
    // reports "secure storage unavailable" — which is exactly why the original
    // version of this test could never observe the cleartext PAT being deleted.
    private inner class WorkingTokenManager :
        FireflyTokenManager(org.robolectric.RuntimeEnvironment.getApplication()) {
        override fun createEncryptedPreferences(): SharedPreferences =
            // Cleared first: Robolectric reuses the app data dir across test classes
            // in a sandbox, and a stale secure copy would flip ensureLegacyMigration
            // into its already-migrated cleanup path.
            org.robolectric.RuntimeEnvironment.getApplication()
                .getSharedPreferences("firefly_secure_prefs_s1_test", Context.MODE_PRIVATE)
                .also { it.edit().clear().apply() }
    }

    @Test
    fun plaintextPatIsRemovedFromAppDataStoreAfterSecureMigrationAndNeverReappears() {
        runBlocking {
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val tokenKey = stringPreferencesKey("firefly_access_token")
            val urlKey = stringPreferencesKey("firefly_base_url")
            val enabledKey = booleanPreferencesKey("firefly_sync_enabled")
            val migratedKey = booleanPreferencesKey("cashiro_firefly_legacy_migrated")

            // The legacy install state: a plaintext PAT sitting in the app DataStore.
            context.dataStore.edit { prefs ->
                prefs[tokenKey] = "PAT-LEGACY-PLAINTEXT-123"
                prefs[urlKey] = "https://ff.example.org"
                prefs[enabledKey] = true
            }

            val tokenManager = WorkingTokenManager()
            // Unique sidecar name + explicit delete: the migration marker lives here
            // and a leftover marker from another run would skip the migration itself.
            val sidecarFile = File(context.filesDir, "s1_sidecar_store_${System.nanoTime()}.preferences_pb")
            sidecarFile.delete()
            val fireflyDataStore = PreferenceDataStoreFactory.create { sidecarFile }
            val repository = FireflyPreferencesRepository(
                context,
                fireflyDataStore,
                mockk<UserPreferencesRepository>(relaxed = true),
                tokenManager
            )

            // ── First launch after upgrade ────────────────────────────────────
            repository.ensureLegacyMigration()

            assertEquals(
                "the token must now live in secure storage",
                "PAT-LEGACY-PLAINTEXT-123",
                tokenManager.getAccessToken()
            )
            assertNull(
                "the cleartext PAT must be gone from the app DataStore",
                context.dataStore.data.first()[tokenKey]
            )
            assertEquals("https://ff.example.org", repository.baseUrlFlow.first())
            assertEquals(true, repository.syncEnabledFlow.first())
            assertEquals(
                "the migration marker must be set",
                true,
                fireflyDataStore.data.first()[migratedKey]
            )

            // ── Idempotent re-runs: nothing re-seeded, nothing destroyed ─────
            repository.ensureLegacyMigration()
            repository.ensureLegacyMigration()
            assertEquals("PAT-LEGACY-PLAINTEXT-123", tokenManager.getAccessToken())
            assertNull(context.dataStore.data.first()[tokenKey])

            // ── Copy-then-crash residue: already-migrated store, leftover key ─
            context.dataStore.edit { it[tokenKey] = "PAT-LEFTOVER-FROM-CRASH" }
            repository.ensureLegacyMigration()
            assertNull(
                "the cleanup pass must remove a leftover cleartext token",
                context.dataStore.data.first()[tokenKey]
            )
            assertEquals(
                "…without ever touching the secure copy",
                "PAT-LEGACY-PLAINTEXT-123",
                tokenManager.getAccessToken()
            )
            assertTrue(repository.getAccessToken()!!.isNotBlank())
        }
    }
}
