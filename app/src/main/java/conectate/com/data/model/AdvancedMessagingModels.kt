package conectate.com.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class StickerItem(
    val id: String,
    val title: String,
    val media_url: String? = null,
    val thumbnail_url: String? = null,
    val is_animated: Boolean = false,
    val tags: List<String> = emptyList(),
    val source: String = "local",
    val pack_id: String? = null,
    val pack_name: String? = null,
    val is_favorite: Boolean = false
)

@Serializable
data class StickerPack(
    val id: String = UUID.randomUUID().toString(),
    val owner_id: String? = null,
    val name: String,
    val description: String? = null,
    val cover_sticker_id: String? = null,
    val is_public: Boolean = false,
    val created_at: String? = null,
    val updated_at: String? = null
)

@Serializable
data class ScheduledMessageJob(
    val id: String,
    val scope_type: String,
    val target_id: String,
    val content: String,
    val content_type: String,
    val media_url: String? = null,
    val translate_to: String? = null,
    val self_destruct_seconds: Int? = null,
    val scheduled_for: String,
    val status: String,
    val error_message: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

@Serializable
data class ChatTranslationSettings(
    val chat_id: String,
    val enabled: Boolean = false,
    val target_lang: String? = null,
    val updated_at: String? = null
)

@Serializable
data class ChatFolder(
    val id: String,
    val name: String,
    val color_hex: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val chat_ids: List<String> = emptyList()
)

@Serializable
data class ChatSummary(
    val chat_id: String,
    val summary: String,
    val source_message_count: Int = 0,
    val generated_at: String? = null
)

@Serializable
data class StoredAccountSession(
    val id: String = UUID.randomUUID().toString(),
    val user_id: String? = null,
    val email: String,
    val access_token: String,
    val refresh_token: String,
    val display_name: String? = null,
    val avatar_url: String? = null,
    val last_used_at: Long = System.currentTimeMillis()
)
