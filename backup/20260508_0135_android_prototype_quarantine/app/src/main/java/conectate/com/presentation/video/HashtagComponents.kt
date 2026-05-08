package conectate.com.presentation.video

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import conectate.com.data.model.HashtagTrending

@Composable
fun TrendingHashtagsRow(
    hashtags: List<HashtagTrending>,
    onHashtagClick: (String) -> Unit,
    onSeeAllClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.TrendingUp,
                    contentDescription = null,
                    tint = Color(0xFFFF5722),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tendencias",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            TextButton(onClick = onSeeAllClick) {
                Text("Ver todo")
            }
        }
        
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(hashtags.take(10)) { hashtag ->
                TrendingHashtagChip(
                    hashtag = hashtag,
                    onClick = { onHashtagClick(hashtag.name) }
                )
            }
        }
    }
}

@Composable
private fun TrendingHashtagChip(
    hashtag: HashtagTrending,
    onClick: () -> Unit
) {
    val rankColor = when (hashtag.rank) {
        1 -> Color(0xFFFFD700) // Gold
        2 -> Color(0xFFC0C0C0) // Silver
        3 -> Color(0xFFCD7F32) // Bronze
        else -> MaterialTheme.colorScheme.primary
    }
    
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank indicator
            if (hashtag.rank <= 3) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(rankColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "#${hashtag.rank}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = rankColor
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }
            
            Column {
                Text(
                    text = "#${hashtag.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Text(
                    text = formatCount(hashtag.videoCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun HashtagSelectorDialog(
    suggestedHashtags: List<String>,
    trendingHashtags: List<HashtagTrending>,
    selectedHashtags: List<String>,
    onHashtagSelected: (String) -> Unit,
    onHashtagRemoved: (String) -> Unit,
    onCustomHashtagAdded: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var customInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredTrending = remember(searchQuery, trendingHashtags) {
        if (searchQuery.isBlank()) trendingHashtags
        else trendingHashtags.filter { 
            it.name.contains(searchQuery, ignoreCase = true) 
        }
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Text(
                    text = "Agregar hashtags",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "${selectedHashtags.size}/10 hashtags seleccionados",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar hashtags...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true
                )
                
                // Selected hashtags chips
                if (selectedHashtags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "Seleccionados:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedHashtags.forEach { tag ->
                            InputChip(
                                selected = false,
                                onClick = { onHashtagRemoved(tag) },
                                label = { Text("#$tag") },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Eliminar",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Suggested hashtags
                if (suggestedHashtags.isNotEmpty() && searchQuery.isBlank()) {
                    Text(
                        text = "Sugeridos para ti",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestedHashtags.forEach { tag ->
                            if (!selectedHashtags.contains(tag)) {
                                AssistChip(
                                    onClick = { onHashtagSelected(tag) },
                                    label = { Text("#$tag") }
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Trending hashtags
                Text(
                    text = if (searchQuery.isBlank()) "Tendencias" else "Resultados",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Custom input for new hashtags
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { 
                            customInput = it.replace("#", "").replace(" ", "") 
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Crear hashtag personalizado") },
                        singleLine = true,
                        leadingIcon = { Text("#", color = MaterialTheme.colorScheme.primary) }
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(
                        onClick = {
                            if (customInput.isNotBlank() && selectedHashtags.size < 10) {
                                onCustomHashtagAdded(customInput.lowercase())
                                customInput = ""
                            }
                        },
                        enabled = customInput.isNotBlank() && selectedHashtags.size < 10
                    ) {
                        Icon(Icons.Default.Add, null)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Trending list
                filteredTrending.forEach { hashtag ->
                    if (!selectedHashtags.contains(hashtag.name)) {
                        ListItem(
                            headlineContent = { Text("#${hashtag.name}") },
                            supportingContent = { 
                                Text("${formatCount(hashtag.videoCount)} videos • ${formatCount(hashtag.viewCount)} views") 
                            },
                            leadingContent = {
                                if (hashtag.rank <= 3) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when (hashtag.rank) {
                                                    1 -> Color(0xFFFFD700)
                                                    2 -> Color(0xFFC0C0C0)
                                                    3 -> Color(0xFFCD7F32)
                                                    else -> MaterialTheme.colorScheme.primary
                                                }.copy(alpha = 0.2f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "#${hashtag.rank}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    Icon(Icons.Default.Tag, null)
                                }
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = { onHashtagSelected(hashtag.name) }
                                ) {
                                    Icon(Icons.Default.Add, null)
                                }
                            },
                            modifier = Modifier.clickable { onHashtagSelected(hashtag.name) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Done button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Listo (${selectedHashtags.size})")
                }
            }
        }
    }
}

@Composable
fun HashtagDisplay(
    hashtags: List<String>,
    onHashtagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxVisible: Int = 3
) {
    if (hashtags.isEmpty()) return
    
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        hashtags.take(maxVisible).forEach { tag ->
            Text(
                text = "#$tag",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onHashtagClick(tag) }
            )
        }
        
        if (hashtags.size > maxVisible) {
            Text(
                text = "+${hashtags.size - maxVisible} más",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

// Simple FlowRow implementation for Compose
@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val hGapPx = 4.dp.roundToPx()
        val vGapPx = 4.dp.roundToPx()
        val rows = mutableListOf<List<androidx.compose.ui.layout.Placeable>>()
        val rowWidths = mutableListOf<Int>()
        val rowHeights = mutableListOf<Int>()
        
        var currentRow = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var currentRowWidth = 0
        var currentRowHeight = 0
        
        measurables.forEach { measurable ->
            val placeable = measurable.measure(constraints)
            
            if (currentRow.isNotEmpty() && currentRowWidth + hGapPx + placeable.width > constraints.maxWidth) {
                rows.add(currentRow)
                rowWidths.add(currentRowWidth)
                rowHeights.add(currentRowHeight)
                currentRow = mutableListOf()
                currentRowWidth = 0
                currentRowHeight = 0
            }
            
            currentRow.add(placeable)
            currentRowWidth += if (currentRow.size == 1) placeable.width else hGapPx + placeable.width
            currentRowHeight = maxOf(currentRowHeight, placeable.height)
        }
        
        if (currentRow.isNotEmpty()) {
            rows.add(currentRow)
            rowWidths.add(currentRowWidth)
            rowHeights.add(currentRowHeight)
        }
        
        val width = constraints.maxWidth
        val height = rowHeights.sum() + (rows.size - 1) * vGapPx
        
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = when (horizontalArrangement) {
                    Arrangement.Center -> (width - rowWidths[rowIndex]) / 2
                    Arrangement.End -> width - rowWidths[rowIndex]
                    else -> 0
                }
                
                row.forEach { placeable ->
                    placeable.placeRelative(x, y)
                    x += placeable.width + hGapPx
                }
                
                y += rowHeights[rowIndex] + vGapPx
            }
        }
    }
}
