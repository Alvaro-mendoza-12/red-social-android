package conectate.com.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import conectate.com.core.messaging.MessageStatusNormalizer
import conectate.com.data.model.GroupMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "group_messages",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"]
        )
    ],
    indices = [
        Index(value = ["group_id"]),
        Index(value = ["created_at"]),
        Index(value = ["is_synced", "pending_action"]),
        Index(value = ["poll_id"]),
        Index(value = ["is_forwarded"])
    ]
)
data class GroupMessageEntity(
    @PrimaryKey val id: String,
    val group_id: String,
    val sender_id: String,
    val content: String,
    val content_type: String,
    val media_url: String? = null,
    val poll_id: String? = null,
    val created_at: String,
    val status: String,
    val delivered_at: String? = null,
    val read_at: String? = null,
    val sender_name: String? = null,
    val sender_image: String? = null,
    val reply_to_id: String? = null,
    val reply_to_json: String? = null, // Store full quoted message as JSON
    val is_edited: Boolean = false,
    val deleted_for_all: Boolean = false,
    val deleted_by_json: String = "[]",
    @ColumnInfo(defaultValue = "'{}'")
    val reactions_json: String = "{}",
    @ColumnInfo(defaultValue = "0")
    val is_forwarded: Boolean = false,
    val forwarded_from_message_id: String? = null,
    val forwarded_from_user_id: String? = null,
    @ColumnInfo(defaultValue = "1")
    val is_synced: Boolean = true,
    val pending_action: String? = null, // "send", "edit", "delete", null
    @ColumnInfo(defaultValue = "0")
    val retry_count: Int = 0,
    val last_retry_at: String? = null
) {
    fun toDomain(): GroupMessage {
        var canonicalStatus = MessageStatusNormalizer.canonical(status, delivered_at, read_at)
        return GroupMessage(
            id = id,
            group_id = group_id,
            sender_id = sender_id,
            content = content,
            content_type = content_type,
            media_url = media_url,
            poll_id = poll_id,
            created_at = created_at,
            status = canonicalStatus,
            delivered_at = delivered_at,
            read_at = read_at,
            sender_name = sender_name,
            sender_image = sender_image,
            reply_to_id = reply_to_id,
            is_edited = is_edited,
            deleted_for_all = deleted_for_all,
            deleted_by = try { Json.decodeFromString(deleted_by_json) } catch(e:Exception) { emptyList() },
            reactions = try { Json.decodeFromString(reactions_json) } catch(e:Exception) { emptyMap() },
            is_forwarded = is_forwarded,
            forwarded_from_message_id = forwarded_from_message_id,
            forwarded_from_user_id = forwarded_from_user_id,
            reply_to = if (reply_to_json != null) try { Json.decodeFromString(reply_to_json) } catch(e:Exception){ null } else null
        )
    }

    companion object {
        fun fromDomain(
            msg: GroupMessage,
            isSynced: Boolean = MessageStatusNormalizer.normalize(msg.status) == "sent",
            pendingAction: String? = null,
            retryCount: Int = 0,
            lastRetryAt: String? = null
        ): GroupMessageEntity {
            var canonicalStatus = MessageStatusNormalizer.canonical(msg.status, msg.delivered_at, msg.read_at)
            return GroupMessageEntity(
                id = msg.id,
                group_id = msg.group_id,
                sender_id = msg.sender_id,
                content = msg.content,
                content_type = msg.content_type,
                media_url = msg.media_url,
                poll_id = msg.poll_id,
                created_at = msg.created_at,
                status = canonicalStatus,
                delivered_at = msg.delivered_at,
                read_at = msg.read_at,
                sender_name = msg.sender_name,
                sender_image = msg.sender_image,
                reply_to_id = msg.reply_to_id,
                reply_to_json = if (msg.reply_to != null) Json.encodeToString(msg.reply_to) else null,
                is_edited = msg.is_edited,
                deleted_for_all = msg.deleted_for_all,
                deleted_by_json = Json.encodeToString(msg.deleted_by),
                reactions_json = Json.encodeToString(msg.reactions),
                is_forwarded = msg.is_forwarded,
                forwarded_from_message_id = msg.forwarded_from_message_id,
                forwarded_from_user_id = msg.forwarded_from_user_id,
                is_synced = isSynced,
                pending_action = pendingAction,
                retry_count = retryCount,
                last_retry_at = lastRetryAt
            )
        }
    }
}



