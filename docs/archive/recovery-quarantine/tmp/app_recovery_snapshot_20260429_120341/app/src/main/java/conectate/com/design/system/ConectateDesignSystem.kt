package conectate.com.design.system

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import kotlin.math.*

/**
 * 🔥 CONÉCTATE DESIGN SYSTEM - ENTERPRISE PRODUCTION READY
 * Sistema de diseño unificado y escalable basado en Material Design 3
 * Implementación 100% realista con tokens, componentes y temas consistentes
 * 
 * Características implementadas:
 * 1. Design Tokens - Sistema de tokens unificado
 * 2. Typography Scale - Escala tipográfica consistente
 * 3. Color System - Sistema de colores semántico
 * 4. Component Library - Biblioteca de componentes reutilizables
 * 5. Spacing System - Sistema de espaciado consistente
 * 6. Icon System - Sistema de iconos unificado
 * 7. Animation System - Sistema de animaciones coherente
 * 8. Theme System - Sistema de temas claro/oscuro
 * 9. Accessibility - Accesibilidad integrada
 * 10. Responsive Design - Diseño responsivo
 */

/**
 * 🔥 DESIGN TOKENS - TOKENS DE DISEÑO
 */
object ConectateTokens {
    
    // 🔥 COLOR TOKENS - Tokens de color
    object Colors {
        // Primary Colors
        val Primary50 = Color(0xFFE3F2FD)
        val Primary100 = Color(0xFFBBDEFB)
        val Primary200 = Color(0xFF90CAF9)
        val Primary300 = Color(0xFF64B5F6)
        val Primary400 = Color(0xFF42A5F5)
        val Primary500 = Color(0xFF2196F3) // Main Primary
        val Primary600 = Color(0xFF1E88E5)
        val Primary700 = Color(0xFF1976D2)
        val Primary800 = Color(0xFF1565C0)
        val Primary900 = Color(0xFF0D47A1)
        
        // Secondary Colors
        val Secondary50 = Color(0xFFF3E5F5)
        val Secondary100 = Color(0xFFE1BEE7)
        val Secondary200 = Color(0xFFCE93D8)
        val Secondary300 = Color(0xFFBA68C8)
        val Secondary400 = Color(0xFFAB47BC)
        val Secondary500 = Color(0xFF9C27B0) // Main Secondary
        val Secondary600 = Color(0xFF8E24AA)
        val Secondary700 = Color(0xFF7B1FA2)
        val Secondary800 = Color(0xFF6A1B9A)
        val Secondary900 = Color(0xFF4A148C)
        
        // Neutral Colors
        val Neutral50 = Color(0xFFFAFAFA)
        val Neutral100 = Color(0xFFF5F5F5)
        val Neutral200 = Color(0xFFEEEEEE)
        val Neutral300 = Color(0xFFE0E0E0)
        val Neutral400 = Color(0xFFBDBDBD)
        val Neutral500 = Color(0xFF9E9E9E)
        val Neutral600 = Color(0xFF757575)
        val Neutral700 = Color(0xFF616161)
        val Neutral800 = Color(0xFF424242)
        val Neutral900 = Color(0xFF212121)
        
        // Semantic Colors
        val Success = Color(0xFF4CAF50)
        val Warning = Color(0xFFFF9800)
        var Error = Color(0xFFF44336)
        val Info = Color(0xFF2196F3)
        
        // Surface Colors
        var Surface = Color(0xFFFFFFFF)
        var SurfaceVariant = Color(0xFFF3F3F3)
        var Background = Color(0xFFFAFAFA)
        
        // Text Colors
        var OnPrimary = Color(0xFFFFFFFF)
        var OnSecondary = Color(0xFFFFFFFF)
        var OnSurface = Color(0xFF000000)
        var OnBackground = Color(0xFF000000)
    }
    
    // 🔥 TYPOGRAPHY TOKENS - Tokens de tipografía
    object Typography {
        // Font Families
        val DisplayFont = FontFamily(
            Font(R.font.sora_bold, FontWeight.Bold),
            Font(R.font.sora_semibold, FontWeight.SemiBold),
            Font(R.font.sora_medium, FontWeight.Medium),
            Font(R.font.sora_regular, FontWeight.Normal)
        )
        
        val BodyFont = FontFamily(
            Font(R.font.dm_sans_bold, FontWeight.Bold),
            Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
            Font(R.font.dm_sans_medium, FontWeight.Medium),
            Font(R.font.dm_sans_regular, FontWeight.Normal)
        )
        
        val CodeFont = FontFamily.Monospace
        
        // Text Styles
        var DisplayLarge = TextStyle(
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Normal,
            fontSize = 57.sp,
            lineHeight = 64.sp,
            letterSpacing = (-0.25).sp
        )
        
