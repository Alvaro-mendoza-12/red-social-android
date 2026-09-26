package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

enum class LiveSource {
    CAMERA,
    SCREEN,
    AUDIO
}

/**
 * Device-local presentation chosen by the creator for live setup and host controls.
 * It never grants permissions or changes server authority; those remain in the
 * existing live state machine.
 */
enum class LiveCreatorLayoutStyle(val wireValue: String, val displayName: String) {
    STUDIO("studio", "Estudio"),
    IMMERSIVE("immersive", "Inmersivo"),
    ROOM("room", "Sala"),
    FUSION("fusion", "Fusión");

    companion object {
        fun fromWireValue(value: String?): LiveCreatorLayoutStyle =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: FUSION
    }
}

enum class LiveRole {
    HOST,
    VIEWER
}

enum class LiveQuality(val label: String, val width: Int, val height: Int) {
    P480("480p", 854, 480),
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080)
}

enum class LiveGuestRequestStatus {
    NONE,
    PENDING,
    ACCEPTED,
    REJECTED
}

enum class LiveBattleStatus {
    NONE,
    INVITED,
    ACTIVE,
    FINISHED,
    REJECTED
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
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("game_name") val gameName: String? = null,
    val category: String? = null,
    val visibility: String = "public",
    @SerialName("gifts_enabled") val giftsEnabled: Boolean = true,
    @SerialName("battles_enabled") val battlesEnabled: Boolean = true,
    @SerialName("viewer_count") val viewerCount: Int = 0,
    @SerialName("total_reactions") val totalReactions: Long = 0,
    @SerialName("allow_guest_requests") val allowGuestRequests: Boolean = false,
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
    val kind: String = "heart",
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

data class LiveGuestRequest(
    val socketId: String,
    val userId: String,
    val name: String,
    val avatarUrl: String? = null,
    val requestedAtMs: Long = System.currentTimeMillis()
)

data class LiveGuestParticipant(
    val socketId: String,
    val userId: String,
    val name: String,
    val avatarUrl: String? = null
)

data class LiveGuestAccessState(
    val status: LiveGuestRequestStatus = LiveGuestRequestStatus.NONE,
    val message: String? = null
)

data class LiveBattleState(
    val battleId: String = "",
    val status: LiveBattleStatus = LiveBattleStatus.NONE,
    val opponentLiveId: String = "",
    val opponentSocketId: String = "",
    val opponentUserId: String = "",
    val opponentName: String = "",
    val opponentAvatarUrl: String? = null,
    val hostScore: Int = 0,
    val opponentScore: Int = 0,
    val durationSeconds: Int = 180,
    val startedAtMs: Long = 0L,
    val invitationIncoming: Boolean = false,
    val message: String? = null
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

data class LiveMusicPlaybackState(
    val displayName: String? = null,
    val uri: String? = null,
    val durationMs: Long = 0L,
    val enabled: Boolean = false,
    val volume: Float = 0.35f,
    val preparing: Boolean = false,
    val error: String? = null,
) {
    val isPrepared: Boolean get() = !displayName.isNullOrBlank() && !uri.isNullOrBlank()
}
