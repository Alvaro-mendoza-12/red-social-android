package conectate.com.presentation.messages

import conectate.com.data.model.Message

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
        "poll" -> "Encuesta"
        "document", "file" -> "Archivo"
        else -> message.content
    }
}
