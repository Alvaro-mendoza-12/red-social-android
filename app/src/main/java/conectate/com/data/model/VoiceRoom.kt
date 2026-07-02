package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Serializable
data class VoiceRoom(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    @SerialName("creator_id") val creatorId: String = "",
    @Transient val creatorName: String = "",
    @SerialName("participant_count") val participantCount: Int = 0,
    @SerialName("max_participants") val maxParticipants: Int = 50,
    @SerialName("is_public") val isPublic: Boolean = true,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("agora_channel_name") val agoraChannelName: String = "",
    val password: String? = null,
    @SerialName("readable_id") val readableId: String? = null,
    val settings: kotlinx.serialization.json.JsonElement? = null,
    @Transient val agoraToken: String = "",
    @Transient val participantIds: List<String> = emptyList(),
    @Transient val speakerIds: List<String> = emptyList(),
    @Transient val participantNames: Map<String, String> = emptyMap(),
    @SerialName("is_cinema_mode") val isCinemaMode: Boolean = false,
    @SerialName("current_video_url") val currentVideoUrl: String? = null,
    @SerialName("current_video_title") val currentVideoTitle: String? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("current_position") val currentPosition: Long = 0L,
    @Transient val created_at: String = "",  // Database generates this
    @Transient val updated_at: String = ""   // Database generates this
)

@Serializable
data class VoiceParticipant(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = false,
    @Transient val socketId: String = ""
)
