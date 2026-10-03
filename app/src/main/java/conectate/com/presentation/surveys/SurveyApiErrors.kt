package conectate.com.presentation.surveys

/** An absent module route is different from an individual survey without access. */
internal fun surveyApiErrorMessage(status: Int, path: String, serverMessage: String?): String {
    serverMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val route = path.substringBefore('?').trimEnd('/')
    return when (status) {
        401 -> "Tu sesión venció. Inicia sesión de nuevo."
        403 -> "No tienes permiso para realizar esta operación."
        404 -> when (route) {
            "/v1/surveys/me", "/v1/surveys/forms", "/v1/surveys/campaigns" ->
                "El servicio de Encuestas no está disponible en este momento. Inténtalo más adelante."
            else -> "Encuesta no disponible para esta cuenta."
        }
        429 -> "Demasiadas solicitudes. Espera un momento."
        503 -> "El servicio no está disponible. Inténtalo de nuevo."
        else -> "No se pudo completar la operación ($status)."
    }
}
