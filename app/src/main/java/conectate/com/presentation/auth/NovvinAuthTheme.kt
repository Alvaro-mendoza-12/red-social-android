package conectate.com.presentation.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import conectate.com.presentation.theme.LocalSystemBarColorOverride

private val authColors = lightColorScheme(
    primary = Color(0xFF5146C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E6FF),
    onPrimaryContainer = Color(0xFF241B67),
    secondary = Color(0xFF087D91),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9F4F5),
    onSecondaryContainer = Color(0xFF144C56),
    tertiary = Color(0xFF00A7B7),
    background = Color(0xFFF7F9FF),
    onBackground = Color(0xFF18223D),
    surface = Color.White,
    onSurface = Color(0xFF18223D),
    surfaceVariant = Color(0xFFEDF1FA),
    onSurfaceVariant = Color(0xFF52607A),
    outline = Color(0xFF9BA8BE),
    error = Color(0xFFB32637),
    errorContainer = Color(0xFFFFE8E9),
    onErrorContainer = Color(0xFF681C27),
)

/** A calm, readable entry palette independent of the signed-in theme. */
@Composable
internal fun NovvinAuthTheme(content: @Composable () -> Unit) {
    val typography = MaterialTheme.typography
    val setSystemBarColorOverride = LocalSystemBarColorOverride.current
    DisposableEffect(setSystemBarColorOverride) {
        setSystemBarColorOverride?.invoke(authColors.background)
        onDispose { setSystemBarColorOverride?.invoke(null) }
    }

    MaterialTheme(colorScheme = authColors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides authColors.onBackground) {
            content()
        }
    }
}
