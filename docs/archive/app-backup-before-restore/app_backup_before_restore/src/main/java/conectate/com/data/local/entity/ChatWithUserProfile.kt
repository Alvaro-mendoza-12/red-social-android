package conectate.com.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class ChatWithUserProfile(
    @Embedded val chat: ChatEntity,
    @Relation(
        parentColumn = "other_user_id",
        entityColumn = "id"
    )
    val user: UserEntity?
) {
    fun toDomainWithDetails(
        lastMessageOverride: conectate.com.data.model.Message? = null
    ): conectate.com.data.model.ChatWithLastMessage {
        var lastMessage = lastMessageOverride ?: if (chat.last_message_preview != null) {
            conectate.com.data.model.Message(
                id = chat.last_message_id ?: java.util.UUID.randomUUID().toString(),
                chat_id = chat.id,
                sender_id = chat.last_message_sender_id ?: "",
                content = chat.last_message_preview,
                content_type = "text",
                created_at = chat.last_message_timestamp ?: "",
                status = "sent"
            )
        } else null
        
        // CRITICAL FIX: Use user.displayName (fresh) if available, otherwise fallback to chat cache
        val finalDisplayName = user?.displayName ?: chat.display_name ?: ""
        val finalDisplayImage = user?.avatar_url ?: chat.display_image

        return conectate.com.data.model.ChatWithLastMessage(
            chat = chat.toDomain(),
            lastMessage = lastMessage,
            unreadCount = chat.unread_count,
            otherUserName = finalDisplayName,
            otherUserImage = finalDisplayImage,
            otherUserId = chat.other_user_id,
            nickname = chat.nickname,
            clearedAt = chat.cleared_at
        )
    }
}



