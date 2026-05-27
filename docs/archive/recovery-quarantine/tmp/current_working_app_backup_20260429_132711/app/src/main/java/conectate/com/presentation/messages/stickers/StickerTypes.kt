package conectate.com.presentation.messages.stickers

enum class StickerPanelTab(val label: String) {
    RECENT("Recientes"),
    FAVORITES("Favoritos"),
    PACKS("Packs"),
    CATALOG("Explorar")
}

data class StickerEditorTransform(
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val overlayText: String = "",
    val overlayEmoji: String = ""
)
