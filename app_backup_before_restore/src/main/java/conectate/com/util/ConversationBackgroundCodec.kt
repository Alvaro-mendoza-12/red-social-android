package conectate.com.util

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val BACKGROUND_CHANGE_CONTENT_TYPE = "background_change"

@Serializable
data class SharedConversationBackgroundPayload(
    val backgroundType: String = "default",
    val backgroundName: String = "Predeterminado",
    val backgroundUri: String? = null,
    val changedByUserId: String = "",
    val changedByName: String = "Usuario",
    val changedAt: String = Instant.now().toString()
)

private val backgroundJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodeSharedConversationBackground(payload: SharedConversationBackgroundPayload): String =
    backgroundJson.encodeToString(SharedConversationBackgroundPayload.serializer(), payload)

fun decodeSharedConversationBackground(raw: String?): SharedConversationBackgroundPayload? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        backgroundJson.decodeFromString(SharedConversationBackgroundPayload.serializer(), raw)
    }.getOrNull()
}

fun buildSharedConversationBackgroundNotice(payload: SharedConversationBackgroundPayload?): String {
    val safePayload = payload ?: return "Se actualizó el fondo compartido"
    val actor = safePayload.changedByName.trim().ifBlank { "Usuario" }
    val backgroundName = safePayload.backgroundName.trim().ifBlank { "Predeterminado" }
    return "$actor cambió el fondo a $backgroundName"
}

fun buildConversationBackgroundPreview(contentType: String, rawContent: String): String? {
    if (!contentType.trim().equals(BACKGROUND_CHANGE_CONTENT_TYPE, ignoreCase = true)) return null
    return buildSharedConversationBackgroundNotice(decodeSharedConversationBackground(rawContent))
}



