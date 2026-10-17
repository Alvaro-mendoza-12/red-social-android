package conectate.com.presentation.messages

import conectate.com.data.model.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun inboxMessagePreview(message: Message?): String = when {
    message == null -> "Sin actividad reciente"
    message.deleted_for_all -> "Mensaje eliminado"
    else -> when (message.content_type) {
        "image" -> "Foto"
        "video" -> "Video"
        "audio" -> "Audio"
        "gif" -> "GIF"
        "sticker" -> "Sticker"
        "contact" -> "Contacto"
        "location" -> "Ubicación"
        "question" -> readableCollectionPreview(message.content)
        "birthday_card" -> "🎂 Tarjeta de cumpleaños"
        "poll" -> "Encuesta"
        "document", "file" -> "Archivo"
        else -> message.content
    }
}

/** Defensive decoding for historical inbox rows that still contain a question descriptor. */
internal fun readableCollectionPreview(content: String?): String {
    val text=content.orEmpty().trim()
    if(!text.startsWith("{")) return text
    val value=runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return text
    if(!value.containsKey("questionId")) return text
    return runCatching{value["prompt"]?.jsonPrimitive?.contentOrNull?.trim()}.getOrNull()?.takeIf{it.isNotEmpty()}?.let{"Pregunta: ${it.take(160)}"} ?: "Pregunta de la comunidad"
}
