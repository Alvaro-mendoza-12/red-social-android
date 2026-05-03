package conectate.com.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import conectate.com.core.messaging.MessageStatusNormalizer
import conectate.com.data.model.Message
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["chat_id", "created_at"]),  // Composite index for chat message listings
        Index(value = ["sender_id"]),               // Index for filtering by sender
        Index(value = ["status", "is_synced"]),     // Index for sync queries
        Index(value = ["pending_action"]),          // Index for pending operations
        Index(value = ["is_forwarded"])             // Index for forwarded message filtering
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chat_id: String,
    val sender_id: String,
    val content: String,
    val content_type: String, // text, image, video, audio
    val media_url: String? = null,
    val media_size: Long? = null,
    val created_at: String,
    val reply_to_id: String? = null,
    val reply_to_json: String? = null, // Store full quoted message as JSON
    
    // Sync & Status Fields
    val status: String, // "sending", "sent", "delivered", "read", "failed", "pending_delete"
    val is_edited: Boolean = false,
    val deleted_for_all: Boolean = false,
    val deleted_by_json: String = "[]", // List<String> of users who deleted for themselves
    val reactions_json: String = "{}",
    val is_forwarded: Boolean = false,
    val forwarded_from_message_id: String? = null,
    val forwarded_from_user_id: String? = null,
    val poll_id: String? = null,
    val delivered_at: String? = null,
    val read_at: String? = null,
    
    // Local-only flags
    val is_synced: Boolean = true,
    val pending_action: String? = null // "send", "delete", "edit", null
) {
    fun toDomain(): Message {
        var canonicalStatus = MessageStatusNormalizer.canonical(status, delivered_at, read_at)
        return Message(
            id = id,
            chat_id = chat_id,
            sender_id = sender_id,
            content = content,
            content_type = content_type,
            media_url = media_url,
            media_size = media_size ?: 0L,
            created_at = created_at,
            reply_to_id = reply_to_id,
            status = canonicalStatus,
            is_edited = is_edited,
            deleted_for_all = deleted_for_all,
            deleted_by = try { Json.decodeFromString(deleted_by_json) } catch(e:Exception) { emptyList() },
            reactions = try { Json.decodeFromString(reactions_json) } catch(e:Exception) { emptyMap() },
            is_forwarded = is_forwarded,
            forwarded_from_message_id = forwarded_from_message_id,
            forwarded_from_user_id = forwarded_from_user_id,
            poll_id = poll_id,
            delivered_at = delivered_at,
            read_at = read_at,
            reply_to = if (reply_to_json != null) try { Json.decodeFromString(reply_to_json) } catch(e:Exception){ null } else null
        )
    }

    companion object {
        fun fromDomain(msg: Message, isSynced: Boolean = true, pendingAction: String? = null): MessageEntity {
            var canonicalStatus = MessageStatusNormalizer.canonical(msg.status, msg.delivered_at, msg.read_at)
            return MessageEntity(
                id = msg.id,
                chat_id = msg.chat_id,
                sender_id = msg.sender_id,
                content = msg.content,
                content_type = msg.content_type,
                media_url = msg.media_url,
                media_size = msg.media_size,
                created_at = msg.created_at ?: "",
                reply_to_id = msg.reply_to_id,
                reply_to_json = if (msg.reply_to != null) Json.encodeToString(msg.reply_to) else null,
                status = canonicalStatus,
                is_edited = msg.is_edited,
                deleted_for_all = msg.deleted_for_all,
                deleted_by_json = Json.encodeToString(msg.deleted_by),
                reactions_json = Json.encodeToString(msg.reactions),
                is_forwarded = msg.is_forwarded,
                forwarded_from_message_id = msg.forwarded_from_message_id,
                forwarded_from_user_id = msg.forwarded_from_user_id,
                poll_id = msg.poll_id,
                delivered_at = msg.delivered_at,
                read_at = msg.read_at,
                is_synced = isSynced,
                pending_action = pendingAction
            )
        }
    }
}



