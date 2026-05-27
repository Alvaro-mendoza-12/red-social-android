package conectate.com.presentation.theme

import androidx.compose.ui.text.font.FontFamily
import conectate.com.data.local.prefs.AppFontOption
import org.junit.Assert.assertEquals
import org.junit.Test

class AppTypographyRuntimeMappingTest {

    @Test
    fun `stored font options resolve to expected safe families`() {
        assertEquals(FontFamily.SansSerif, appFontFamilyFor(AppFontOption.DEFAULT))
        assertEquals(FontFamily.SansSerif, appFontFamilyFor(AppFontOption.SANS_SERIF.storageValue))
        assertEquals(FontFamily.SansSerif, appFontFamilyFor(AppFontOption.ROUNDED.storageValue))
        assertEquals(FontFamily.Serif, appFontFamilyFor(AppFontOption.SERIF.storageValue))
        assertEquals(FontFamily.Monospace, appFontFamilyFor(AppFontOption.SERIF_MONO.storageValue))
        assertEquals(FontFamily.Cursive, appFontFamilyFor(AppFontOption.CURSIVE.storageValue))
    }

    @Test
    fun `typography builder applies selected family and clamps scale`() {
        val typography = buildAppTypography(
            fontFamily = FontFamily.Serif,
            textScale = 2f
        )

        assertEquals(FontFamily.Serif, typography.bodyLarge.fontFamily)
        assertEquals(20f, typography.bodyLarge.fontSize.value, 0.01f)
        assertEquals(35f, typography.titleLarge.lineHeight.value, 0.01f)
    }
}
