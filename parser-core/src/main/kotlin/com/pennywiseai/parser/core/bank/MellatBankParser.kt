package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.TransactionType
import java.math.BigDecimal

/**
 * Mellat Bank parser for Iranian banking SMS messages.
 * Only handles deposit and withdrawal messages, ignoring OTPs and other notifications.
 */
class MellatBankParser : BaseIranianBankParser() {

    private val pattern1 = Regex(
        """^Ï¡Ï│ÏºÏ¿\d+\s+(Ï¿Ï▒Ï»ÏºÏ┤Ï¬|┘êÏºÏ▒█îÏ▓)\s*([0-9,]+)\s+┘àÏº┘åÏ»┘ç\s*([0-9,]+)\s+\d{2}/\d{2}/\d{2}-\d{2}:\d{2}""",
        RegexOption.MULTILINE
    )
    private val pattern2 = Regex(
        """^┘êÏºÏ▒█îÏ▓ Ï│┘êÏ» ┌®┘êÏ¬Ïº┘ç ┘àÏ»Ï¬\s+Ï¡Ï│ÏºÏ¿\d+\s+┘àÏ¿┘äÏ║\s*([0-9,]+)\s+\d{2}/\d{2}/\d{2}""",
        RegexOption.MULTILINE
    )
    private val accountPattern = Regex("""Ï¡Ï│ÏºÏ¿\s*(\d+)""")

    override fun getBankName(): String = "Mellat Bank"

    override fun canHandle(sender: String): Boolean {
        val upperSender = sender.uppercase()
        val mellatSenders = setOf(
            "BANK MELLAT",
            "BANKMELLAT",
            "MELLAT",
            "MELLATBANK",
            "MELLAT BANK"
        )
        return upperSender in mellatSenders
    }

    override fun isTransactionMessage(message: String): Boolean {
        val trimmed = message.trim()
        return pattern1.containsMatchIn(trimmed) || pattern2.containsMatchIn(trimmed)
    }

    override fun extractAmount(message: String): BigDecimal? {
        val trimmed = message.trim()
        pattern1.find(trimmed)?.let { match ->
            val amountStr = match.groupValues[2].replace(",", "")
            return try {
                BigDecimal(amountStr)
            } catch (e: NumberFormatException) {
                null
            }
        }
        pattern2.find(trimmed)?.let { match ->
            val amountStr = match.groupValues[1].replace(",", "")
            return try {
                BigDecimal(amountStr)
            } catch (e: NumberFormatException) {
                null
            }
        }
        return null
    }

    override fun extractTransactionType(message: String): TransactionType? {
        val trimmed = message.trim()
        pattern1.find(trimmed)?.let { match ->
            return when (match.groupValues[1]) {
                "Ï¿Ï▒Ï»ÏºÏ┤Ï¬" -> TransactionType.EXPENSE
                "┘êÏºÏ▒█îÏ▓" -> TransactionType.INCOME
                else -> null
            }
        }
        if (pattern2.containsMatchIn(trimmed)) {
            return TransactionType.INCOME
        }
        return null
    }

    override fun extractAccountLast4(message: String): String? {
        accountPattern.find(message)?.let { match ->
            val fullAccount = match.groupValues[1]
            if (fullAccount.length >= 4) {
                return fullAccount.takeLast(4)
            }
            return fullAccount
        }
        return null
    }

    override fun extractBalance(message: String): BigDecimal? {
        val trimmed = message.trim()
        pattern1.find(trimmed)?.let { match ->
            val balanceStr = match.groupValues[3].replace(",", "")
            return try {
                BigDecimal(balanceStr)
            } catch (e: NumberFormatException) {
                null
            }
        }
        return null
    }
}
