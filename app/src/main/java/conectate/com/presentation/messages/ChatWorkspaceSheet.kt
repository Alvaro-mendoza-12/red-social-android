package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.Message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWorkspaceSheet(
    state: ChatWorkspaceUiState,
    initialKind: ChatWorkspaceKind,
    initialSource: Message?,
    composerText: String,
    onDismiss: () -> Unit,
    onSave: (ChatWorkspaceKind, String, Message?, () -> Unit) -> Unit,
    onToggleTask: (String) -> Unit,
    onRemove: (String) -> Unit,
    onLoadDraft: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    onRetry: () -> Unit,
    onSchedule: (String, Long?) -> Unit = { _, _ -> },
) {
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var taskText by rememberSaveable { mutableStateOf(if (initialKind == ChatWorkspaceKind.TASK) initialSource?.content.orEmpty().take(ChatWorkspace.MAX_TEXT) else "") }
    var contextText by rememberSaveable { mutableStateOf(if (initialKind == ChatWorkspaceKind.CONTEXT) initialSource?.content.orEmpty().take(ChatWorkspace.MAX_TEXT) else "") }
    var draftText by rememberSaveable { mutableStateOf("") }
    val entryText = when (kind) {
        ChatWorkspaceKind.TASK -> taskText
        ChatWorkspaceKind.CONTEXT -> contextText
        ChatWorkspaceKind.DRAFT -> draftText
    }
    fun setEntryText(text: String) {
        when (kind) {
            ChatWorkspaceKind.TASK -> taskText = text
            ChatWorkspaceKind.CONTEXT -> contextText = text
            ChatWorkspaceKind.DRAFT -> draftText = text
        }
    }
    var source by remember(initialSource) { mutableStateOf(initialSource) }
    var draftToLoad by remember { mutableStateOf<ChatWorkspaceEntry?>(null) }
    var scheduleEntry by remember { mutableStateOf<ChatWorkspaceEntry?>(null) }
    var agendaOnly by rememberSaveable { mutableStateOf(false) }
    val allEntries = state.workspace.entries.filter { it.kind == kind }
    val entries = orderedWorkspaceEntries(allEntries).filter {
        kind != ChatWorkspaceKind.TASK || !agendaOnly || (it.dueAt != null && !it.completed)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mi espacio del chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cerrar espacio personal") }
            }
            Text(
                "Solo tú, en este dispositivo. No se envía al chat ni se sincroniza con otros dispositivos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PrimaryTabRow(selectedTabIndex = kind.ordinal) {
                ChatWorkspaceKind.entries.forEach { tab ->
                    Tab(selected = tab == kind, enabled = !state.saving, onClick = { kind = tab }, text = {
                        Text(when (tab) {
                            ChatWorkspaceKind.TASK -> "Pendientes"
                            ChatWorkspaceKind.CONTEXT -> "Contexto"
                            ChatWorkspaceKind.DRAFT -> "Borradores"
                        })
                    })
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                if (!state.available) TextButton(onClick = onRetry) { Text("Reintentar") }
            }
            if (state.available) {
                if (kind == ChatWorkspaceKind.TASK) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !agendaOnly, onClick = { agendaOnly = false }, label = { Text("Todos") })
                        FilterChip(selected = agendaOnly, onClick = { agendaOnly = true }, label = { Text("Cronograma") })
                    }
                    if (agendaOnly) Text("Pendientes con fecha, ordenados por vencimiento. Las fechas no crean alarmas ni envían mensajes.", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    when (kind) {
                        ChatWorkspaceKind.TASK -> "Convierte compromisos en acciones privadas. Puedes completarlas y volver al mensaje de origen."
                        ChatWorkspaceKind.CONTEXT -> "Conserva decisiones y contexto para retomar la conversación sin releerla."
                        ChatWorkspaceKind.DRAFT -> "Guarda distintas respuestas y recupéralas cuando estés listo para enviarlas."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                OutlinedTextField(
                    value = entryText,
                    onValueChange = { setEntryText(it.take(ChatWorkspace.MAX_TEXT)) },
                    label = { Text(when (kind) {
                        ChatWorkspaceKind.TASK -> "Siguiente acción"
                        ChatWorkspaceKind.CONTEXT -> "Nota de contexto"
                        ChatWorkspaceKind.DRAFT -> "Nueva respuesta guardada"
                    }) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    enabled = !state.saving
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${allEntries.size}/${ChatWorkspace.MAX_PER_KIND}", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    if (kind == ChatWorkspaceKind.DRAFT && composerText.isNotBlank()) {
                        TextButton(onClick = { setEntryText(composerText.take(ChatWorkspace.MAX_TEXT)) }, enabled = !state.saving) {
                            Text("Usar texto actual")
                        }
                    }
                    TextButton(enabled = entryText.isNotBlank() && !state.saving, onClick = {
                        onSave(kind, entryText, source.takeIf { kind == initialKind }) { setEntryText(""); source = null }
                    }) { Text(if (state.saving) "Guardando…" else "Guardar") }
                }
                if (source != null && kind == initialKind) Text("Se vinculará al mensaje seleccionado", style = MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (entries.isEmpty()) item {
                        Text("Todavía no tienes elementos guardados aquí.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    items(entries, key = { it.id }) { entry ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    if (entry.kind == ChatWorkspaceKind.TASK) Checkbox(
                                        checked = entry.completed,
                                        onCheckedChange = { onToggleTask(entry.id) },
                                        enabled = !state.saving
                                    )
                                    Text(entry.text, modifier = Modifier.weight(1f).padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                                    IconButton(onClick = { onRemove(entry.id) }, enabled = !state.saving) {
                                        Icon(Icons.Default.DeleteOutline, "Eliminar elemento guardado")
                                    }
                                }
                                if (entry.completed) Text("Completado", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                entry.dueAt?.let { dueAt ->
                                    val overdue = !entry.completed && dueAt < System.currentTimeMillis()
                                    Text((if (overdue) "Vencido · " else "") + workspaceDueLabel(dueAt),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (entry.kind == ChatWorkspaceKind.TASK) Row {
                                    TextButton(enabled = !state.saving, onClick = { scheduleEntry = entry }) {
                                        Text(if (entry.dueAt == null) "Añadir fecha" else "Cambiar fecha")
                                    }
                                    if (entry.dueAt != null) TextButton(enabled = !state.saving, onClick = { onSchedule(entry.id, null) }) { Text("Quitar fecha") }
                                }
                                Row {
                                    entry.sourceMessageId?.let { sourceId ->
                                        TextButton(onClick = { onOpenSource(sourceId) }) { Text("Ver mensaje") }
                                    }
                                    if (entry.kind == ChatWorkspaceKind.DRAFT) TextButton(onClick = {
                                        if (composerText.isNotBlank() && composerText != entry.text) draftToLoad = entry
                                        else onLoadDraft(entry.text)
                                    }) { Text("Usar borrador") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    scheduleEntry?.let { entry ->
        WorkspaceScheduleDialog(entry, onDismiss = { scheduleEntry = null }, onSchedule = { onSchedule(entry.id, it) })
    }
    draftToLoad?.let { draft ->
        AlertDialog(
            onDismissRequest = { draftToLoad = null },
            title = { Text("Conservar lo que estabas escribiendo") },
            text = { Text("Guardaremos tu texto actual como otro borrador antes de cargar esta respuesta.") },
            confirmButton = { TextButton(enabled = !state.saving, onClick = {
                onSave(ChatWorkspaceKind.DRAFT, composerText, null) {
                    draftToLoad = null
                    onLoadDraft(draft.text)
                }
            }) { Text("Guardar y cargar") } },
            dismissButton = { TextButton(onClick = { draftToLoad = null }) { Text("Cancelar") } }
        )
    }
}
