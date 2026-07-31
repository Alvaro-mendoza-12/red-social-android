package conectate.com.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Community(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val image: String? = null,
    val description: String? = null,
    val category: String? = null,
    val is_public: Boolean = true,
    val created_at: String,
    val creator_id: String,
    val last_message: String? = null,
    val last_message_at: String? = null,
    val is_deleted: Boolean = false,
    val only_admins_can_talk: Boolean = false,
    val daily_message_limit: Int = 0,
    val is_member: Boolean = false,
    val current_user_role: String? = null
)

@Serializable
data class CommunityMember(
    val community_id: String,
    val user_id: String,
    val role: String = "member",
    val joined_at: String,
    val userName: String? = null,
    val userImage: String? = null
)

@Serializable
data class CommunityMessage(
    val id: String = UUID.randomUUID().toString(),
    val community_id: String = "",
    val channel_id: String? = null,
    val sender_id: String = "",
    val content: String = "",
    val content_type: String = "text",
    val media_url: String? = null,
    val poll_id: String? = null,
    val created_at: String = "",
    val status: String = "sent",
    val delivered_at: String? = null,
    val read_at: String? = null,
    val sender_name: String? = null,
    val sender_image: String? = null,
    val reply_to_id: String? = null,
    val is_edited: Boolean = false,
    val deleted_for_all: Boolean = false,
    val deleted_by: List<String> = emptyList(),
    val reactions: Map<String, List<String>> = emptyMap(),
    val is_forwarded: Boolean = false,
    val forwarded_from_message_id: String? = null,
    val forwarded_from_user_id: String? = null,
    @kotlinx.serialization.SerialName("reply_to") val reply_to: CommunityMessage? = null
) {
    val timestampMillis: Long
        get() {
            return try {
                if (created_at.isBlank()) System.currentTimeMillis()
                else {
                    val cleaned = created_at.trim()
                        .replace(" ", "T")
                        .let { if (!it.contains("Z") && !it.contains("+") && it.length > 10) "${it}Z" else it }
                    
                    try {
                        java.time.Instant.parse(cleaned).toEpochMilli()
                    } catch (e1: Exception) {
                        try {
                            java.time.OffsetDateTime.parse(cleaned).toInstant().toEpochMilli()
                        } catch (e2: Exception) {
                            try {
                                java.time.LocalDateTime.parse(cleaned.substringBefore("Z")).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                            } catch (e3: Exception) {
                                try { cleaned.toLong() } catch (e4: Exception) { System.currentTimeMillis() }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
        }

    val sortKey: String
        get() = created_at.trim().replace(" ", "T")
}

@Serializable
data class CommunityWithLastMessage(
    val community: Community,
    val lastMessage: CommunityMessage? = null,
    val unreadCount: Int = 0
)

@Serializable
data class CommunityRoleCapabilities(
    val role: String,
    val label: String,
    val canPost: Boolean = false,
    val canModerateMessages: Boolean = false,
    val canManageMembers: Boolean = false,
    val canManageRoles: Boolean = false,
    val canManageChannels: Boolean = false,
    val canManageEvents: Boolean = false,
    val canEditCommunity: Boolean = false,
    val canPauseChannels: Boolean = false
)

@Serializable
data class CommunityRoleCatalog(
    val currentRole: String,
    val roles: List<CommunityRoleCapabilities> = emptyList()
)

@Serializable
data class CommunityChannel(
    val id: String,
    val communityId: String,
    val name: String,
    val description: String? = null,
    val type: String = "text",
    val position: Int = 0,
    val isDefault: Boolean = false,
    val pausedUntil: String? = null,
    val pauseReason: String? = null,
    val isPaused: Boolean = false,
    val canManage: Boolean = false,
    val canPause: Boolean = false,
    val voiceRoomId: String? = null,
    val voiceParticipantCount: Int = 0
)

@Serializable
data class CommunityChannelCatalog(
    val currentRole: String = "member",
    val channels: List<CommunityChannel> = emptyList()
)

@Serializable
data class CommunityMemberProfile(
    val communityId: String,
    val userId: String,
    val alias: String? = null,
    val bio: String? = null,
    val distinctive: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class CommunityMemberProfileCatalog(
    val profile: CommunityMemberProfile,
    val profiles: List<CommunityMemberProfile> = emptyList()
)

@Serializable
data class CommunityEvent(
    val id: String,
    val communityId: String,
    val title: String,
    val description: String? = null,
    val startsAt: String,
    val endsAt: String? = null,
    val location: String? = null,
    val createdBy: String,
    val isCancelled: Boolean = false,
    val attendanceCount: Int = 0,
    val currentAttendance: String? = null,
    val reminderMinutes: Int? = null,
    val reminderAt: String? = null,
    val reminderSentAt: String? = null,
    val canManage: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class CommunityEventCatalog(
    val currentRole: String = "member",
    val canManageEvents: Boolean = false,
    val events: List<CommunityEvent> = emptyList()
)

fun CommunityChannel.acceptsMessages(): Boolean = type != "voice"

fun CommunityChannel.isEffectivelyPaused(nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (!isPaused) return false
    val until = pausedUntil?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
    return until == null || until > nowMillis
}

fun normalizeCommunityRole(role: String?, isCreator: Boolean = false): String {
    if (isCreator) return "owner"
    return when (role?.trim()?.lowercase()) {
        "owner", "creator" -> "owner"
        "admin" -> "admin"
        "moderator", "mod" -> "moderator"
        else -> "member"
    }
}

fun communityRoleCapabilities(role: String?): CommunityRoleCapabilities {
    val normalized = normalizeCommunityRole(role)
    val isOwner = normalized == "owner"
    val isAdmin = normalized == "admin"
    val isModerator = normalized == "moderator"
    return CommunityRoleCapabilities(
        role = normalized,
        label = when (normalized) {
            "owner" -> "Propietario"
            "admin" -> "Administrador"
            "moderator" -> "Moderador"
            else -> "Miembro"
        },
        canPost = true,
        canModerateMessages = isOwner || isAdmin || isModerator,
        canManageMembers = isOwner || isAdmin,
        canManageRoles = isOwner || isAdmin,
        canManageChannels = isOwner || isAdmin,
        canManageEvents = isOwner || isAdmin,
        canEditCommunity = isOwner || isAdmin,
        canPauseChannels = isOwner || isAdmin || isModerator
    )
}

fun defaultCommunityRoleCatalog(currentRole: String?): CommunityRoleCatalog =
    CommunityRoleCatalog(
        currentRole = normalizeCommunityRole(currentRole),
        roles = listOf("owner", "admin", "moderator", "member").map(::communityRoleCapabilities)
    )


