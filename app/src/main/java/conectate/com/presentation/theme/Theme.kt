package conectate.com.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal val AppTypography = Typography

private val DarkColorScheme = darkColorScheme(
    primary = RedPrimary,
    onPrimary = TextOnPrimary,
    primaryContainer = RedPrimaryDark,
    onPrimaryContainer = TextOnPrimary,
    secondary = RedSecondary,
    onSecondary = TextOnPrimary,
    secondaryContainer = RedSecondaryDark,
    onSecondaryContainer = TextOnPrimary,
    tertiary = RedSecondaryLight,
    background = BackgroundDark,
    onBackground = Color.White,
    surface = SurfaceDark,
    onSurface = Color.White,
    error = ErrorColor,
    onError = TextOnPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = RedPrimary,
    onPrimary = TextOnPrimary,
    primaryContainer = RedPrimaryLight,
    onPrimaryContainer = TextPrimary,
    secondary = RedSecondary,
    onSecondary = TextOnPrimary,
    secondaryContainer = RedSecondaryLight,
    onSecondaryContainer = TextPrimary,
    tertiary = RedSecondaryDark,
    background = BackgroundLight,
    onBackground = TextPrimary,
    surface = SurfaceLight,
    onSurface = TextPrimary,
    error = ErrorColor,
    onError = TextOnPrimary
)

@Composable
fun RedSocialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val context = view.context
            var currentContext = context
            while (currentContext is android.content.ContextWrapper) {
                if (currentContext is Activity) break
                currentContext = currentContext.baseContext
            }
            
            val activity = currentContext as? Activity
            activity?.window?.let { window ->
                if (Build.VERSION.SDK_INT < 35) { // API 35 handles edge-to-edge differently
                    @Suppress("DEPRECATION")
                    window.statusBarColor = colorScheme.primary.toArgb()
                }
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
