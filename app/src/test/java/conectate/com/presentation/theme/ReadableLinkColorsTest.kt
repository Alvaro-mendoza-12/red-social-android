package conectate.com.presentation.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableLinkColorsTest {
    @Test fun everyChatAccentKeepsReadableBlueLinksInBothThemes() {
        for (dark in listOf(false, true)) {
            for (key in listOf("conectate_red", "clean_white", "electric_blue", "graphite")) {
                assertReadable(resolveChatAccent(key, dark).sentBackground)
            }
        }
    }
    @Test fun lightAndDarkReceivedSurfacesKeepReadableBlueLinks() {
        listOf(Color.White, Color.Black, Color(0xFFF0F0F0), Color(0xFF242424), Color(0xFF555B66))
            .forEach(::assertReadable)
    }
    @Test fun saturatedMidtoneUsesAReadableBackgroundIfNecessary() {
        val colors = readableLinkColors(Color(0xFF287DFF))
        assertTrue(colors.background != Color.Unspecified)
        assertReadable(Color(0xFF287DFF))
    }
    private fun assertReadable(background: Color) {
        val colors = readableLinkColors(background)
        val effectiveBackground = colors.background.takeUnless { it == Color.Unspecified } ?: background
        assertTrue(textContrastRatio(colors.foreground, effectiveBackground) >= 4.5f)
        assertTrue(colors.foreground.blue > colors.foreground.red)
        assertTrue(colors.foreground.blue > colors.foreground.green)
    }
}
