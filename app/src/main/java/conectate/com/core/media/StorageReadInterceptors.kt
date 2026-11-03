package conectate.com.core.media

import conectate.com.BuildConfig
import conectate.com.data.localfallback.UbuntuStorageUrl
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

internal class StorageReadRequestContext(
    val identity: StorageReadIdentity?,
    val startedAtStorageOrigin: Boolean,
    val edgeUrl: HttpUrl? = null,
)

/** Capture identity once, before redirects; credentials themselves are read at each attempt. */
internal class StorageReadRequestInterceptor(
    private val backendUrl: () -> String = { UbuntuStorageUrl.baseUrl },
    private val credentials: () -> StorageReadCredential? = StorageReadSession::snapshot,
    private val prepareCredentials: () -> StorageReadCredential? = StorageReadSession::readySnapshot,
    private val edgeOrigin: String = BuildConfig.MEDIA_EDGE_ORIGIN,
    private val pinnedBackend: String = BuildConfig.FALLBACK_API_URL,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val storageRead = StorageReadPolicy.accepts(original.url.toString(), backendUrl())
        val captured = if (storageRead) prepareCredentials() else credentials()
        val context = StorageReadRequestContext(captured?.identity, storageRead)
        val request = original.newBuilder().removeHeader("Authorization")
            .tag(StorageReadRequestContext::class.java, context).build()
        val edgeUrl = if (storageRead && original.method == "GET" && edgeOrigin.isNotBlank()) {
            StorageReadPolicy.edgeUrl(original.url, backendUrl(), pinnedBackend, edgeOrigin)
        } else null
        if (edgeUrl != null) {
            val accelerated = request.newBuilder().url(edgeUrl).removeHeader("Cookie")
                .tag(StorageReadRequestContext::class.java, StorageReadRequestContext(captured?.identity, true, edgeUrl)).build()
            try {
                val response = chain.proceed(accelerated)
                if (response.code !in setOf(429, 500, 502, 503, 504)) return response
                response.close()
            } catch (error: IOException) {
                if (chain.call().isCanceled() || credentials()?.identity != captured?.identity) throw error
            }
        }
        return chain.proceed(request)
    }
}

/** A network interceptor runs for EVERY redirect/retry; application interceptors do not. */
internal class StorageReadNetworkInterceptor(
    private val backendUrl: () -> String = { UbuntuStorageUrl.baseUrl },
    private val credentials: () -> StorageReadCredential? = StorageReadSession::snapshot,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val current = credentials()
        val context = original.tag(StorageReadRequestContext::class.java)
            ?: StorageReadRequestContext(current?.identity, StorageReadPolicy.accepts(original.url.toString(), backendUrl()))
        val trusted = StorageReadPolicy.accepts(original.url.toString(), backendUrl()) || original.url == context.edgeUrl
        if (context.startedAtStorageOrigin && current?.identity != context.identity) throw changedAccount()
        val request = authorizedRequest(original, context, current, trusted)
        val response = chain.proceed(request)
        if (!context.startedAtStorageOrigin) return response
        if (credentials()?.identity != context.identity) { response.close(); throw changedAccount() }
        val body = response.body ?: return response
        val guardedBody = object : ResponseBody() {
            private val guardedSource by lazy {
                object : ForwardingSource(body.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        if (credentials()?.identity != context.identity) throw changedAccount()
                        val pending = okio.Buffer()
                        val count = super.read(pending, byteCount)
                        if (credentials()?.identity != context.identity) { pending.clear(); throw changedAccount() }
                        if (count > 0) sink.write(pending, count)
                        return count
                    }
                }.buffer()
            }
            override fun contentType(): MediaType? = body.contentType()
            override fun contentLength(): Long = body.contentLength()
            // Okio buffers previously fetched bytes. Consumers guard every read
            // separately and Coil checks identity before returning the image.
            // close() remains usable after logout so sockets are always released.
            override fun source(): BufferedSource = guardedSource
        }
        return response.newBuilder().body(guardedBody).build()
    }

    internal fun authorizedRequest(request: Request, context: StorageReadRequestContext, current: StorageReadCredential?, trusted: Boolean): Request =
        request.newBuilder().removeHeader("Authorization").apply {
            if (trusted && context.startedAtStorageOrigin && request.method in setOf("GET", "HEAD")) {
                current?.accessToken?.let { header("Authorization", "Bearer $it") }
                // A cached 304 must not bypass a fresh authorization check in the backend.
                header("Cache-Control", "no-store")
            }
        }.build()

    private fun changedAccount() = IOException("La cuenta activa cambió durante la lectura.")
}
