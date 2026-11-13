package com.pennywiseai.tracker.data.firefly

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.pennywiseai.tracker.data.database.converter.Converters
import java.time.LocalDateTime

/**
 * Sidecar Room database owned by the Cashiro Firefly add-on.
 *
 * Holds all Firefly sync state (external ids, remote transaction ids, last
 * sync timestamps, errors) plus the in-app sync log. Keeping this in a
 * separate database means app entities, DAOs, migrations and preferences
 * stay pristine upstream files — upstream database bumps never require
 * re-migrating Firefly data, and merging upstream never collides here.
 *
 * Schema version is add-on-local: starts at 1 and only changes when the
 * ADD-ON changes its own schema. There are no migrations yet and NO
 * destructive fallback by design — a future schema change must ship an
 * explicit Migration in this file (or upgrade will crash, loudly and
 * correctly, rather than silently losing sync state).
 */
@Database(
    entities = [
        FireflyTransactionStateEntity::class,
        FireflyBudgetStateEntity::class,
        FireflySubscriptionStateEntity::class,
        FireflyLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class FireflySyncDatabase : RoomDatabase() {

    abstract fun fireflySyncDao(): FireflySyncDao

    companion object {
        const val DATABASE_NAME = "cashiro_firefly.db"

        @Volatile
        private var INSTANCE: FireflySyncDatabase? = null

        /**
         * Returns the singleton sidecar database. Used by non-Hilt components
         * (mirrors [com.pennywiseai.tracker.data.database.PennyWiseDatabase.getInstance]).
         */
        fun getInstance(context: Context): FireflySyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FireflySyncDatabase::class.java,
                    DATABASE_NAME
                ).build()
                INSTANCE = instance
                instance
            }
        }

        /** Used by the Hilt module to keep a single instance. */
        fun setInstance(database: FireflySyncDatabase) {
            INSTANCE = database
        }
    }
}

/**
 * Sync state for one local transaction.
 *
 * [localTransactionId] is the id of the row in the app's `transactions`
 * table; the row itself is owned by the app. All other columns describe the
 * mirrored Firefly III row.
 */
@Entity(tableName = "firefly_transaction_state")
data class FireflyTransactionStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "localTransactionId")
    val localTransactionId: Long,
    @ColumnInfo(name = "externalId")
    val externalId: String? = null,
    @ColumnInfo(name = "fireflyTransactionId")
    val fireflyTransactionId: String? = null,
    @ColumnInfo(name = "fireflySourceName")
    val fireflySourceName: String? = null,
    @ColumnInfo(name = "fireflyDestinationName")
    val fireflyDestinationName: String? = null,
    @ColumnInfo(name = "fireflyUpdatedAt")
    val fireflyUpdatedAt: LocalDateTime? = null,
    @ColumnInfo(name = "syncedAt")
    val syncedAt: LocalDateTime? = null,
    @ColumnInfo(name = "lastError")
    val lastError: String? = null
)

/**
 * Sync state for one local budget (maps a `budgets` row to a Firefly budget
 * and its current limit).
 */
@Entity(tableName = "firefly_budget_state")
data class FireflyBudgetStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "localBudgetId")
    val localBudgetId: Long,
    @ColumnInfo(name = "fireflyBudgetId")
    val fireflyBudgetId: String? = null,
    @ColumnInfo(name = "fireflyLimitId")
    val fireflyLimitId: String? = null,
    @ColumnInfo(name = "syncedAt")
    val syncedAt: LocalDateTime? = null
)

/**
 * Sync state for one local subscription (maps a `subscriptions` row to a
 * Firefly recurring transaction).
 */
@Entity(tableName = "firefly_subscription_state")
data class FireflySubscriptionStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "localSubscriptionId")
    val localSubscriptionId: Long,
    @ColumnInfo(name = "fireflyRecurringId")
    val fireflyRecurringId: String? = null,
    @ColumnInfo(name = "syncedAt")
    val syncedAt: LocalDateTime? = null
)

/**
 * One entry of the in-app sync log. Keep rows cheap: this table grows with
 * sync activity and is trimmed by the UI query ([FireflySyncDao.recentLogs]).
 */
@Entity(tableName = "firefly_log")
data class FireflyLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "localTransactionId")
    val localTransactionId: Long? = null,
    /** One of "push", "pull", "resync", "budget", "subscription". */
    @ColumnInfo(name = "direction")
    val direction: String,
    /** One of "ok", "error", "skip". */
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "message")
    val message: String? = null,
    @ColumnInfo(name = "at")
    val at: LocalDateTime = LocalDateTime.now()
)
