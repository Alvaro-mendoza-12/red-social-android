package conectate.com.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import conectate.com.data.model.Group

@Entity(
    tableName = "groups",
    indices = [
        Index(value = ["last_message_at"])
    ]
)
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val image: String?,
    val description: String?,
    val created_at: String,
    val creator_id: String,
    val last_message: String?,
    val last_message_at: String?,
    val unread_count: Int = 0,
    val is_deleted: Boolean = false,
    val is_member: Boolean = true,
    val only_admins_can_talk: Boolean = false,
    val rest_until: String? = null
) {
    fun toDomain(): Group {
        return Group(
            id = id,
            name = name,
            image = image,
            description = description,
            created_at = created_at,
            creator_id = creator_id,
            last_message = last_message,
            last_message_at = last_message_at,
            is_deleted = is_deleted,
            is_member = is_member,
            only_admins_can_talk = only_admins_can_talk,
            rest_until = rest_until
        )
    }

    companion object {
        fun fromDomain(group: Group, unreadCount: Int = 0): GroupEntity {
            return GroupEntity(
                id = group.id,
                name = group.name,
                image = group.image,
                description = group.description,
                created_at = group.created_at,
                creator_id = group.creator_id,
                last_message = group.last_message,
                last_message_at = group.last_message_at,
                unread_count = unreadCount,
                is_deleted = group.is_deleted,
                is_member = group.is_member,
                only_admins_can_talk = group.only_admins_can_talk,
                rest_until = group.rest_until
            )
        }
    }
}
