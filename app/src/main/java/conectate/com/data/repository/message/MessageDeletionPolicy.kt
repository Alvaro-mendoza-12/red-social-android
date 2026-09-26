package conectate.com.data.repository.message

import conectate.com.data.local.entity.MessageDeletionEntity
import conectate.com.data.model.Message

/** Applied after Room/memory reconciliation so late history cannot resurrect a local deletion. */
internal fun applyMessageDeletions(
    messages: List<Message>,
    deletions: List<MessageDeletionEntity>,
    accountId: String
): List<Message> {
    val account = accountId.trim().lowercase()
    val byMessage = deletions.filter { it.state != "failed" }.groupBy { it.message_id }
    fun hiddenForMe(message: Message, intents: List<MessageDeletionEntity>) =
        message.deleted_by.any { it.trim().lowercase() == account } || intents.any { !it.for_everyone && it.actor_id == account }
    fun intentsFor(message: Message) = byMessage[message.id].orEmpty().filter { it.chat_id == message.chat_id }
    fun deletedForAll(message: Message, intents: List<MessageDeletionEntity>) = message.deleted_for_all ||
        intents.any { it.for_everyone && it.actor_id == message.sender_id.trim().lowercase() }
    fun tombstone(message: Message) = message.copy(
        deleted_for_all = true, content = "", media_url = null, thumbnail_url = null, reply_to = null
    )
    return messages.mapNotNull { message ->
        val intents = byMessage[message.id].orEmpty().filter { it.chat_id == message.chat_id }
        if (hiddenForMe(message, intents)) return@mapNotNull null
        if (deletedForAll(message, intents)) return@mapNotNull tombstone(message)
        val quote = message.reply_to
        if (quote != null && (hiddenForMe(quote, intentsFor(quote)) || deletedForAll(quote, intentsFor(quote)))) {
            message.copy(reply_to = tombstone(quote))
        } else message
    }
}
