package conectate.com.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFD4422F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD4),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF4D6357),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9D8),
    onSecondaryContainer = Color(0xFF0A1F16),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF201A19),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF201A19),
    surfaceVariant = Color(0xFFF3DDD8),
    onSurfaceVariant = Color(0xFF524340)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A8),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD4),
    secondary = Color(0xFFB3CCBC),
    onSecondary = Color(0xFF20352A),
    secondaryContainer = Color(0xFF364B40),
    onSecondaryContainer = Color(0xFFCFE9D8),
    background = Color(0xFF181211),
    onBackground = Color(0xFFEDE0DD),
    surface = Color(0xFF181211),
    onSurface = Color(0xFFEDE0DD),
    surfaceVariant = Color(0xFF524340),
    onSurfaceVariant = Color(0xFFD7C2BD)
)

@Composable
fun RedSocialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
