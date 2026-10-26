package conectate.com.presentation.messages.stickers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import conectate.com.data.model.StickerItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerGrid(
    stickers: List<StickerItem>,
    onStickerClick: (StickerItem) -> Unit,
    isFavorite: (StickerItem) -> Boolean,
    onFavoriteChange: (StickerItem, Boolean) -> Unit,
    onCreateClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 72.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (onCreateClick != null) {
            item(key = "create-sticker", contentType = "create-sticker") {
                Card(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clickable { onCreateClick() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Crear sticker",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        if (stickers.isEmpty()) {
            item(key = "empty-stickers", span = { GridItemSpan(maxLineSpan) }, contentType = "empty-stickers") {
                Text(
                    "Aún no hay stickers en esta sección.",
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        items(stickers, key = { it.id }, contentType = { "sticker" }) { sticker ->
            var menuExpanded by remember(sticker.id) { mutableStateOf(false) }
            val currentlyFavorite = isFavorite(sticker)
            Box {
                Card(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .combinedClickable(
                            onClick = { onStickerClick(sticker) },
                            onLongClickLabel = "Opciones de sticker",
                            onLongClick = { menuExpanded = true }
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (!sticker.media_url.isNullOrBlank()) {
                        conectate.com.presentation.components.StickerArtwork(
                            url = sticker.media_url,
                            thumbnailUrl = sticker.thumbnail_url,
                            description = sticker.title,
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sticker.title.take(2).ifBlank { "🧩" },
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Enviar sticker") },
                        onClick = {
                            menuExpanded = false
                            onStickerClick(sticker)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (currentlyFavorite) "Quitar de favoritos" else "Añadir a favoritos") },
                        onClick = {
                            menuExpanded = false
                            onFavoriteChange(sticker, !currentlyFavorite)
                        }
                    )
                }
            }
        }
    }
}
