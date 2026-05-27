package conectate.com.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import conectate.com.data.local.prefs.AppFontOption

private val BaseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)

val Typography = BaseTypography

fun buildAppTypography(
    fontFamily: FontFamily = FontFamily.SansSerif,
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

fun appFontFamilyFor(option: AppFontOption): FontFamily {
    return when (option) {
        AppFontOption.DEFAULT -> FontFamily.SansSerif
        AppFontOption.SANS_SERIF,
        AppFontOption.ROUNDED,
        AppFontOption.CONDENSED,
        AppFontOption.MEDIUM,
        AppFontOption.LIGHT,
        AppFontOption.CASUAL -> FontFamily.SansSerif
        AppFontOption.SERIF,
        AppFontOption.SMALL_CAPS -> FontFamily.Serif
        AppFontOption.SERIF_MONO,
        AppFontOption.MONOSPACE -> FontFamily.Monospace
        AppFontOption.CURSIVE -> FontFamily.Cursive
    }
}

fun appFontFamilyFor(storageValue: String): FontFamily {
    return when (storageValue.trim().lowercase()) {
        "sans",
        "sans_serif" -> appFontFamilyFor(AppFontOption.SANS_SERIF)
        "rounded" -> appFontFamilyFor(AppFontOption.ROUNDED)
        "condensed" -> appFontFamilyFor(AppFontOption.CONDENSED)
        "medium" -> appFontFamilyFor(AppFontOption.MEDIUM)
        "light" -> appFontFamilyFor(AppFontOption.LIGHT)
        "serif" -> appFontFamilyFor(AppFontOption.SERIF)
        "small_caps" -> appFontFamilyFor(AppFontOption.SMALL_CAPS)
        "serif_mono" -> appFontFamilyFor(AppFontOption.SERIF_MONO)
        "monospace" -> appFontFamilyFor(AppFontOption.MONOSPACE)
        "cursive" -> appFontFamilyFor(AppFontOption.CURSIVE)
        "casual" -> appFontFamilyFor(AppFontOption.CASUAL)
        else -> appFontFamilyFor(AppFontOption.DEFAULT)
    }
}
