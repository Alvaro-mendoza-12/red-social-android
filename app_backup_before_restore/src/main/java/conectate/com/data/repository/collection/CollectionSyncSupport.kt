package conectate.com.data.repository.collection

internal data class CollectionMessageSnapshot(
    val lastMessage: String?,
    val lastMessageAt: String?
)

internal object CollectionSyncSupport {
    fun resolveLastMessageSnapshot(
        serverMessage: String?,
        serverTime: String?,
        localMessage: String?,
        localTime: String?
    ): CollectionMessageSnapshot {
        val useLocal = shouldPreferLocalSnapshot(localTime = localTime, serverTime = serverTime)
        val selectedMessage = if (useLocal) localMessage else serverMessage
        val selectedTime = if (useLocal) localTime else serverTime

        val effectiveMessage = if (selectedMessage.isNullOrBlank() && !localMessage.isNullOrBlank()) {
            localMessage
        } else {
            selectedMessage
        }
        val effectiveTime = if (selectedTime.isNullOrBlank() && !localTime.isNullOrBlank()) {
            localTime
        } else {
            selectedTime
        }

        return CollectionMessageSnapshot(
            lastMessage = effectiveMessage,
            lastMessageAt = effectiveTime
        )
    }

    fun hasServerNewerContent(serverTime: String?, localTime: String?): Boolean {
        if (serverTime.isNullOrBlank()) return false
        if (localTime.isNullOrBlank()) return true
        return compareInstants(serverTime, localTime) > 0
    }

    private fun shouldPreferLocalSnapshot(localTime: String?, serverTime: String?): Boolean {
        if (localTime.isNullOrBlank()) return false
        if (serverTime.isNullOrBlank()) return true
        return compareInstants(localTime, serverTime) > 0
    }

    private fun compareInstants(left: String, right: String): Int {
        return runCatching {
            java.time.Instant.parse(left).compareTo(java.time.Instant.parse(right))
        }.getOrElse {
            left.compareTo(right)
        }
    }
}


