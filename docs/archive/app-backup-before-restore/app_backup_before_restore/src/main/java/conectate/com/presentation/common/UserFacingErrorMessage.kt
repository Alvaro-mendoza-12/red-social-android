package conectate.com.presentation.common

import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.supabase.mapSupabaseServiceRestrictionMessage
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.network.sockets.SocketTimeoutException
import java.io.IOException

internal fun Throwable?.toUserFacingMessage(
    fallback: String = "Ocurrió un problema. Inténtalo de nuevo."
): String {
    val throwable = this ?: return fallback
    if (throwable is AccountSuspendedException) {
        return throwable.message
    }

    val rawMessage = throwable.message?.trim().orEmpty()
    mapSupabaseServiceRestrictionMessage(rawMessage)?.let { return it }

    return when {
        looksLikeStoredAccountSessionIssue(rawMessage) ->
            "Esta cuenta guardada venció o dejó de ser válida. Inicia sesión manualmente con esa cuenta para volver a guardarla."

        looksLikeSessionIssue(rawMessage) ->
            "Tu sesión venció o dejó de ser válida. Inicia sesión de nuevo."

        throwable is HttpRequestTimeoutException ||
            throwable is SocketTimeoutException ||
            rawMessage.contains("timeout", ignoreCase = true) ||
            rawMessage.contains("timed out", ignoreCase = true) ->
            "La solicitud tardó demasiado. Revisa tu conexión e inténtalo otra vez."

        throwable is IOException ||
            rawMessage.contains("network", ignoreCase = true) ||
            rawMessage.contains("unable to resolve host", ignoreCase = true) ||
            rawMessage.contains("failed to connect", ignoreCase = true) ||
            rawMessage.contains("connection reset", ignoreCase = true) ||
            rawMessage.contains("software caused connection abort", ignoreCase = true) ||
            rawMessage.contains("no route to host", ignoreCase = true) ->
            "No pudimos conectarnos con el servidor. Revisa tu internet e inténtalo otra vez."

        rawMessage.contains("invalid login credentials", ignoreCase = true) ->
            "Correo o contraseña incorrectos."

        rawMessage.contains("user not found", ignoreCase = true) ->
            "No encontramos esa cuenta."

        rawMessage.contains("email not confirmed", ignoreCase = true) ->
            "Tu correo todavía no ha sido confirmado."

        rawMessage.contains("user already registered", ignoreCase = true) ->
            "Ese correo ya está registrado."

        rawMessage.contains("signup requires a valid password", ignoreCase = true) ->
            "La contraseña no cumple los requisitos mínimos."

        rawMessage.contains("secure storage", ignoreCase = true) ||
            rawMessage.contains("almacenamiento seguro", ignoreCase = true) ->
            "El almacenamiento seguro del dispositivo no está disponible. Reinicia el dispositivo o vuelve a intentar en un entorno confiable."

        throwable is ClientRequestException -> when (throwable.response.status.value) {
            401 -> "Tu sesión ya no es válida. Inicia sesión nuevamente."
            403 -> "No tienes permisos para completar esta acción."
            404 -> "No encontramos lo que estabas buscando."
            422 -> "Los datos enviados no son válidos."
            else -> rawMessage.ifBlank { fallback }
        }

        rawMessage.isBlank() -> fallback
        else -> rawMessage
    }
}

internal fun looksLikeSessionIssue(message: String?): Boolean {
    var normalized = message?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return normalized.contains("jwt expired") ||
        normalized.contains("sesión expirada") ||
        normalized.contains("sesion expirada") ||
        normalized.contains("no hay una sesión activa") ||
        normalized.contains("no hay una sesion activa") ||
        normalized.contains("no access token") ||
        normalized.contains("no token") ||
        normalized.contains("invalid jwt") ||
        normalized.contains("pgrst303") ||
        normalized.contains("not authenticated") ||
        normalized.contains("unauthorized") ||
        normalized.contains("la cuenta activa ya no está disponible") ||
        normalized.contains("la cuenta activa ya no esta disponible")
}

internal fun looksLikeStoredAccountSessionIssue(message: String?): Boolean {
    var normalized = message?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return normalized.contains("sesión guardada") ||
        normalized.contains("sesion guardada") ||
        normalized.contains("cuenta guardada") ||
        normalized.contains("la sesión guardada expiró") ||
        normalized.contains("la sesion guardada expiro") ||
        normalized.contains("la sesión importada no coincide") ||
        normalized.contains("la sesion importada no coincide") ||
        normalized.contains("no se pudo restaurar la sesión guardada") ||
        normalized.contains("no se pudo restaurar la sesion guardada") ||
        normalized.contains("invalid refresh token") ||
        normalized.contains("refresh token not found")
}







