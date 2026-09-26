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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun VideoCollectionsSheet(
    viewModel: VideoCollectionsViewModel,
    videoToAdd: String? = null,
    onDismiss: () -> Unit,
    onOpenVideo: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    var renameName by remember { mutableStateOf("") }
    var confirmingDelete by remember { mutableStateOf<String?>(null) }
    val selected = state.collections.find { it.id == state.selectedId }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(selected?.id) { renameName = selected?.name.orEmpty() }

    confirmingDelete?.let { collectionId ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Eliminar colección") },
            text = { Text("Se quitará la lista, pero los videos originales permanecerán publicados.") },
            confirmButton = { TextButton(onClick = {
                confirmingDelete = null
                viewModel.delete(collectionId)
            }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text("Cancelar") } },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 660.dp).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) TextButton(onClick = { viewModel.select(null) }) { Text("‹ Volver") }
                Text(if (selected == null) "Mis colecciones" else selected.name,
                    style = MaterialTheme.typography.titleLarge)
            }
            Text("Privadas: solo tú puedes verlas", style = MaterialTheme.typography.bodySmall)
            state.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.refresh() }) { Text("Reintentar") }
                }
            }
            if (state.loading || state.loadingVideos) CircularProgressIndicator(
                Modifier.padding(8.dp).size(24.dp))

            if (selected == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, onValueChange = { if (it.length <= 60) newName = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("Nueva colección") })
                    Button(onClick = {
                        viewModel.create(newName, videoToAdd)
                    }, enabled = newName.isNotBlank() && !state.working && state.collections.size < 100) {
                        Text("Crear")
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (state.collections.isEmpty() && !state.loading && state.error == null) item {
                        Text("Crea una colección para organizar tus videos.",
                            modifier = Modifier.padding(12.dp))
                    }
                    items(state.collections, key = { it.id }) { collection ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${collection.itemCount} videos",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (videoToAdd != null) TextButton(
                                onClick = { viewModel.add(collection.id, videoToAdd) },
                                enabled = !state.working && collection.itemCount < 500,
                            ) { Text("Añadir") }
                            TextButton(onClick = { viewModel.select(collection.id) }) { Text("Abrir") }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(renameName,
                        onValueChange = { if (it.length <= 60) renameName = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("Nombre") })
                    TextButton(onClick = { viewModel.rename(selected.id, renameName) },
                        enabled = renameName.isNotBlank() && renameName != selected.name && !state.working) {
                        Text("Guardar")
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(state.search, onValueChange = viewModel::search,
                        modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("Buscar en esta colección") })
                    TextButton(onClick = { confirmingDelete = selected.id },
                        enabled = !state.working) { Text("Eliminar") }
                }
                LazyColumn(modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (state.videos.isEmpty() && !state.loadingVideos && state.error == null) item {
                        Text("No hay videos para mostrar.", modifier = Modifier.padding(12.dp))
                    }
                    items(state.videos, key = { it.videoId }) { video ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!video.unavailable) AsyncImage(
                                model = UbuntuMedia.resolveUrl(video.coverUrl) ?: video.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 64.dp, height = 84.dp),
                            ) else Spacer(Modifier.width(64.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (video.unavailable) "Video ya no disponible" else video.title,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!video.unavailable) TextButton(onClick = {
                                        onOpenVideo(video.videoId)
                                    }) { Text("Abrir") }
                                    TextButton(onClick = { viewModel.remove(selected.id, video.videoId) },
                                        enabled = !state.working) { Text("Quitar") }
                                }
                            }
                            Column {
                                IconButton(onClick = { viewModel.move(selected.id, video.videoId, "up") },
                                    enabled = !state.working && state.search.isBlank()) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Subir en la colección")
                                }
                                IconButton(onClick = { viewModel.move(selected.id, video.videoId, "down") },
                                    enabled = !state.working && state.search.isBlank()) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Bajar en la colección")
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    if (state.nextOffset != null) item {
                        TextButton(onClick = viewModel::loadMore, enabled = !state.loadingVideos,
                            modifier = Modifier.fillMaxWidth()) { Text("Cargar más") }
                    }
                }
            }
        }
    }
}
