package conectate.com.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import conectate.com.data.local.prefs.AppFontOption
import conectate.com.data.local.prefs.AppThemeOption

internal val AppTypography = Typography

@Composable
fun RedSocialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appTheme: AppThemeOption = AppThemeOption.CONECTATE,
    fontOption: AppFontOption = AppFontOption.DEFAULT,
    textScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val colorScheme = appColorScheme(appTheme, darkTheme)
    val typography = buildAppTypography(
        fontFamily = appFontFamilyFor(fontOption.storageValue),
        textScale = textScale
    )
    
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
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                    colorScheme.primary.luminance() > 0.5f
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
