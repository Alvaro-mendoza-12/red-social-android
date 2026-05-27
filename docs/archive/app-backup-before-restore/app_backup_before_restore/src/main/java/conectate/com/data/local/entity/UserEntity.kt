package conectate.com.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import conectate.com.data.model.User

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val full_name: String,
    val avatar_url: String?,
    val bio: String?,
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
    val updated_at: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = if (full_name.isNotBlank()) full_name else username

    fun toDomain(): User {
        return User(
            id = id,
            username = username,
            full_name = full_name,
            avatar_url = avatar_url,
            bio = bio,
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
        fun fromDomain(user: User): UserEntity {
            return UserEntity(
                id = user.id,
                username = user.username ?: "",
                full_name = user.full_name ?: "",
                avatar_url = user.avatar_url,
                bio = user.bio,
                follower_count = user.followers_count ?: 0,
                following_count = user.following_count ?: 0,
                is_verified = user.is_verified,
                is_admin = user.is_admin,
                voice_access_granted = user.voice_access_granted,
                is_online = user.is_online,
                last_seen = user.lastSeen
            )
        }
    }
}
