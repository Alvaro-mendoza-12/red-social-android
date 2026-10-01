package conectate.com.data.repository.collection

internal object CollectionMessageSyncSupport {
    @Suppress("UNUSED_PARAMETER")
    fun resolveLocallyReadableIds(
        requestedIds: Collection<String>,
        remotelyReadIds: Set<String>
    ): List<String> {
        val cleanRequestedIds = requestedIds
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (cleanRequestedIds.isEmpty()) return emptyList()
        if (remotelyReadIds.isEmpty()) {
            return cleanRequestedIds
        }

        val confirmedIds = remotelyReadIds
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        return cleanRequestedIds.filter { confirmedIds.contains(it) }
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolveDisplayedUnreadCount(
        isConversationOpen: Boolean,
        exactUnread: Int
    ): Int {
        // Being inside the room does not mean older or off-screen messages were seen.
        return exactUnread.coerceAtLeast(0)
    }

    fun <T> mergeActiveMessages(
        current: List<T>,
        incoming: List<T>,
        idSelector: (T) -> String,
        createdAtSelector: (T) -> String
    ): List<T> {
        return (current + incoming)
            .associateBy(idSelector).values
            .sortedBy(createdAtSelector)
    }

    fun buildMetadataPreview(contentType: String?, content: String?): String {
        return when (contentType?.trim()?.lowercase()) {
            "text" -> content.orEmpty()
            "image" -> "📷 Foto"
            "video" -> "🎥 Video"
            "audio" -> "🎤 Audio"
            "sticker" -> "🧩 Sticker"
            "gif" -> "🌀 GIF"
            "poll" -> "📊 Encuesta"
            "document", "file" -> "📎 Archivo"
            "contact" -> "👤 Contacto"
            "location" -> "📍 Ubicación"
            "view_once_image" -> "👁️ Foto ver una vez"
            "view_once_video" -> "👁️ Video ver una vez"
            else -> content.orEmpty().ifBlank { "Archivo multimedia" }
        }
    }

    suspend fun reconcileUnreadCount(
        isConversationOpen: Boolean,
        countUnread: suspend () -> Int,
        resetUnread: suspend () -> Unit,
        incrementUnread: suspend (Int) -> Unit,
        onUnreadReconciled: ((Int) -> Unit)? = null
    ): Int {
        val exactUnread = runCatching { countUnread() }.getOrDefault(0)
        val displayedUnread = resolveDisplayedUnreadCount(isConversationOpen, exactUnread)
        resetUnread()
        if (displayedUnread > 0) {
            incrementUnread(displayedUnread)
            onUnreadReconciled?.invoke(displayedUnread)
        }
        return exactUnread
    }
}


