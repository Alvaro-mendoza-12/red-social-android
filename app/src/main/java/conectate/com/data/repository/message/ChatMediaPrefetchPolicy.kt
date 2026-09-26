package conectate.com.data.repository.message

internal const val DEFAULT_CHAT_MEDIA_PREFETCH_LIMIT = 12
internal const val DEFAULT_CHAT_VIDEO_PREFETCH_LIMIT_BYTES = 12L * 1024L * 1024L

internal data class ChatMediaPrefetchCandidate(
    val contentType: String,
    val mediaUrl: String?,
    val mediaSizeBytes: Long? = null
)

/**
 * Selects recent chat media that is safe to copy into the durable offline cache.
 * View-once content is intentionally excluded and known oversized videos remain
 * stream-only until a person deliberately opens them.
 */
internal fun selectRecentChatMediaForPrefetch(
    candidates: Iterable<ChatMediaPrefetchCandidate>,
    maxItems: Int = DEFAULT_CHAT_MEDIA_PREFETCH_LIMIT,
    maxVideoBytes: Long = DEFAULT_CHAT_VIDEO_PREFETCH_LIMIT_BYTES
): List<String> {
    if (maxItems <= 0 || maxVideoBytes <= 0L) return emptyList()

    return candidates.asSequence()
        .mapNotNull { candidate ->
            val contentType = candidate.contentType.trim().lowercase()
            if (contentType.startsWith("view_once_")) return@mapNotNull null
            if (contentType !in PREFETCHABLE_CHAT_MEDIA_TYPES) return@mapNotNull null
            if (
                contentType == "video" &&
                candidate.mediaSizeBytes != null &&
                candidate.mediaSizeBytes > maxVideoBytes
            ) return@mapNotNull null

            candidate.mediaUrl?.trim()?.takeIf(String::isNotBlank)
        }
        .distinct()
        .toList()
        .takeLast(maxItems)
}

private val PREFETCHABLE_CHAT_MEDIA_TYPES = setOf(
    "image",
    "audio",
    "video",
    "gif",
    "sticker"
)
