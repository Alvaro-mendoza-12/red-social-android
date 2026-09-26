package conectate.com.data.model

data class VideoClipRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

fun validVideoClipRange(startMs: Long, endMs: Long, videoDurationMs: Long): VideoClipRange? {
    if (startMs < 0L || endMs <= startMs || videoDurationMs <= 0L ||
        endMs > videoDurationMs || endMs - startMs !in 5_000L..60_000L) return null
    return VideoClipRange(startMs, endMs)
}

fun videoClipLink(videoId: String, range: VideoClipRange): String =
    "https://conectate.com/video/${android.net.Uri.encode(videoId)}" +
        "?resumeMs=${range.startMs}&endMs=${range.endMs}"