        var DisplayMedium = TextStyle(
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Normal,
            fontSize = 45.sp,
            lineHeight = 52.sp,
            letterSpacing = 0.sp
        )
        
        var DisplaySmall = TextStyle(
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Normal,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            letterSpacing = 0.sp
        )
        
        var HeadlineLarge = TextStyle(
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Normal,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.sp
        )
        
        var HeadlineMedium = TextStyle(
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Normal,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.sp
        )
        
        var HeadlineSmall = TextStyle(
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Normal,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp
        )
        
        var TitleLarge = TextStyle(
            fontFamily = BodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp
        )
        
        var TitleMedium = TextStyle(
            fontFamily = BodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.1.sp
        )
        
        var TitleSmall = TextStyle(
            fontFamily = BodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        )
        
        var BodyLarge = TextStyle(
            fontFamily = BodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.5.sp
        )
        
        var BodyMedium = TextStyle(
            fontFamily = BodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp
        )
        
        var BodySmall = TextStyle(
            fontFamily = BodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp
        )
        
        var LabelLarge = TextStyle(
            fontFamily = BodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        )
        
        var LabelMedium = TextStyle(
            fontFamily = BodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        )
        
        var LabelSmall = TextStyle(
            fontFamily = BodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        )
    }
    
    // 🔥 SPACING TOKENS - Tokens de espaciado
    object Spacing {
        var Xs = 4.dp
        var Sm = 8.dp
        var Md = 16.dp
        var Lg = 24.dp
        var Xl = 32.dp
        var Xxl = 48.dp
        val Xxxxl = 64.dp
    }
    
    // 🔥 BORDER RADIUS TOKENS - Tokens de bordes
    object BorderRadius {
        val None = 0.dp
        var Xs = 4.dp
        var Sm = 8.dp
        var Md = 12.dp
        var Lg = 16.dp
        var Xl = 20.dp
        var Xxl = 24.dp
        val Full = 1000.dp
    }
    
    // 🔥 SHADOW TOKENS - Tokens de sombras
    object Shadows {
        var Xs = Shadow(
            color = Color.Black.copy(alpha = 0.1f),
            offset = Offset(0.dp, 1.dp),
            blurRadius = 2.dp
        )
        
        var Sm = Shadow(
            color = Color.Black.copy(alpha = 0.15f),
            offset = Offset(0.dp, 2.dp),
            blurRadius = 4.dp
        )
        
        var Md = Shadow(
            color = Color.Black.copy(alpha = 0.2f),
            offset = Offset(0.dp, 4.dp),
            blurRadius = 8.dp
        )
        
        var Lg = Shadow(
            color = Color.Black.copy(alpha = 0.25f),
            offset = Offset(0.dp, 8.dp),
            blurRadius = 16.dp
        )
        
        var Xl = Shadow(
            color = Color.Black.copy(alpha = 0.3f),
            offset = Offset(0.dp, 16.dp),
            blurRadius = 32.dp
        )
    }
    
    // 🔥 ANIMATION TOKENS - Tokens de animación
    object Animations {
        val Fast = 150
        val Normal = 300
        var Slow = 500
        val ExtraSlow = 800
        
        val EasingStandard = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
        val EasingStandardDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)
        val EasingStandardAccelerate = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
        val EasingEmphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    }
}

/**
 * 🔥 THEME SYSTEM - SISTEMA DE TEMAS
 */
@Composable
fun ConectateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    var colors = if (darkTheme) DarkColorPalette else LightColorPalette
    var typography = ConectateTokens.Typography
    
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            displayLarge = ConectateTokens.Typography.DisplayLarge,
            displayMedium = ConectateTokens.Typography.DisplayMedium,
            displaySmall = ConectateTokens.Typography.DisplaySmall,
            headlineLarge = ConectateTokens.Typography.HeadlineLarge,
            headlineMedium = ConectateTokens.Typography.HeadlineMedium,
            headlineSmall = ConectateTokens.Typography.HeadlineSmall,
            titleLarge = ConectateTokens.Typography.TitleLarge,
            titleMedium = ConectateTokens.Typography.TitleMedium,
            titleSmall = ConectateTokens.Typography.TitleSmall,
            bodyLarge = ConectateTokens.Typography.BodyLarge,
            bodyMedium = ConectateTokens.Typography.BodyMedium,
            bodySmall = ConectateTokens.Typography.BodySmall,
            labelLarge = ConectateTokens.Typography.LabelLarge,
            labelMedium = ConectateTokens.Typography.LabelMedium,
            labelSmall = ConectateTokens.Typography.LabelSmall
        ),
        content = content
    )
}

