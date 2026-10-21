package conectate.com.util

/** Unknown/zero duration is normal while a WebM/Opus stream is being prepared. */
fun finiteAudioProgress(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

fun audioPlaybackProgress(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L || positionMs < 0L) 0f
    else finiteAudioProgress((positionMs.toDouble() / durationMs.toDouble()).toFloat())

/** Voice recordings from older clients store their duration in the caption. */
fun audioDurationMillis(label: String?): Long? {
    val parts = label?.trim()?.split(':') ?: return null
    if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.any { char -> !char.isDigit() } }) return null
    val values = parts.map { it.toLongOrNull() ?: return null }
    if (values.first() !in 0..10_080) return null
    if (values.last() !in 0..59 || (values.size == 3 && values[1] !in 0..59)) return null
    val seconds = if (values.size == 2) values[0] * 60 + values[1]
        else values[0] * 3600 + values[1] * 60 + values[2]
    return seconds.takeIf { it in 1..604_800 }?.times(1000)
}

fun audioTimeLabel(durationMs: Long?): String? = durationMs?.takeIf { it > 0 }?.let {
    val seconds = (it / 1000).coerceAtLeast(1)
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

fun audioBubbleTimeLabel(active: Boolean, elapsed: String, totalMs: Long?): String {
    val total = audioTimeLabel(totalMs)
    return if (active) if (total != null) "$elapsed / $total" else elapsed
        else total ?: "—:—"
}

fun audioMessageCaption(content: String): String {
    val value = content.trim()
    val technical = value.startsWith("https://", true) || value.startsWith("file:", true) ||
        value.startsWith("content:", true) ||
        Regex("(?i).*\\.(?:webm|m4a|mp3|ogg|oga|opus|aac|wav|flac)(?:[?#].*)?$").matches(value) ||
        Regex("\\d{1,5}:\\d{2}(?::\\d{2})?").matches(value)
    return if (value.isBlank() || technical) "Audio" else value
}
