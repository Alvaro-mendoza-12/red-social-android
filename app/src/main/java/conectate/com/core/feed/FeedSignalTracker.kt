package conectate.com.core.feed

import conectate.com.data.model.Post
import javax.inject.Inject
import javax.inject.Singleton

enum class FeedInteractionAction {
    IMPRESSION,
    OPEN,
    LIKE,
    UNLIKE,
    SHARE,
    UNSHARE,
    COMMENT
}

data class FeedSignalSnapshot(
    val authorAffinity: Map<String, Double>,
    val mediaAffinity: Map<String, Double>,
    val seenPostIds: Set<String>
) {
    companion object {
        val EMPTY = FeedSignalSnapshot(
            authorAffinity = emptyMap(),
            mediaAffinity = emptyMap(),
            seenPostIds = emptySet()
        )
    }
}

interface FeedSignalTracker {
    fun snapshot(): FeedSignalSnapshot
    fun recordImpressions(posts: List<Post>)
    fun recordInteraction(post: Post, action: FeedInteractionAction)
}

@Singleton
class InMemoryFeedSignalTracker @Inject constructor() : FeedSignalTracker {

    private val lock = Any()
    private val authorAffinity = mutableMapOf<String, Double>()
    private val mediaAffinity = mutableMapOf<String, Double>()
    private val seenPostIds = LinkedHashSet<String>()

    override fun snapshot(): FeedSignalSnapshot {
        synchronized(lock) {
            return FeedSignalSnapshot(
                authorAffinity = authorAffinity.toMap(),
                mediaAffinity = mediaAffinity.toMap(),
                seenPostIds = seenPostIds.toSet()
            )
        }
    }

    override fun recordImpressions(posts: List<Post>) {
        if (posts.isEmpty()) return
        synchronized(lock) {
            decay(authorAffinity, IMPRESSION_DECAY)
            decay(mediaAffinity, IMPRESSION_DECAY)
            posts.take(MAX_IMPRESSIONS_BATCH).forEach { post ->
                rememberPost(post.id)
                applyDelta(authorAffinity, post.userId, IMPRESSION_AUTHOR_DELTA)
                applyDelta(mediaAffinity, inferMediaType(post), IMPRESSION_MEDIA_DELTA)
            }
        }
    }

    override fun recordInteraction(post: Post, action: FeedInteractionAction) {
        val delta = when (action) {
            FeedInteractionAction.IMPRESSION -> IMPRESSION_AUTHOR_DELTA
            FeedInteractionAction.OPEN -> 0.45
            FeedInteractionAction.LIKE -> 1.40
            FeedInteractionAction.UNLIKE -> -1.10
            FeedInteractionAction.SHARE -> 1.80
            FeedInteractionAction.UNSHARE -> -1.20
            FeedInteractionAction.COMMENT -> 1.60
        }
        val mediaDelta = when (action) {
            FeedInteractionAction.LIKE -> 0.70
            FeedInteractionAction.UNLIKE -> -0.50
            FeedInteractionAction.SHARE -> 1.00
            FeedInteractionAction.UNSHARE -> -0.70
            FeedInteractionAction.COMMENT -> 0.80
            FeedInteractionAction.OPEN -> 0.35
            FeedInteractionAction.IMPRESSION -> IMPRESSION_MEDIA_DELTA
        }

        synchronized(lock) {
            decay(authorAffinity, INTERACTION_DECAY)
            decay(mediaAffinity, INTERACTION_DECAY)
            rememberPost(post.id)
            applyDelta(authorAffinity, post.userId, delta)
            applyDelta(mediaAffinity, inferMediaType(post), mediaDelta)
        }
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

    private fun rememberPost(postId: String) {
        if (postId.isBlank()) return
        seenPostIds.remove(postId)
        seenPostIds.add(postId)
        while (seenPostIds.size > MAX_SEEN_POSTS) {
            val first = seenPostIds.firstOrNull() ?: break
            seenPostIds.remove(first)
        }
    }

    private fun decay(target: MutableMap<String, Double>, factor: Double) {
        if (target.isEmpty()) return
        val iterator = target.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val decayed = entry.value * factor
            if (kotlin.math.abs(decayed) < MIN_SIGNAL_ABS_VALUE) {
                iterator.remove()
            } else {
                entry.setValue(decayed.coerceIn(MIN_SIGNAL_VALUE, MAX_SIGNAL_VALUE))
            }
        }
    }

    private fun applyDelta(target: MutableMap<String, Double>, key: String, delta: Double) {
        if (key.isBlank()) return
        val current = target[key] ?: 0.0
        val updated = (current + delta).coerceIn(MIN_SIGNAL_VALUE, MAX_SIGNAL_VALUE)
        if (kotlin.math.abs(updated) < MIN_SIGNAL_ABS_VALUE) {
            target.remove(key)
        } else {
            target[key] = updated
        }
    }

    private companion object {
        private const val MAX_IMPRESSIONS_BATCH = 40
        private const val MAX_SEEN_POSTS = 900
        private const val IMPRESSION_DECAY = 0.997
        private const val INTERACTION_DECAY = 0.992
        private const val IMPRESSION_AUTHOR_DELTA = 0.08
        private const val IMPRESSION_MEDIA_DELTA = 0.04
        private const val MIN_SIGNAL_ABS_VALUE = 0.02
        private const val MIN_SIGNAL_VALUE = -3.5
        private const val MAX_SIGNAL_VALUE = 8.0
    }
}
