package conectate.com.data.model

data class VideoClipRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

private const val PUBLIC_VIDEO_WEB_BASE = "https://red-social-android.vercel.app/app/reels"

/** Resolve only Novvin-owned links before opening a video inside the app. */
fun videoIdFromSharedLink(raw: String): String? {
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val segments = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
    val id = when {
        scheme == "conectate" && host == "video" && segments.size == 1 -> segments[0]
        scheme in setOf("http", "https") && host in setOf("conectate.com", "www.conectate.com") &&
            segments.size == 2 && segments[0] == "video" -> segments[1]
        scheme in setOf("http", "https") && host == "red-social-android.vercel.app" &&
            segments.size == 3 && segments[0] == "app" && segments[1] == "reels" -> segments[2]
        else -> return null
    }
    return id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,100}")) }
}

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
