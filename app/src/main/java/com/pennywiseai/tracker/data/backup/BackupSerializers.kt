package com.pennywiseai.tracker.data.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.modules.SerializersModule
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.pennywiseai.tracker.data.database.entity.BudgetImpactType
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType

/**
 * kotlinx.serialization plumbing for the backup format.
 *
 * The three custom serializers below emit **byte-identical** output to the
 * legacy Gson type adapters they replace, so that:
 *  - backups written by older (Gson) releases parse here unchanged, and
 *  - backups written here remain readable by the older Gson-based importer.
 *
 * Formats (must not change without bumping the backup format version):
 *  - [BigDecimal]      → plain decimal string  (`toPlainString()`)
 *  - [LocalDateTime]   → `ISO_LOCAL_DATE_TIME` (e.g. `2024-01-02T10:15:30`)
 *  - [LocalDate]       → `ISO_LOCAL_DATE`      (e.g. `2024-01-02`)
 *
 * See `docs/backup-format.md` for the full compatibility contract.
 */

object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal =
        BigDecimal(decoder.decodeString())
}

object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.format(formatter))
    }

    override fun deserialize(decoder: Decoder): LocalDateTime =
        LocalDateTime.parse(decoder.decodeString(), formatter)
}

object LocalDateSerializer : KSerializer<LocalDate> {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.format(formatter))
    }

    override fun deserialize(decoder: Decoder): LocalDate =
        LocalDate.parse(decoder.decodeString(), formatter)
}

/**
 * Registers the contextual serializers above. Entity fields of these types are
 * marked `@Contextual` so the same serializer is reused everywhere.
 */
val backupSerializersModule: SerializersModule = SerializersModule {
    contextual(BigDecimal::class, BigDecimalSerializer)
    contextual(LocalDateTime::class, LocalDateTimeSerializer)
    contextual(LocalDate::class, LocalDateSerializer)
}

/**
 * The single [Json] instance used for both export and import.
 *
 * Compatibility-critical settings — the whole point of this module:
 *  - `ignoreUnknownKeys = true` → a backup written by a *newer* app (extra
 *    keys) still imports into an *older* app (forward compatibility).
 *  - `coerceInputValues = true` + Kotlin constructor defaults → a backup
 *    written by an *older* app (missing keys) still imports into a *newer*
 *    app; the missing field falls back to its default instead of crashing
 *    (backward compatibility). This is the fix for the "can't restore old
 *    backup" bug — Gson's `Unsafe` allocation ignored these defaults.
 *  - `encodeDefaults = true` → exported JSON is explicit/self-describing.
 *  - `isLenient = true` → tolerant of minor formatting quirks.
 */
val backupJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
    serializersModule = backupSerializersModule
}

// --- TransactionEntity <-> JsonElement bridge for backup ---
// TransactionEntity deliberately has NO @Serializable / @Contextual to avoid
// transient IR/KSP backend errors during KSP (Room) + kotlinx.serialization
// plugin interaction on complex entities. We use raw JsonElement storage in
// DatabaseSnapshot for the transactions list only, and convert at I/O time.
// This preserves all fields including the Firefly III sync columns and keeps
// the on-disk backup JSON format stable/compatible.

private val txDateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

