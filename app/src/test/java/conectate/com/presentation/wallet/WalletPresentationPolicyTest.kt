package conectate.com.presentation.wallet

import conectate.com.data.model.WalletTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletPresentationPolicyTest {
    @Test
    fun `spend snapshot counts only committed gift debits in each window`() {
        val now = Instant.parse("2026-08-02T18:00:00Z")
        val transactions = listOf(
            transaction("today", "virtual_gift", -80, "2026-08-02T17:00:00Z"),
            transaction("week", "purchase", -120, "2026-07-29T12:00:00-05:00"),
            transaction("deposit", "google_play_coin_purchase", 500, "2026-08-02T16:00:00Z"),
            transaction("old", "virtual_gift", -900, "2026-07-20T17:00:00Z")
        )

        assertEquals(WalletSpendSnapshot(80, 200), calculateWalletSpend(transactions, now))
    }

    @Test
    fun `payout reference never exposes the full external identifier`() {
        assertEquals("•••• 4821", maskPayoutReference("bank-account-4821"))
        assertNull(maskPayoutReference("  "))
    }

    @Test
    fun `money is formatted with requested currency`() {
        val formatted = formatMoney(BigDecimal("286.40"), "PEN", Locale.US)
        assertTrue(formatted.contains("286.40"))
        assertTrue(formatted.contains("PEN"))
    }

    private fun transaction(id: String, type: String, amount: Int, createdAt: String) = WalletTransaction(
        id = id,
        type = type,
        amountDiamonds = amount,
        balanceAfterDiamonds = 0,
        createdAt = createdAt
    )
}
