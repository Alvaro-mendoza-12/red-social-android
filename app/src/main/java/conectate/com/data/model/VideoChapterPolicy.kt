package conectate.com.data.model

fun validVideoChapters(chapters: List<VideoChapter>, durationMs: Long): Boolean {
    if (chapters.size > 50 || (chapters.isNotEmpty() && durationMs <= 0L)) return false
    var previousStart = -1L
    return chapters.all { chapter ->
        val title = chapter.title.trim()
        val valid = title.isNotEmpty() && title.length <= 80 &&
            title.none { it.isISOControl() } &&
            chapter.startMs > previousStart && chapter.startMs < durationMs
        previousStart = chapter.startMs
        valid
    }
}

fun parseVideoChapterTime(value: String): Long? {
    val parts = value.trim().split(':')
    val seconds = when (parts.size) {
        1 -> parts[0].toLongOrNull()
        2 -> {
            val minutes = parts[0].toLongOrNull() ?: return null
            val remaining = parts[1].toLongOrNull() ?: return null
            if (minutes < 0L || remaining !in 0L..59L ||
                minutes > (Long.MAX_VALUE - remaining) / 60L) return null
            minutes * 60L + remaining
        }
        else -> return null
    } ?: return null
    return seconds.takeIf { it >= 0L && it <= Long.MAX_VALUE / 1_000L }?.times(1_000L)
}

fun videoChapterTimeLabel(startMs: Long): String {
    val seconds = startMs.coerceAtLeast(0L) / 1_000L
    return "${seconds / 60L}:${(seconds % 60L).toString().padStart(2, '0')}"
}
