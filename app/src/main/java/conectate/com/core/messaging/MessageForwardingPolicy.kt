package conectate.com.core.messaging

/** The server must know the original; a pending message must retain sole ownership of its local file. */
internal fun messageForwardingError(
    deletedForAll: Boolean,
    contentType: String,
    status: String,
    mediaUrl: String?,
): String? = when {
    deletedForAll -> "No se puede reenviar un mensaje eliminado."
    contentType.trim().lowercase().startsWith("view_once") -> "Los mensajes de una sola visualización no se pueden reenviar."
    status.trim().lowercase() !in setOf("sent", "delivered", "read", "received") -> "Espera a que el mensaje se envíe antes de reenviarlo."
    mediaUrl?.trim()?.substringBefore(':')?.lowercase() in setOf("file", "content") -> "El archivo aún está pendiente. Espera a que se envíe antes de reenviarlo."
    contentType.trim().lowercase() == "poll" -> "Las encuestas no admiten reenvíos. Puedes crear otra en el destino."
    contentType.trim().lowercase() == "raffle" || contentType.trim().lowercase().startsWith("system_") ->
        "Este tipo de mensaje no admite reenvíos."
    else -> null
}
