package conectate.com.data.supabase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.HttpUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class StorageUploadHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message) {
    val canRecoverOffset: Boolean
        get() = statusCode == 409 || (statusCode in 500..599 && statusCode != 503)
}

private val uploadRequestIdPattern = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")

internal fun Response.storageUploadFailure(fallbackMessage: String): StorageUploadHttpException {
    val message = when (code) {
        401 -> "Tu sesión venció. Inicia sesión y vuelve a subir el archivo."
        403 -> "No tienes permiso para subir este archivo."
        413 -> "El archivo supera el tamaño permitido. Selecciona uno más pequeño."
        415, 422 -> "No pudimos validar el archivo. Selecciona otro archivo compatible."
        429 -> "Hay demasiadas subidas en este momento. Espera antes de volver a intentarlo."
        503 -> "Las subidas están temporalmente interrumpidas. Vuelve a intentarlo más tarde."
        else -> fallbackMessage
    }
    val reference = header("X-Request-ID")?.takeIf(uploadRequestIdPattern::matches)
    return StorageUploadHttpException(code, if (reference == null) message else "$message Referencia $reference.")
}

/** Cancelling a screen/account operation must also stop the upload on the wire. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun Call.awaitStorageResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** OPTIONS may be consumed by a generic CORS handler. The real creation response is authoritative. */
internal suspend fun Call.createTusSessionOrNull(endpoint: HttpUrl): HttpUrl? = awaitStorageResponse().use { response ->
    // Only a route explicitly absent/unsupported permits a legacy stream upload.
    // Authentication, throttling, server failures and transport failures must retain resumability.
    if (response.code in setOf(404, 405, 501)) return null
    if (response.code != 201) throw response.storageUploadFailure("No se pudo preparar la subida. Inténtalo de nuevo.")
    resolveTusSessionUrl(endpoint, response.header("Location").orEmpty())
}
