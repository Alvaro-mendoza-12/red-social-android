package conectate.com.presentation.wallet

import conectate.com.data.model.LiveGiftEvent
import conectate.com.data.model.NOVVIN_CANONICAL_GIFT_CATALOG
import conectate.com.data.model.NOVVIN_GIFT_VISUAL_KEYS
import conectate.com.data.model.VirtualGift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GiftAnimationTierTest {
    @Test
    fun pricesChooseMicroHighlightAndCelebrationTiers() {
        assertEquals(0, giftAnimationTier(5))
        assertEquals(0, giftAnimationTier(99))
        assertEquals(1, giftAnimationTier(100))
        assertEquals(1, giftAnimationTier(899))
        assertEquals(2, giftAnimationTier(900))
        assertEquals(2, giftAnimationTier(5_000))
    }

    @Test
    fun tiersStayInsideTheDocumentedAnimationWindows() {
        assertTrue(giftAnimationDurationMs(5) in 700..1_200)
        assertTrue(giftAnimationDurationMs(100) in 1_200..2_400)
        assertTrue(giftAnimationDurationMs(900) in 2_600..4_800)
    }

    @Test
    fun canonicalDurationsMatchTheVersionedManifestPolicy() {
        val expected = listOf(
            725, 750, 775, 825, 900, 1_000, 1_150, 1_245,
            1_305, 1_350, 1_425, 1_500, 1_575, 1_725, 1_950, 2_175,
            2_600, 2_707, 2_868, 3_082, 3_297, 3_619, 4_048, 4_800
        )

        assertEquals(
            expected,
            NOVVIN_CANONICAL_GIFT_CATALOG.map { giftAnimationDurationMs(it.priceDiamonds) }
        )
    }

    @Test
    fun everyCanonicalArtworkHasItsOwnMotionProfile() {
        val profiles = NOVVIN_GIFT_VISUAL_KEYS.map(::giftMotionProfile)
        assertEquals(NOVVIN_GIFT_VISUAL_KEYS.size, profiles.toSet().size)
    }

    @Test
    fun celebrationQueueKeepsOneProtagonistAndOneWaiting() {
        val queue = GiftEventQueue(capacity = 2)
        val events = (1..3).map(::event)

        queue.offerAll(events)

        assertEquals(2, queue.size())
        assertEquals("transaction-1", queue.peek()?.transactionId)
        queue.complete("transaction-1")
        assertEquals("transaction-2", queue.peek()?.transactionId)
        queue.complete("transaction-2")
        assertNull(queue.peek())
    }

    private fun event(index: Int) = LiveGiftEvent(
        transactionId = "transaction-$index",
        liveStreamId = "live",
        senderId = "sender",
        senderName = "Usuario",
        receiverId = "receiver",
        gift = VirtualGift(
            id = "gift-$index",
            name = "Regalo $index",
            imageUrl = "conectate://gift-assets/pulse",
            priceDiamonds = 5
        )
    )
}
