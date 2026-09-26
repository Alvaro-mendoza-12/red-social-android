package conectate.com.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import conectate.com.data.local.prefs.AppFontOption
import conectate.com.data.local.prefs.AppThemeOption

internal val AppTypography = Typography
internal val LocalSystemBarColorOverride = staticCompositionLocalOf<((Color?) -> Unit)?> { null }

@Composable
fun RedSocialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appTheme: AppThemeOption = AppThemeOption.NEUTRO,
    fontOption: AppFontOption = AppFontOption.DEFAULT,
    textScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val colorScheme = appColorScheme(appTheme, darkTheme)
    val typography = buildAppTypography(
        fontFamily = appFontFamilyFor(fontOption.storageValue),
        textScale = textScale
    )
    var systemBarColorOverride by remember { mutableStateOf<Color?>(null) }
    var systemBarOverrideOwners by remember { mutableIntStateOf(0) }
    val systemBarColor = systemBarColorOverride ?: colorScheme.background
    val setSystemBarColorOverride = remember {
        { color: Color? ->
            if (color == null) {
                systemBarOverrideOwners = (systemBarOverrideOwners - 1).coerceAtLeast(0)
                if (systemBarOverrideOwners == 0) systemBarColorOverride = null
            } else {
                // Navigation may compose the next auth route before removing the
                // previous one. Its disposal must not undo the new route's bars.
                systemBarOverrideOwners += 1
                systemBarColorOverride = color
            }
        }
    }
    
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
                if (Build.VERSION.SDK_INT < 35) { // Android 15+ enforces edge-to-edge for targeted apps.
                    @Suppress("DEPRECATION")
                    window.statusBarColor = systemBarColor.toArgb()
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = systemBarColor.toArgb()
                }
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                    systemBarColor.luminance() > 0.5f
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars =
                    systemBarColor.luminance() > 0.5f
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        CompositionLocalProvider(
            LocalSystemBarColorOverride provides setSystemBarColorOverride
        ) {
            content()
        }
    }
}
