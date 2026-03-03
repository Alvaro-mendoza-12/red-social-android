package conectate.com.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "group_members",
    primaryKeys = ["group_id", "user_id"],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["user_id"])
    ]
)
data class GroupMemberEntity(
    val group_id: String,
    val user_id: String,
    val role: String,
    val joined_at: String
)
