package conectate.com.data.localfallback

import kotlinx.coroutines.CancellationException

/** Uploads can await a bounded 180-second antivirus scan after the last byte. */
internal object StorageUploadPolicy {
    const val CONNECT_TIMEOUT_MILLIS = 30_000L
    const val RESPONSE_IDLE_TIMEOUT_MILLIS = 240_000L
    const val REQUEST_TIMEOUT_MILLIS = 1_200_000L

    // Only an explicit unsupported route proves that the first endpoint did
    // not commit an object. A timeout, scan rejection, outage or conflict does
    // not authorize a second mutable request.
    fun isUnsupportedRawRoute(status: Int, code: String?, reportedPath: String?, requestedPath: String): Boolean =
        when (status) {
            404 -> code == "NOT_FOUND" && reportedPath == requestedPath
            405 -> code == "METHOD_NOT_ALLOWED"
            501 -> code == "NOT_IMPLEMENTED" || code == "UNSUPPORTED_STORAGE_ROUTE"
            else -> false
        }

    suspend fun <T> upload(raw: suspend () -> Result<T>, multipart: suspend () -> Result<T>): Result<T> {
        val first = raw()
        val error = first.exceptionOrNull()
        if (error is CancellationException) throw error
        if (error !is StorageRawRouteUnavailableException) return first
        val second = multipart()
        second.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return second
    }
}

internal class StorageRawRouteUnavailableException : Exception("La ruta de subida directa no está disponible.")
