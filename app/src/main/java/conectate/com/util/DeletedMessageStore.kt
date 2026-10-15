package conectate.com.util

import android.content.Context
import conectate.com.data.model.Message
import conectate.com.data.model.GroupMessage
import conectate.com.data.model.CommunityMessage
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Durable account/chat/message tombstones survive process death and push reordering. */
class DeletedMessageStore(context: Context) {
    companion object { private val revision = MutableStateFlow(0L) }
    val changes: StateFlow<Long> get() = revision
    private val prefs = context.applicationContext.getSharedPreferences("deleted_message_tombstones", Context.MODE_PRIVATE)
    fun mark(identity: DeletedMessageIdentity, createdAt: String? = null, forEveryone: Boolean = true) {
        val key = (if (forEveryone) "all:" else "me:") + identity.key
        val edit = prefs.edit().putBoolean(key, true)
        if (!createdAt.isNullOrBlank() && prefs.getString("created:" + identity.key, null).isNullOrBlank()) edit.putString("created:" + identity.key, createdAt)
        check(edit.commit()) { "Could not persist message deletion" }
        revision.update { it + 1 }
    }
    fun isDeleted(identity: DeletedMessageIdentity) = prefs.getBoolean("all:" + identity.key, false)
    fun isSuppressed(identity: DeletedMessageIdentity) = isDeleted(identity) || prefs.getBoolean("me:" + identity.key, false)
    fun recordSummary(account: String?, type: String, room: String, messageId: String?, createdAt: String?) {
        val scope = DeletedMessageIdentity.create(account, type, room, "summary")?.conversationKey ?: return
        prefs.edit().putString("summary:" + scope, messageId?.trim()?.lowercase())
            .putString("summary-at:" + scope, createdAt).apply()
        revision.update { it + 1 }
    }
    private fun deleted(account: String?, type: String, room: String, id: String) =
        DeletedMessageIdentity.create(account, type, room, id)?.let(::isDeleted) == true
    fun redact(message: Message, account: String?): Message {
        if (message.deleted_for_all || deleted(account, "chat", message.chat_id, message.id)) return message.copy(
            deleted_for_all = true, content = "", content_type = "text", media_url = null, thumbnail_url = null,
            media_size = 0, poll_id = null, reply_to_id = null, reply_to = null, reactions = emptyMap())
        val quote = message.reply_to
        return if (quote == null) message else message.copy(reply_to = redact(quote.copy(reply_to = null), account))
    }
    fun redact(message: GroupMessage, account: String?): GroupMessage {
        if (message.deleted_for_all || deleted(account, "group", message.group_id, message.id)) return message.copy(
            deleted_for_all = true, content = "", content_type = "text", media_url = null, poll_id = null,
            reply_to_id = null, reply_to = null, reactions = emptyMap())
        val quote = message.reply_to
        return if (quote == null) message else message.copy(reply_to = redact(quote.copy(reply_to = null), account))
    }
    fun redact(message: CommunityMessage, account: String?): CommunityMessage {
        if (message.deleted_for_all || deleted(account, "community", message.community_id, message.id)) return message.copy(
            deleted_for_all = true, content = "", content_type = "text", media_url = null, poll_id = null,
            reply_to_id = null, reply_to = null, reactions = emptyMap())
        val quote = message.reply_to
        return if (quote == null) message else message.copy(reply_to = redact(quote.copy(reply_to = null), account))
    }
    fun redactSummary(account: String?, type: String, room: String, preview: String?, createdAt: String?): String? {
        val scope = DeletedMessageIdentity.create(account, type, room, "summary")?.conversationKey ?: return preview
        val at = instant(createdAt) ?: return preview
        if (instant(prefs.getString("summary-at:" + scope, null)) != at) return preview
        val identity = DeletedMessageIdentity.create(account, type, room, prefs.getString("summary:" + scope, null)) ?: return preview
        return if (isDeleted(identity)) "Mensaje eliminado" else preview
    }
    private fun instant(value: String?) = runCatching { Instant.parse(value?.trim()?.replace(' ', 'T')) }.getOrNull()
}
