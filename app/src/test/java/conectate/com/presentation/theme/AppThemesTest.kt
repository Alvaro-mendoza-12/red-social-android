package conectate.com.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import conectate.com.data.local.prefs.AppThemeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemesTest {

    @Test
    fun themeCatalogIncludesTheNeutralNoThemeOption() {
        assertEquals(25, AppThemeOption.entries.size)
        assertEquals(AppThemeOption.NEUTRO, AppThemeOption.fromStorage(null))
        assertEquals(
            AppThemeOption.entries.size,
            AppThemeOption.entries.map { it.storageValue }.toSet().size
        )
        AppThemeOption.entries.forEach { option ->
            assertEquals(option, AppThemeOption.fromStorage(option.storageValue))
        }
    }

    @Test
    fun everyThemeKeepsCoreTextPairsReadableInLightAndDarkModes() {
        AppThemeOption.entries.forEach { option ->
            listOf(false, true).forEach { dark ->
                val scheme = appColorScheme(option, dark)
                val pairs = listOf(
                    scheme.primary to scheme.onPrimary,
                    scheme.primaryContainer to scheme.onPrimaryContainer,
                    scheme.secondary to scheme.onSecondary,
                    scheme.secondaryContainer to scheme.onSecondaryContainer,
                    scheme.tertiary to scheme.onTertiary,
                    scheme.tertiaryContainer to scheme.onTertiaryContainer,
                    scheme.background to scheme.onBackground,
                    scheme.surface to scheme.onSurface,
                    scheme.surfaceVariant to scheme.onSurfaceVariant
                )
                pairs.forEach { (background, foreground) ->
                    assertTrue(
                        "${option.storageValue} dark=$dark contrast=${contrastRatio(background, foreground)}",
                        contrastRatio(background, foreground) >= 4.5f
                    )
                }
            }
        }
    }

    @Test
    fun selectedDarkThemesReplaceTheDefaultBlackCanvasWithTheirOwnHue() {
        val defaultBackground = appColorScheme(AppThemeOption.NEUTRO, dark = true).background
        AppThemeOption.entries
            .filterNot { it == AppThemeOption.NEUTRO }
            .forEach { option ->
                val background = appColorScheme(option, dark = true).background
                val distance = kotlin.math.abs(background.red - defaultBackground.red) +
                    kotlin.math.abs(background.green - defaultBackground.green) +
                    kotlin.math.abs(background.blue - defaultBackground.blue)
                assertTrue("${option.storageValue} must replace the default canvas", distance >= 0.025f)
                val dominantChannel = maxOf(background.red, background.green, background.blue)
                assertTrue("${option.storageValue} must remain visibly tinted", dominantChannel >= 0.20f)
            }
    }

    @Test
    fun defaultDarkCanvasIsNeutralAndCustomCanvasIsOpaqueThemeColor() {
        assertEquals(Color(0xFF0B0B0D), appColorScheme(AppThemeOption.NEUTRO, dark = true).background)
        assertEquals(Color(0xFF151519), appColorScheme(AppThemeOption.NEUTRO, dark = true).surface)
        assertEquals(Color(0xFFF2F2F4), appColorScheme(AppThemeOption.NEUTRO, dark = true).primaryContainer)
        assertEquals(Color(0xFF006B60), appColorScheme(AppThemeOption.OCEANO, dark = true).background)
        assertEquals(Color(0xFF176B52), appColorScheme(AppThemeOption.JADE, dark = true).background)
    }

    @Test
    fun elevatedSurfacesNeverMixTheAccentIntoTheActiveCanvas() {
        AppThemeOption.entries.forEach { option ->
            assertEquals(Color.Transparent, appColorScheme(option, dark = true).surfaceTint)
            assertEquals(Color.Transparent, appColorScheme(option, dark = false).surfaceTint)
        }
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
