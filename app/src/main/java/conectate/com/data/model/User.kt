package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
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
    val avatar_media_type: String? = null,
    val avatar_video_muted: Boolean = true,
    val avatar_duration_ms: Long? = null,
    val cover_url: String? = null,
    val profile_theme: String = "conectate",
    val profile_effect: String = "none",
    val profile_welcome: String? = null,
    val country: String? = null,
    val city: String? = null,
    val gender: String = "unspecified",
    val birth_date: String? = null,
    val interests: List<String> = emptyList(),
    val hobbies: List<String> = emptyList(),
    val linked_games: List<String> = emptyList(),
    val game_logo_urls: Map<String, String> = emptyMap(),
    val favorite_music_url: String? = null,
    val favorite_book: String? = null,
    val favorite_sport: String? = null,
    val career: String? = null,
    val availability_schedule: String? = null,
    val profile_section_order: List<String> = emptyList(),
    val profile_section_visibility: Map<String, String> = emptyMap(),
    val social_links: Map<String, String> = emptyMap(),
    val followers_count: Int? = 0,
    val following_count: Int? = 0,
    val post_count: Int? = 0,
    val status: String? = null,
    val display_name: String? = null,
    val last_name_change: String? = null,
    val is_private: Boolean = false,
    val favorites_public: Boolean = false,
    val break_mode: String = "off",
    val profile_visibility: String = "everyone",
    @SerialName("profile_view_history_enabled") val profile_view_history_enabled: Boolean = true,
    @SerialName("last_seen_visibility") val last_seen_visibility: String = "everyone",
    @SerialName("online_status_visibility") val online_status_visibility: String = "everyone",
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
    @SerialName("profile_frame") val profile_frame: String = "",
    val is_online: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
    @Serializable(with = NullableDoubleAsZeroSerializer::class)
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

    val isProfileAvatarVideo: Boolean
        get() {
            val type = avatar_media_type?.trim()?.lowercase().orEmpty()
            val url = avatar_url?.substringBefore("?")?.lowercase().orEmpty()
            return type == "video" ||
                type.startsWith("video/") ||
                url.endsWith(".mp4") ||
                url.endsWith(".m4v") ||
                url.endsWith(".mov")
        }
        
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

object NullableDoubleAsZeroSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NullableDoubleAsZeroSerializer", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val element = jsonDecoder.decodeJsonElement()

        if (element is JsonNull) {
            return 0.0
        }

        val primitive = element as? JsonPrimitive ?: return 0.0

        return primitive.doubleOrNull
            ?: primitive.content.toDoubleOrNull()
            ?: 0.0
    }

    override fun serialize(encoder: Encoder, value: Double) {
        encoder.encodeDouble(value)
    }
}


