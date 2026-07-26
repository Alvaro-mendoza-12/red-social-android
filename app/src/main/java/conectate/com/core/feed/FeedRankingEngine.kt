package conectate.com.core.feed

import conectate.com.data.model.Post
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

interface FeedRankingEngine {
    fun rankPosts(
        posts: List<Post>,
        currentUserId: String?,
        friendIds: Set<String> = emptySet(),
        signalSnapshot: FeedSignalSnapshot = FeedSignalSnapshot.EMPTY,
        interestTerms: Set<String> = emptySet()
    ): List<Post>

    fun explainPost(
        post: Post,
        currentUserId: String?,
        friendIds: Set<String> = emptySet(),
        signalSnapshot: FeedSignalSnapshot = FeedSignalSnapshot.EMPTY,
        interestTerms: Set<String> = emptySet()
    ): List<String>
}

@Singleton
class DefaultFeedRankingEngine @Inject constructor() : FeedRankingEngine {

    override fun rankPosts(
        posts: List<Post>,
        currentUserId: String?,
        friendIds: Set<String>,
        signalSnapshot: FeedSignalSnapshot,
        interestTerms: Set<String>
    ): List<Post> {
        if (posts.isEmpty()) return emptyList()

        val nowMs = System.currentTimeMillis()
        val scored = posts.map { post ->
            val engagement = post.likeCount + (post.commentCount * 2) + (post.shareCount * 3)
            val engagementScore = ln(1.0 + engagement.toDouble())

            val ageHours = ageHours(post.createdAt, nowMs)
            val freshnessScore = exp(-ageHours / 36.0)

            val socialBoost = when {
                currentUserId != null && post.userId == currentUserId -> 0.45
                friendIds.contains(post.userId) -> 0.85
                else -> 0.0
            }

            val authorAffinity = signalSnapshot.authorAffinity[post.userId] ?: 0.0
            val mediaPreference = signalSnapshot.mediaAffinity[inferMediaType(post)] ?: 0.0
            val noveltyPenalty = if (signalSnapshot.seenPostIds.contains(post.id)) 0.30 else 0.0

            val mediaBoost = if (post.mediaUrls.isNotEmpty() || post.mediaUrlLegacy != null) 0.18 else 0.0
            val verifiedBoost = if (post.isVerified) 0.22 else 0.0
            val affinityBoost = if (post.likedByMe || post.sharedByMe) 0.30 else 0.0
            val trendBoost = if (engagement >= 40 && ageHours <= 8.0) 0.28 else 0.0
            val interestBoost = interestScore(post, interestTerms)
            val tieBreaker = ((post.id.hashCode() and 0xFFFF).toDouble() / 65_535.0) * 0.01

            val totalScore = (engagementScore * 0.55) +
                (freshnessScore * 2.15) +
                socialBoost +
                (authorAffinity * 0.65) +
                (mediaPreference * 0.45) +
                mediaBoost +
                verifiedBoost +
                affinityBoost +
                trendBoost -
                noveltyPenalty +
                interestBoost +
                tieBreaker

            post to totalScore
        }

        val sorted = scored
            .sortedByDescending { it.second }
            .map { it.first }

        return diversifyByAuthor(sorted)
    }

