package conectate.com.data.repository

import java.time.Instant

internal fun shouldReplaceConversationPreview(
    currentTimestamp: String?,
    incomingTimestamp: String?
): Boolean {
    val incoming = parseConversationPreviewInstant(incomingTimestamp) ?: return false
    val current = parseConversationPreviewInstant(currentTimestamp) ?: return true
    return !incoming.isBefore(current)
}

private fun parseConversationPreviewInstant(value: String?): Instant? {
    val normalized = value
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.replace(" ", "T")
        ?: return null

    return runCatching { Instant.parse(normalized) }.getOrNull()
}
