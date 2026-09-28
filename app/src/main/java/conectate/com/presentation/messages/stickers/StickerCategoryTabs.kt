package conectate.com.presentation.messages.stickers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerCategoryTabs(
    selectedTab: StickerPanelTab,
    onTabSelected: (StickerPanelTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StickerPanelTab.entries.forEach { tab ->
            FilterChip(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                label = { Text(tab.label, maxLines = 1, softWrap = false) },
                leadingIcon = {
                    Icon(
                        imageVector = StickerService.tabIcon(tab),
                        contentDescription = null,
                        tint = if (selectedTab == tab) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            )
        }
    }
}
