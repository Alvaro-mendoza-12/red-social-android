package conectate.com.data.local.entity

import androidx.room.*
import conectate.com.core.messaging.MessageStatusNormalizer
import conectate.com.data.model.CommunityMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import conectate.com.data.model.Community

@Entity(
    tableName = "communities",
    indices = [
        Index(value = ["last_message_at"]),
        Index(value = ["category"])
    ]
)
data class CommunityEntity(
    @PrimaryKey val id: String,
    val name: String,
    val image: String?,
    val description: String?,
    val category: String?,
    val is_public: Boolean,
    val created_at: String,
    val creator_id: String,
    val last_message: String?,
    val last_message_at: String?,
    val unread_count: Int = 0,
    val is_deleted: Boolean = false,
    val only_admins_can_talk: Boolean = false,
    val daily_message_limit: Int = 0,
    val is_member: Boolean = false
) {
    fun toDomain(): Community {
        return Community(
            id = id,
            name = name,
            image = image,
            description = description,
            category = category,
            is_public = is_public,
            created_at = created_at,
            creator_id = creator_id,
            last_message = last_message,
            last_message_at = last_message_at,
            is_deleted = is_deleted,
            only_admins_can_talk = only_admins_can_talk,
            daily_message_limit = daily_message_limit,
            is_member = is_member
        )
    }

    companion object {
        fun fromDomain(community: Community, unreadCount: Int = 0): CommunityEntity {
            return CommunityEntity(
                id = community.id,
                name = community.name,
                image = community.image,
                description = community.description,
                category = community.category,
                is_public = community.is_public,
                created_at = community.created_at,
                creator_id = community.creator_id,
                last_message = community.last_message,
                last_message_at = community.last_message_at,
                unread_count = unreadCount,
                is_deleted = community.is_deleted,
                only_admins_can_talk = community.only_admins_can_talk,
                daily_message_limit = community.daily_message_limit,
                is_member = community.is_member
            )
        }
    }
}

@Entity(
    tableName = "community_members",
    primaryKeys = ["community_id", "user_id"],
    foreignKeys = [
        ForeignKey(
            entity = CommunityEntity::class,
            parentColumns = ["id"],
            childColumns = ["community_id"]
        )
    ],
    indices = [
        Index(value = ["user_id"])
    ]
)
data class CommunityMemberEntity(
    val community_id: String,
    val user_id: String,
    val role: String,
    val joined_at: String
)

@Entity(
    tableName = "community_messages",
    foreignKeys = [
        ForeignKey(
            entity = CommunityEntity::class,
            parentColumns = ["id"],
            childColumns = ["community_id"]
        )
    ],
    indices = [
        Index(value = ["community_id"]),
        Index(value = ["channel_id"]),
        Index(value = ["created_at"]),
        Index(value = ["is_synced", "pending_action"]),
        Index(value = ["poll_id"]),
        Index(value = ["is_forwarded"])
    ]
)
data class CommunityMessageEntity(
    @PrimaryKey val id: String,
    val community_id: String,
    val channel_id: String? = null,
    val sender_id: String,
    val content: String,
    val content_type: String,
    val media_url: String? = null,
    val poll_id: String? = null,
    val created_at: String,
    val status: String = "sent",
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
    fun toDomain(): conectate.com.data.model.CommunityMessage {
        var canonicalStatus = MessageStatusNormalizer.canonical(status, delivered_at, read_at)
        return conectate.com.data.model.CommunityMessage(
            id = id,
            community_id = community_id,
            channel_id = channel_id,
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
            msg: conectate.com.data.model.CommunityMessage,
            isSynced: Boolean = true,
            pendingAction: String? = null,
            retryCount: Int = 0,
            lastRetryAt: String? = null
        ): CommunityMessageEntity {
            var canonicalStatus = MessageStatusNormalizer.canonical(msg.status, msg.delivered_at, msg.read_at)
            return CommunityMessageEntity(
                id = msg.id,
                community_id = msg.community_id,
                channel_id = msg.channel_id,
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



