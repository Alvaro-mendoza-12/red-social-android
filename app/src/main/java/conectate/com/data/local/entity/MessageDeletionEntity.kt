package conectate.com.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** Local intent, not delivery state. The actor may be the recipient, not the sender. */
@Entity(
    tableName = "message_deletions",
    primaryKeys = ["message_id", "actor_id", "for_everyone"],
    indices = [Index(value = ["actor_id", "state", "created_at"]), Index(value = ["chat_id"])]
)
data class MessageDeletionEntity(
    val message_id: String,
    val actor_id: String,
    val for_everyone: Boolean,
    val chat_id: String,
    val revision: String,
    val created_at: Long,
    val state: String = "pending",
    val error: String? = null
)
