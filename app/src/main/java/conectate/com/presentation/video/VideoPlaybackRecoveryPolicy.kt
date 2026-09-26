package conectate.com.presentation.video

import androidx.media3.common.PlaybackException

internal const val MAX_AUTOMATIC_VIDEO_RETRIES = 3

internal sealed interface VideoPlaybackRecoveryDecision {
    data class Retry(
        val attempt: Int,
        val delayMs: Long,
        val refreshEndpoint: Boolean,
        val evictCache: Boolean = false,
    ) : VideoPlaybackRecoveryDecision

    data object ShowError : VideoPlaybackRecoveryDecision
}

/**
 * Keeps transient transport failures away from the user without creating an
 * infinite decoder/retry loop for malformed or unsupported media.
 */
internal fun decideVideoPlaybackRecovery(
    errorCode: Int,
    httpStatusCode: Int?,
    previousAttempts: Int,
    maxAutomaticRetries: Int = MAX_AUTOMATIC_VIDEO_RETRIES,
): VideoPlaybackRecoveryDecision {
    if (previousAttempts >= maxAutomaticRetries || !isRetryableVideoPlaybackFailure(errorCode, httpStatusCode)) {
        return VideoPlaybackRecoveryDecision.ShowError
    }

    val attempt = previousAttempts + 1
    return VideoPlaybackRecoveryDecision.Retry(
        attempt = attempt,
        delayMs = when (attempt) {
            1 -> 350L
            2 -> 1_000L
            else -> 2_000L
        },
        // Retry the same connection once. Discovery is more expensive and is
        // normally reserved for a repeated failure. A stale/missing media URL
        // and Cloudflare origin errors are refreshed immediately because a
        // byte-for-byte retry against the same address cannot repair them.
        refreshEndpoint = attempt > 1 || shouldRefreshVideoEndpoint(httpStatusCode),
        // Connection failures leave valid cached prefixes useful offline.
        // Only an invalid byte range justifies evicting this resource.
        evictCache = httpStatusCode == 416 || errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    )
}

internal fun isRetryableVideoPlaybackFailure(errorCode: Int, httpStatusCode: Int?): Boolean {
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_TIMEOUT -> true

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> httpStatusCode in retryableHttpStatuses

        else -> false
    }
}

private val retryableHttpStatuses = setOf(
    401, 403, 404, 408, 410, 416, 421, 425, 429,
    500, 502, 503, 504,
    520, 521, 522, 523, 524, 525, 526, 527, 530,
)

private fun shouldRefreshVideoEndpoint(httpStatusCode: Int?): Boolean = httpStatusCode in setOf(
    401, 403, 404, 410, 416, 421,
    520, 521, 522, 523, 524, 525, 526, 527, 530,
)
