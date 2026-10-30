package conectate.com.data.supabase

import java.util.Base64
import okhttp3.HttpUrl
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Small, platform-independent pieces of the TUS upload protocol. Keeping
 * these outside the HTTP client makes their limits and header encoding easy
 * to test on the local JVM.
 */
internal const val RESUMABLE_UPLOAD_THRESHOLD_BYTES = 6L * 1024L * 1024L
internal const val RESUMABLE_UPLOAD_CHUNK_BYTES = 6L * 1024L * 1024L
internal const val RESUMABLE_UPLOAD_PROTOCOL_VERSION = "1.0.0"
internal const val RESUMABLE_UPLOAD_SESSION_MAX_AGE_MS = 23L * 60L * 60L * 1_000L

internal fun shouldAttemptResumableUpload(sizeBytes: Long, localFallback: Boolean): Boolean =
    localFallback && sizeBytes >= RESUMABLE_UPLOAD_THRESHOLD_BYTES

internal fun tusMetadataHeader(values: Map<String, String>): String {
    return values.entries.joinToString(",") { (key, value) ->
        require(key.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid TUS metadata key" }
        require(value.none { it == '\u0000' }) { "Invalid TUS metadata value" }
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        "$key $encoded"
    }
}

internal fun resumableUploadOffset(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isEmpty() || value.any { !it.isDigit() }) return null
    return value.toLongOrNull()?.takeIf { it >= 0L }
}

internal fun boundedResumableChunkSize(totalBytes: Long, offsetBytes: Long): Long {
    require(totalBytes > 0L)
    require(offsetBytes in 0..totalBytes)
    return minOf(RESUMABLE_UPLOAD_CHUNK_BYTES, totalBytes - offsetBytes)
}

internal fun isReusableTusSession(
    endpoint: HttpUrl,
    sessionUrl: HttpUrl,
    createdAtMs: Long,
    nowMs: Long,
): Boolean {
    val ageMs = nowMs - createdAtMs
    val expectedPrefix = endpoint.encodedPath.trimEnd('/') + "/"
    return createdAtMs > 0L &&
        ageMs in 0..RESUMABLE_UPLOAD_SESSION_MAX_AGE_MS &&
        sessionUrl.scheme == endpoint.scheme &&
        sessionUrl.host == endpoint.host &&
        sessionUrl.port == endpoint.port &&
        sessionUrl.username.isEmpty() && sessionUrl.password.isEmpty() &&
        sessionUrl.query == null && sessionUrl.fragment == null &&
        sessionUrl.encodedPath.startsWith(expectedPrefix)
}

internal fun resolveTusSessionUrl(endpoint: HttpUrl, location: String): HttpUrl {
    val session = endpoint.resolve(location.trim())
    require(session != null && isReusableTusSession(endpoint, session, 1L, 1L)) {
        "El servidor devolvió una dirección de subida no permitida."
    }
    return session
}

/** A changed file of the same length must never resume an earlier file's prefix. */
internal suspend fun uploadSourceSha256(source: InputStream, expectedBytes: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    var size = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = source.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        digest.update(buffer, 0, read)
        size += read
        require(size <= expectedBytes) { "El archivo seleccionado cambió de tamaño." }
    }
    require(size == expectedBytes) { "El archivo seleccionado cambió de tamaño." }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
