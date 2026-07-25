package conectate.com.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import conectate.com.data.local.prefs.AppThemeOption

private data class ThemePalette(
    val option: AppThemeOption,
    val lightPrimary: Color,
    val lightSecondary: Color,
    val lightTertiary: Color,
    val lightBackground: Color,
    val lightSurface: Color,
    val darkPrimary: Color,
    val darkSecondary: Color,
    val darkTertiary: Color,
    val darkBackground: Color,
    val darkSurface: Color
)

data class ProfileThemeColors(
    val background: Color,
    val foreground: Color,
    val accent: Color,
    val accentForeground: Color,
    val secondary: Color,
    val messageBackground: Color,
    val messageForeground: Color
)

private val palettes = listOf(
    ThemePalette(
        AppThemeOption.CONECTATE,
        Color(0xFFB3261E), Color(0xFF006A6A), Color(0xFF7A5900), Color(0xFFFFFBFF), Color(0xFFFFF8F7),
        Color(0xFFFFB4AB), Color(0xFF4DDAD3), Color(0xFFF2C14E), Color(0xFF181211), Color(0xFF211918)
    ),
    ThemePalette(
        AppThemeOption.OCEANO,
        Color(0xFF006B60), Color(0xFFB33F62), Color(0xFF315DA8), Color(0xFFF5FCFA), Color(0xFFEDF8F6),
        Color(0xFF58DBC7), Color(0xFFFFB0C5), Color(0xFFAEC6FF), Color(0xFF0D1615), Color(0xFF14201E)
    ),
    ThemePalette(
        AppThemeOption.JADE,
        Color(0xFF176B52), Color(0xFF8A4F00), Color(0xFF465D91), Color(0xFFF7FBF7), Color(0xFFF0F6F1),
        Color(0xFF72DCB4), Color(0xFFFFB95F), Color(0xFFB4C6FF), Color(0xFF101612), Color(0xFF182019)
    ),
    ThemePalette(
        AppThemeOption.SOLAR,
        Color(0xFF705D00), Color(0xFF005FAF), Color(0xFF9A3E00), Color(0xFFFFFCF3), Color(0xFFFFF8E5),
        Color(0xFFE9D15B), Color(0xFFA6C8FF), Color(0xFFFFB68D), Color(0xFF17150B), Color(0xFF211E10)
    ),
    ThemePalette(
        AppThemeOption.SAKURA,
        Color(0xFFA33862), Color(0xFF4256A6), Color(0xFF657000), Color(0xFFFFF8FA), Color(0xFFFFF1F5),
        Color(0xFFFFB0C8), Color(0xFFBAC3FF), Color(0xFFD0DA72), Color(0xFF1A1115), Color(0xFF241820)
    ),
    ThemePalette(
        AppThemeOption.COBALTO,
        Color(0xFF1E56A8), Color(0xFFA54600), Color(0xFF007063), Color(0xFFF8F9FF), Color(0xFFF1F4FC),
        Color(0xFFAEC6FF), Color(0xFFFFB68C), Color(0xFF65DCC8), Color(0xFF10141C), Color(0xFF171C26)
    ),
    ThemePalette(
        AppThemeOption.BOSQUE,
        Color(0xFF2D6846), Color(0xFF6F4D00), Color(0xFF00658A), Color(0xFFF8FBF5), Color(0xFFF1F6EE),
        Color(0xFF91D5A8), Color(0xFFE7C36D), Color(0xFF70D1FF), Color(0xFF101510), Color(0xFF182019)
    ),
    ThemePalette(
        AppThemeOption.AURORA,
        Color(0xFF006879), Color(0xFF9B3F78), Color(0xFF526400), Color(0xFFF8FBFC), Color(0xFFF0F7F8),
        Color(0xFF54D7ED), Color(0xFFFFAED8), Color(0xFFBBD36A), Color(0xFF0D1517), Color(0xFF152023)
    ),
    ThemePalette(
        AppThemeOption.GRAFITO,
        Color(0xFF3F5F00), Color(0xFF555F71), Color(0xFF9A3F32), Color(0xFFFAFAF7), Color(0xFFF2F3ED),
        Color(0xFFB4D96B), Color(0xFFBCC7DA), Color(0xFFFFB4A8), Color(0xFF121410), Color(0xFF1B1E18)
    ),
    ThemePalette(
        AppThemeOption.RUBI,
        Color(0xFFA11F42), Color(0xFF00705E), Color(0xFF5D5EAA), Color(0xFFFFF8F8), Color(0xFFFFF1F2),
        Color(0xFFFFB2C0), Color(0xFF66DDBF), Color(0xFFC4C3FF), Color(0xFF1A1113), Color(0xFF24181B)
    ),
    ThemePalette(
        AppThemeOption.CIRUELA,
        Color(0xFF76506F), Color(0xFF715B00), Color(0xFF006A68), Color(0xFFFFF8FC), Color(0xFFF9F1F7),
        Color(0xFFE5B9DD), Color(0xFFEACF64), Color(0xFF5EDAD5), Color(0xFF181217), Color(0xFF211A20)
    ),
    ThemePalette(
        AppThemeOption.ARTICO,
        Color(0xFF00677D), Color(0xFFA53A2A), Color(0xFF4E6196), Color(0xFFF7FBFD), Color(0xFFEEF7FA),
        Color(0xFF59D6F1), Color(0xFFFFB4A8), Color(0xFFB8C7FF), Color(0xFF0D1518), Color(0xFF142025)
    ),
    ThemePalette(
        AppThemeOption.NEON,
        Color(0xFF006B5F), Color(0xFF9E2F73), Color(0xFF525E00), Color(0xFFF8FCFA), Color(0xFFF0F7F4),
        Color(0xFF39E6C2), Color(0xFFFF8FD0), Color(0xFFD0E95C), Color(0xFF090E0D), Color(0xFF101817)
    ),
    ThemePalette(
        AppThemeOption.TERRACOTA,
        Color(0xFF974526), Color(0xFF006B75), Color(0xFF566500), Color(0xFFFFF9F5), Color(0xFFFAF2EC),
        Color(0xFFFFB69A), Color(0xFF62D8E5), Color(0xFFC1D66B), Color(0xFF19120F), Color(0xFF231A16)
    ),
    ThemePalette(
        AppThemeOption.TINTA,
        Color(0xFF30343B), Color(0xFF9D3434), Color(0xFF006B61), Color(0xFFFAF9F7), Color(0xFFF3F1EE),
        Color(0xFFD0D4DC), Color(0xFFFFB3AF), Color(0xFF63DBC8), Color(0xFF111214), Color(0xFF1A1B1E)
    ),
    ThemePalette(
        AppThemeOption.LAGUNA,
        Color(0xFF006B70), Color(0xFFA63B2E), Color(0xFF536200), Color(0xFFF5FCFC), Color(0xFFEDF8F8),
        Color(0xFF4ED8DE), Color(0xFFFFB4A9), Color(0xFFBED469), Color(0xFF0B1516), Color(0xFF132022)
    ),
    ThemePalette(
        AppThemeOption.CITRICO,
        Color(0xFF5F6200), Color(0xFF0062A1), Color(0xFFA23E00), Color(0xFFFCFCEB), Color(0xFFF5F6DF),
        Color(0xFFDADD62), Color(0xFF9DCAFF), Color(0xFFFFB690), Color(0xFF151509), Color(0xFF1E1F10)
    ),
    ThemePalette(
        AppThemeOption.ORQUIDEA,
        Color(0xFF8D3D75), Color(0xFF006B55), Color(0xFF745B00), Color(0xFFFFF8FC), Color(0xFFF9F0F6),
        Color(0xFFFFADD9), Color(0xFF63DCB3), Color(0xFFE9CD61), Color(0xFF191116), Color(0xFF231820)
    ),
    ThemePalette(
        AppThemeOption.CARMESI,
        Color(0xFFA1283F), Color(0xFF006874), Color(0xFF4E60A0), Color(0xFFFFF8F8), Color(0xFFFFF0F1),
        Color(0xFFFFB2BD), Color(0xFF52D7E5), Color(0xFFBBC7FF), Color(0xFF1A1113), Color(0xFF25181B)
    ),
    ThemePalette(
        AppThemeOption.PRISMA,
        Color(0xFF006B5C), Color(0xFFAA365D), Color(0xFF4B62A8), Color(0xFFF8FBF7), Color(0xFFF0F6F2),
        Color(0xFF53DBC1), Color(0xFFFFB1C2), Color(0xFFB8C7FF), Color(0xFF0D1613), Color(0xFF15201C)
    ),
    ThemePalette(
        AppThemeOption.MELON,
        Color(0xFF9A4524), Color(0xFF006A68), Color(0xFF555F9B), Color(0xFFFFF9F5), Color(0xFFFCF1EA),
        Color(0xFFFFB698), Color(0xFF5DDBD6), Color(0xFFBEC7FF), Color(0xFF19120E), Color(0xFF241A15)
    ),
    ThemePalette(
        AppThemeOption.ACERO,
        Color(0xFF455D75), Color(0xFFA23D32), Color(0xFF2F6A45), Color(0xFFF8FAFC), Color(0xFFF0F4F7),
        Color(0xFFADC9E7), Color(0xFFFFB4AA), Color(0xFF93D5A5), Color(0xFF101519), Color(0xFF182027)
    ),
    ThemePalette(
        AppThemeOption.SELVA,
        Color(0xFF146B48), Color(0xFF8D4F00), Color(0xFF00658D), Color(0xFFF6FCF6), Color(0xFFEDF7EF),
        Color(0xFF75DDA2), Color(0xFFFFB961), Color(0xFF70D1FF), Color(0xFF0C1610), Color(0xFF142019)
    ),
    ThemePalette(
        AppThemeOption.NOCHE,
        Color(0xFF3C5F00), Color(0xFF8A4567), Color(0xFF006B68), Color(0xFFF9FBF4), Color(0xFFF1F5EA),
        Color(0xFFB0D96A), Color(0xFFFFAED3), Color(0xFF58DAD4), Color(0xFF0C1009), Color(0xFF151A11)
    )
)

