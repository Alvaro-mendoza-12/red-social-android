package conectate.com.presentation.messages.stickers

import conectate.com.data.model.StickerItem

data class StickerMessagePayload(
    val type: String = "sticker",
    val mediaUrl: String?,
    val title: String
)

fun StickerItem.toStickerMessagePayload(): StickerMessagePayload {
    return StickerMessagePayload(
        mediaUrl = media_url,
        title = title.ifBlank { "🧩 Sticker" }
    )
}
