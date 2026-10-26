package conectate.com.presentation.messages

import conectate.com.data.model.Message
import java.util.Locale

/** Reject the whole selection; never turn a mixed selection into a partial deletion. */
internal object MessageDeletionPolicy {
    data class Candidate(val id: String, val senderId: String, val deletedForAll: Boolean)

    fun canDeleteForEveryone(message: Message?, actorId: String?): Boolean =
        message != null && canDeleteForEveryone(
            actorId, setOf(message.id),
            listOf(Candidate(message.id, message.sender_id, message.deleted_for_all)),
        )

    fun canDeleteForEveryone(messages: Collection<Message>, selectedIds: Set<String>, actorId: String?): Boolean =
        canDeleteForEveryone(actorId, selectedIds, messages.map { Candidate(it.id, it.sender_id, it.deleted_for_all) })

    fun canDeleteForEveryone(actorId: String?, selectedIds: Set<String>, candidates: Collection<Candidate>): Boolean {
        val actor = normalize(actorId.orEmpty())
        if (actor.isEmpty() || selectedIds.isEmpty() || selectedIds.any(String::isBlank)) return false
        val ids = selectedIds.mapTo(mutableSetOf(), ::normalize)
        val selected = candidates.filter { normalize(it.id) in ids }
        // A stale/missing selection cannot be authorized from incomplete local state.
        return selected.mapTo(mutableSetOf()) { normalize(it.id) } == ids &&
            selected.all { !it.deletedForAll && normalize(it.senderId) == actor }
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
}
