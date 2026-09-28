package conectate.com.data.model

import java.text.Normalizer
import java.util.Locale

val publicMentionPattern = Regex("(?<![\\p{L}\\p{N}_.-])@([\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\p{M}_.-]{0,22}[\\p{L}\\p{N}\\p{M}])?)")

/** New accounts use their unique public username; older profiles retain a safe fallback. */
fun profilePublicHandle(user: User): String {
    user.username?.trim()?.removePrefix("@")?.takeIf {
        it.matches(Regex("[A-Za-z0-9_.-]{3,24}")) &&
            (user.username_origin == "chosen" || !it.equals(user.email.substringBefore('@'), ignoreCase = true))
    }?.let { return it.lowercase(Locale.ROOT) }
    val displayName = sequenceOf(user.display_name, user.full_name)
        .map { it.orEmpty().trim() }
        .firstOrNull { it.isNotBlank() && '@' !in it }
        ?: "usuario"
    return normalizedPublicMentionHandle(displayName).ifBlank { "usuario" }
}

fun normalizedPublicMentionHandle(value: String): String =
    Normalizer.normalize(value.trim().removePrefix("@"), Normalizer.Form.NFD)
        .replace(Regex("(\\p{IsLatin})\\p{M}+"), "$1")
        .let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}\\p{M}_.-]+"), "_")
        .trim('_', '.', '-')
        .take(24)
        .trim('_', '.', '-')

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
