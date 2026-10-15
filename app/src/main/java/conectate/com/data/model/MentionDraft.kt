package conectate.com.data.model

data class ActiveMention(val start: Int, val end: Int, val query: String)

fun activeMentionAt(text: String, cursor: Int): ActiveMention? {
    if (cursor !in 0..text.length) return null
    val start = text.lastIndexOf('@', (cursor - 1).coerceAtLeast(0))
    if (start < 0 || start >= cursor) return null
    if (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] in "_.-")) return null
    val query = text.substring(start + 1, cursor)
    if (query.length !in 0..24 || query.any { !it.isLetterOrDigit() && it !in "_.-" && Character.getType(it) != Character.NON_SPACING_MARK.toInt() }) return null
    var end = cursor
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] in "_.-" || Character.getType(text[end]) == Character.NON_SPACING_MARK.toInt())) end++
    return ActiveMention(start, end, query)
}

fun insertMention(text: String, mention: ActiveMention, handle: String): Pair<String, Int> {
    require(mention.start in text.indices && mention.end in mention.start + 1..text.length)
    require(publicMentionPattern.matchEntire("@$handle") != null)
    require(text[mention.start] == '@')
    val insertion = "@$handle "
    return text.replaceRange(mention.start, mention.end, insertion) to (mention.start + insertion.length)
}

/** Resolve suggestions inside the current room; ambiguous handles cannot select a person reliably. */
fun roomMentionCandidates(users: List<User>, query: String, excludeUserId: String?): List<User> {
    fun normalized(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
    val eligible = users.distinctBy { it.id.lowercase(java.util.Locale.ROOT) }
        .filter { it.id.isNotBlank() && !it.id.equals(excludeUserId, true) }
    val counts = eligible.groupingBy { profilePublicHandle(it) }.eachCount()
    val needle = normalized(query)
    return eligible.filter { user ->
        counts[profilePublicHandle(user)] == 1 &&
            (needle.isBlank() || normalized(profilePublicHandle(user)).contains(needle) || normalized(user.displayName).contains(needle))
    }.sortedBy { normalized(it.displayName) }.take(6)
}

/** A display name alone is not a resolved public mention identity. */
fun resolvedRoomMentionMembers(memberIds: Collection<String>, profiles: Collection<User>): List<User> {
    val ids = memberIds.map { it.trim().lowercase(java.util.Locale.ROOT) }.filter(String::isNotBlank).toSet()
    return profiles.filter { it.id.trim().lowercase(java.util.Locale.ROOT) in ids }
        .distinctBy { it.id.trim().lowercase(java.util.Locale.ROOT) }
}
