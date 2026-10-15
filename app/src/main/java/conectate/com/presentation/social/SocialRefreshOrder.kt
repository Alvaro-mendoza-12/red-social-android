package conectate.com.presentation.social

import conectate.com.data.model.Post
import kotlin.random.Random

/** Keeps relevance bands intact while allowing an explicit refresh to feel new. */
internal fun diversifySocialRefresh(
    ranked: List<Post>,
    refreshSeed: String?,
    bandSize: Int = 5,
): List<Post> {
    if (ranked.size < 2 || refreshSeed.isNullOrBlank()) return ranked
    val safeBandSize = bandSize.coerceIn(2, 8)
    val random = Random(refreshSeed.hashCode())
    return ranked.chunked(safeBandSize).flatMap { band ->
        if (band.size < 2) band else band.shuffled(random)
    }
}

/** Account/session scoped ordering; refreshing counters never changes this seed. */
internal fun orderSocialDiscoveryForSession(posts: List<Post>, accountId: String?, sessionId: String,
    previousFirstId: String? = null, seenIds: Set<String> = emptySet()): List<Post> {
    val unique = posts.distinctBy { it.id.trim().lowercase() }
    val ordered = diversifySocialRefresh(unique, "${accountId?.trim()?.lowercase().orEmpty()}:$sessionId")
    return conectate.com.core.feed.prioritizeFeedCandidates(
        ordered, seenIds, previousFirstId, headBandSize = 5,
    ) { it.id }
}
