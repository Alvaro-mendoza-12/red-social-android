package conectate.com.data.repository

internal fun liveGuestRequestErrorMessage(error: Throwable): String {
    val reason = generateSequence(error) { it.cause }.take(8)
        .mapNotNull { it.message }.joinToString(" ").uppercase()
    return when {
        "LIVE_GUEST_REQUESTS_DISABLED" in reason -> "El creador ha cerrado las solicitudes para unirse."
        "LIVE_GUEST_REQUEST_CONFLICT" in reason -> "Tu solicitud ya cambió. Cierra este panel y vuelve a abrirlo."
        "LIVE_GUEST_LIMIT" in reason || "LIVE_GUEST_CAPACITY" in reason -> "Todos los lugares están ocupados. Intenta cuando quede uno libre."
        "LIVE_NOT_ACTIVE" in reason || "LIVE_NOT_FOUND" in reason -> "Este directo ya no está disponible."
        else -> "No se pudo enviar la solicitud. Revisa tu conexión y vuelve a intentarlo."
    }
}
