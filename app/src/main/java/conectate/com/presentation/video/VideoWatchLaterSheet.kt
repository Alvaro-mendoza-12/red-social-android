package conectate.com.presentation.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import conectate.com.util.UbuntuMedia

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoWatchLaterSheet(
    viewModel: VideoWatchLaterViewModel,
    onDismiss: () -> Unit,
    onOpenVideo: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 16.dp)) {
            Text("Ver después", style = MaterialTheme.typography.titleLarge)
            Text("Lista privada sincronizada con tu cuenta", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.sort == "newest", onClick = { viewModel.setSort("newest") },
                    label = { Text("Más recientes") })
                FilterChip(selected = state.sort == "oldest", onClick = { viewModel.setSort("oldest") },
                    label = { Text("Más antiguos") })
            }
            state.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::refresh) { Text("Reintentar") }
                }
            }
            if (state.loading) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 12.dp)) {
                if (state.items.isEmpty() && !state.loading && state.error == null) item {
                    Text("Aún no guardaste videos para ver después.", modifier = Modifier.padding(12.dp))
                }
                items(state.items, key = { it.videoId }) { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!item.unavailable) {
                            AsyncImage(
                                model = UbuntuMedia.resolveUrl(item.coverUrl) ?: item.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 72.dp, height = 96.dp),
                            )
                        } else Spacer(Modifier.width(72.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (item.unavailable) "Video ya no disponible" else item.title,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!item.unavailable) Text(item.creatorName,
                                style = MaterialTheme.typography.bodySmall)
                            Row {
                                if (!item.unavailable) Button(onClick = { onOpenVideo(item.videoId) }) {
                                    androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Text("Abrir")
                                }
                                TextButton(onClick = { viewModel.remove(item.videoId) }) { Text("Quitar") }
                            }
                        }
                    }
                    HorizontalDivider()
                }
                if (state.nextCursor != null) item {
                    TextButton(onClick = viewModel::loadMore, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth()) { Text("Cargar más") }
                }
            }
        }
    }
}
