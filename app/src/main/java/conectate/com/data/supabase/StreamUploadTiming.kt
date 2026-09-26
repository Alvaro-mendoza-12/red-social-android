package conectate.com.data.supabase

/**
 * PII-free timing breakdown for one streaming upload. Keeping this calculation
 * independent from OkHttp and Android clocks makes observability regression-testable.
 */
internal data class StreamUploadTiming(
    val transportMs: Long,
    val responseWaitMs: Long,
    val throughputKibPerSecond: Long,
)

internal object StreamUploadTimingCalculator {

    fun calculate(
        uploadStartedElapsedMs: Long,
        requestBodyStartedElapsedMs: Long,
        requestBodyFinishedElapsedMs: Long,
        completedElapsedMs: Long,
        sizeBytes: Long,
    ): StreamUploadTiming {
        val completed = completedElapsedMs.coerceAtLeast(uploadStartedElapsedMs)
        val requestStarted = requestBodyStartedElapsedMs
            .takeIf { it > 0L }
            ?.coerceIn(uploadStartedElapsedMs, completed)

        if (requestStarted == null) {
            return StreamUploadTiming(
                transportMs = 0L,
                responseWaitMs = 0L,
                throughputKibPerSecond = 0L,
            )
        }

        val requestFinished = requestBodyFinishedElapsedMs
            .takeIf { it >= requestStarted }
            ?.coerceIn(requestStarted, completed)
            ?: completed
        val transportMs = requestFinished - requestStarted
        val responseWaitMs = completed - requestFinished
        val throughputKibPerSecond = if (transportMs > 0L && sizeBytes > 0L) {
            ((sizeBytes.toDouble() * 1_000.0) / transportMs.toDouble() / 1_024.0)
                .toLong()
                .coerceAtLeast(0L)
        } else {
            0L
        }

        return StreamUploadTiming(
            transportMs = transportMs,
            responseWaitMs = responseWaitMs,
            throughputKibPerSecond = throughputKibPerSecond,
        )
    }
}
