package conectate.com.core.messaging

internal data class MessageReceiptState(val status: String, val deliveredAt: String?, val readAt: String?)

/** A delivery/read receipt cannot be undone by a late send, history or retry response. */
internal fun mergeMessageReceiptState(
    incomingStatus: String, incomingDeliveredAt: String?, incomingReadAt: String?,
    currentStatus: String?, currentDeliveredAt: String?, currentReadAt: String?,
): MessageReceiptState {
    val read = incomingReadAt?.takeIf(String::isNotBlank) ?: currentReadAt?.takeIf(String::isNotBlank)
    var delivered = incomingDeliveredAt?.takeIf(String::isNotBlank) ?: currentDeliveredAt?.takeIf(String::isNotBlank)
    val incoming = MessageStatusNormalizer.canonical(incomingStatus, delivered, read)
    val current = MessageStatusNormalizer.canonical(currentStatus, currentDeliveredAt, currentReadAt)
    // 'sent' can also be a legacy optimistic state. Only actual delivery/read
    // receipts outrank an explicit new failure; persisted ACK ownership is checked separately.
    fun rank(status: String) = when (status) { "read" -> 2; "delivered" -> 1; else -> 0 }
    val status = if (rank(current) > rank(incoming)) current else incoming
    if (status == "read" && delivered == null) delivered = read
    return MessageReceiptState(status, delivered, read)
}
