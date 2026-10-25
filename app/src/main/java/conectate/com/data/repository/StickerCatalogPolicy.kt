package conectate.com.data.repository

import conectate.com.data.model.StickerItem

/** Old starter rows were labels for emojis, not sticker artwork to send as media. */
internal object StickerCatalogPolicy {
    private val legacyEmojiIds = setOf("emoji_happy", "emoji_love", "emoji_fire", "emoji_party", "emoji_robot", "emoji_star")

    fun isLegacyEmojiPlaceholder(item: StickerItem): Boolean =
        item.id in legacyEmojiIds && item.source == "local" && item.media_url.isNullOrBlank()
}
