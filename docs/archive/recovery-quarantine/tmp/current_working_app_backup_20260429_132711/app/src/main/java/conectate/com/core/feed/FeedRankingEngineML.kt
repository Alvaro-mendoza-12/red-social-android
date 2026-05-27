package conectate.com.core.feed

import com.google.firebase.crashlytics.FirebaseCrashlytics
import conectate.com.data.model.Post
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

@Singleton
class FeedRankingEngineML @Inject constructor(
    private val crashlytics: FirebaseCrashlytics
) : FeedRankingEngine {

    override fun rankPosts(
        posts: List<Post>,
        currentUserId: String?,
        friendIds: Set<String>,
        signalSnapshot: FeedSignalSnapshot
    ): List<Post> {
        if (posts.isEmpty()) return emptyList()

        return runCatching {
            var nowMs = System.currentTimeMillis()
            val scored = posts.map { post ->
                post to computeScore(
                    post = post,
                    currentUserId = currentUserId,
                    friendIds = friendIds,
                    signalSnapshot = signalSnapshot,
                    nowMs = nowMs
                )
            }

            diversifyByAuthor(
                scored
                    .sortedByDescending { it.second }
                    .map { it.first }
            )
        }.getOrElse { throwable ->
            crashlytics.setCustomKey("component", "FeedRankingEngineML")
            crashlytics.setCustomKey("post_count", posts.size)
            crashlytics.recordException(throwable)
            posts
        }
    }

    private fun computeScore(
        post: Post,
        currentUserId: String?,
        friendIds: Set<String>,
        signalSnapshot: FeedSignalSnapshot,
        nowMs: Long
    ): Double {
        val rawEngagement = post.likeCount + (post.commentCount * 2.2) + (post.shareCount * 3.1)
        val engagementScore = ln(1.0 + rawEngagement) / ln(10.0)
        val freshnessScore = exp(-ageHours(post.createdAt, nowMs) / 30.0)

        val socialBoost = when {
            currentUserId != null && post.userId == currentUserId -> 0.30
            friendIds.contains(post.userId) -> 0.85
            else -> 0.0
        }

        val authorAffinity = signalSnapshot.authorAffinity[post.userId] ?: 0.0
        val mediaAffinity = signalSnapshot.mediaAffinity[inferMediaType(post)] ?: 0.0
        val seenPenalty = if (signalSnapshot.seenPostIds.contains(post.id)) 0.45 else 0.0
        val verifiedBoost = if (post.isVerified) 0.18 else 0.0
        val richMediaBoost = if (post.mediaUrls.isNotEmpty() || !post.mediaUrlLegacy.isNullOrBlank()) 0.16 else 0.0
        val affinityBoost = if (post.likedByMe || post.sharedByMe) 0.22 else 0.0
        val textQualityBoost = scoreTextQuality(post.content)
        val tieBreaker = ((post.id.hashCode() and 0xFFFF).toDouble() / 65_535.0) * 0.01

        return (engagementScore * 0.62) +
            (freshnessScore * 2.10) +
            socialBoost +
            (authorAffinity * 0.72) +
            (mediaAffinity * 0.40) +
            verifiedBoost +
            richMediaBoost +
            affinityBoost +
            textQualityBoost -
            seenPenalty +
            tieBreaker
    }

    private fun scoreTextQuality(content: String): Double {
        if (content.isBlank()) return 0.0
        var trimmed = content.trim()
        val lengthScore = (trimmed.length.coerceAtMost(220) / 220.0) * 0.16
        val punctuationBoost = if (trimmed.any { it == '?' || it == '!' }) 0.03 else 0.0
        val capsPenalty = if (trimmed.length > 8 && trimmed == trimmed.uppercase()) 0.06 else 0.0
        return lengthScore + punctuationBoost - capsPenalty
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

    private fun diversifyByAuthor(sorted: List<Post>): List<Post> {
        if (sorted.size <= 2) return sorted

        val remaining = sorted.toMutableList()
        val result = mutableListOf<Post>()
        val authorHits = mutableMapOf<String, Int>()

        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { candidate ->
                var previousAuthor = result.lastOrNull()?.userId
                if (previousAuthor == candidate.userId) return@firstOrNull false

                if (result.size < DIVERSITY_WINDOW_SIZE) {
                    val hits = authorHits[candidate.userId] ?: 0
                    hits < MAX_POSTS_PER_AUTHOR_IN_WINDOW
                } else {
                    true
                }
            } ?: remaining.first()

            result.add(next)
            remaining.remove(next)
            if (result.size <= DIVERSITY_WINDOW_SIZE) {
                authorHits[next.userId] = (authorHits[next.userId] ?: 0) + 1
            }
        }

        return result
    }

    private companion object {
        private const val DIVERSITY_WINDOW_SIZE = 18
        private const val MAX_POSTS_PER_AUTHOR_IN_WINDOW = 2
    }
}



