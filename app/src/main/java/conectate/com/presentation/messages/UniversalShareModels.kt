package conectate.com.presentation.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import conectate.com.data.model.CommunityMessage
import conectate.com.data.model.GroupMessage
import conectate.com.data.model.Message
import java.util.UUID

enum class ShareDestinationType {
    CHAT,
    GROUP,
    COMMUNITY
}

data class ShareDestination(
    val id: String,
    val type: ShareDestinationType,
    val title: String,
    val imageUrl: String? = null,
    val subtitle: String? = null
) {
    val key: String
        get() = "${type.name.lowercase()}:$id"
}

data class ForwardMessagePayload(
    val id: String,
    val senderId: String,
    val content: String,
    val contentType: String,
    val mediaUrl: String?,
    val timestampMs: Long,
    val deletedForAll: Boolean
) {
    val preview: String
        get() = content.trim().ifBlank {
            when (contentType.trim().lowercase()) {
                "image", "view_once_image" -> "Imagen"
                "video", "view_once_video" -> "Video"
                "audio" -> "Audio"
                "gif" -> "GIF"
                "sticker" -> "Sticker"
                "document", "file" -> "Archivo"
                else -> "Mensaje"
            }
        }
}

data class UniversalForwardRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val candidates: List<ForwardMessagePayload>,
    val initiallySelectedIds: Set<String>
)

class UniversalForwardCoordinator {
    var request by mutableStateOf<UniversalForwardRequest?>(null)
        private set

    fun open(
        candidates: List<ForwardMessagePayload>,
        initiallySelectedIds: Set<String>
    ) {
        val eligible = candidates
            .asSequence()
            .filterNot(ForwardMessagePayload::deletedForAll)
            .distinctBy(ForwardMessagePayload::id)
            .sortedBy(ForwardMessagePayload::timestampMs)
            .take(MAX_FORWARD_CANDIDATES)
            .toList()
        if (eligible.isEmpty()) return
        val eligibleIds = eligible.mapTo(mutableSetOf(), ForwardMessagePayload::id)
        val initial = initiallySelectedIds.intersect(eligibleIds).ifEmpty { setOf(eligible.last().id) }
        request = UniversalForwardRequest(
            candidates = eligible,
            initiallySelectedIds = initial
        )
    }

    fun dismiss() {
        request = null
    }
}

val LocalUniversalForwardCoordinator = staticCompositionLocalOf<UniversalForwardCoordinator?> { null }

fun Message.toForwardMessagePayload(): ForwardMessagePayload = ForwardMessagePayload(
    id = id,
    senderId = sender_id,
    content = content,
    contentType = content_type,
    mediaUrl = media_url,
    timestampMs = timestampMillis,
    deletedForAll = deleted_for_all
)

fun GroupMessage.toForwardMessagePayload(): ForwardMessagePayload = ForwardMessagePayload(
    id = id,
    senderId = sender_id,
    content = content,
    contentType = content_type,
    mediaUrl = media_url,
    timestampMs = timestampMillis,
    deletedForAll = deleted_for_all
)

fun CommunityMessage.toForwardMessagePayload(): ForwardMessagePayload = ForwardMessagePayload(
    id = id,
    senderId = sender_id,
    content = content,
    contentType = content_type,
    mediaUrl = media_url,
    timestampMs = timestampMillis,
    deletedForAll = deleted_for_all
)

internal fun validateForwardSelection(
    selectedMessageCount: Int,
    selectedDestinationCount: Int
): String? = when {
    selectedMessageCount <= 0 -> "Selecciona al menos un mensaje"
    selectedMessageCount > MAX_FORWARD_MESSAGES -> "Puedes reenviar hasta $MAX_FORWARD_MESSAGES mensajes por vez"
    selectedDestinationCount <= 0 -> "Selecciona al menos un destino"
    selectedDestinationCount > MAX_FORWARD_DESTINATIONS ->
        "Puedes reenviar a un máximo de $MAX_FORWARD_DESTINATIONS destinos por vez"
    selectedMessageCount * selectedDestinationCount > MAX_FORWARD_OPERATIONS ->
        "Reduce mensajes o destinos; el máximo es $MAX_FORWARD_OPERATIONS envíos por lote"
    else -> null
}

internal const val MAX_FORWARD_MESSAGES = 30
internal const val MAX_FORWARD_DESTINATIONS = 20
internal const val MAX_FORWARD_OPERATIONS = 100
private const val MAX_FORWARD_CANDIDATES = 100
