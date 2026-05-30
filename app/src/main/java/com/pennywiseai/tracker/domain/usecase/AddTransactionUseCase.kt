package com.pennywiseai.tracker.domain.usecase

import com.pennywiseai.tracker.data.database.entity.AccountBalanceEntity
import com.pennywiseai.tracker.data.database.entity.SubscriptionEntity
import com.pennywiseai.tracker.data.database.entity.SubscriptionState
import com.pennywiseai.tracker.data.database.entity.BudgetImpactType
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.firefly.FireflyClient
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.AccountBalanceRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDateTime
import javax.inject.Inject

class AddTransactionUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val fireflyClient: FireflyClient
) {
    suspend fun execute(
        amount: BigDecimal,
        merchant: String,
        category: String,
        type: TransactionType,
        date: LocalDateTime,
        notes: String? = null,
        isRecurring: Boolean = false,
        bankName: String? = null,
        accountLast4: String? = null,
        currency: String = "INR",
        receiptPath: String? = null,
        budgetCategory: String? = null,
        budgetImpactType: BudgetImpactType? = null
    ) {
        // Generate a unique hash for manual transactions
        val transactionHash = generateManualTransactionHash(
            amount = amount,
            merchant = merchant,
            date = date
        )
        
        // Create the transaction entity
        val transaction = TransactionEntity(
            amount = amount,
            merchantName = merchant,
            category = category,
            transactionType = type,
            dateTime = date,
            description = notes,
            smsBody = null, // null indicates manual entry
            bankName = bankName ?: "Manual Entry",
            smsSender = null, // null indicates manual entry
            accountNumber = accountLast4,
            balanceAfter = null,
            transactionHash = transactionHash,
            isRecurring = isRecurring,
            currency = currency,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
            receiptPath = receiptPath,
            budgetCategory = budgetCategory,
            budgetImpactType = budgetImpactType
        )

        // Insert the transaction
        val transactionId = transactionRepository.insertTransaction(transaction)

        // Update account balance if account was selected
        if (transactionId != -1L && bankName != null && accountLast4 != null) {
            updateAccountBalance(
                bankName = bankName,
                accountLast4 = accountLast4,
                amount = amount,
                type = type,
                date = date,
                transactionId = transactionId
            )
        }
        
        // If marked as recurring, create a subscription
        if (isRecurring && transactionId != -1L) {
            val nextPaymentDate = date.toLocalDate().plusMonths(1) // Default to monthly
            
            val subscription = SubscriptionEntity(
                merchantName = merchant,
                amount = amount,
                nextPaymentDate = nextPaymentDate,
                state = SubscriptionState.ACTIVE,
                bankName = "Manual Entry",
                category = category,
                currency = currency,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now()
            )
            
            subscriptionRepository.insertSubscription(subscription)
        }

        // Opt-in: push manual transaction to Firefly III
        if (transactionId != -1L) {
            triggerFireflySyncIfEnabled(transactionId, transaction)
        }
    }

    private suspend fun triggerFireflySyncIfEnabled(transactionId: Long, tx: TransactionEntity) {
        try {
            val prefs = userPreferencesRepository.userPreferences.first()
            if (!prefs.fireflySyncEnabled) return

            val url = prefs.fireflyBaseUrl?.takeIf { it.isNotBlank() } ?: return
            val token = prefs.fireflyAccessToken?.takeIf { it.isNotBlank() } ?: return

            val accountMappings = userPreferencesRepository.fireflyAccountMappingsFlow.first()
            val categoryMappings = userPreferencesRepository.fireflyCategoryMappingsFlow.first()

            val includeRawSms = userPreferencesRepository.fireflyIncludeRawSmsFlow.first()

            fireflyClient.syncTransaction(
                transaction = tx.copy(id = transactionId),
                baseUrl = url,
                accessToken = token,
                defaultAssetAccount = prefs.fireflyDefaultAssetAccount,
                accountMappings = accountMappings,
                categoryMappings = categoryMappings,
                includeRawSmsInNotes = includeRawSms
            )
        } catch (e: Exception) {
            // Fire and forget - don't block manual entry
        }
    }
    
    private suspend fun updateAccountBalance(
        bankName: String,
        accountLast4: String,
        amount: BigDecimal,
        type: TransactionType,
        date: LocalDateTime,
        transactionId: Long
    ) {
        // Get current account balance
        val currentAccount = accountBalanceRepository.getLatestBalance(bankName, accountLast4)

        if (currentAccount != null) {
            // Calculate new balance based on transaction type
            val newBalance = when (type) {
                TransactionType.INCOME -> currentAccount.balance + amount
                TransactionType.EXPENSE, TransactionType.CREDIT -> currentAccount.balance - amount
                TransactionType.TRANSFER -> currentAccount.balance - amount  // Simplified - from account
                TransactionType.INVESTMENT -> currentAccount.balance - amount
            }

            // Insert new balance record
            accountBalanceRepository.insertBalance(
                currentAccount.copy(
                    id = 0,  // Auto-generate new ID
                    balance = newBalance,
                    timestamp = date,
                    transactionId = transactionId,
                    sourceType = "TRANSACTION",
                    smsSource = null
                )
            )
        }
    }

    private fun generateManualTransactionHash(
        amount: BigDecimal,
        merchant: String,
        date: LocalDateTime
    ): String {
        // Create a unique hash for manual transactions
        // Format: MANUAL_<amount>_<merchant>_<datetime>
        val data = "MANUAL_${amount}_${merchant}_${date}"

        return MessageDigest.getInstance("MD5")
            .digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}