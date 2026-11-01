package conectate.com.presentation.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.localfallback.GlobalSavedMessage
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSavedMessagesSheet(
    onDismiss: () -> Unit,
    onOpenConversation: (String, String, String) -> Unit,
    viewModel: GlobalSavedMessagesViewModel = hiltViewModel(),
) {
    val account = conectate.com.presentation.components.LocalProfileIdentityAccount.current?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    val rawState by viewModel.state.collectAsStateWithLifecycle()
    val state = rawState.forAccount(account)
    var query by rememberSaveable(account) { mutableStateOf(state.search) }
    var roomMenu by remember(account) { mutableStateOf(false) }
    var confirmRemove by remember(account) { mutableStateOf<GlobalSavedMessage?>(null) }
    LaunchedEffect(viewModel, account) { if (account == null) onDismiss() else viewModel.bindAccount() }
    LaunchedEffect(viewModel, account, query) {
        if (account == null) return@LaunchedEffect
        delay(350)
        viewModel.setFilters(search = query.trim())
    }
    if (account == null) return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mensajes guardados", style = MaterialTheme.typography.titleLarge)
                Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text("Solo tú puedes ver esta colección.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it.take(120) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Buscar en todos tus guardados") }, singleLine = true)
            state.error?.let { error -> Row {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("Reintentar") }
            } }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
              item {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("" to "Todos", "chat" to "Chats", "group" to "Grupos", "community" to "Comunidades")
                            .forEach { (value, label) -> FilterChip(selected = state.kind == value,
                                onClick = { viewModel.setFilters(kind = value, roomId = "") }, label = { Text(label) }) }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("" to "Cualquier tipo", "text" to "Texto", "document" to "Documentos",
                            "image" to "Imágenes", "video" to "Videos", "audio" to "Audio").forEach { (value, label) ->
                            FilterChip(selected = state.type == value, onClick = { viewModel.setFilters(type = value) },
                                label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            TextButton(onClick = { roomMenu = true }) {
                                Text(state.rooms[state.roomId] ?: "Todas las conversaciones", maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = roomMenu, onDismissRequest = { roomMenu = false }) {
                                DropdownMenuItem(text = { Text("Todas las conversaciones") }, onClick = {
                                    roomMenu = false; viewModel.setFilters(roomId = "")
                                })
                                state.rooms.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = {
                                    roomMenu = false; viewModel.setFilters(roomId = id)
                                }) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Cualquier fecha", 7 to "7 días", 30 to "30 días").forEach { (days, label) ->
                            val selected = if (days == 0) state.from.isBlank() else state.from.isNotBlank() &&
                                (runCatching { ChronoUnit.DAYS.between(Instant.parse(state.from), Instant.now()) }.getOrNull() ?: 0L)
                                    .let { it in (days - 1L)..(days + 1L) }
                            FilterChip(selected = selected, onClick = {
                                viewModel.setFilters(from = if (days == 0) "" else Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toString())
                            }, label = { Text(label) })
                        }
                    }
                }
              }
                if (state.items.isEmpty() && !state.loading && state.error == null && state.nextCursor == null) item {
                    Text(if (query.isBlank()) "No hay mensajes guardados con estos filtros."
                        else "No hay coincidencias con esta búsqueda.",
                        modifier = Modifier.padding(vertical = 20.dp))
                }
                items(state.items, key = { it.savedInboxKey }) { item ->
                    SavedInboxItem(item, isRemoving = item.savedInboxKey in state.removing,
                        onOpen = { onDismiss(); onOpenConversation(item.roomKind, item.roomId, item.messageId) },
                        onRemove = { confirmRemove = item })
                    HorizontalDivider()
                }
                if (state.nextCursor != null) item {
                    TextButton(onClick = viewModel::loadMore, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth()) { Text("Cargar más guardados") }
                }
            }
        }
    }
    confirmRemove?.let { item -> AlertDialog(onDismissRequest = { confirmRemove = null },
        title = { Text("Quitar de guardados") },
        text = { Text("El mensaje original permanecerá en la conversación.") },
        confirmButton = { TextButton(onClick = { viewModel.remove(item, account); confirmRemove = null }) { Text("Quitar") } },
        dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancelar") } }) }
}

@Composable
private fun SavedInboxItem(item: GlobalSavedMessage, isRemoving: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = item.message != null, onClick = onOpen)
        .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${item.roomName} · ${item.savedAt.take(16)}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        item.label.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        Text(item.message?.content?.ifBlank { "Adjunto · ${item.message.contentType}" } ?: "Mensaje ya no disponible",
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row {
            if (item.message != null) TextButton(onClick = onOpen) { Text("Abrir conversación") }
            TextButton(onClick = onRemove, enabled = !isRemoving) { Text(if (isRemoving) "Quitando…" else "Quitar") }
        }
    }
}
