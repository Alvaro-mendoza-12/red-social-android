package conectate.com.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import conectate.com.data.model.User
import kotlinx.serialization.json.Json

@Entity(tableName = "users", indices = [
    Index(value = ["username"])
])
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val full_name: String,
    val avatar_url: String?,
    val cover_url: String? = null,
    val avatar_media_type: String? = null,
    @ColumnInfo(defaultValue = "1")
    val avatar_video_muted: Boolean = true,
    val avatar_duration_ms: Long? = null,
    val bio: String?,
    @ColumnInfo(defaultValue = "'conectate'")
    val profile_theme: String = "conectate",
    @ColumnInfo(defaultValue = "'none'")
    val profile_effect: String = "none",
    val profile_welcome: String? = null,
    @ColumnInfo(defaultValue = "'{}'")
    val social_links_json: String = "{}",
    @ColumnInfo(defaultValue = "0")
    val follower_count: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val following_count: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val is_verified: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val is_admin: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val voice_access_granted: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val is_online: Boolean = false,
    val last_seen: String? = null,
    @ColumnInfo(defaultValue = "0")
    val updated_at: Long = System.currentTimeMillis(),
    val profile_json: String? = null,
) {
    val displayName: String
        get() = if (full_name.isNotBlank()) full_name else username

    fun toDomain(): User {
        // Keep authoritative identity, music and privacy fields omitted by the
        // compact columns. Legacy rows retain the safe, name-based fallback.
        val snapshot = profile_json?.let { raw ->
            runCatching { profileJson.decodeFromString(User.serializer(), raw) }.getOrNull()
        }?.takeIf { it.id == id && it.username.orEmpty() == username }
        return (snapshot ?: User(id = id)).copy(
            id = id,
            username = username,
            username_origin = snapshot?.username_origin,
            full_name = full_name,
            avatar_url = avatar_url,
            cover_url = cover_url,
            avatar_media_type = avatar_media_type,
            avatar_video_muted = avatar_video_muted,
            avatar_duration_ms = avatar_duration_ms,
            bio = bio,
            profile_theme = profile_theme,
            profile_effect = profile_effect,
            profile_welcome = profile_welcome,
            social_links = decodeSocialLinks(social_links_json),
            followers_count = follower_count,
            following_count = following_count,
            is_verified = is_verified,
            is_admin = is_admin,
            voice_access_granted = voice_access_granted,
            is_online = is_online,
            lastSeen = last_seen
        )
    }

    companion object {
        private val profileJson = Json { ignoreUnknownKeys = true }

        fun fromDomain(user: User): UserEntity {
            return UserEntity(
                id = user.id,
                username = user.username ?: "",
                full_name = user.full_name ?: "",
                avatar_url = user.avatar_url,
                cover_url = user.cover_url,
                avatar_media_type = user.avatar_media_type,
                avatar_video_muted = user.avatar_video_muted,
                avatar_duration_ms = user.avatar_duration_ms,
                bio = user.bio,
                profile_theme = user.profile_theme,
                profile_effect = user.profile_effect,
                profile_welcome = user.profile_welcome,
                social_links_json = encodeSocialLinks(user.social_links),
                follower_count = user.followers_count ?: 0,
                following_count = user.following_count ?: 0,
                is_verified = user.is_verified,
                is_admin = user.is_admin,
                voice_access_granted = user.voice_access_granted,
                is_online = user.is_online,
                last_seen = user.lastSeen,
                profile_json = profileJson.encodeToString(User.serializer(), user),
            )
        }

        private fun encodeSocialLinks(values: Map<String, String>): String =
            org.json.JSONObject(values).toString()

        private fun decodeSocialLinks(raw: String): Map<String, String> = runCatching {
            val json = org.json.JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    json.optString(key).takeIf(String::isNotBlank)?.let { put(key, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }
}
