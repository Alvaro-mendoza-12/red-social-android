package conectate.com.design.system

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import conectate.com.data.local.prefs.AppFontOption

@Immutable
data class ConectateAppearanceTokens(
    val darkTheme: Boolean = false,
    val fontOption: AppFontOption = AppFontOption.DEFAULT,
    val fontFamily: FontFamily = FontFamily.Default,
    val textScale: Float = 1f,
    val reduceMotion: Boolean = false
)

@Immutable
data class ConectateMotionTokens(
    val reduceMotion: Boolean = false,
    val standardDurationMillis: Int = if (reduceMotion) 0 else 220,
    val emphasizedDurationMillis: Int = if (reduceMotion) 0 else 320
)

@Immutable
data class ConectateSpacingTokens(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp
)

@Immutable
data class ConectateCornerTokens(
    val sm: Dp = 8.dp,
    val md: Dp = 14.dp,
    val lg: Dp = 20.dp,
    val pill: Dp = 999.dp
)

@Immutable
data class ConectateDesignSystem(
    val appearance: ConectateAppearanceTokens = ConectateAppearanceTokens(),
    val motion: ConectateMotionTokens = ConectateMotionTokens(),
    val spacing: ConectateSpacingTokens = ConectateSpacingTokens(),
    val corners: ConectateCornerTokens = ConectateCornerTokens()
)

val LocalConectateDesignSystem = staticCompositionLocalOf { ConectateDesignSystem() }

val MaterialTheme.conectateDesignSystem: ConectateDesignSystem
    @Composable get() = LocalConectateDesignSystem.current
