package conectate.com.data.model

import java.text.Normalizer
import java.util.Locale

/** Public label only. The full user ID remains the canonical identity. */
fun profilePublicHandle(user: User): String {
    val emailLocalPart = user.email.substringBefore('@').trim()
    val chosenUsername = user.username.orEmpty().trim().removePrefix("@")
    val displayName = (user.display_name ?: user.full_name).orEmpty().trim()
    val base = when {
        chosenUsername.isNotBlank() && !chosenUsername.equals(emailLocalPart, ignoreCase = true) -> chosenUsername
        displayName.isNotBlank() && !displayName.equals(emailLocalPart, ignoreCase = true) -> displayName
        else -> "usuario"
    }
    val cleanBase = Normalizer.normalize(base, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_.-]+"), "_")
        .trim('_', '.', '-')
        .take(24)
        .ifBlank { "usuario" }
    val shortId = user.id.lowercase(Locale.ROOT)
        .filter { it in '0'..'9' || it in 'a'..'f' }
        .takeLast(12)
    return if (shortId.isBlank()) cleanBase else "${cleanBase}_$shortId"
}

/** The suffix is searchable, but the full ID is what users should copy and share. */
fun profileSearchQuery(rawQuery: String): String {
    val query = rawQuery.trim().removePrefix("@")
    val suffix = Regex("^[a-z0-9_.-]+_([0-9a-f]{12})$", RegexOption.IGNORE_CASE)
        .matchEntire(query)
        ?.groupValues
        ?.get(1)
    return suffix ?: query
}
