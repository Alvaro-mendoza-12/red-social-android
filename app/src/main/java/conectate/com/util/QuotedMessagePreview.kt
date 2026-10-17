package conectate.com.util

import conectate.com.data.model.Message

/** Shared by the reply composer and delivered quote in all three chat types. */
internal fun quotedMessagePreview(message: Message): String {
    if (message.deleted_for_all) return "Mensaje eliminado"
    val content = message.content.trim()
    return when (message.content_type) {
        "birthday_card" -> "🎂 Tarjeta de cumpleaños"
        "image" -> "📷 Imagen"
        "video" -> "📹 Video"
        "view_once_image" -> "📷 Foto para ver una vez"
        "view_once_video" -> "📹 Video para ver una vez"
        "sticker" -> "Sticker"
        "gif" -> "GIF"
        "audio" -> "🎤 Audio" + content.takeIf { it.matches(Regex("[0-9]{1,3}:[0-9]{2}")) }?.let { " $it" }.orEmpty()
        "document", "file" -> documentMessageLabel(content, null)
        "poll" -> content.ifBlank { "Encuesta" }
        "contact" -> "Contacto"
        "location" -> "Ubicación"
        else -> content.ifBlank { "Mensaje" }
    }
}
