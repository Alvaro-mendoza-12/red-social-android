package conectate.com.presentation.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import conectate.com.data.model.NOVVIN_GIFT_VISUAL_KEYS

class GiftAnimatedArtworkTest {

    @Test
    fun `all canonical gifts use pose sprite sheets`() {
        assertEquals(24, NOVVIN_GIFT_VISUAL_KEYS.size)
        NOVVIN_GIFT_VISUAL_KEYS.forEach { key -> assertNotNull(key, giftSpriteSheetSpec(key)) }
    }

    @Test
    fun `reduced motion keeps a stable first pose`() {
        val spec = requireNotNull(giftSpriteSheetSpec("solar-hummingbird"))
        assertEquals(0, giftSpriteFrame(progress = 0.73f, animationsEnabled = false, spec = spec))
    }

    @Test
    fun `animated sequence advances through distinct poses`() {
        val spec = requireNotNull(giftSpriteSheetSpec("cosmic-whale"))
        val frames = (0..20).map { step ->
            giftSpriteFrame(progress = step / 21f, animationsEnabled = true, spec = spec)
        }.toSet()
        assertEquals(spec.frameCount, frames.size)
    }
}
