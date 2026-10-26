package conectate.com.presentation.messages.stickers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
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
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StickerPanelTab.entries.forEach { tab ->
            if (compact) {
                IconToggleButton(
                    checked = selectedTab == tab,
                    onCheckedChange = { onTabSelected(tab) },
                    modifier = Modifier.size(48.dp).background(
                        if (selectedTab == tab) MaterialTheme.colorScheme.secondaryContainer
                        else androidx.compose.ui.graphics.Color.Transparent,
                        CircleShape,
                    ),
                ) {
                    Icon(StickerService.tabIcon(tab), contentDescription = tab.label,
                        tint = if (selectedTab == tab) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
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
}
