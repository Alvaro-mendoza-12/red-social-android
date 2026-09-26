package conectate.com.presentation.videos

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun VideoCommentFilterControls(
    order: VideoCommentOrder,
    authorFilter: VideoCommentAuthorFilter,
    onOrderChange: (VideoCommentOrder) -> Unit,
    onAuthorFilterChange: (VideoCommentAuthorFilter) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            VideoCommentOrder.entries.forEach { option ->
                FilterChip(
                    selected = order == option,
                    onClick = { onOrderChange(option) },
                    label = { Text(option.label) },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            VideoCommentAuthorFilter.entries.forEach { option ->
                FilterChip(
                    selected = authorFilter == option,
                    onClick = { onAuthorFilterChange(option) },
                    label = { Text(option.label) },
                )
            }
        }
    }
}
