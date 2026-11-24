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
        assertEquals(GiftSpriteBlend(0, 0, 0f), giftSpriteBlend(progress = 0.73f, animationsEnabled = false, spec = spec))
    }

    @Test
    fun `animated sequence advances through distinct poses`() {
        val spec = requireNotNull(giftSpriteSheetSpec("cosmic-whale"))
        val frames = (0..20).map { step ->
            giftSpriteBlend(progress = step / 21f, animationsEnabled = true, spec = spec).currentFrame
        }.toSet()
        assertEquals(spec.frameCount, frames.size)
    }

    @Test
    fun `drawing interpolates valid poses and opacity throughout each gift timeline`() {
        NOVVIN_GIFT_VISUAL_KEYS.forEach { key ->
            val spec = requireNotNull(giftSpriteSheetSpec(key))
            val blends = (0..96).map { step -> giftSpriteBlend(step / 96f, animationsEnabled = true, spec = spec) }
            assertTrue("$key changes continuously", blends.distinct().size >= 50)
            assertTrue("$key stays inside its atlas", blends.all {
                it.currentFrame in 0 until spec.frameCount &&
                    it.nextFrame in 0 until spec.frameCount && it.nextFrame != it.currentFrame &&
                    it.nextAlpha in 0f..1f
            })
        }
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
