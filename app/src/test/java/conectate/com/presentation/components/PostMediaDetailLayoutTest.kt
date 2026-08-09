package conectate.com.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostMediaDetailLayoutTest {
    @Test
    fun portraitMedia_keepsMetadataBelowAcrossRequiredViewports() {
        listOf(
            360f to 640f,
            393f to 873f,
            412f to 915f,
        ).forEach { (width, height) ->
            val layout = calculatePostMediaDetailLayout(width, height - 56f, 9f, 16f)
            assertEquals(width / (9f / 16f), layout.mediaHeightDp, 0.2f)
            assertTrue(layout.mediaUsesScrollableHeight)
        }
    }

    @Test
    fun landscapeMedia_doesNotCreateLargeEmptyArea() {
        val layout = calculatePostMediaDetailLayout(412f, 859f, 16f, 9f)
        assertEquals(231.75f, layout.mediaHeightDp, 0.2f)
        assertTrue(!layout.mediaUsesScrollableHeight)
    }

    @Test
    fun malformedDimensions_areBoundedSafely() {
        val layout = calculatePostMediaDetailLayout(360f, 584f, 0f, 0f)
        assertTrue(layout.mediaHeightDp in 220f..788.4f)
    }
}
