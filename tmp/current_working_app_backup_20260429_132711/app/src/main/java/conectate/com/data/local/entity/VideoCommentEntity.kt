package conectate.com.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import conectate.com.data.model.VideoComment

@Entity(tableName = "video_comments")
data class VideoCommentEntity(
    @PrimaryKey val id: String,
    val videoId: String,
    val userId: String,
    val userName: String,
    val userAvatarUrl: String?,
    val content: String,
    val parentId: String? = null, // For nested replies
    val likeCount: Int = 0,
    val createdAt: String,
    val isPosted: Boolean = true, // False if pending upload
    val contentType: String = "text",
    val mediaUrl: String? = null
) {
    fun toDomain(): VideoComment {
        return VideoComment(
            id = id,
            videoId = videoId,
            userId = userId,
            userName = userName,
            userProfileImage = userAvatarUrl ?: "",
            content = content,
            likes = likeCount,
            created_at = createdAt,
            parentId = parentId,
            contentType = contentType,
            mediaUrl = mediaUrl
        )
    }

    companion object {
        fun fromDomain(domain: VideoComment): VideoCommentEntity {
            // Live metadata logic: prefer data from the 'profiles' join if available
            val liveUserName = domain.profiles?.username 
                ?: domain.profiles?.fullName 
                ?: domain.userName 
                ?: "Usuario"
            
            val liveAvatarUrl = domain.profiles?.avatarUrl 
                ?: domain.userProfileImage

            return VideoCommentEntity(
                id = domain.id,
                videoId = domain.videoId,
                userId = domain.userId,
                userName = liveUserName,
                userAvatarUrl = liveAvatarUrl,
                content = domain.content,
                parentId = domain.parentId,
                likeCount = domain.likes,
                createdAt = domain.created_at,
                isPosted = true,
                contentType = domain.contentType,
                mediaUrl = domain.mediaUrl
            )
        }
    }
}


