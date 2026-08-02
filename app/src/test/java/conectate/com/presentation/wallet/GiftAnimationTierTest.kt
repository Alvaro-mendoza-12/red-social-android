package conectate.com.presentation.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class GiftAnimationTierTest {
    @Test
    fun pricesChooseMicroHighlightAndCelebrationTiers() {
        assertEquals(0, giftAnimationTier(5))
        assertEquals(0, giftAnimationTier(49))
        assertEquals(1, giftAnimationTier(50))
        assertEquals(1, giftAnimationTier(249))
        assertEquals(2, giftAnimationTier(250))
        assertEquals(2, giftAnimationTier(1000))
    }
}
