package conectate.com.presentation.video

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun VideoLearningTools(
    accountId: String?,
    videoId: String?,
    positionMs: Long,
    durationMs: Long,
    canSaveMarker: Boolean,
    markers: List<VideoPersonalMarker>,
    reviewLoop: VideoReviewLoop?,
    sessionMinutes: Int,
    onSaveMarker: (String) -> Unit,
    onReturnToMarker: (Long) -> Unit,
    onUpdateMarker: (Long, String) -> Unit,
    onRemoveMarker: (Long) -> Unit,
    onToggleLoop: () -> Unit,
    onSessionMinutesChange: (Int) -> Unit
) {
    val markerAtPosition = markers.firstOrNull { it.positionMs == positionMs }
    val markerLimitReached = markerAtPosition == null &&
        markers.size >= VideoPersonalMarkerStore.MAX_MARKERS_PER_VIDEO
    var note by remember(accountId, videoId, positionMs, markerAtPosition) {
        mutableStateOf(markerAtPosition?.note.orEmpty())
    }
    var editingPositionMs by remember(accountId, videoId) { mutableStateOf<Long?>(null) }
    var editingNote by remember(accountId, videoId) { mutableStateOf("") }
    HorizontalDivider()
    Text("Aprende a tu ritmo", style = MaterialTheme.typography.titleMedium)
    Text("Punto privado · ${videoTimeLabel(positionMs)}", style = MaterialTheme.typography.labelLarge)
    Text("Guarda hasta 20 puntos con notas para volver a lo que te interesa. Solo en este dispositivo.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = note,
        onValueChange = { note = it.take(240) },
        label = { Text("Nota personal") },
        supportingText = { Text("${note.length}/240") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
        enabled = canSaveMarker && videoId != null && !markerLimitReached
    )
    Button(onClick = { onSaveMarker(note) }, enabled = canSaveMarker && videoId != null && !markerLimitReached) {
        Text(if (markerAtPosition == null) "Guardar punto" else "Actualizar punto")
    }
    if (!canSaveMarker) Text("Inicia sesión para guardar tus puntos privados.")
    if (markerLimitReached) Text("Llegaste al límite de 20 puntos. Borra uno para guardar otro.")
    if (markers.isNotEmpty()) {
        Text("Tus puntos (${markers.size}/20)", style = MaterialTheme.typography.labelLarge)
        markers.sortedBy { it.positionMs }.forEach { marker ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(videoTimeLabel(marker.positionMs), style = MaterialTheme.typography.labelLarge)
                if (marker.note.isNotBlank()) Text(marker.note, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onReturnToMarker(marker.positionMs) }) { Text("Ir al punto") }
                    TextButton(onClick = {
                        editingPositionMs = marker.positionMs
                        editingNote = marker.note
                    }) { Text("Editar nota") }
                    TextButton(onClick = {
                        if (editingPositionMs == marker.positionMs) editingPositionMs = null
                        onRemoveMarker(marker.positionMs)
                    }) { Text("Borrar este punto") }
                }
                if (editingPositionMs == marker.positionMs) {
                    OutlinedTextField(
                        value = editingNote,
                        onValueChange = { editingNote = it.take(VideoPersonalMarkerStore.MAX_NOTE_LENGTH) },
                        label = { Text("Editar nota de ${videoTimeLabel(marker.positionMs)}") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    TextButton(onClick = {
                        onUpdateMarker(marker.positionMs, editingNote)
                        editingPositionMs = null
                    }) { Text("Guardar nota") }
                }
            }
            HorizontalDivider()
        }
    }
    HorizontalDivider()
    Text("Repaso de un tramo", style = MaterialTheme.typography.labelLarge)
    Text("Repite hasta 10 segundos desde el punto actual; se desactiva al cambiar de video.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onToggleLoop, enabled = videoId != null && durationMs >= 1_000L) {
        Text(if (reviewLoop == null) "Repetir desde aquí" else
            "Terminar repaso ${videoTimeLabel(reviewLoop.startMs)}–${videoTimeLabel(reviewLoop.endMs)}")
    }
    HorizontalDivider()
    Text("Tu tiempo de video", style = MaterialTheme.typography.labelLarge)
    Text("Pausa al llegar al tiempo elegido. Solo cuenta la reproducción en primer plano; cada selección inicia un bloque nuevo.",
        style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0, 5, 15, 30).forEach { minutes ->
            FilterChip(
                selected = sessionMinutes == minutes,
                onClick = { onSessionMinutesChange(minutes) },
                label = { Text(if (minutes == 0) "Sin límite" else "$minutes min") }
            )
        }
    }
}
