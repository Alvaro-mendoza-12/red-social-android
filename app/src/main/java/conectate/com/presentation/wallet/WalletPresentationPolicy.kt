package conectate.com.presentation.wallet

import conectate.com.data.model.WalletTransaction
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Currency
import java.util.Locale

data class WalletSpendSnapshot(
    val spentTodayCoins: Int,
    val spentWeekCoins: Int
)

internal fun calculateWalletSpend(
    transactions: List<WalletTransaction>,
    now: Instant = Instant.now()
): WalletSpendSnapshot {
    val oneDayAgo = now.minusSeconds(24 * 60 * 60)
    val oneWeekAgo = now.minusSeconds(7 * 24 * 60 * 60)
    var today = 0
    var week = 0
    transactions.forEach { transaction ->
        if (transaction.amountDiamonds >= 0 || !transaction.isGiftDebit()) return@forEach
        val createdAt = transaction.createdAt?.let { value ->
            runCatching { Instant.parse(value) }
                .recoverCatching { OffsetDateTime.parse(value).toInstant() }
                .getOrNull()
        } ?: return@forEach
        val debit = -transaction.amountDiamonds
        if (!createdAt.isBefore(oneWeekAgo)) week += debit
        if (!createdAt.isBefore(oneDayAgo)) today += debit
    }
    return WalletSpendSnapshot(today.coerceAtLeast(0), week.coerceAtLeast(0))
}

internal fun WalletTransaction.isGiftDebit(): Boolean = when (type.lowercase(Locale.ROOT)) {
    "virtual_gift", "gift", "purchase" -> true
    else -> false
}

internal fun formatCoins(value: Int, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getIntegerInstance(locale).format(value.coerceAtLeast(0))

internal fun formatMoney(
    value: BigDecimal,
    currencyCode: String,
    locale: Locale = Locale.getDefault()
): String {
    val formatter = NumberFormat.getCurrencyInstance(locale)
    runCatching { formatter.currency = Currency.getInstance(currencyCode.uppercase(Locale.ROOT)) }
    return formatter.format(value)
}

internal fun maskPayoutReference(reference: String?): String? {
    val clean = reference?.trim().orEmpty()
    if (clean.isBlank()) return null
    val visible = clean.takeLast(4)
    return "•••• $visible"
}

internal fun readablePayoutStatus(status: String): String = when (status.lowercase(Locale.ROOT)) {
    "verified", "active" -> "Cuenta verificada"
    "pending", "under_review" -> "Verificación pendiente"
    "restricted" -> "Cuenta restringida"
    "rejected" -> "Verificación rechazada"
    else -> "Cuenta sin verificar"
}

internal fun readableEarningStatus(status: String): String = when (status.lowercase(Locale.ROOT)) {
    "available" -> "Disponible"
    "pending" -> "En retención"
    "held", "in_hold", "under_review" -> "En revisión"
    "paid" -> "Retirado"
    "refunded", "reversed" -> "Ajuste por reembolso"
    else -> "Procesando"
}

internal fun readableEarningSource(sourceType: String): String = when (sourceType.lowercase(Locale.ROOT)) {
    "virtual_gift" -> "Regalo confirmado"
    "retention_release" -> "Liberación de retención"
    "refund", "reversal" -> "Ajuste por reembolso"
    else -> "Ingreso de creador"
}
