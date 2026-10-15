package conectate.com.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoViewportTest {
    @Test fun landscapeScreenFitsCompletelyOnPortraitPhone() {
        val (width, height) = containedVideoFraction(9f / 16, 16f / 9)
        assertEquals(1f, width, 0.0001f)
        assertEquals(0.31640625f, height, 0.0001f)
    }

    @Test fun portraitScreenFitsCompletelyOnLandscapeDisplay() {
        val (width, height) = containedVideoFraction(16f / 9, 9f / 16)
        assertEquals(0.31640625f, width, 0.0001f)
        assertEquals(1f, height, 0.0001f)
    }

    @Test fun matchingFrameUsesTheWholeViewport() {
        assertEquals(1f to 1f, containedVideoFraction(16f / 9, 16f / 9))
    }

    @Test fun unknownOrInvalidDimensionsDoNotProduceInvalidLayout() {
        for (ratio in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(1f to 1f, containedVideoFraction(1f, ratio))
            assertEquals(1f to 1f, containedVideoFraction(ratio, 1f))
        }
    }
}
