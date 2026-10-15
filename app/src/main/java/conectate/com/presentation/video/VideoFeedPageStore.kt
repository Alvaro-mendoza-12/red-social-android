package conectate.com.presentation.video

import conectate.com.data.model.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The page and its session reach Compose together, including when the list is unchanged. */
data class VideoFeedSnapshot(val videos: List<Video> = emptyList(), val sessionId: String? = null)

internal class VideoFeedPageStore {
    private val state = MutableStateFlow(VideoFeedSnapshot())
    val snapshot = state.asStateFlow()

    // Reactions, pagination and realtime edits keep the current navigation session.
    var value: List<Video>
        get() = state.value.videos
        set(videos) { state.value = state.value.copy(videos = videos) }

    fun publishInitial(videos: List<Video>, sessionId: String, allowEmpty: Boolean = false) {
        if (allowEmpty || videos.isNotEmpty()) state.value = VideoFeedSnapshot(videos, sessionId)
    }

    fun clear() { state.value = VideoFeedSnapshot() }
}
