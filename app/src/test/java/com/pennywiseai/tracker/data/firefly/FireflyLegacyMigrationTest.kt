package com.pennywiseai.tracker.data.firefly

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-mapper test: the old app-DataStore `firefly_*` keys map into the
 * sidecar store's typed values exactly (name-for-name), and absent keys stay
 * null. No Android Context needed.
 */
class FireflyLegacyMigrationTest {

    @Test
    fun `maps all legacy firefly keys into typed values`() {
        val appPrefs = mutablePreferencesOf().apply {
            this[booleanPreferencesKey("firefly_sync_enabled")] = true
            this[stringPreferencesKey("firefly_base_url")] = "https://ff.example.org/"
            this[stringPreferencesKey("firefly_default_asset_account")] = "Checking"
            this[longPreferencesKey("firefly_last_sync_timestamp")] = 123456789L
            this[stringPreferencesKey("firefly_last_sync_error")] = "boom"
            this[stringPreferencesKey("firefly_account_mappings")] = "{\"HDFC**1234\":\"Checking\"}"
            this[stringPreferencesKey("firefly_category_mappings")] = "{\"Groceries\":\"Food\"}"
            this[booleanPreferencesKey("firefly_include_raw_sms")] = false
            this[stringPreferencesKey("firefly_auto_sync_interval")] = "daily"
            this[booleanPreferencesKey("firefly_migration_ran")] = true
        }

        val mapped = legacyFireflyValues(appPrefs)

        assertEquals(true, mapped.syncEnabled)
        assertEquals("https://ff.example.org/", mapped.baseUrl)
        assertEquals("Checking", mapped.defaultAssetAccount)
        assertEquals(123456789L, mapped.lastSyncTimestamp)
        assertEquals("boom", mapped.lastSyncError)
        assertEquals("{\"HDFC**1234\":\"Checking\"}", mapped.accountMappings)
        assertEquals("{\"Groceries\":\"Food\"}", mapped.categoryMappings)
        assertEquals(false, mapped.includeRawSms)
        assertEquals("daily", mapped.autoSyncInterval)
        assertEquals(true, mapped.migrationRan)
    }

    @Test
    fun `absent legacy keys map to null`() {
        val appPrefs = mutablePreferencesOf() // empty store
        val mapped = legacyFireflyValues(appPrefs)
        assertNull(mapped.syncEnabled)
        assertNull(mapped.baseUrl)
        assertNull(mapped.defaultAssetAccount)
        assertNull(mapped.lastSyncTimestamp)
        assertNull(mapped.lastSyncError)
        assertNull(mapped.accountMappings)
        assertNull(mapped.categoryMappings)
        assertNull(mapped.includeRawSms)
        assertNull(mapped.autoSyncInterval)
        assertNull(mapped.migrationRan)
    }
}
