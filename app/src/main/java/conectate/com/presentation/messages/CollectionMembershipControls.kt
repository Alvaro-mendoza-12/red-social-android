package conectate.com.presentation.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import conectate.com.data.localfallback.CollectionMembershipPolicy
import conectate.com.data.model.User
import conectate.com.presentation.components.CircularProfileImage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun CollectionMembershipSettings(policy: CollectionMembershipPolicy?, busy: Boolean, onChange: (Boolean) -> Unit) {
    if (policy?.canManagePolicy != true) return
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Permitir que los miembros añadan personas", style = MaterialTheme.typography.titleSmall)
            Text("Al desactivarlo, solo los administradores podrán añadirlas.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = policy.membersCanAdd, onCheckedChange = onChange, enabled = !busy)
    }
}

@Composable
fun CollectionSystemNotice(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionAddMemberDialog(
    onDismiss: () -> Unit, onSearch: (String) -> Unit, searchResults: List<User>,
    policy: CollectionMembershipPolicy?, busy: Boolean, error: String?,
    onAdd: (String, String?, String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<User?>(null) }
    var shareHistory by remember { mutableStateOf(false) }
    var showDates by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf<String?>(null) }
    var until by remember { mutableStateOf<String?>(null) }
    val range = rememberDateRangePickerState()
    LaunchedEffect(query) { onSearch(query.trim()) }
    if (showDates) {
        DatePickerDialog(onDismissRequest = { showDates = false }, confirmButton = {
            TextButton(enabled = range.selectedStartDateMillis != null && range.selectedEndDateMillis != null, onClick = {
                val start = range.selectedStartDateMillis ?: return@TextButton
                val end = minOf((range.selectedEndDateMillis ?: start) + 86_400_000L - 1L, System.currentTimeMillis())
                val history = policy?.historyAccess
                val availableStart = runCatching { Instant.parse(history?.joinedAt).toEpochMilli() }.getOrNull()
                // Selecting the joining day starts at the real joining time.
                val clippedStart = if (history?.allHistory != true && availableStart != null &&
                    availableStart in start..end) availableStart else start
                if (clippedStart <= end) { from = Instant.ofEpochMilli(clippedStart).toString(); until = Instant.ofEpochMilli(end).toString() }
                showDates = false
            }) { Text("Elegir intervalo") }
        }, dismissButton = { TextButton(onClick = { showDates = false }) { Text("Cancelar") } }) {
            DateRangePicker(state = range, modifier = Modifier.heightIn(max = 500.dp))
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Añadir persona") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected == null) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Buscar por nombre o usuario") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    if (query.trim().length >= 3 && searchResults.isEmpty()) item { Text("No se encontraron personas") }
                    items(searchResults, key = { it.id }) { user ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = user }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProfileImage(ownerId = user.id, imageUrl = user.avatar_url, size = 40.dp, fallbackName = user.displayName)
                            Spacer(Modifier.width(12.dp))
                            Text(user.displayName, modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                Text(selected?.displayName.orEmpty(), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = !busy, onClick = { selected = null }) { Text("Elegir otra persona") }
                Text("Verá los mensajes nuevos desde que se una.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = shareHistory, onCheckedChange = { shareHistory = it }, enabled = !busy)
                    Text("Compartir también un intervalo anterior", modifier = Modifier.weight(1f))
                }
                if (shareHistory) {
                    TextButton(enabled = !busy, onClick = { showDates = true }) { Text("Elegir desde cuándo y hasta cuándo") }
                    if (from != null && until != null) {
                        val format = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC)
                        Text("${format.format(Instant.parse(from))} — ${format.format(Instant.parse(until))} UTC", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Solo puedes compartir mensajes que tienes permiso para ver.", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (policy?.canAddMembers != true) Text("No tienes permiso para añadir personas en este momento.")
        }
    }, confirmButton = {
        TextButton(enabled = selected != null && !busy && policy?.canAddMembers == true &&
            (!shareHistory || (from != null && until != null)), onClick = {
                selected?.let { onAdd(it.id, if (shareHistory) from else null, if (shareHistory) until else null) }
            }) { Text(if (busy) "Añadiendo…" else "Añadir") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } })
}
