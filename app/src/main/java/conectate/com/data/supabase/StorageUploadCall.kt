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
    if (response.code != 201) throw IOException("No se pudo preparar la subida. Inténtalo de nuevo.")
    resolveTusSessionUrl(endpoint, response.header("Location").orEmpty())
}
