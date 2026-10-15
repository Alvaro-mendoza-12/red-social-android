package conectate.com.core.media

import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.util.MediaPlaybackDataSources
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small explicit downloads. Large streaming playback remains on Media3/Range. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StorageReadDownloads {
    private val client by lazy {
        MediaPlaybackDataSources.networkClient().newBuilder()
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(120, TimeUnit.SECONDS).build()
    }

    internal fun networkCalls(): Call.Factory = client

    suspend fun readBytes(url: String, maxBytes: Long, timeoutMillis: Long = 30_000, calls: Call.Factory = client): ByteArray {
        require(maxBytes in 1L..Int.MAX_VALUE.toLong())
        return read(url, maxBytes, timeoutMillis, calls, null, {}) { input, expected, check ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024L).toInt())
            val count = copy(input, output, maxBytes, check)
            if (expected >= 0 && expected != count) throw IOException("La descarga quedó incompleta.")
            output.toByteArray()
        }
    }

    suspend fun downloadToFile(
        url: String, destination: File, maxBytes: Long, timeoutMillis: Long = 120_000,
        allowedMimeTypes: ((String) -> Boolean)? = null, calls: Call.Factory = client,
        requireMimeType: Boolean = false,
    ): Long = try {
        read(url, maxBytes, timeoutMillis, calls, allowedMimeTypes, { destination.delete(); Unit }, requireMimeType) { input, expected, check ->
            destination.outputStream().buffered().use { output ->
                val count = copy(input, output, maxBytes, check)
                if (expected >= 0 && expected != count) throw IOException("La descarga quedó incompleta.")
                count
            }
        }
    } catch (error: Throwable) { destination.delete(); throw error }

    private suspend fun <T> read(
        url: String, maxBytes: Long, timeoutMillis: Long, calls: Call.Factory,
        allowedMimeTypes: ((String) -> Boolean)?, discard: () -> Unit, requireMimeType: Boolean = false,
        reader: (InputStream, Long, () -> Unit) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        require(maxBytes > 0 && timeoutMillis in 1..120_000)
        val identity = StorageReadSession.snapshot()?.identity
        val request = Request.Builder().url(url).apply {
            require(url.startsWith("https://", true)) { "La multimedia remota debe usar HTTPS." }
            // The session interceptor adds Bearer; no caller supplied credential is accepted.
            if (StorageReadSession.isStorageRequest(url)) {
                UbuntuStorageUrl.publicRequestHeaders.forEach { (key, value) -> header(key, value) }
                header("Cache-Control", "no-store")
            }
        }.build()
        val call = calls.newCall(request)
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                discard()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val check = {
                            if (!continuation.isActive) throw kotlinx.coroutines.CancellationException()
                            StorageReadSession.requireCurrent(identity)
                        }
                        check()
                        if (!response.isSuccessful) throw IOException("No se pudo descargar la multimedia (${response.code}).")
                        val body = response.body ?: throw IOException("La multimedia está vacía.")
                        val expected = body.contentLength()
                        if (expected > maxBytes) throw IOException("El archivo supera el tamaño máximo permitido.")
                        val mime = body.contentType()?.toString()?.substringBefore(';').orEmpty().trim().lowercase(java.util.Locale.ROOT)
                        if ((requireMimeType && mime.isEmpty()) || (mime.isNotEmpty() && allowedMimeTypes != null && !allowedMimeTypes(mime))) {
                            throw IOException("El servidor devolvió un tipo de archivo inesperado.")
                        }
                        val value = StorageReadSession.guardInputStream(url, body.byteStream(), identity).use { input ->
                            reader(input, expected, check)
                        }
                        check()
                        // Cancellation may win while a completed file waits for
                        // dispatch back to the UI. Delete that undelivered copy.
                        continuation.resume(value, onCancellation = { _, _, _ -> discard() })
                    }
                } catch (error: Throwable) {
                    discard()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun copy(input: InputStream, output: java.io.OutputStream, maxBytes: Long, check: () -> Unit): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            check()
            val count = input.read(buffer)
            // Covers cancellation/logout while the socket was blocked, including external URLs.
            check()
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("El archivo supera el tamaño máximo permitido.")
            output.write(buffer, 0, count)
        }
        if (total == 0L) throw IOException("La multimedia está vacía.")
        output.flush()
        check()
        return total
    }
}
