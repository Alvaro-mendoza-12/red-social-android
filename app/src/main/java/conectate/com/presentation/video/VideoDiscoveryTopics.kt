package conectate.com.presentation.video

import conectate.com.data.model.Video
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

internal data class VideoDiscoveryTopic(val query: String, val label: String)

internal fun normalizedVideoSearch(value: String): String = Normalizer.normalize(value.trim().removePrefix("#"),
    Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).take(120)

internal fun videoRelatedTopic(video: Video): String? = video.tags.firstOrNull {
    it.removePrefix("#").matches(Regex("[\\p{L}\\p{N}_ -]{2,48}"))
}?.removePrefix("#") ?: video.title.trim().takeIf { it.length in 3..72 && !it.contains('@') }

/** Uses real visible content, never fabricated search counters. */
internal fun videoDiscoveryTopics(videos: List<Video>, now: Instant = Instant.now()): List<VideoDiscoveryTopic> {
    val month = now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
    val topics = linkedMapOf<String, Pair<String, Long>>()
    videos.filter { it.isPublic }.forEach { video ->
        val created = runCatching { Instant.parse(video.created_at) }.getOrNull()
        if (created != null && created < month) return@forEach
        video.tags.take(12).forEach tagLoop@ { tag ->
            val display = tag.trim().removePrefix("#")
            if (!display.matches(Regex("[\\p{L}\\p{N}_ -]{2,48}"))) return@tagLoop
            val key = normalizedVideoSearch(display)
            val score = 1L + video.views.coerceAtLeast(0) + 3L * video.likeCount.coerceAtLeast(0)
            topics[key] = display to ((topics[key]?.second ?: 0) + score)
        }
    }
    return topics.values.sortedByDescending { it.second }.take(8).map { VideoDiscoveryTopic(it.first, "Tema del mes") }
}
