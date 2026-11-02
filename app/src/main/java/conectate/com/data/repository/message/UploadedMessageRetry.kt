package conectate.com.data.repository.message

import conectate.com.data.model.Message
import java.net.URI

/** An upload confirmed before a lost send response must not depend on the original URI again. */
internal fun uploadedMessageForRetry(existing: Message?, contentType: String): Message? {
    if (existing == null || existing.deleted_for_all ||
        !existing.content_type.trim().equals(contentType.trim(), ignoreCase = true)) return null
    val value = existing.media_url?.takeIf { it.isNotBlank() } ?: return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    return existing
}
