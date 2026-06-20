package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.TransactionType
import java.math.BigDecimal

/**
 * Parser for blu Bank (Ï¿┘ä┘ê), Iran.
 *
 * Handles line-based Persian SMS such as:
 *   Ï¿┘ä┘ê
 *   Ï¿Ï▒Ï»ÏºÏ┤Ï¬ ┘¥┘ê┘ä
 *   <NAME> Ï╣Ï▓█îÏ▓Ïî 2,500,000 Ï▒█îÏº┘ä ÏºÏ▓ Ï¡Ï│ÏºÏ¿ Ï┤┘àÏº ┘¥Ï▒█îÏ».
 *   ┘à┘êÏ¼┘êÏ»█î: 488,152 Ï▒█îÏº┘ä
 *   █À:█▓█©
 *   █▒█┤█░█Á.█░█│.█▓█▓
 *
 * Notes:
 * - Amounts use Western digits with comma grouping; only date/time use Persian
 *   digits, which we do not parse (timestamp comes from SMS metadata).
 * - Type signals: "Ï¿Ï▒Ï»ÏºÏ┤Ï¬ ┘¥┘ê┘ä" / "┘¥Ï▒█îÏ»" => EXPENSE (money left the account);
 *   "┘êÏºÏ▒█îÏ▓ ┘¥┘ê┘ä" / "┘åÏ┤Ï│Ï¬" => INCOME (money landed in the account).
 * - blu samples carry no card/account number, so accountLast4 is always null.
 * - Currency is Iranian Rial (IRR).
 *
 * NOTE: blu sender IDs vary widely with no reliable shared prefix (e.g.
 * "0999 998 7641", "+989999987641", "98300087641"). The only stable core
 * across observed samples is the "87641" suffix, plus the "9999987641" core.
 * canHandle matches those; additional sender IDs may need to be added as more
 * samples surface (known limitation).
 */
class BluBankParser : BankParser() {

    override fun getBankName() = "blu Bank"

    override fun getCurrency() = "IRR"

    // "Ï¿┘ä┘ê" = blu (bank-name line; strongest in-body signal).
    private val bankNameMarker = "Ï¿┘ä┘ê"

    // "Ï▒█îÏº┘ä" = Rial. Main sentence amount: a comma-grouped number before "Ï▒█îÏº┘ä".
    private val amountPattern = Regex("""([0-9][0-9,]*)\s*Ï▒█îÏº┘ä""")

    // "┘à┘êÏ¼┘êÏ»█î:" = balance, followed by a comma-grouped number before "Ï▒█îÏº┘ä".
    private val balancePattern = Regex("""┘à┘êÏ¼┘êÏ»█î:\s*([0-9][0-9,]*)\s*Ï▒█îÏº┘ä""")

    override fun canHandle(sender: String): Boolean {
        val digits = sender.filter { it.isDigit() }
        return digits.endsWith("87641") || digits.contains("9999987641")
    }

    override fun isTransactionMessage(message: String): Boolean {
        if (!message.contains(bankNameMarker)) return false
        // Must carry one of the action signals.
        return message.contains("Ï¿Ï▒Ï»ÏºÏ┤Ï¬ ┘¥┘ê┘ä") || message.contains("┘¥Ï▒█îÏ»") ||
                message.contains("┘êÏºÏ▒█îÏ▓ ┘¥┘ê┘ä") || message.contains("┘åÏ┤Ï│Ï¬")
    }

    override fun extractAmount(message: String): BigDecimal? {
        // First "<number> Ï▒█îÏº┘ä" is the transaction amount (balance line comes later).
        amountPattern.find(message)?.let { match ->
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
        if (message.contains("Ï¿Ï▒Ï»ÏºÏ┤Ï¬ ┘¥┘ê┘ä") || message.contains("┘¥Ï▒█îÏ»")) {
            return TransactionType.EXPENSE
        }
        if (message.contains("┘êÏºÏ▒█îÏ▓ ┘¥┘ê┘ä") || message.contains("┘åÏ┤Ï│Ï¬")) {
            return TransactionType.INCOME
        }
        return null
    }

    override fun extractBalance(message: String): BigDecimal? {
        balancePattern.find(message)?.let { match ->
            val balanceStr = match.groupValues[1].replace(",", "")
            return try {
                BigDecimal(balanceStr)
            } catch (e: NumberFormatException) {
                null
            }
        }
        return null
    }

    override fun extractAccountLast4(message: String): String? {
        // blu samples carry no card/account number.
        return null
    }

    override fun extractMerchant(message: String, sender: String): String? {
        // blu SMS carries no merchant/payee field.
        return null
    }
}
