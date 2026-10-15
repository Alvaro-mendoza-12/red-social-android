package conectate.com.presentation.components

import java.net.URI
import java.util.Locale

/** Infer only the owner directory of an application avatar, never an arbitrary URL UUID. */
internal fun avatarMomentOwnerId(explicitOwnerId: String?, imageUrl: String?): String? {
    val pattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    explicitOwnerId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.matches(pattern) }?.let { return it }
    val uri = runCatching { URI(imageUrl.orEmpty()) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https")) return null
    val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
    val prefix = listOf("storage", "v1", "object", "public", "avatars")
    if (segments.take(prefix.size) != prefix || segments.size < 7) return null
    return segments[5].lowercase(Locale.ROOT).takeIf { it.matches(pattern) }
}
