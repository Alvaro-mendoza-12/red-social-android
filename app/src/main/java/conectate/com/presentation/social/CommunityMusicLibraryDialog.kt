package conectate.com.presentation.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.music.CommunityMusicTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityMusicLibraryDialog(
    onDismiss: () -> Unit,
    onRemoved: (String) -> Unit,
    viewModel: CommunityMusicLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<CommunityMusicTrack?>(null) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mis canciones publicadas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
            Text("Historial de tus subidas a la comunidad, incluidas las que siguen en revisión.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = viewModel::refresh,
                enabled = !state.loading && state.removingId == null,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Default.History, null)
                Spacer(Modifier.width(8.dp))
                Text("Abrir y actualizar historial")
                if (state.loading) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.tracks.isEmpty() && !state.loading && state.error == null) item { Text("Todavía no has subido canciones a la comunidad.") }
                items(state.tracks, key = { it.id }) { track ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(track.title, style = MaterialTheme.typography.titleMedium)
                            Text(track.artist)
                            Text(when (track.moderationState) {
                                "APPROVED" -> "Publicada"
                                "REJECTED" -> "Rechazada"
                                "PROCESSING" -> "Procesando"
                                "REMOVED" -> "Retirada"
                                else -> "En revisión"
                            }, style = MaterialTheme.typography.labelMedium)
                            track.createdAt?.let { Text("Subida: ${it.replace('T', ' ').take(16)} UTC", style = MaterialTheme.typography.bodySmall) }
                            track.moderationReason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            OutlinedButton(
                                onClick = { removing = track },
                                enabled = !state.loading && state.removingId == null,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            ) {
                                Icon(Icons.Default.DeleteForever, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.removingId == track.id) "Eliminando…" else "Eliminar canción")
                            }
                        }
                    }
                }
                if (state.nextCursor != null) item {
                    OutlinedButton(onClick = viewModel::more, enabled = !state.loading && state.removingId == null, modifier = Modifier.fillMaxWidth()) { Text("Cargar más canciones") }
                }
            }
        }
    }
    removing?.let { track ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Eliminar ${track.title}") },
            text = { Text("Dejará de aparecer en el catálogo y no podrá elegirse para nuevos momentos. Los momentos ya publicados conservarán su audio.") },
            confirmButton = { TextButton(onClick = { removing = null; viewModel.remove(track, onRemoved) }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } })
    }
}
