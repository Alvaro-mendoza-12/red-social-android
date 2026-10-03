package conectate.com.presentation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceRoomSearchDialog(
    id: String,
    state: VoiceRoomSearchState,
    onIdChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val searching = state == VoiceRoomSearchState.Searching
    val error = (state as? VoiceRoomSearchState.Failed)?.message
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unirse por código") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Escribe el código de la sala. Si es privada, te pediremos su contraseña al entrar.")
                OutlinedTextField(
                    value = id,
                    onValueChange = onIdChange,
                    label = { Text("Código de sala") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !searching,
                    isError = error != null,
                    supportingText = { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                )
            }
        },
        confirmButton = {
            Button(onClick = onSearch, enabled = !searching && id.isNotBlank()) {
                Text(if (searching) "Buscando…" else "Buscar sala")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
