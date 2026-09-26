package conectate.com.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

data class ChatAccentColors(
    val sentBackground: Color,
    val sentContent: Color,
)

val LocalChatAccentKey = staticCompositionLocalOf { "conectate_red" }
val LocalChatBackgroundKey = staticCompositionLocalOf { "deep_black" }

fun resolveChatAccent(key: String, darkTheme: Boolean): ChatAccentColors = when (key.trim().lowercase()) {
    "clean_white" -> ChatAccentColors(
        sentBackground = if (darkTheme) Color(0xFFF4F4F5) else Color.White,
        sentContent = Color(0xFF111114),
    )
    "electric_blue" -> ChatAccentColors(
        sentBackground = if (darkTheme) Color(0xFF287DFF) else Color(0xFF1769E0),
        sentContent = Color.White,
    )
    "graphite" -> ChatAccentColors(
        sentBackground = if (darkTheme) Color(0xFF555B66) else Color(0xFF3E444D),
        sentContent = Color.White,
    )
    else -> ChatAccentColors(
        sentBackground = if (darkTheme) Color(0xFFB71C1C) else Color(0xFFE53935),
        sentContent = Color.White,
    )
}

@Composable
fun currentChatAccentColors(): ChatAccentColors = resolveChatAccent(
    key = LocalChatAccentKey.current,
    darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f,
)
