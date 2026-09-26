package conectate.com.presentation.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatAppearanceTest {
    @Test
    fun `all selectable accents resolve to distinct backgrounds`() {
        val colors = listOf("conectate_red", "clean_white", "electric_blue", "graphite")
            .map { resolveChatAccent(it, darkTheme = true).sentBackground }

        assertEquals(4, colors.distinct().size)
    }

    @Test
    fun `white accent uses dark readable content`() {
        val white = resolveChatAccent("clean_white", darkTheme = true)

        assertNotEquals(Color.White, white.sentContent)
        assertEquals(Color(0xFF111114), white.sentContent)
    }

    @Test
    fun `unknown accent restores Novvin red`() {
        assertEquals(
            resolveChatAccent("conectate_red", darkTheme = false),
            resolveChatAccent("unknown", darkTheme = false),
        )
    }
}
