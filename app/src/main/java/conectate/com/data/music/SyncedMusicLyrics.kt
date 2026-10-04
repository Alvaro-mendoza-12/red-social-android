package conectate.com.data.music

/** Absolute positions in the source track, never estimated from plain text. */
data class TimedMusicLyric(val startMs: Long, val endMs: Long, val text: String)

private const val MAX_LYRICS_CHARS = 12_000
private const val MAX_LYRIC_CUES = 1_000
private val lyricTimestamp = Regex("^\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
private val lyricOffset = Regex("(?im)^\\s*\\[offset:([+-]?\\d+)]\\s*$")

/** LRC timestamps and optional offset only. Unmarked lyrics do not imply timings. */
fun timedMusicLyrics(raw: String?, durationMs: Long? = null): List<TimedMusicLyric> {
    if (raw.isNullOrBlank() || raw.length > MAX_LYRICS_CHARS) return emptyList()
    val limit = durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
    val offsetTag = lyricOffset.find(raw)
    val offset = if (offsetTag == null) 0L else offsetTag.groupValues[1].toLongOrNull() ?: return emptyList()
    if (offset !in -60_000L..60_000L) return emptyList()
    val parsed = mutableListOf<Pair<Long, String>>()
    for (line in raw.lineSequence()) {
        var remaining = line.trim()
        val timestamps = mutableListOf<Long>()
        while (true) {
            val match = lyricTimestamp.find(remaining) ?: break
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fraction = match.groupValues[3].padEnd(3, '0').ifEmpty { "000" }.toLong()
            timestamps += ((minutes * 60 + seconds) * 1_000 + fraction + offset).coerceAtLeast(0L)
            remaining = remaining.substring(match.value.length).trimStart()
        }
        if (timestamps.isEmpty()) continue
        val text = remaining.filter { it >= ' ' || it == '\t' }.trim().take(280)
        timestamps.filter { it < limit }.forEach { parsed += it to text }
        if (parsed.size > MAX_LYRIC_CUES) return emptyList()
    }
    val grouped = parsed.groupBy({ it.first }, { it.second }).toSortedMap()
        .map { (time, lines) -> time to lines.filter(String::isNotBlank).distinct().joinToString("\n").take(280) }
    if (grouped.none { it.second.isNotBlank() }) return emptyList()
    return grouped.mapIndexed { index, (start, text) ->
        TimedMusicLyric(start, grouped.getOrNull(index + 1)?.first ?: limit, text)
    }
}

/** A blank timed cue clears the current line, including instrumental gaps. */
fun activeMusicLyricIndex(cues: List<TimedMusicLyric>, positionMs: Long): Int {
    var low = 0
    var high = cues.lastIndex
    var candidate = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (cues[mid].startMs <= positionMs) {
            candidate = mid
            low = mid + 1
        } else high = mid - 1
    }
    return candidate.takeIf { it >= 0 && positionMs < cues[it].endMs && cues[it].text.isNotBlank() } ?: -1
}
