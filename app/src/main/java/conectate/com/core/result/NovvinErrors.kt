package conectate.com.core.result

import conectate.com.core.network.ReadDeadlineExceededException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import java.net.SocketTimeoutException

internal enum class ErrorCharacter { Confused, Guardian, Protector, Detective, Overloaded, Sleeper }
internal enum class ErrorLevel { Notice, Connection, Critical }

internal data class NovvinProblem(
    val code: Int?,
    val character: ErrorCharacter,
    val level: ErrorLevel,
    val title: String,
    val description: String,
    private val spokenCode: String = "",
) {
    val narration: String
        get() = if (code == null) "$title. $description" else "Error $spokenCode. $title. $description"
}

internal class HttpStatusFailure(val status: Int, message: String) : Exception(message)

internal object NovvinErrors {
    val catalog = listOf(
        NovvinProblem(400, ErrorCharacter.Confused, ErrorLevel.Notice, "Solicitud incorrecta",
            "Algo no encaja. Revisa lo que ingresaste e inténtalo de nuevo.", "cuatrocientos"),
        NovvinProblem(401, ErrorCharacter.Guardian, ErrorLevel.Notice, "Vuelve a iniciar sesión",
            "Necesitas una sesión válida para continuar.", "cuatrocientos uno"),
        NovvinProblem(403, ErrorCharacter.Protector, ErrorLevel.Notice, "Acceso restringido",
            "Este espacio está protegido. Tu cuenta no tiene permiso para entrar.", "cuatrocientos tres"),
        NovvinProblem(404, ErrorCharacter.Detective, ErrorLevel.Notice, "No encontramos esta página",
            "Seguí la pista, pero el enlace pudo cambiar o el contenido ya no está disponible.", "cuatrocientos cuatro"),
        NovvinProblem(408, ErrorCharacter.Sleeper, ErrorLevel.Connection, "Se agotó el tiempo de espera",
            "La solicitud tardó más de lo esperado. Revisa la conexión y vuelve a intentarlo.", "cuatrocientos ocho"),
        NovvinProblem(409, ErrorCharacter.Confused, ErrorLevel.Notice, "El contenido cambió",
            "Parece que alguien hizo un cambio. Actualiza y revisa los datos antes de continuar.", "cuatrocientos nueve"),
        NovvinProblem(422, ErrorCharacter.Confused, ErrorLevel.Notice, "Revisa los datos",
            "La solicitud llegó, pero falta corregir un dato antes de continuar.", "cuatrocientos veintidós"),
        NovvinProblem(429, ErrorCharacter.Protector, ErrorLevel.Notice, "Demasiadas solicitudes",
            "Hagamos una pausa. Espera un momento antes de volver a intentarlo.", "cuatrocientos veintinueve"),
        NovvinProblem(500, ErrorCharacter.Overloaded, ErrorLevel.Critical, "Algo salió mal",
            "Un problema en NOVVIN impidió completar la acción. Puedes volver a intentarlo.", "quinientos"),
        NovvinProblem(502, ErrorCharacter.Overloaded, ErrorLevel.Connection, "No pudimos conectar con el servicio",
            "Un servicio no respondió como esperaba. Inténtalo en unos momentos.", "quinientos dos"),
        NovvinProblem(503, ErrorCharacter.Sleeper, ErrorLevel.Connection, "Servicio no disponible",
            "El servicio se está tomando un descanso. Inténtalo más tarde.", "quinientos tres"),
        NovvinProblem(504, ErrorCharacter.Sleeper, ErrorLevel.Connection, "El servicio tardó demasiado",
            "La respuesta no llegó a tiempo. Inténtalo en unos momentos.", "quinientos cuatro"),
    )
    private val byCode = catalog.associateBy { it.code }
    val offline = NovvinProblem(null, ErrorCharacter.Sleeper, ErrorLevel.Connection,
        "No tienes conexión a internet", "Revisa la conexión y vuelve a intentarlo.")

    val unexpected = NovvinProblem(null, ErrorCharacter.Overloaded, ErrorLevel.Critical,
        "Algo salió mal", "Un problema en NOVVIN impidió completar la acción. Puedes volver a intentarlo.")

    operator fun get(code: Int): NovvinProblem? = byCode[code]

    fun from(error: Throwable): NovvinProblem? {
        var current: Throwable? = error
        repeat(8) {
            val cause = current ?: return null
            val problem = when (cause) {
                is HttpStatusFailure -> get(cause.status)
                is ResponseException -> get(cause.response.status.value)
                is HttpRequestTimeoutException, is SocketTimeoutException, is ReadDeadlineExceededException -> get(408)
                is java.net.UnknownHostException, is java.net.ConnectException -> offline
                else -> null
            }
            if (problem != null) return problem
            current = cause.cause
        }
        return null
    }
}