fun transactionToJsonElement(tx: TransactionEntity): JsonElement = buildJsonObject {
    put("id", JsonPrimitive(tx.id))
    put("amount", JsonPrimitive(tx.amount.toPlainString()))
    put("merchantName", JsonPrimitive(tx.merchantName))
    put("category", JsonPrimitive(tx.category))
    put("transactionType", JsonPrimitive(tx.transactionType.name))
    put("dateTime", JsonPrimitive(tx.dateTime.format(txDateFormatter)))
    put("description", tx.description?.let { JsonPrimitive(it) } ?: JsonNull)
    put("smsBody", tx.smsBody?.let { JsonPrimitive(it) } ?: JsonNull)
    put("bankName", tx.bankName?.let { JsonPrimitive(it) } ?: JsonNull)
    put("smsSender", tx.smsSender?.let { JsonPrimitive(it) } ?: JsonNull)
    put("accountNumber", tx.accountNumber?.let { JsonPrimitive(it) } ?: JsonNull)
    put("balanceAfter", tx.balanceAfter?.let { JsonPrimitive(it.toPlainString()) } ?: JsonNull)
    put("transactionHash", JsonPrimitive(tx.transactionHash))
    put("isRecurring", JsonPrimitive(tx.isRecurring))
    put("isDeleted", JsonPrimitive(tx.isDeleted))
    put("createdAt", JsonPrimitive(tx.createdAt.format(txDateFormatter)))
    put("updatedAt", JsonPrimitive(tx.updatedAt.format(txDateFormatter)))
    put("currency", JsonPrimitive(tx.currency))
    put("fromAccount", tx.fromAccount?.let { JsonPrimitive(it) } ?: JsonNull)
    put("toAccount", tx.toAccount?.let { JsonPrimitive(it) } ?: JsonNull)
    put("reference", tx.reference?.let { JsonPrimitive(it) } ?: JsonNull)
    put("loanId", tx.loanId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("loanContribution", tx.loanContribution?.let { JsonPrimitive(it.toPlainString()) } ?: JsonNull)
    put("receiptPath", tx.receiptPath?.let { JsonPrimitive(it) } ?: JsonNull)
    put("budgetCategory", tx.budgetCategory?.let { JsonPrimitive(it) } ?: JsonNull)
    put("budgetImpactType", tx.budgetImpactType?.let { JsonPrimitive(it.name) } ?: JsonNull)
    put("groupId", tx.groupId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("profileId", tx.profileId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("fireflySyncedAt", tx.fireflySyncedAt?.let { JsonPrimitive(it.format(txDateFormatter)) } ?: JsonNull)
    put("fireflyExternalId", tx.fireflyExternalId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("fireflyLastError", tx.fireflyLastError?.let { JsonPrimitive(it) } ?: JsonNull)
    put("excludedFromAnalytics", JsonPrimitive(tx.excludedFromAnalytics))
}

fun jsonElementToTransaction(elem: JsonElement): TransactionEntity {
    val o = if (elem is JsonObject) elem else elem.jsonObject
    fun isNotNull(k: String): Boolean = (o[k] != null) && (o[k] !is JsonNull)
    fun s(k: String): String? = if (isNotNull(k)) o[k]!!.jsonPrimitive.contentOrNull else null
    fun l(k: String): Long? = if (isNotNull(k)) o[k]!!.jsonPrimitive.longOrNull else null
    fun b(k: String): Boolean = if (isNotNull(k)) (o[k]!!.jsonPrimitive.booleanOrNull ?: false) else false
    fun bd(k: String): BigDecimal? = s(k)?.let { runCatching { BigDecimal(it) }.getOrNull() }
    fun ldt(k: String): LocalDateTime? = s(k)?.let { runCatching { LocalDateTime.parse(it, txDateFormatter) }.getOrNull() }
    fun tt(k: String): TransactionType = runCatching { TransactionType.valueOf(s(k) ?: "EXPENSE") }.getOrDefault(TransactionType.EXPENSE)
    fun bit(k: String): BudgetImpactType? = runCatching { s(k)?.let { BudgetImpactType.valueOf(it) } }.getOrNull()

    return TransactionEntity(
        id = l("id") ?: 0L,
        amount = bd("amount") ?: BigDecimal.ZERO,
        merchantName = s("merchantName") ?: "",
        category = s("category") ?: "",
        transactionType = tt("transactionType"),
        dateTime = ldt("dateTime") ?: LocalDateTime.now(),
        description = s("description"),
        smsBody = s("smsBody"),
        bankName = s("bankName"),
        smsSender = s("smsSender"),
        accountNumber = s("accountNumber"),
        balanceAfter = bd("balanceAfter"),
        transactionHash = s("transactionHash") ?: "",
        isRecurring = b("isRecurring"),
        isDeleted = b("isDeleted"),
        createdAt = ldt("createdAt") ?: LocalDateTime.now(),
        updatedAt = ldt("updatedAt") ?: LocalDateTime.now(),
        currency = s("currency") ?: "INR",
        fromAccount = s("fromAccount"),
        toAccount = s("toAccount"),
        reference = s("reference"),
        loanId = l("loanId"),
        loanContribution = bd("loanContribution"),
        receiptPath = s("receiptPath"),
        budgetCategory = s("budgetCategory"),
        budgetImpactType = bit("budgetImpactType"),
        groupId = l("groupId"),
        profileId = l("profileId"),
        fireflySyncedAt = ldt("fireflySyncedAt"),
        fireflyExternalId = s("fireflyExternalId"),
        fireflyLastError = s("fireflyLastError"),
        excludedFromAnalytics = b("excludedFromAnalytics")
    )
}
