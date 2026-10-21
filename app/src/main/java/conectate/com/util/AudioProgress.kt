package conectate.com.util

/** Unknown/zero duration is normal while a WebM/Opus stream is being prepared. */
fun finiteAudioProgress(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

fun audioPlaybackProgress(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L || positionMs < 0L) 0f
    else finiteAudioProgress((positionMs.toDouble() / durationMs.toDouble()).toFloat())

fun audioMessageCaption(content: String): String {
    val value = content.trim()
    val technical = value.startsWith("https://", true) || value.startsWith("file:", true) ||
        value.startsWith("content:", true) ||
        Regex("(?i).*\\.(?:webm|m4a|mp3|ogg|oga|opus|aac|wav|flac)(?:[?#].*)?$").matches(value) ||
        Regex("\\d{1,3}:\\d{2}").matches(value)
    return if (value.isBlank() || technical) "Audio" else value
}
