package conectate.com.util

/** Catalog labels and transport URLs are not useful notification text for sticker media. */
internal fun typedMessageNotificationPreview(contentType: String): String? = when (contentType.trim().lowercase()) {
    "sticker" -> "Sticker"
    "gif" -> "GIF"
    "image" -> "Foto"
    "video" -> "Video"
    "audio" -> "Audio"
    "document", "file" -> "Archivo"
    "birthday_card" -> "🎂 Te envió una tarjeta de cumpleaños"
    else -> null
}
