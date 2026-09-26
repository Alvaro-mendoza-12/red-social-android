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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
fun VideoHistorySheet(
    viewModel: VideoHistoryViewModel,
    onDismiss: () -> Unit,
    onContinue: (String, Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Borrar historial") },
        text = { Text("Se quitarán todos los videos de tu historial privado. Esto no cambia las visualizaciones de los creadores.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            viewModel.remove(null)
        }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Historial", style = MaterialTheme.typography.titleLarge)
                    Text("Solo tú puedes ver lo que has reproducido", style = MaterialTheme.typography.bodySmall)
                }
                if (state.items.isNotEmpty()) TextButton(onClick = { confirmClear = true },
                    enabled = !state.deleting) { Text("Borrar todo") }
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
                    Text("Aún no hay videos en tu historial.", modifier = Modifier.padding(12.dp))
                }
                items(state.items, key = { it.videoId }) { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!item.unavailable) AsyncImage(
                            model = UbuntuMedia.resolveUrl(item.coverUrl) ?: item.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 72.dp, height = 96.dp),
                        ) else Spacer(Modifier.width(72.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (item.unavailable) "Video ya no disponible" else item.title,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!item.unavailable) {
                                Text(item.creatorName, style = MaterialTheme.typography.bodySmall)
                                Text("${item.positionMs / 1000}s de ${item.durationMs / 1000}s",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                if (!item.unavailable) Button(onClick = {
                                    onContinue(item.videoId, item.positionMs)
                                }) { Text("Continuar") }
                                TextButton(onClick = { viewModel.remove(item.videoId) },
                                    enabled = !state.deleting) { Text("Quitar") }
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
