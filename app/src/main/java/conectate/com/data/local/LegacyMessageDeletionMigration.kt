package conectate.com.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import kotlinx.serialization.json.Json

/** Old delete rows had no actor. Never replay a for-everyone mutation by guessing one. */
internal object LegacyMessageDeletionMigration {
    fun import(db: SupportSQLiteDatabase) {
        db.query("SELECT id, chat_id, sender_id, deleted_for_all, deleted_by_json FROM messages WHERE pending_action = 'delete'").use { rows ->
            while (rows.moveToNext()) {
                val messageId = rows.getString(0)
                val chatId = rows.getString(1)
                val sender = rows.getString(2).trim().lowercase()
                val forEveryone = rows.getInt(3) != 0
                val deletedBy = runCatching { Json.decodeFromString<List<String>>(rows.getString(4)) }
                    .getOrDefault(emptyList()).map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
                val actors = if (forEveryone) listOf(sender).filter { it.isNotBlank() } else deletedBy
                actors.forEach { actor ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO message_deletions (message_id, actor_id, for_everyone, chat_id, revision, created_at, state, error) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(messageId, actor, if (forEveryone) 1 else 0, chatId, UUID.randomUUID().toString(), 0L,
                            if (forEveryone) "failed" else "pending",
                            if (forEveryone) "Un borrado antiguo no guardó su cuenta. Vuelve a eliminar el mensaje desde su menú para confirmarlo." else null)
                    )
                }
                if (actors.isNotEmpty()) {
                    // These were optimistic flags, not a server acknowledgement. A
                    // for-me overlay is now durable; an ambiguous all-delete is retryable by the user.
                    db.execSQL("UPDATE messages SET pending_action = NULL, deleted_for_all = CASE WHEN ? = 1 THEN 0 ELSE deleted_for_all END WHERE id = ?", arrayOf(if (forEveryone) 1 else 0, messageId))
                }
            }
        }
    }
}
