package conectate.com.data.model

data class SharedVideoReference(val videoId: String, val caption: String)

/** Only Novvin video links become cards. Never fetch an arbitrary preview URL. */
fun sharedVideoReference(content: String): SharedVideoReference? {
    val urls = Regex("(?:https?://|conectate://)\\S+", RegexOption.IGNORE_CASE).findAll(content).toList()
    if (urls.size != 1) return null
    val match = urls.single()
    val id = videoIdFromSharedLink(match.value) ?: return null
    val before = content.substring(0, match.range.first).trim()
        .removePrefix("¡Mira este video!").removePrefix("Mira este video en Novvin:").trim()
    val after = content.substring(match.range.last + 1).trim()
    return SharedVideoReference(id, listOf(before, after).filter(String::isNotBlank).joinToString("\n").take(800))
}
