package conectate.com.data.model

import java.text.Normalizer
import java.util.Locale

/** Public label comes from the chosen display name, never from an email-derived username. */
fun profilePublicHandle(user: User): String {
    val displayName = (user.display_name ?: user.full_name).orEmpty().trim()
    return Normalizer.normalize(displayName.ifBlank { "usuario" }, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_.-]+"), "_")
        .trim('_', '.', '-')
        .take(24)
        .ifBlank { "usuario" }
}

/** Five-digit visual reference. It is not a unique account key; the UUID remains canonical. */
fun profileShortId(user: User): String {
    val existing = user.numeric_id?.takeIf { it in 1_000L..99_999L }
    if (existing != null) return existing.toString()
    val stable = user.id.trim().lowercase(Locale.ROOT)
    return ((stable.hashCode().toLong() and 0xffffffffL) % 90_000L + 10_000L).toString()
}

/** Accept old shared handles with a UUID suffix as well as full account IDs. */
fun profileSearchQuery(rawQuery: String): String {
    val query = rawQuery.trim().removePrefix("@")
    val suffix = Regex("^[a-z0-9_.-]+_([0-9a-f]{12})$", RegexOption.IGNORE_CASE)
        .matchEntire(query)
        ?.groupValues
        ?.get(1)
    return suffix ?: query
}
