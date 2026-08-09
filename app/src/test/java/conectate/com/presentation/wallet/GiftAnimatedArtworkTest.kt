package conectate.com.presentation.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import conectate.com.data.model.NOVVIN_GIFT_VISUAL_KEYS
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

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

    @Test
    fun `medium and premium timelines interpolate at least 16 useful states`() {
        val medium = requireNotNull(giftSpriteSheetSpec("star-garden"))
        val premium = requireNotNull(giftSpriteSheetSpec("aurora-genesis"))
        val dragon = requireNotNull(giftSpriteSheetSpec("celestial-dragon"))

        assertEquals(16, giftEffectiveTimelineStates(medium))
        assertEquals(64, giftEffectiveTimelineStates(premium))
        assertEquals(64, giftEffectiveTimelineStates(dragon))
        val blend = giftSpriteBlend(0.137f, animationsEnabled = true, spec = premium)
        assertTrue(blend.nextFrame != blend.currentFrame)
        assertTrue(blend.nextAlpha in 0f..1f)
    }

    @Test
    fun `rectangular poses fit within square artwork without distortion`() {
        val (offset, size) = fitGiftSpriteFrame(
            source = IntSize(width = 384, height = 512),
            destination = IntSize(width = 198, height = 198)
        )

        assertEquals(IntOffset(x = 24, y = 0), offset)
        assertEquals(IntSize(width = 149, height = 198), size)
    }

    @Test
    fun `square poses use the complete destination`() {
        val (offset, size) = fitGiftSpriteFrame(
            source = IntSize(width = 313, height = 313),
            destination = IntSize(width = 158, height = 158)
        )

        assertEquals(IntOffset.Zero, offset)
        assertEquals(IntSize(width = 158, height = 158), size)
    }
}
