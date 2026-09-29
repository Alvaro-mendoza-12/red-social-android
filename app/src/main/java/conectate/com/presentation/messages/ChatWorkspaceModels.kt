package conectate.com.presentation.messages

import conectate.com.data.model.Chat
import conectate.com.data.model.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest

@Serializable
enum class ChatWorkspaceKind { TASK, CONTEXT, DRAFT }

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ChatWorkspaceEntry(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val id: String = UUID.randomUUID().toString(),
    val kind: ChatWorkspaceKind,
    val text: String,
    val sourceMessageId: String? = null,
    val completed: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val createdAt: Long = System.currentTimeMillis(),
    val dueAt: Long? = null,
)

@Serializable
data class ChatWorkspace(val entries: List<ChatWorkspaceEntry> = emptyList()) {
    fun add(entry: ChatWorkspaceEntry): ChatWorkspace {
        val clean = entry.copy(text = entry.text.trim())
        require(clean.text.isNotBlank()) { "Escribe algo para guardar." }
        require(clean.text.length <= MAX_TEXT) { "El máximo es de $MAX_TEXT caracteres." }
        // Repeated taps and recovery of the same failed send must not fill the library.
        if (entries.any { it.kind == clean.kind && it.text == clean.text && it.sourceMessageId == clean.sourceMessageId }) return this
        require(entries.count { it.kind == clean.kind } < MAX_PER_KIND) {
            "Has llegado a $MAX_PER_KIND elementos. Elimina alguno para guardar otro."
        }
        return copy(entries = listOf(clean) + entries)
    }

    fun toggleTask(id: String) = copy(entries = entries.map {
        if (it.id == id && it.kind == ChatWorkspaceKind.TASK) it.copy(completed = !it.completed) else it
    })

    fun remove(id: String) = copy(entries = entries.filterNot { it.id == id })

    fun scheduleTask(id: String, dueAt: Long?): ChatWorkspace {
        require(dueAt == null || dueAt > 0) { "Elige una fecha válida." }
        return copy(entries = entries.map {
            if (it.id == id && it.kind == ChatWorkspaceKind.TASK) it.copy(dueAt = dueAt) else it
        })
    }

    companion object {
        const val MAX_TEXT = 4000
        const val MAX_PER_KIND = 50
    }
}

internal fun conversationWorkspaceScope(type: String, id: String, channelId: String? = null): String? {
    if (type !in setOf("group", "community")) return null
    val conversation = id.trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() } ?: return null
    val channel = channelId?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return "$type:${conversation.length}:$conversation:${channel.length}:$channel"
}

internal fun orderedWorkspaceEntries(entries: List<ChatWorkspaceEntry>): List<ChatWorkspaceEntry> =
    entries.sortedWith(compareBy<ChatWorkspaceEntry> { it.completed }
        .thenBy { it.dueAt ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })

/** A stable direct-chat identity also works before the first message creates its server chat. */
internal fun chatWorkspaceScope(accountId: String, chat: Chat): String? {
    val account = accountId.trim().lowercase(Locale.ROOT)
    val participants = chat.participants.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct().sorted()
    if (account.isBlank() || account !in participants) return null
    return if (!chat.is_group && participants.size == 2) {
        "dm:" + participants.joinToString(":") { "${it.length}:$it" }
    } else chat.id.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }?.let { "chat:$it" }
}

internal fun chatWorkspaceStorageKey(accountId: String, scope: String): String {
    val account = accountId.trim().lowercase(Locale.ROOT)
    require(account.isNotBlank() && scope.isNotBlank())
    return MessageDigest.getInstance("SHA-256")
        .digest("${account.length}:$account:$scope".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun canSaveMessageInWorkspace(message: Message, accountId: String): Boolean =
    message.content_type == "text" && !message.deleted_for_all && message.content.isNotBlank() &&
        message.deleted_by.none { it.equals(accountId, ignoreCase = true) }

internal fun shouldScrollChatToLatest(
    previousLastMessageId: String?,
    latestMessage: Message,
    wasNearBottom: Boolean,
    accountId: String
): Boolean = shouldScrollChatToLatest(previousLastMessageId, latestMessage.id, latestMessage.sender_id, wasNearBottom, accountId)

internal fun shouldScrollChatToLatest(
    previousLastMessageId: String?,
    latestMessageId: String,
    latestSenderId: String,
    wasNearBottom: Boolean,
    accountId: String
): Boolean = previousLastMessageId == null ||
    (previousLastMessageId != latestMessageId &&
        (wasNearBottom || latestSenderId.equals(accountId, ignoreCase = true)))
