package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the Cashiro Firefly add-on. Everything here is add-on-owned;
 * the app's DI modules are untouched.
 */
@Module
@InstallIn(SingletonComponent::class)
object FireflyModule {

    /**
     * Single definition site of the sidecar preferences DataStore file. The
     * repository takes the instance via constructor so tests can inject any
     * [DataStore] implementation.
     */
    private val Context.fireflyPrefsDataStore by preferencesDataStore(name = "cashiro_firefly_prefs")

    @Provides
    @Singleton
    fun provideFireflyPrefsDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.fireflyPrefsDataStore

    @Provides
    @Singleton
    fun provideFireflySyncDatabase(
        @ApplicationContext context: Context
    ): FireflySyncDatabase {
        val database = Room.databaseBuilder(
            context,
            FireflySyncDatabase::class.java,
            FireflySyncDatabase.DATABASE_NAME
        ).build()
        FireflySyncDatabase.setInstance(database)
        return database
    }

    @Provides
    @Singleton
    fun provideFireflySyncDao(database: FireflySyncDatabase): FireflySyncDao {
        return database.fireflySyncDao()
    }

    /**
     * M4: the MAIN app database for the add-on engine's candidate query only
     * (see FireflySyncEngine.findSyncCandidates). It is an INPUT to the add-on:
     * the engine reads the main `transactions` table through Room's own open
     * connection and never writes app tables from it.
     *
     * No provider is needed here: the `PennyWiseDatabase` singleton is already
     * provided by `di/DatabaseModule.providePennyWiseDatabase`, so Hilt injects
     * it straight into the engine's new constructor parameter. What IS required
     * in that file (upstream-ownership boundary — see the task report) is that
     * the transaction hooks stay able to reach the engine, which they already
     * do; no edit to DatabaseModule is needed for this parameter.
     */
}