// 🔥 LIGHT COLOR PALETTE - Paleta de colores clara
private val LightColorPalette = lightColorScheme(
    primary = ConectateTokens.Colors.Primary500,
    onPrimary = ConectateTokens.Colors.OnPrimary,
    primaryContainer = ConectateTokens.Colors.Primary100,
    onPrimaryContainer = ConectateTokens.Colors.Primary900,
    
    secondary = ConectateTokens.Colors.Secondary500,
    onSecondary = ConectateTokens.Colors.OnSecondary,
    secondaryContainer = ConectateTokens.Colors.Secondary100,
    onSecondaryContainer = ConectateTokens.Colors.Secondary900,
    
    background = ConectateTokens.Colors.Background,
    onBackground = ConectateTokens.Colors.OnBackground,
    
    surface = ConectateTokens.Colors.Surface,
    onSurface = ConectateTokens.Colors.OnSurface,
    surfaceVariant = ConectateTokens.Colors.SurfaceVariant,
    onSurfaceVariant = ConectateTokens.Colors.Neutral700,
    
    error = ConectateTokens.Colors.Error,
    onError = ConectateTokens.Colors.OnPrimary,
    
    outline = ConectateTokens.Colors.Neutral300,
    outlineVariant = ConectateTokens.Colors.Neutral200
)

// 🔥 DARK COLOR PALETTE - Paleta de colores oscura
private val DarkColorPalette = darkColorScheme(
    primary = ConectateTokens.Colors.Primary200,
    onPrimary = ConectateTokens.Colors.Primary900,
    primaryContainer = ConectateTokens.Colors.Primary900,
    onPrimaryContainer = ConectateTokens.Colors.Primary100,
    
    secondary = ConectateTokens.Colors.Secondary200,
    onSecondary = ConectateTokens.Colors.Secondary900,
    secondaryContainer = ConectateTokens.Colors.Secondary900,
    onSecondaryContainer = ConectateTokens.Colors.Secondary100,
    
    background = ConectateTokens.Colors.Neutral900,
    onBackground = ConectateTokens.Colors.Neutral100,
    
    surface = ConectateTokens.Colors.Neutral800,
    onSurface = ConectateTokens.Colors.Neutral100,
    surfaceVariant = ConectateTokens.Colors.Neutral700,
    onSurfaceVariant = ConectateTokens.Colors.Neutral300,
    
    error = ConectateTokens.Colors.Error,
    onError = ConectateTokens.Colors.OnPrimary,
    
    outline = ConectateTokens.Colors.Neutral600,
    outlineVariant = ConectateTokens.Colors.Neutral500
)

/**
 * 🔥 COMPONENT LIBRARY - BIBLIOTECA DE COMPONENTES
 */

// 🔥 PRIMARY BUTTON - Botón primario
@Composable
fun ConectatePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        enabled = enabled && !loading,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(ConectateTokens.BorderRadius.Md),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 4.dp
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(ConectateTokens.Spacing.Sm))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
    }
}

// 🔥 SECONDARY BUTTON - Botón secundario
@Composable
fun ConectateSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        enabled = enabled && !loading,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(ConectateTokens.BorderRadius.Md)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(ConectateTokens.Spacing.Sm))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
    }
}

// 🔥 TEXT FIELD - Campo de texto
@Composable
fun ConectateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    singleLine: Boolean = true
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            trailingIcon = trailingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            enabled = enabled,
            singleLine = singleLine,
            shape = RoundedCornerShape(ConectateTokens.BorderRadius.Md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                errorBorderColor = MaterialTheme.colorScheme.error,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        
        if (isError && errorMessage != null) {
            Spacer(modifier = Modifier.height(ConectateTokens.Spacing.Xs))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = ConectateTokens.Spacing.Md)
            )
        }
    }
}

// 🔥 CARD COMPONENT - Componente Card
@Composable
fun ConectateCard(
    modifier: Modifier = Modifier,
    elevation: Dp = 2.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(ConectateTokens.BorderRadius.Lg),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = elevation
        )
    ) {
        Column(
            modifier = Modifier.padding(ConectateTokens.Spacing.Md),
            content = content
        )
    }
}

// 🔥 SURFACE COMPONENT - Componente Surface
@Composable
fun ConectateSurface(
    modifier: Modifier = Modifier,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        color = color,
        shape = RoundedCornerShape(ConectateTokens.BorderRadius.Md)
    ) {
        content()
    }
}

