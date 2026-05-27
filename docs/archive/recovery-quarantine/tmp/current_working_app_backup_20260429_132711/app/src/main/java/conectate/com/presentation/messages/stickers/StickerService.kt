package conectate.com.presentation.messages.stickers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import conectate.com.data.model.StickerItem

object StickerService {
    fun resolveItemsForTab(
        tab: StickerPanelTab,
        recents: List<StickerItem>,
        favorites: List<StickerItem>,
        packsItems: List<StickerItem>,
        catalogItems: List<StickerItem>
    ): List<StickerItem> {
        return when (tab) {
            StickerPanelTab.RECENT -> recents
            StickerPanelTab.FAVORITES -> favorites
            StickerPanelTab.PACKS -> packsItems
            StickerPanelTab.CATALOG -> catalogItems
        }
    }

    fun tabIcon(tab: StickerPanelTab): ImageVector {
        return when (tab) {
            StickerPanelTab.RECENT -> Icons.Default.History
            StickerPanelTab.FAVORITES -> Icons.Default.Star
            StickerPanelTab.PACKS -> Icons.Default.Collections
            StickerPanelTab.CATALOG -> Icons.Default.Explore
        }
    }
}
