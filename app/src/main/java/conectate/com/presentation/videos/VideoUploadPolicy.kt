package conectate.com.presentation.videos

internal const val MAX_VIDEO_UPLOAD_DURATION_MS = 45_000L
internal const val MAX_VIDEO_DELIVERY_BITRATE_BPS = 4_500_000L

internal enum class VideoPreparationReason {
    READY,
    UNKNOWN_DURATION,
    TOO_LONG,
    HIGH_RESOLUTION,
    HIGH_FRAME_RATE,
    HIGH_BITRATE
}

internal data class VideoPreparationDecision(
    val requiresEditor: Boolean,
    val reason: VideoPreparationReason
)

internal fun evaluateVideoPreparation(
    durationMs: Long,
    width: Int,
    height: Int,
    fps: Float,
    fileSizeBytes: Long
): VideoPreparationDecision {
    val exceeds1080p = width > 0 && height > 0 && (
        maxOf(width, height) > 1920 ||
            minOf(width, height) > 1080 ||
            width.toLong() * height.toLong() > 2_073_600L
        )
    val estimatedAverageBitrateBps = if (durationMs > 0L && fileSizeBytes > 0L) {
        (fileSizeBytes.toDouble() * 8_000.0 / durationMs.toDouble()).toLong()
    } else {
        0L
    }

    val reason = when {
        durationMs <= 0L -> VideoPreparationReason.UNKNOWN_DURATION
        durationMs > MAX_VIDEO_UPLOAD_DURATION_MS -> VideoPreparationReason.TOO_LONG
        exceeds1080p -> VideoPreparationReason.HIGH_RESOLUTION
        fps > 31f -> VideoPreparationReason.HIGH_FRAME_RATE
        estimatedAverageBitrateBps > MAX_VIDEO_DELIVERY_BITRATE_BPS -> VideoPreparationReason.HIGH_BITRATE
        else -> VideoPreparationReason.READY
    }
    return VideoPreparationDecision(
        requiresEditor = reason != VideoPreparationReason.READY,
        reason = reason
    )
}
