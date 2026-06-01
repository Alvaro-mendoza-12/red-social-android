package conectate.com.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import conectate.com.data.model.Chat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@Entity(
    tableName = "chats",
    indices = [
        Index(value = ["last_message_at"]),  // Index for ordering chats by recent activity
        // Keep DESC to match historical migration schema and avoid startup crash on upgrades.
        Index(value = ["is_pinned", "last_message_at"], orders = [Index.Order.ASC, Index.Order.DESC]),
        Index(value = ["is_archived", "is_pinned", "is_favorite", "last_message_at"], orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.DESC]),
        Index(value = ["is_group"]),         // Index for filtering group vs direct chats
        Index(value = ["unread_count"])      // Index for unread message queries
    ]
)
data class ChatEntity(
    @PrimaryKey val id: String,
    val name: String?,
    val is_group: Boolean,
    val created_by: String,
    // Store participants as JSON string for simplicity in MVP
    val participantsJson: String, 
    val last_message_at: String?,
    // Cached display data
    val display_name: String?,
    val display_image: String?,
    val unread_count: Int = 0,
    val other_user_id: String? = null, // For 1-on-1 joins
    val last_message_preview: String? = null,
    val last_message_timestamp: String? = null,
    val last_message_id: String? = null,
    val last_message_sender_id: String? = null,
    val is_deleted: Boolean = false,
    val nickname: String? = null,
    val cleared_at: String? = null,
    @ColumnInfo(defaultValue = "0")
    val is_muted: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val is_pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val is_archived: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val is_favorite: Boolean = false
) {
    fun toDomain(): Chat {
        val participantsList = try {
             Json.decodeFromString<List<String>>(participantsJson)
        } catch (e: Exception) { emptyList() }
        
        return Chat(
            id = id,
            group_name = name, // Map entity 'name' to domain 'group_name'
            is_group = is_group,
            // created_by removed from domain
            participants = participantsList,
            created_at = last_message_timestamp ?: "", // Fallback
            last_message_at = last_message_at,
            nickname = nickname,
            is_muted = is_muted,
            is_pinned = is_pinned,
            is_archived = is_archived,
            is_favorite = is_favorite,
            cleared_at = cleared_at
        )
    }

    fun toDomainWithDetails(): conectate.com.data.model.ChatWithLastMessage {
        var lastMessage = if (last_message_preview != null) {
            conectate.com.data.model.Message(
                id = last_message_id ?: java.util.UUID.randomUUID().toString(),
                chat_id = id,
                sender_id = last_message_sender_id ?: "",
                content = last_message_preview,
                content_type = "text",
                created_at = last_message_timestamp ?: "",
                status = "sent"
            )
        } else null

        val finalDisplayName = display_name ?: ""
        val finalDisplayImage = display_image

        return conectate.com.data.model.ChatWithLastMessage(
            chat = toDomain(),
            lastMessage = lastMessage,
            unreadCount = unread_count,
            otherUserName = finalDisplayName,
            otherUserImage = finalDisplayImage,
            otherUserId = other_user_id,
            nickname = nickname,
            clearedAt = cleared_at
        )
    }

    companion object {
        fun fromDomain(chat: Chat, displayName: String?, displayImage: String?, unreadCount: Int, lastMsg: String?, lastTime: String?, otherUserId: String? = null, lastMsgId: String? = null, lastSenderId: String? = null, nickname: String? = null, clearedAt: String? = null): ChatEntity {
            val normalizedLastAt = (lastTime ?: chat.last_message_at ?: chat.created_at).replace(" ", "T")
            return ChatEntity(
                id = chat.id,
                name = chat.group_name, // Map domain 'group_name' to entity 'name'
                is_group = chat.is_group,
                created_by = "", // Default as it's not in domain
                participantsJson = Json.encodeToString(chat.participants),
                last_message_at = normalizedLastAt,
                display_name = displayName,
                display_image = displayImage,
                unread_count = unreadCount,
                other_user_id = otherUserId,
                last_message_preview = lastMsg,
                last_message_timestamp = normalizedLastAt,
                last_message_id = lastMsgId ?: chat.last_message_id,
                last_message_sender_id = lastSenderId ?: chat.last_message_sender_id,
                is_deleted = false, // Default to false when creating new
                nickname = nickname ?: chat.nickname,
                cleared_at = clearedAt,
                is_muted = chat.is_muted,
                is_pinned = chat.is_pinned,
                is_archived = chat.is_archived,
                is_favorite = chat.is_favorite
            )
        }
    }
}



