package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

enum class LiveSource {
    CAMERA,
    SCREEN
}

enum class LiveRole {
    HOST,
    VIEWER
}

enum class LiveQuality(val label: String, val width: Int, val height: Int) {
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080)
}

@Serializable
data class LiveChapter(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("live_id") val liveId: String = "",
    val title: String = "",
    @SerialName("offset_ms") val offsetMs: Long = 0,
    @SerialName("is_highlight") val isHighlight: Boolean = true,
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class LiveStream(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    @SerialName("host_user_id") val hostUserId: String = "",
    @SerialName("host_name") val hostName: String = "",
    @SerialName("host_avatar_url") val hostAvatarUrl: String? = null,
    val source: String = "camera",
    @SerialName("viewer_count") val viewerCount: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("replay_url") val replayUrl: String? = null,
    @SerialName("replay_thumbnail_url") val replayThumbnailUrl: String? = null,
    @SerialName("replay_duration_ms") val replayDurationMs: Long = 0,
    @SerialName("replay_published") val replayPublished: Boolean = false,
    @SerialName("replay_updated_at") val replayUpdatedAt: String? = null,
    val chapters: List<LiveChapter> = emptyList()
)

@Serializable
data class LiveComment(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("live_id") val liveId: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val content: String = "",
    @SerialName("created_at") val createdAt: String? = null
)

data class LiveReaction(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String = "❤️",
    val userId: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)

data class LiveViewerParticipant(
    val socketId: String,
    val userId: String,
    val name: String,
    val avatarUrl: String? = null,
    val isModerator: Boolean = false
)

data class LiveModerationState(
    val chatPaused: Boolean = false,
    val blockedWords: List<String> = emptyList(),
    val canModerate: Boolean = false,
    val isModerator: Boolean = false,
    val isHost: Boolean = false,
    val moderatorUserIds: Set<String> = emptySet()
)

data class ActiveLiveSession(
    val stream: LiveStream,
    val role: LiveRole,
    val viewerCount: Int = 0
)
