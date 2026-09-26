package conectate.com.data.supabase

import java.util.Base64
import okhttp3.HttpUrl

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
        sessionUrl.encodedPath.startsWith(expectedPrefix)
}
