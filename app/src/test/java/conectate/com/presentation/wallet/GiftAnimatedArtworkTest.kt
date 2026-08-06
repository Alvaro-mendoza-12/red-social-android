package conectate.com.presentation.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GiftAnimatedArtworkTest {

    @Test
    fun `principal character gifts use pose sprite sheets`() {
        listOf(
            "solar-hummingbird",
            "forest-guardian",
            "cosmic-whale",
            "celestial-dragon"
        ).forEach { key -> assertNotNull(key, giftSpriteSheetSpec(key)) }
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
