package conectate.com.presentation.video

import conectate.com.data.model.Video
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

internal data class VideoDiscoveryTopic(val query: String, val label: String)

internal fun normalizedVideoSearch(value: String): String = Normalizer.normalize(value.trim().removePrefix("#"),
    Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).take(120)

private val genericTopics = setOf("video", "videos", "viral", "fyp", "parati", "para ti", "nuevo", "trending", "novvin")
private fun topicWords(value: String) = normalizedVideoSearch(value).split(Regex("[^\\p{L}\\p{N}]+"))
    .filter { it.length >= 3 && it !in setOf("con", "para", "una", "del", "las", "los", "por", "que") }.toSet()

/** Show a contextual CTA only when independent metadata supports a specific topic. */
internal fun videoRelatedTopic(video: Video): String? {
    if (!video.isPublic || video.isSensitive) return null
    val textWords = topicWords("${video.title} ${video.description}")
    video.tags.forEach { tag ->
        val display = tag.trim().removePrefix("#").replace('_', ' ')
        val key = normalizedVideoSearch(display)
        if (!display.matches(Regex("[\\p{L}\\p{N} -]{3,48}")) || key in genericTopics) return@forEach
        val words = topicWords(display)
        if (words.isNotEmpty() && words.all { it in textWords }) return display
    }
    val title = video.title.trim()
    val words = topicWords(title)
    val descriptionWords = topicWords(video.description)
    return title.takeIf {
        it.length in 12..60 && !it.contains('@') && !it.contains("http", true) &&
            words.size >= 2 && words.count { word -> word in descriptionWords } >= 2
    }
}

/** Uses real visible content, never fabricated search counters. */
internal fun videoDiscoveryTopics(videos: List<Video>, now: Instant = Instant.now()): List<VideoDiscoveryTopic> {
    val month = now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
    val topics = linkedMapOf<String, Pair<String, Long>>()
    videos.filter { it.isPublic }.forEach { video ->
        val created = runCatching { Instant.parse(video.created_at) }.getOrNull()
        if (created == null || created < month || created > now) return@forEach
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
