package conectate.com.data.repository.message

import conectate.com.data.model.Chat
import conectate.com.data.local.entity.ChatEntity

internal fun normalizedParticipantIds(values: Iterable<String>): List<String> =
    values.asSequence()
        .map { it.trim().lowercase() }
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
        .toList()

internal fun Chat.matchesExactDirectParticipants(expectedParticipants: Iterable<String>): Boolean {
    if (is_group) return false
    val expected = normalizedParticipantIds(expectedParticipants)
    return expected.size == 2 && normalizedParticipantIds(participants) == expected
}

/** A shared Room database may hold a conversation with the same person for several accounts. */
internal fun selectCachedDirectChat(
    candidates: List<ChatEntity>,
    accountId: String,
    targetUserId: String
): ChatEntity? {
    val expected = normalizedParticipantIds(listOf(accountId, targetUserId))
    if (expected.size != 2) return null
    return candidates.asSequence()
        .filter { !it.is_deleted && it.toDomain().matchesExactDirectParticipants(expected) }
        .maxByOrNull { it.toDomain().safeLastMessageAt }
}
