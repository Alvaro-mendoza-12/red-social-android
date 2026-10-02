package conectate.com.presentation.components

internal fun quotedMessageAuthor(
    quotedSenderId: String,
    currentUserId: String?,
    quotedSenderName: String?,
    messageSenderId: String,
    messageSenderName: String?,
): String {
    val quotedId = quotedSenderId.trim()
    if (quotedId.isNotEmpty() && !currentUserId.isNullOrBlank() &&
        quotedId.equals(currentUserId.trim(), ignoreCase = true)) return "Tú"
    return quotedSenderName?.trim()?.takeIf(String::isNotEmpty)
        ?: messageSenderName?.trim()?.takeIf {
            it.isNotEmpty() && quotedId.isNotEmpty() && quotedId.equals(messageSenderId.trim(), ignoreCase = true)
        }
        ?: "Mensaje citado"
}