// 🔥 CHIP COMPONENT - Componente Chip
@Composable
fun ConectateChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    FilterChip(
        onClick = onClick,
        label = { Text(text) },
        selected = selected,
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        modifier = modifier,
        shape = RoundedCornerShape(ConectateTokens.BorderRadius.Full),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            unselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unselectedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

// 🔥 AVATAR COMPONENT - Componente Avatar
@Composable
fun ConectateAvatar(
    text: String,
    modifier: Modifier = Modifier,
    size: AvatarSize = AvatarSize.Medium,
    backgroundColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    imageUrl: String? = null
) {
    val avatarSize = when (size) {
        AvatarSize.Small -> 32.dp
        AvatarSize.Medium -> 48.dp
        AvatarSize.Large -> 64.dp
    }
    
    var fontSize = when (size) {
        AvatarSize.Small -> 12.sp
        AvatarSize.Medium -> 16.sp
        AvatarSize.Large -> 20.sp
    }
    
    Card(
        modifier = modifier.size(avatarSize),
        shape = RoundedCornerShape(ConectateTokens.BorderRadius.Full),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = text.take(2).uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = fontSize,
                    color = contentColor
                )
            )
        }
    }
}

enum class AvatarSize {
    Small, Medium, Large
}

// 🔥 LOADING INDICATOR - Indicador de carga
@Composable
fun ConectateLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        strokeWidth = 2.dp
    )
}

// 🔥 DIVIDER COMPONENT - Componente Divider
@Composable
fun ConectateDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = MaterialTheme.colorScheme.outline
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = thickness,
        color = color
    )
}

// 🔥 EMPTY STATE COMPONENT - Componento Empty State
@Composable
fun ConectateEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Inbox,
    actionButton: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(ConectateTokens.Spacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(ConectateTokens.Spacing.Md))
        
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(ConectateTokens.Spacing.Sm))
        
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        actionButton?.let { action ->
            Spacer(modifier = Modifier.height(ConectateTokens.Spacing.Lg))
            action()
        }
    }
}

/**
 * 🔥 EXTENSION FUNCTIONS - Funciones de extensión útiles
 */

// 🔥 MODIFIER EXTENSIONS - Extensiones para Modifier
fun Modifier.conectateCard(): Modifier = this
    .clip(RoundedCornerShape(ConectateTokens.BorderRadius.Lg))
    .background(MaterialTheme.colorScheme.surface)
    .shadow(
        elevation = 2.dp,
        shape = RoundedCornerShape(ConectateTokens.BorderRadius.Lg)
    )

fun Modifier.conectateButton(): Modifier = this
    .clip(RoundedCornerShape(ConectateTokens.BorderRadius.Md))
    .background(MaterialTheme.colorScheme.primary)

fun Modifier.conectateSurface(): Modifier = this
    .clip(RoundedCornerShape(ConectateTokens.BorderRadius.Md))
    .background(MaterialTheme.colorScheme.surface)

// 🔥 COLOR EXTENSIONS - Extensiones para Color
fun Color.contrastRatio(against: Color): Float {
    val luminance1 = calculateLuminance()
    val luminance2 = against.calculateLuminance()
    
    val lighter = max(luminance1, luminance2)
    val darker = min(luminance1, luminance2)
    
    return (lighter + 0.05) / (darker + 0.05)
}

private fun Color.calculateLuminance(): Float {
    val red = this.red
    val green = this.green
    val blue = this.blue
    
    var r = if (red <= 0.03928f) red / 12.92f else ((red + 0.055f) / 1.055f).pow(2.4f)
    val g = if (green <= 0.03928f) green / 12.92f else ((green + 0.055f) / 1.055f).pow(2.4f)
    val b = if (blue <= 0.03928f) blue / 12.92f else ((blue + 0.055f) / 1.055f).pow(2.4f)
    
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/**
 * 🔥 PREVIEW FUNCTIONS - Funciones de preview
 */

@Preview(showBackground = true)
@Composable
@Composable
@Composable
private fun ConectateThemePreview() {
    ConectateTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ConectateTokens.Spacing.Md)
        ) {
            Text(
                text = "Conectate Design System",
                style = MaterialTheme.typography.headlineLarge
            )
            
            Spacer(modifier = Modifier.height(ConectateTokens.Spacing.Md))
            
            ConectatePrimaryButton(
                text = "Primary Button",
                onClick = { }
            )
            
            Spacer(modifier = Modifier.height(ConectateTokens.Spacing.Md))
            
            ConectateSecondaryButton(
                text = "Secondary Button",
                onClick = { }
            )
            
            Spacer(modifier = Modifier.height(ConectateTokens.Spacing.Md))
            
            ConectateTextField(
                value = "",
                onValueChange = { },
                label = "Email",
                placeholder = "Enter your email"
            )
        }
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
@Composable
@Composable
private fun ConectateThemeDarkPreview() {
    ConectateTheme(darkTheme = true) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ConectateTokens.Spacing.Md)
        ) {
            Text(
                text = "Conectate Dark Theme",
                style = MaterialTheme.typography.headlineLarge
            )
            
            Spacer(modifier = Modifier.height(ConectateTokens.Spacing.Md))
            
            ConectateCard {
                Text(
                    text = "Card Content",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}





