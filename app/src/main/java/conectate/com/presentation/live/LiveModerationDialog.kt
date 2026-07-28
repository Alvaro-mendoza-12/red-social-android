package conectate.com.presentation.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.LiveModerationState
import conectate.com.data.model.LiveViewerParticipant

@Composable
fun LiveModerationDialog(
    moderation: LiveModerationState,
    viewers: List<LiveViewerParticipant>,
    onDismiss: () -> Unit,
    onSaveSettings: (chatPaused: Boolean, blockedWords: List<String>) -> Unit,
    onSetModerator: (userId: String, enabled: Boolean) -> Unit,
    onKick: (userId: String) -> Unit,
    onBan: (userId: String) -> Unit
) {
    var chatPaused by remember(moderation.chatPaused) { mutableStateOf(moderation.chatPaused) }
    var blockedWordsText by remember(moderation.blockedWords) {
        mutableStateOf(moderation.blockedWords.joinToString(", "))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Moderación del directo") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pausar comentarios", fontWeight = FontWeight.Bold)
                        Text("Los moderadores y el anfitrión todavía podrán comentar.")
                    }
                    Switch(checked = chatPaused, onCheckedChange = { chatPaused = it })
                }

                if (moderation.isHost) {
                    OutlinedTextField(
                        value = blockedWordsText,
                        onValueChange = { blockedWordsText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Palabras bloqueadas") },
                        supportingText = { Text("Sepáralas con comas. Se aplican como palabras completas.") },
                        minLines = 2,
                        maxLines = 4
                    )
                }

                Text(
                    text = if (viewers.isEmpty()) "No hay espectadores conectados." else "Personas conectadas",
                    fontWeight = FontWeight.Bold
                )
                viewers.forEach { viewer ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = viewer.name.ifBlank { "Usuario" } +
                                if (viewer.isModerator) " · Moderador" else "",
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (moderation.isHost) {
                                TextButton(
                                    onClick = {
                                        onSetModerator(viewer.userId, !viewer.isModerator)
                                    }
                                ) {
                                    Text(if (viewer.isModerator) "Quitar mod" else "Hacer mod")
                                }
                            }
                            TextButton(onClick = { onKick(viewer.userId) }) {
                                Text("Expulsar")
                            }
                            TextButton(onClick = { onBan(viewer.userId) }) {
                                Text("Bloquear")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSaveSettings(
                        chatPaused,
                        blockedWordsText
                            .split(',', '\n')
                            .map(String::trim)
                            .filter { it.length >= 2 }
                            .distinct()
                    )
                    onDismiss()
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}
