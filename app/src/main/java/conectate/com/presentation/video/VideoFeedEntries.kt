package conectate.com.presentation.video

import conectate.com.data.model.*

internal sealed interface VideoFeedEntry {
    val stableKey: String
}

internal data class VideoFeedVideoEntry(val video: Video) : VideoFeedEntry {
    override val stableKey: String = "video:${video.id}"
}

internal data class VideoFeedLiveEntry(
    val stream: conectate.com.data.model.LiveStream
) : VideoFeedEntry {
    override val stableKey: String = "live:${stream.id}"
}

internal data class VideoFeedSocialEntry(
    val posts: List<Post>
) : VideoFeedEntry {
    override val stableKey: String = "social:${posts.joinToString(",") { it.id }}"
}

internal data class VideoFeedPeopleEntry(
    val users: List<conectate.com.data.model.User>
) : VideoFeedEntry {
    override val stableKey: String = "people:${users.joinToString(",") { it.id }}"
}

internal data class VideoFeedSocializationEntry(
    val cards: List<conectate.com.data.model.SocializationCard>,
) : VideoFeedEntry {
    override val stableKey: String = "socialize:${cards.joinToString(",") { it.id }}"
}

internal data object VideoFeedTailEntry : VideoFeedEntry {
    override val stableKey = "tail:recommended"
}

/** Secondary pages appear between videos, never as a substitute for an empty feed. */
internal fun mixedVideoFeed(
    videos: List<Video>, streams: List<LiveStream>, includeLives: Boolean,
    socialPosts: List<Post> = emptyList(), recommendedPeople: List<User> = emptyList(),
    socializationCards: List<SocializationCard> = emptyList(), includeTail: Boolean = false,
): List<VideoFeedEntry> {
    val primary = videos.distinctBy { it.id.trim().lowercase() }
    if (primary.isEmpty()) return emptyList()
    val lives = streams.distinctBy { it.id.trim().lowercase() }
    val result = ArrayList<VideoFeedEntry>()
    var nextLiveAfter = 12; var liveIndex = 0; var lastAuxiliaryAfter = 0
    var socialInserted = false; var peopleInserted = false; var socializationInserted = false
    primary.forEachIndexed { index,video ->
        result += VideoFeedVideoEntry(video)
        val count = index + 1
        // At least three real videos separate auxiliary cards. No terminal
        // rails, no cycling a single LIVE into duplicate pager keys.
        if (index == primary.lastIndex || count - lastAuxiliaryAfter < 3) return@forEachIndexed
        val auxiliary: VideoFeedEntry? = when {
            includeLives && liveIndex < lives.size && count >= nextLiveAfter -> {
                VideoFeedLiveEntry(lives[liveIndex++]).also { nextLiveAfter = count + 12 }
            }
            !socialInserted && socialPosts.isNotEmpty() && count >= 8 -> {
                socialInserted = true; VideoFeedSocialEntry(socialPosts.distinctBy { it.id }.take(8))
            }
            !peopleInserted && recommendedPeople.isNotEmpty() && count >= 15 -> {
                peopleInserted = true; VideoFeedPeopleEntry(recommendedPeople.distinctBy { it.id }.take(10))
            }
            !socializationInserted && socializationCards.isNotEmpty() && count >= 12 -> {
                socializationInserted = true; VideoFeedSocializationEntry(socializationCards.distinctBy { it.id }.take(4))
            }
            else -> null
        }
        if (auxiliary != null) { result += auxiliary; lastAuxiliaryAfter = count }
    }
    if (includeTail) result += VideoFeedTailEntry
    return result
}