private val palettesByOption = palettes.associateBy(ThemePalette::option)

private fun paletteFor(option: AppThemeOption): ThemePalette =
    palettesByOption[option] ?: palettes.first()

private fun foregroundFor(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White

private fun themedColorScheme(palette: ThemePalette, dark: Boolean): ColorScheme {
    val primary = if (dark) palette.darkPrimary else palette.lightPrimary
    val secondary = if (dark) palette.darkSecondary else palette.lightSecondary
    val tertiary = if (dark) palette.darkTertiary else palette.lightTertiary
    val background = if (dark) palette.darkBackground else palette.lightBackground
    val surface = if (dark) palette.darkSurface else palette.lightSurface
    val primaryContainer = lerp(primary, background, if (dark) 0.62f else 0.78f)
    val secondaryContainer = lerp(secondary, background, if (dark) 0.64f else 0.82f)
    val tertiaryContainer = lerp(tertiary, background, if (dark) 0.64f else 0.82f)
    val surfaceVariant = lerp(secondary, surface, if (dark) 0.82f else 0.91f)
    val outline = lerp(foregroundFor(background), background, if (dark) 0.48f else 0.56f)
    val error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
    val errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6)

    val common: ColorScheme = if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = foregroundFor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = foregroundFor(primaryContainer),
            secondary = secondary,
            onSecondary = foregroundFor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = foregroundFor(secondaryContainer),
            tertiary = tertiary,
            onTertiary = foregroundFor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = foregroundFor(tertiaryContainer),
            background = background,
            onBackground = foregroundFor(background),
            surface = surface,
            onSurface = foregroundFor(surface),
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = foregroundFor(surfaceVariant),
            outline = outline,
            outlineVariant = lerp(outline, surface, 0.55f),
            error = error,
            onError = foregroundFor(error),
            errorContainer = errorContainer,
            onErrorContainer = foregroundFor(errorContainer),
            inverseSurface = palette.lightSurface,
            inverseOnSurface = foregroundFor(palette.lightSurface),
            inversePrimary = palette.lightPrimary,
            scrim = Color.Black
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = foregroundFor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = foregroundFor(primaryContainer),
            secondary = secondary,
            onSecondary = foregroundFor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = foregroundFor(secondaryContainer),
            tertiary = tertiary,
            onTertiary = foregroundFor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = foregroundFor(tertiaryContainer),
            background = background,
            onBackground = foregroundFor(background),
            surface = surface,
            onSurface = foregroundFor(surface),
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = foregroundFor(surfaceVariant),
            outline = outline,
            outlineVariant = lerp(outline, surface, 0.55f),
            error = error,
            onError = foregroundFor(error),
            errorContainer = errorContainer,
            onErrorContainer = foregroundFor(errorContainer),
            inverseSurface = palette.darkSurface,
            inverseOnSurface = foregroundFor(palette.darkSurface),
            inversePrimary = palette.darkPrimary,
            scrim = Color.Black
        )
    }
    return common
}

fun appColorScheme(option: AppThemeOption, dark: Boolean): ColorScheme =
    themedColorScheme(paletteFor(option), dark)

fun appThemePreviewColors(option: AppThemeOption, dark: Boolean): List<Color> {
    val palette = paletteFor(option)
    return if (dark) {
        listOf(palette.darkPrimary, palette.darkSecondary, palette.darkTertiary)
    } else {
        listOf(palette.lightPrimary, palette.lightSecondary, palette.lightTertiary)
    }
}

fun profileThemeColors(rawTheme: String?, dark: Boolean): ProfileThemeColors {
    val option = AppThemeOption.fromStorage(rawTheme)
    val scheme = appColorScheme(option, dark)
    return ProfileThemeColors(
        background = scheme.primaryContainer,
        foreground = scheme.onPrimaryContainer,
        accent = scheme.primary,
        accentForeground = scheme.onPrimary,
        secondary = scheme.secondary,
        messageBackground = scheme.secondaryContainer,
        messageForeground = scheme.onSecondaryContainer
    )
}
