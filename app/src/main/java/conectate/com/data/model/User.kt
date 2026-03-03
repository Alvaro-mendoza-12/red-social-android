package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.time.LocalDateTime
import java.time.Instant

@Serializable
data class User(
    val id: String = "",
    val numeric_id: Long? = null,
    val email: String = "",
    val username: String? = null,
    val full_name: String? = null,
    val phone: String? = null,
    val bio: String? = null,
    val avatar_url: String? = null,
    val cover_url: String? = null,
    val country: String? = null,
    val city: String? = null,
    val followers_count: Int? = 0,
    val following_count: Int? = 0,
    val post_count: Int? = 0,
    val status: String? = null,
    val display_name: String? = null,
    val last_name_change: String? = null,
    val is_private: Boolean = false,
    val profile_visibility: String = "everyone",
    val is_admin: Boolean = false,
    val voice_access_granted: Boolean = false,
    val is_verified: Boolean = false,
    @SerialName("is_suspended") val is_suspended: Boolean = false,
    @SerialName("suspended_until") val suspended_until: String? = null,
    @SerialName("suspension_end_date") val suspension_end_date: String? = null,
    @SerialName("suspension_reason") val suspension_reason: String? = null,
    @SerialName("suspension_count") val suspension_count: Int = 0,
    @SerialName("privacy_policy_accepted") val privacy_policy_accepted: Boolean = false,
    @SerialName("privacy_policy_accepted_at") val privacy_policy_accepted_at: String? = null,
    val badges: List<Badge>? = null,
    val is_online: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
    val balance: Double = 0.0,
    @SerialName("created_at") val created_at: String = "",
    @SerialName("updated_at") val updated_at: String = ""
) {
    val displayName: String
        get() {
            val name = display_name ?: full_name ?: username ?: email.substringBefore("@")
            return if (name.equals("null", ignoreCase = true)) "Usuario" else name
        }
    
    val safeDisplayName: String
        get() = displayName
    
    val profileImage: String
        get() = if (avatar_url.isNullOrEmpty() || avatar_url.equals("null", ignoreCase = true)) "" else avatar_url
        
    val safeProfileImage: String?
        get() = if (profileImage.isEmpty()) null else profileImage
        
    val coverImage: String
        get() = if (cover_url.isNullOrEmpty() || cover_url.equals("null", ignoreCase = true)) "" else cover_url
        
    // Compatibility properties for repositories
    val name: String
        get() = displayName
        
    val profileImageUrl: String
        get() = profileImage
        
    val coverImageUrl: String
        get() = coverImage

    val isSuspendedNow: Boolean
        get() {
            if (!is_suspended) return false
            val until = effectiveSuspensionEndDate?.trim().orEmpty()
            if (until.isBlank()) return true
            val parsed = runCatching {
                val normalized = if (until.contains("T")) until else until.replace(" ", "T")
                Instant.parse(
                    if (normalized.endsWith("Z") || normalized.contains("+")) normalized else "${normalized}Z"
                )
            }.getOrNull() ?: return true
            return parsed.isAfter(Instant.now())
        }

    val effectiveSuspensionEndDate: String?
        get() = suspension_end_date?.takeIf { it.isNotBlank() } ?: suspended_until
}
