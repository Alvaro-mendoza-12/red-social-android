package conectate.com.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp

private val BaseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

fun buildAppTypography(
    fontFamily: FontFamily = FontFamily.Default,
    textScale: Float = 1f
): Typography {
    val safeScale = textScale.coerceIn(0.9f, 1.25f)
    return Typography(
        displayLarge = BaseTypography.displayLarge.withFont(fontFamily, safeScale),
        displayMedium = BaseTypography.displayMedium.withFont(fontFamily, safeScale),
        displaySmall = BaseTypography.displaySmall.withFont(fontFamily, safeScale),
        headlineLarge = BaseTypography.headlineLarge.withFont(fontFamily, safeScale),
        headlineMedium = BaseTypography.headlineMedium.withFont(fontFamily, safeScale),
        headlineSmall = BaseTypography.headlineSmall.withFont(fontFamily, safeScale),
        titleLarge = BaseTypography.titleLarge.withFont(fontFamily, safeScale),
        titleMedium = BaseTypography.titleMedium.withFont(fontFamily, safeScale),
        titleSmall = BaseTypography.titleSmall.withFont(fontFamily, safeScale),
        bodyLarge = BaseTypography.bodyLarge.withFont(fontFamily, safeScale),
        bodyMedium = BaseTypography.bodyMedium.withFont(fontFamily, safeScale),
        bodySmall = BaseTypography.bodySmall.withFont(fontFamily, safeScale),
        labelLarge = BaseTypography.labelLarge.withFont(fontFamily, safeScale),
        labelMedium = BaseTypography.labelMedium.withFont(fontFamily, safeScale),
        labelSmall = BaseTypography.labelSmall.withFont(fontFamily, safeScale)
    )
}

private fun TextStyle.withFont(fontFamily: FontFamily, scale: Float): TextStyle {
    return copy(
        fontFamily = fontFamily,
        fontSize = fontSize.scaleBy(scale),
        lineHeight = lineHeight.scaleBy(scale),
        letterSpacing = letterSpacing.scaleBy(scale)
    )
}

private fun TextUnit.scaleBy(scale: Float): TextUnit {
    return if (type == TextUnitType.Sp) {
        (value * scale).sp
    } else {
        this
    }
}






