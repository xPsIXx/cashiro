package com.pennywiseai.tracker.data.firefly

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * DAO for the Cashiro Firefly sidecar database. This is the single place the
 * add-on persists Firefly sync state; the app's own entities/DAOs never know
 * Firefly exists.
 */
@Dao
interface FireflySyncDao {

    // ── Transaction state ────────────────────────────────────────────────────

    @Query(
        """
        INSERT INTO firefly_transaction_state (
            localTransactionId, externalId, fireflyTransactionId, fireflyUpdatedAt,
            fireflySourceName, fireflyDestinationName, syncedAt, lastError
        )
        VALUES (:txId, :externalId, :remoteTxId, :updatedAt, :source, :destination, :syncedAt, NULL)
        ON CONFLICT(localTransactionId) DO UPDATE SET
            externalId = :externalId,
            fireflyTransactionId = :remoteTxId,
            fireflyUpdatedAt = :updatedAt,
            -- COALESCE keeps previously recorded names unless the caller
            -- explicitly supplies new ones (only the pull path does).
            fireflySourceName = COALESCE(:source, fireflySourceName),
            fireflyDestinationName = COALESCE(:destination, fireflyDestinationName),
            syncedAt = :syncedAt,
            lastError = NULL
        """
    )
    suspend fun markSynced(
        txId: Long,
        externalId: String?,
        remoteTxId: String?,
        updatedAt: LocalDateTime?,
        source: String? = null,
        destination: String? = null,
        syncedAt: LocalDateTime = LocalDateTime.now()
    )

    @Query(
        """
        UPDATE firefly_transaction_state
        SET lastError = :error
        WHERE localTransactionId = :txId
        """
    )
    suspend fun markError(txId: Long, error: String)

    @Query(
        """
        UPDATE firefly_transaction_state
        SET externalId = :newExtId
        WHERE localTransactionId = :txId
        """
    )
    suspend fun updateExternalId(txId: Long, newExtId: String)

    @Query("SELECT * FROM firefly_transaction_state WHERE localTransactionId = :txId")
    fun stateFor(txId: Long): Flow<FireflyTransactionStateEntity?>

    @Query("SELECT * FROM firefly_transaction_state WHERE localTransactionId IN (:ids)")
    suspend fun statesFor(ids: List<Long>): List<FireflyTransactionStateEntity>

    @Query("SELECT * FROM firefly_transaction_state WHERE lastError IS NOT NULL")
    fun statesWithError(): Flow<List<FireflyTransactionStateEntity>>

    @Query("SELECT COUNT(*) FROM firefly_transaction_state WHERE lastError IS NOT NULL")
    fun errorCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactionState(state: FireflyTransactionStateEntity)

    // ── Budget state ────────────────────────────────────────────────────────

    @Query("SELECT * FROM firefly_budget_state WHERE localBudgetId = :localBudgetId")
    suspend fun budgetStateFor(localBudgetId: Long): FireflyBudgetStateEntity?

    @Query("SELECT * FROM firefly_budget_state WHERE localBudgetId IN (:ids)")
    suspend fun budgetStatesFor(ids: List<Long>): List<FireflyBudgetStateEntity>

    @Query("SELECT * FROM firefly_budget_state WHERE fireflyBudgetId = :fireflyId LIMIT 1")
    suspend fun budgetByFireflyId(fireflyId: String): FireflyBudgetStateEntity?

    @Query(
        """
        UPDATE firefly_budget_state
        SET fireflyBudgetId = :fireflyBudgetId,
            fireflyLimitId = :fireflyLimitId,
            syncedAt = :syncedAt
        WHERE localBudgetId = :localBudgetId
        """
    )
    suspend fun updateBudgetSyncInfo(
        localBudgetId: Long,
        fireflyBudgetId: String?,
        fireflyLimitId: String?,
        syncedAt: LocalDateTime = LocalDateTime.now()
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudgetState(state: FireflyBudgetStateEntity)

    // ── Subscription state ───────────────────────────────────────────────────

    @Query("SELECT * FROM firefly_subscription_state WHERE localSubscriptionId = :localSubscriptionId")
    suspend fun subscriptionStateFor(localSubscriptionId: Long): FireflySubscriptionStateEntity?

    @Query("SELECT * FROM firefly_subscription_state WHERE localSubscriptionId IN (:ids)")
    suspend fun subscriptionStatesFor(ids: List<Long>): List<FireflySubscriptionStateEntity>

    @Query("SELECT * FROM firefly_subscription_state WHERE fireflyRecurringId = :fireflyId LIMIT 1")
    suspend fun subscriptionByFireflyRecurringId(fireflyId: String): FireflySubscriptionStateEntity?

    @Query(
        """
        UPDATE firefly_subscription_state
        SET fireflyRecurringId = :fireflyRecurringId,
            syncedAt = :syncedAt
        WHERE localSubscriptionId = :localSubscriptionId
        """
    )
    suspend fun updateSubscriptionSyncInfo(
        localSubscriptionId: Long,
        fireflyRecurringId: String?,
        syncedAt: LocalDateTime = LocalDateTime.now()
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubscriptionState(state: FireflySubscriptionStateEntity)

    // ── Sync log ─────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertLog(log: FireflyLogEntity): Long

    @Query("SELECT * FROM firefly_log ORDER BY id DESC LIMIT :limit")
    fun recentLogs(limit: Int): Flow<List<FireflyLogEntity>>

    @Query("DELETE FROM firefly_log")
    suspend fun deleteAllLogs()
}
