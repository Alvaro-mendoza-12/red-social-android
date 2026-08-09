package conectate.com.presentation.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GiftCelebrationLayoutTest {
    @Test
    fun `portrait compact screen keeps large gift above system and composer areas`() {
        val layout = calculateGiftCelebrationLayout(
            viewportWidthDp = 320f,
            viewportHeightDp = 560f,
            insets = GiftViewportInsetsDp(topDp = 24f, bottomDp = 48f),
            baseArtworkSizeDp = 198f,
            artworkScale = 1.34f,
            motion = GiftMotionTransform(1f, 0f, translationX = 4f, translationY = 4f),
            preview = false,
            showCaption = true
        )

        assertFalse(layout.landscape)
        assertTrue(layout.compactTypography)
        assertEquals(320f, layout.safeWidthDp, 0.001f)
        assertEquals(488f, layout.safeHeightDp, 0.001f)
        assertTrue(layout.artworkSizeDp <= layout.safeWidthDp * 0.74f)
        assertTrue(layout.artworkSizeDp <= layout.artworkAreaHeightDp * 0.78f)
        assertHeroRemainsInsideArtworkArea(layout)
    }

    @Test
    fun `landscape layout respects side insets and gives live controls room`() {
        val layout = calculateGiftCelebrationLayout(
            viewportWidthDp = 896f,
            viewportHeightDp = 412f,
            insets = GiftViewportInsetsDp(startDp = 44f, topDp = 18f, endDp = 32f, bottomDp = 24f),
            baseArtworkSizeDp = 198f,
            artworkScale = 1.34f,
            motion = GiftMotionTransform(1f, 0f, translationX = -5f, translationY = 5f),
            preview = false,
            showCaption = true
        )

        assertTrue(layout.landscape)
        assertTrue(layout.compactTypography)
        assertEquals(820f, layout.safeWidthDp, 0.001f)
        assertEquals(370f, layout.safeHeightDp, 0.001f)
        assertEquals(72f, layout.bottomControlsClearanceDp, 0.001f)
        assertHeroRemainsInsideArtworkArea(layout)
    }

    @Test
    fun `tablet keeps canonical artwork size instead of expanding unnecessarily`() {
        val layout = calculateGiftCelebrationLayout(
            viewportWidthDp = 1280f,
            viewportHeightDp = 800f,
            insets = GiftViewportInsetsDp(topDp = 30f, bottomDp = 24f),
            baseArtworkSizeDp = 198f,
            artworkScale = 1.34f,
            motion = GiftMotionTransform(1f, 0f, 0f, 0f),
            preview = false,
            showCaption = true
        )

        assertEquals(198f * 1.34f, layout.artworkSizeDp, 0.001f)
        assertHeroRemainsInsideArtworkArea(layout)
    }

    @Test
    fun `preview uses all dialog space and malformed dimensions remain safe`() {
        val preview = calculateGiftCelebrationLayout(
            viewportWidthDp = 360f,
            viewportHeightDp = 320f,
            insets = GiftViewportInsetsDp(startDp = 99f, bottomDp = 99f),
            baseArtworkSizeDp = 198f,
            artworkScale = 1f,
            motion = GiftMotionTransform(1f, 0f, 9f, -9f),
            preview = true,
            showCaption = false
        )
        val empty = calculateGiftCelebrationLayout(
            viewportWidthDp = -1f,
            viewportHeightDp = -1f,
            insets = GiftViewportInsetsDp(startDp = 5f, topDp = 5f),
            baseArtworkSizeDp = 198f,
            artworkScale = 1f,
            motion = GiftMotionTransform(1f, 0f, 1f, 1f),
            preview = false,
            showCaption = true
        )

        assertEquals(0f, preview.bottomControlsClearanceDp, 0.001f)
        assertHeroRemainsInsideArtworkArea(preview)
        assertEquals(0f, empty.artworkSizeDp, 0.001f)
        assertEquals(0f, empty.safeWidthDp, 0.001f)
        assertEquals(0f, empty.safeHeightDp, 0.001f)
    }

    private fun assertHeroRemainsInsideArtworkArea(layout: GiftCelebrationLayout) {
        val halfSize = layout.artworkSizeDp / 2f
        assertTrue(layout.artworkTranslationXDp - halfSize >= -layout.safeWidthDp / 2f - 0.001f)
        assertTrue(layout.artworkTranslationXDp + halfSize <= layout.safeWidthDp / 2f + 0.001f)
        assertTrue(layout.artworkTranslationYDp - halfSize >= -layout.safeHeightDp / 2f - 0.001f)
        assertTrue(
            layout.artworkTranslationYDp + halfSize <=
                layout.safeHeightDp / 2f - layout.bottomControlsClearanceDp + 0.001f
        )
    }
}
