package conectate.com.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import conectate.com.data.local.prefs.AppThemeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemesTest {

    @Test
    fun themeCatalogHasTwentyFourStableOptions() {
        assertEquals(24, AppThemeOption.entries.size)
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

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
