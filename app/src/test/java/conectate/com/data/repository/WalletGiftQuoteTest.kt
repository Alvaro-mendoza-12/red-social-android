package conectate.com.data.repository

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Repository and Ktor are real; the provider, journal and wallet are fictional. */
class WalletGiftQuoteTest {
    private lateinit var f: GiftHttpFixture
    @Before fun start() { f = GiftHttpFixture() }
    @After fun stop() { f.close() }

    private fun receipt(amount: JsonElement? = JsonPrimitive(10), replay: JsonElement? = JsonPrimitive(false)): String =
        buildJsonObject {
            put("ok", true); put("transaction_id", "receipt-quoted")
            put("sender_balance_diamonds", 990)
            if (amount != null) put("amount_diamonds", amount)
            if (replay != null) put("idempotent_replay", replay)
        }.toString()

    @Test fun theRequestBindsTheDisplayedPriceToAVersionedChargeEndpoint() = runBlocking {
        val sent = f.send().getOrThrow()
        assertTrue(f.endpoints.single().endsWith("/send_virtual_gift_v2"))
        assertEquals(10, f.requests.single()["p_expected_price_diamonds"]?.jsonPrimitive?.intOrNull)
        assertEquals(10, sent.gift.priceDiamonds)
        assertFalse(sent.idempotentReplay)
    }

    @Test fun anUnchangedQuoteCanBeConfirmedAndClearsTheJournal() = runBlocking {
        f.overrideReceipt = receipt()
        assertEquals("receipt-quoted", f.send().getOrThrow().transactionId)
        assertTrue(f.disk.isEmpty()); assertEquals(990, f.repository.coinBalance.value)
    }

    @Test fun aMissingChargedAmountCannotConfirmAnAmbiguousGift() = runBlocking {
        f.overrideReceipt = receipt(amount = null)
        assertTrue(f.send().isFailure); assertEquals("first-key", f.disk.values.single())
        assertEquals(0, f.repository.coinBalance.value)
    }

    @Test fun aMissingReplayFlagCannotUseTheCurrentCatalogAsAnOldReceipt() = runBlocking {
        f.overrideReceipt = receipt(replay = null)
        assertTrue(f.send().isFailure); assertEquals("first-key", f.disk.values.single())
    }

    @Test fun invalidChargedAmountsCannotBeConfirmed() = runBlocking {
        for (amount in listOf(JsonNull, JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1.5))) {
            f.overrideReceipt = receipt(amount)
            assertTrue("Invalid charged amount: $amount", f.send().isFailure)
            assertEquals("first-key", f.disk.values.single())
        }
    }

    @Test fun aNewGiftCannotAcknowledgeADifferentPriceThanTheUserConfirmed() = runBlocking {
        for (amount in listOf(5, 30)) {
            f.overrideReceipt = receipt(JsonPrimitive(amount))
            assertTrue(f.send().isFailure); assertEquals(0, f.repository.coinBalance.value)
            assertEquals("first-key", f.disk.values.single())
        }
    }

    @Test fun aRecoveredReceiptShowsItsOriginalChargeInsteadOfTheNewCatalogPrice() = runBlocking {
        f.catalogPrice = 30
        f.overrideReceipt = receipt(JsonPrimitive(5), JsonPrimitive(true))
        val recovered = f.send().getOrThrow()
        assertEquals(30, f.requests.single()["p_expected_price_diamonds"]?.jsonPrimitive?.intOrNull)
        assertEquals(5, recovered.gift.priceDiamonds); assertTrue(recovered.idempotentReplay)
        assertTrue(f.disk.isEmpty())
    }

    @Test fun anOldServerCannotFallBackToAnUnquotedCharge() = runBlocking {
        f.status = HttpStatusCode.NotFound
        assertTrue(f.send().isFailure)
        assertEquals(1, f.endpoints.size)
        assertTrue(f.endpoints.all { it.endsWith("/send_virtual_gift_v2") })
        assertEquals("first-key", f.disk.values.single())
    }
}