    override fun explainPost(
        post: Post,
        currentUserId: String?,
        friendIds: Set<String>,
        signalSnapshot: FeedSignalSnapshot,
        interestTerms: Set<String>
    ): List<String> {
        val reasons = mutableListOf<String>()
        when {
            currentUserId != null && post.userId == currentUserId ->
                reasons += "Es una publicación tuya."
            post.userId in friendIds ->
                reasons += "La publicó una de tus conexiones."
        }

        val normalizedContent = post.content.lowercase()
        interestTerms.firstOrNull { term ->
            val normalized = term.trim().lowercase()
            normalized.length >= 2 && normalizedContent.contains(normalized)
        }?.let { reasons += "Coincide con uno de tus intereses: $it." }

        if ((signalSnapshot.authorAffinity[post.userId] ?: 0.0) >= 0.8) {
            reasons += "Has interactuado con contenido de esta cuenta."
        }
        val mediaType = inferMediaType(post)
        if ((signalSnapshot.mediaAffinity[mediaType] ?: 0.0) >= 0.6) {
            reasons += "Sueles interactuar con publicaciones de tipo ${mediaLabel(mediaType)}."
        }
        val engagement = post.likeCount + (post.commentCount * 2) + (post.shareCount * 3)
        if (engagement >= 25) {
            reasons += "Está generando conversación en Novvin."
        }
        val ageHours = ageHours(post.createdAt, System.currentTimeMillis())
        if (ageHours <= 12.0) {
            reasons += "Es una publicación reciente."
        }
        if (reasons.isEmpty()) {
            reasons += "Forma parte de contenido público que puedes descubrir."
        }
        return reasons.distinct().take(3)
    }

    private fun ageHours(createdAt: String, nowMs: Long): Double {
        val createdMs = parseToEpochMs(createdAt) ?: return 72.0
        val diff = (nowMs - createdMs).coerceAtLeast(0L)
        return diff / 3_600_000.0
    }

    private fun parseToEpochMs(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { value.toLong() }.getOrNull()
    }

    private fun inferMediaType(post: Post): String {
        if (post.mediaUrls.isEmpty() && post.mediaUrlLegacy.isNullOrBlank()) return "text"
        val urls = buildList {
            addAll(post.mediaUrls)
            post.mediaUrlLegacy?.let { add(it) }
        }
        val normalized = urls.joinToString(",").lowercase()
        return when {
            normalized.contains(".mp4") || normalized.contains(".mov") || normalized.contains(".webm") -> "video"
            normalized.contains(".mp3") || normalized.contains(".m4a") || normalized.contains(".wav") -> "audio"
            normalized.contains(".jpg") || normalized.contains(".jpeg") ||
                normalized.contains(".png") || normalized.contains(".webp") -> "image"
            else -> "media"
        }
    }

    private fun mediaLabel(type: String): String = when (type) {
        "video" -> "vídeo"
        "audio" -> "audio"
        "image" -> "imagen"
        "text" -> "texto"
        else -> "multimedia"
    }

    private fun interestScore(post: Post, interestTerms: Set<String>): Double {
        if (interestTerms.isEmpty()) return 0.0
        val text = post.content.lowercase()
        val matches = interestTerms.count { term ->
            val normalized = term.trim().lowercase()
            normalized.length >= 2 && text.contains(normalized)
        }
        return (matches.coerceAtMost(4) * 0.42)
    }

    private fun diversifyByAuthor(sorted: List<Post>): List<Post> {
        if (sorted.size <= 2) return sorted

        val remaining = sorted.toMutableList()
        val result = mutableListOf<Post>()
        val firstWindowAuthorHits = mutableMapOf<String, Int>()

        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { candidate ->
                var previousAuthor = result.lastOrNull()?.userId
                if (previousAuthor == candidate.userId) return@firstOrNull false

                if (result.size < DIVERSITY_WINDOW_SIZE) {
                    var hits = firstWindowAuthorHits[candidate.userId] ?: 0
                    hits < MAX_POSTS_PER_AUTHOR_IN_WINDOW
                } else {
                    true
                }
            } ?: remaining.first()

            result.add(next)
            remaining.remove(next)
            if (result.size <= DIVERSITY_WINDOW_SIZE) {
                val currentHits = firstWindowAuthorHits[next.userId] ?: 0
                firstWindowAuthorHits[next.userId] = currentHits + 1
            }
        }

        return result
    }

    private companion object {
        private const val DIVERSITY_WINDOW_SIZE = 18
        private const val MAX_POSTS_PER_AUTHOR_IN_WINDOW = 2
    }
}



