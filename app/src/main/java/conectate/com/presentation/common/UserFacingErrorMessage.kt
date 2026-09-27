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

        looksLikeStoragePathIssue(rawMessage) ->
            "El archivo seleccionado no es válido o su ruta está dañada. Vuelve a intentarlo con otro archivo."

        looksLikeSignedUrlIssue(rawMessage) ->
            "No se pudo preparar el acceso temporal al archivo. Inténtalo de nuevo."

        looksLikeMediaPolicyIssue(rawMessage) ->
            "El archivo fue bloqueado por la política de seguridad. Revisa el tipo, tamaño o extensión antes de volver a intentarlo."

        throwable is HttpRequestTimeoutException ||
            throwable is SocketTimeoutException ||
            rawMessage.contains("timeout", ignoreCase = true) ||
            rawMessage.contains("timed out", ignoreCase = true) ->
            "El servidor de Novvin no respondió a tiempo. Inténtalo de nuevo en unos momentos."

        throwable is IOException ||
            rawMessage.contains("network", ignoreCase = true) ||
            rawMessage.contains("unable to resolve host", ignoreCase = true) ||
            rawMessage.contains("failed to connect", ignoreCase = true) ||
            rawMessage.contains("connection reset", ignoreCase = true) ||
            rawMessage.contains("software caused connection abort", ignoreCase = true) ||
            rawMessage.contains("no route to host", ignoreCase = true) ->
            "No pudimos llegar al servidor de Novvin desde este dispositivo. Inténtalo otra vez en unos momentos."

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

        rawMessage.contains("PASSWORD_TOO_SHORT", ignoreCase = true) ||
            rawMessage.contains("EMAIL_AND_STRONG_PASSWORD_REQUIRED", ignoreCase = true) ->
            "La contraseña debe tener al menos 8 caracteres."

        rawMessage.contains("LIVE_CHAT_PAUSED", ignoreCase = true) ->
            "El chat está pausado temporalmente por el equipo de moderación."

        rawMessage.contains("LIVE_COMMENT_BLOCKED", ignoreCase = true) ->
            "Ese comentario contiene una palabra que el equipo del directo bloqueó."

        rawMessage.contains("LIVE_COMMENT_RATE_LIMITED", ignoreCase = true) ->
            "Estás comentando muy rápido. Espera un momento para volver a escribir."

        rawMessage.contains("LIVE_BANNED", ignoreCase = true) ->
            "El equipo de moderación restringió tu acceso a este directo."

        rawMessage.contains("LIVE_MODERATOR_REQUIRED", ignoreCase = true) ||
            rawMessage.contains("LIVE_HOST_REQUIRED", ignoreCase = true) ->
            "No tienes permisos para realizar esa acción en el directo."

        rawMessage.contains("job was cancelled", ignoreCase = true) ||
            rawMessage.contains("job was canceled", ignoreCase = true) ||
            rawMessage.contains("cancellationexception", ignoreCase = true) ->
            "La operación se interrumpió antes de terminar. Inténtalo nuevamente."

        looksLikeWebAccessBridgeIssue(rawMessage) ->
            "La conexión entre la app y la web no está disponible en este momento. Inténtalo más tarde."

        rawMessage.contains("secure storage", ignoreCase = true) ||
            rawMessage.contains("almacenamiento seguro", ignoreCase = true) ->
            "El almacenamiento seguro del dispositivo no está disponible. Reinicia el dispositivo o vuelve a intentar en un entorno confiable."

        looksLikeTransportImplementationMessage(rawMessage) ->
            "La conexión con el servidor se interrumpió. Espera un momento e inténtalo nuevamente."

        looksLikeTechnicalBackendMessage(rawMessage) ->
            "El servicio no está disponible en este momento. Inténtalo nuevamente más tarde."

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

internal fun looksLikeWebAccessBridgeIssue(message: String?): Boolean {
    val normalized = message?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return normalized.contains("web-login-pairing") ||
        normalized.contains("pairing") ||
        normalized.contains("qr") ||
        normalized.contains("edge function")
}

internal fun looksLikeTechnicalBackendMessage(message: String?): Boolean {
    val normalized = message?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return normalized.contains("42p") ||
        normalized.contains("pgrst") ||
        normalized.contains("internal_error") ||
        normalized.contains("\"error\"") ||
        normalized.contains("{\"") ||
        normalized.contains("http 500") ||
        normalized.contains("http 502") ||
        normalized.contains("http 503") ||
        normalized.contains("schema cache") ||
        normalized.contains("could not find the table") ||
        normalized.contains("json object requested") ||
        normalized.contains("unexpected token") ||
        normalized.contains("<!doctype") ||
        normalized.contains("illegalstateexception") ||
        normalized.contains("nullpointerexception") ||
        normalized.contains("stack trace") ||
        (
            normalized.contains("function") &&
                (
                    normalized.contains("public.") ||
                        normalized.contains("rpc") ||
                        normalized.contains("missing")
                    )
            ) ||
        (
            normalized.contains("relation") &&
                (
                    normalized.contains("does not exist") ||
                        normalized.contains("missing")
                    )
            )
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
        normalized.contains("no access token available") ||
        normalized.contains("no token") ||
        normalized.contains("no token de acceso") ||
        normalized.contains("invalid jwt") ||
        normalized.contains("pgrst303") ||
        normalized.contains("not authenticated") ||
        normalized.contains("not logged in") ||
        normalized.contains("no user logged in") ||
        normalized.contains("no hay sesión activa") ||
        normalized.contains("no hay sesion activa") ||
        normalized.contains("unauthorized") ||
        normalized.contains("token revoked") ||
        normalized.contains("session revoked") ||
        normalized.contains("sesión revocada") ||
        normalized.contains("sesion revocada") ||
        normalized.contains("la cuenta activa ya no está disponible") ||
        normalized.contains("la cuenta activa ya no esta disponible")
}

internal fun looksLikeTransportImplementationMessage(message: String?): Boolean {
    val normalized = message?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return normalized.contains("websocket") ||
        normalized.contains("web socket") ||
        normalized.contains("closedreceivechannel") ||
        normalized.contains("channel was closed") ||
        normalized.contains("connection was closed") ||
        normalized.contains("io.ktor.") ||
        normalized.contains("java.net.") ||
        normalized.contains("exception:")
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

internal fun looksLikeStoragePathIssue(message: String?): Boolean {
    val normalized = message?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return normalized.contains("invalid storage path") ||
        normalized.contains("unsafe file path") ||
        normalized.contains("invalid file name")
}

internal fun looksLikeSignedUrlIssue(message: String?): Boolean {
    val normalized = message?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return normalized.contains("no signedurl in response") ||
        normalized.contains("signedurl") ||
        normalized.contains("no se pudo generar el enlace del archivo")
}

internal fun looksLikeMediaPolicyIssue(message: String?): Boolean {
    val normalized = message?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return normalized.contains("media_policy_blocked") ||
        normalized.contains("upload blocked by media policy") ||
        normalized.contains("blocked by media policy") ||
        normalized.contains("file signature does not match declared content type") ||
        normalized.contains("unsupported content type") ||
        normalized.contains("unsupported file extension") ||
        normalized.contains("file too large for") ||
        normalized.contains("executable payload is not allowed")
}







