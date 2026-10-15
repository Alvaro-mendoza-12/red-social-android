package conectate.com.presentation.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import conectate.com.data.localfallback.MarkerMessagePreview
import conectate.com.data.model.Message

@Composable
fun MessageMarkerActions(message: Message?, markersViewModel: ConversationMarkersViewModel, onDismiss: () -> Unit) {
    if (message == null || message.deleted_for_all || message.content_type.startsWith("view_once_")) return
    val state by markersViewModel.markers.collectAsState()
    val busy by markersViewModel.loading.collectAsState()
    var confirmPin by remember(message.id) { mutableStateOf(false) }
    val saved = state.saved.any { it.messageId == message.id }
    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
        markersViewModel.toggleSaved(message.id)
        onDismiss()
    }.padding(16.dp)) {
        Icon(if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null)
        Spacer(Modifier.width(16.dp))
        Text(if (saved) "Quitar de mensajes guardados" else "Guardar solo para mí")
    }
    if (state.canPin) {
        val pinned = state.pins.any { it.messageId == message.id }
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
            if (pinned) { markersViewModel.togglePin(message.id); onDismiss() }
            else confirmPin = true
        }.padding(16.dp)) {
            Icon(Icons.Default.PushPin, null)
            Spacer(Modifier.width(16.dp))
            Text(if (pinned) "Desfijar para todos" else "Fijar para todos")
        }
    }
    if (confirmPin) AlertDialog(
        onDismissRequest = { confirmPin = false },
        title = { Text("Fijar para todos") },
        text = { Text("Las personas con acceso a esta conversación verán este mensaje en Fijados.") },
        confirmButton = { TextButton(onClick = {
            confirmPin = false
            markersViewModel.togglePin(message.id)
            onDismiss()
        }) { Text("Fijar") } },
        dismissButton = { TextButton(onClick = { confirmPin = false }) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationMarkersBar(markersViewModel: ConversationMarkersViewModel, onJumpToMessage: (String) -> Unit) {
    val state by markersViewModel.markers.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (state.pins.isEmpty() && state.saved.isEmpty()) return
    TextButton(onClick = { markersViewModel.refreshFromUser(); open = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.PushPin, null)
        Spacer(Modifier.width(8.dp))
        Text("Fijados (${state.pins.size}) · Guardados (${state.saved.size})")
    }
    if (!open) return
    var showSaved by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editingLabel by remember { mutableStateOf<String?>(null) }
    var labelDraft by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mensajes importantes", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showSaved, onClick = { showSaved = false }, label = { Text("Fijados") })
                FilterChip(selected = showSaved, onClick = { showSaved = true }, label = { Text("Solo para mí") })
            }
            OutlinedTextField(value = query, onValueChange = { query = it.take(120) },
                label = { Text("Buscar texto, remitente o fecha") }, modifier = Modifier.fillMaxWidth())
            val entries = if (showSaved) state.saved.map { MarkerEntry(it.messageId, it.message, it.label, it.savedAt) }
                else state.pins.map { MarkerEntry(it.messageId, it.message, "", "Fijado por ${it.pinnedByName.ifBlank { it.pinnedBy.take(12) }} · ${it.pinnedAt.take(16)}") }
            val filtered = entries.filter { entry ->
                query.isBlank() || listOf(entry.message?.content, entry.message?.senderId, entry.message?.createdAt, entry.label, entry.detail)
                    .any { it?.contains(query, ignoreCase = true) == true }
            }
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.messageId }) { entry ->
                    Column {
                        MarkerRow(entry.message, entry.label, entry.detail,
                            Modifier.fillMaxWidth().clickable(enabled = entry.message != null) {
                                open = false
                                onJumpToMessage(entry.messageId)
                            })
                        Row {
                            if (showSaved) TextButton(onClick = {
                                editingLabel = entry.messageId
                                labelDraft = entry.label
                            }) { Text("Etiqueta") }
                            if (showSaved || state.canPin) TextButton(onClick = {
                                if (showSaved) markersViewModel.toggleSaved(entry.messageId)
                                else markersViewModel.togglePin(entry.messageId)
                            }) { Text(if (showSaved) "Quitar de guardados" else "Desfijar") }
                        }
                    }
                }
            }
        }
    }
    editingLabel?.let { messageId ->
        AlertDialog(onDismissRequest = { editingLabel = null },
            title = { Text("Etiqueta privada") },
            text = { OutlinedTextField(labelDraft, { labelDraft = it.take(64) }, label = { Text("Etiqueta opcional") }) },
            confirmButton = { TextButton(onClick = {
                markersViewModel.setSavedLabel(messageId, labelDraft)
                editingLabel = null
            }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editingLabel = null }) { Text("Cancelar") } })
    }
}

private data class MarkerEntry(val messageId: String, val message: MarkerMessagePreview?, val label: String, val detail: String)

@Composable
private fun MarkerRow(message: MarkerMessagePreview?, label: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp)) {
        if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Text(message?.content?.ifBlank { "Adjunto · ${message.contentType}" } ?: "Mensaje ya no disponible",
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        message?.let {
            Text("${it.senderId.take(12)} · ${it.createdAt.take(16)} · ${detail.take(80)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
