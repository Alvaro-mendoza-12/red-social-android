package conectate.com.data.model

data class VideoClipRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

private const val PUBLIC_VIDEO_WEB_BASE = "https://red-social-android.vercel.app/app/reels"

fun videoWebLink(videoId: String): String =
    "$PUBLIC_VIDEO_WEB_BASE/${android.net.Uri.encode(videoId)}"

fun validVideoClipRange(startMs: Long, endMs: Long, videoDurationMs: Long): VideoClipRange? {
    if (startMs < 0L || endMs <= startMs || videoDurationMs <= 0L ||
        endMs > videoDurationMs || endMs - startMs !in 5_000L..60_000L) return null
    return VideoClipRange(startMs, endMs)
}

fun videoClipLink(videoId: String, range: VideoClipRange): String =
    videoWebLink(videoId) +
        "?resumeMs=${range.startMs}&endMs=${range.endMs}"
