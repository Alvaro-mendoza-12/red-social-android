package conectate.com.presentation.components

import java.util.Locale

/** Old built-in catalog rows were sent as their label instead of an image or Unicode. */
internal fun legacyStickerDisplayContent(content: String): String = when (content.trim().lowercase(Locale.ROOT)) {
    "feliz", "emoji_happy" -> "😀"
    "amor", "emoji_love" -> "❤️"
    "fuego", "emoji_fire" -> "🔥"
    "fiesta", "emoji_party" -> "🥳"
    "robot", "emoji_robot" -> "🤖"
    "estrella", "emoji_star" -> "⭐"
    else -> content.ifBlank { "🧩" }
}
