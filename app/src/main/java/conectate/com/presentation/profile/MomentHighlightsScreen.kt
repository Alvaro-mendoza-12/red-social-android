package conectate.com.presentation.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import conectate.com.data.model.MomentHighlight
import conectate.com.data.model.MomentHighlightItemPreview
import conectate.com.presentation.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentHighlightsScreen(
    navController: NavController,
    userId: String,
    viewModel: MomentHighlightViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(userId) {
        viewModel.loadHighlights(userId)
    }

    LaunchedEffect(state.highlights.map { it.id }) {
        val first = state.highlights.firstOrNull()
        if (first != null && state.selectedHighlightId == null) {
            viewModel.loadItems(first.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Momentos destacados") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.highlights.isEmpty() && !state.isLoading) {
                item {
                    EmptyHighlightsMessage()
                }
            } else {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.highlights, key = { it.id }) { highlight ->
                            HighlightChip(
                                highlight = highlight,
                                selected = state.selectedHighlightId == highlight.id,
                                onClick = { viewModel.loadItems(highlight.id) }
                            )
                        }
                    }
                }

                if (state.selectedItems.isEmpty() && !state.isLoading) {
                    item {
                        Text(
                            "Este destacado todavía no tiene momentos visibles.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.selectedItems, key = { it.id }) { item ->
                        HighlightMomentCard(
                            item = item,
                            onClick = {
                                val route = if (item.mediaType == "video") {
                                    Screen.FullScreenVideo.createRoute(item.mediaUrl)
                                } else {
                                    Screen.FullScreenImage.createRoute(item.mediaUrl)
                                }
                                navController.navigate(route)
                            }
                        )
                    }
                }
            }

            if (state.isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HighlightChip(
    highlight: MomentHighlight,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                "${highlight.title} (${highlight.itemCount})",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            if (!highlight.coverMediaUrl.isNullOrBlank()) {
                AsyncImage(
                    model = highlight.coverMediaUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
        }
    )
}

@Composable
private fun HighlightMomentCard(
    item: MomentHighlightItemPreview,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            Box {
                AsyncImage(
                    model = item.thumbnailUrl ?: item.mediaUrl,
                    contentDescription = item.caption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f),
                    contentScale = ContentScale.Crop
                )
                if (item.mediaType == "video") {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(54.dp),
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Reproducir",
                            tint = Color.White,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            if (!item.caption.isNullOrBlank()) {
                Text(
                    item.caption,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyHighlightsMessage() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Sin destacados todavía", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Los momentos guardados como highlights aparecerán aquí.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
