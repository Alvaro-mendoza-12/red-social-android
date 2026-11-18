package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.presentation.components.CircularProfileImage

@Composable
fun GroupVoiceRoomDialog(groupId: String, onDismiss: () -> Unit, onCreated: (String) -> Unit,
                         viewModel: GroupVoiceRoomViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(groupId) { viewModel.open(groupId) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    LaunchedEffect(state.roomId, state.sessionChanged) {
        if (state.sessionChanged) onDismiss()
        else state.roomId?.let(onCreated)
    }
    val editable = !state.loading && !state.creating && !state.pending
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crear sala de voz") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.roster?.groupName ?: "Invita a los integrantes del grupo")
                Text("Recibirán una notificación y decidirán si entran. No se realizará ninguna llamada.", style = MaterialTheme.typography.bodySmall)
                listOf("all" to "Incluir a todos", "selected" to "Seleccionar integrantes").forEach { (mode, label) ->
                    Row(Modifier.fillMaxWidth().selectable(state.mode == mode, enabled = editable, role = Role.RadioButton,
                        onClick = { viewModel.mode(mode) }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.mode == mode, onClick = null, enabled = editable)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(28.dp))
                if (state.mode == "selected") {
                    TextButton(onClick = viewModel::all, enabled = editable) {
                        Text(if (state.selected.size == state.roster?.members?.size) "Deseleccionar a todos" else "Seleccionar a todos")
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(state.roster?.members.orEmpty(), key = { it.id }) { member ->
                            Row(Modifier.fillMaxWidth().toggleable(member.id in state.selected, enabled = editable, role = Role.Checkbox,
                                onValueChange = { viewModel.toggle(member.id) }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProfileImage(member.avatarUrl, size = 36.dp, fallbackName = member.name, openMomentOnClick = false, showMomentRing = false)
                                Text(member.name, Modifier.weight(1f).padding(horizontal = 8.dp))
                                Checkbox(checked = member.id in state.selected, onCheckedChange = null, enabled = editable)
                            }
                        }
                    }
                }
                Text("${state.count} integrantes recibirán la invitación", style = MaterialTheme.typography.labelLarge)
                if (!state.loading && state.roster?.members?.isEmpty() == true) Text("No hay otros integrantes a quienes invitar.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::create, enabled = !state.loading && !state.creating && state.count > 0) {
            Text(if (state.creating) "Creando…" else if (state.pending) "Confirmar invitación pendiente" else "Crear sala de voz")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
