package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.DirectMessageRequestState

@Composable
fun DirectMessageRequestNotice(
    state: DirectMessageRequestState,
    senderName: String,
    warningAcknowledged: Boolean,
    busy: Boolean,
    onContinue: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (state.status in setOf("open", "loading", "error", "offline") || state.status == "new" && warningAcknowledged) return
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (state.status) {
                "new" -> "Enviar una solicitud de mensaje"
                "pending_outgoing" -> "Solicitud enviada"
                "pending_incoming" -> "$senderName te envió una solicitud de mensaje"
                "rejected" -> "Solicitud rechazada"
                "loading" -> "Verificando la conversación…"
                else -> "No se pudo verificar la solicitud"
            }, fontWeight = FontWeight.Bold)
            Text(when (state.status) {
                "new" -> "Puedes enviar un solo mensaje. Espera a que esta persona acepte o responda para continuar."
                "pending_outgoing" -> "Podrás escribir de nuevo cuando esta persona acepte o responda."
                "pending_incoming" -> "Acepta para conversar. Si respondes, también aceptarás la solicitud."
                "rejected" -> "Esta persona rechazó la solicitud de mensaje."
                else -> "Comprueba tu conexión y vuelve a intentarlo."
            }, style = MaterialTheme.typography.bodyMedium)
            if (state.status == "new") Button(onClick = onContinue) { Text("Continuar") }
            if (state.status == "pending_incoming") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAccept, enabled = !busy) { Text("Aceptar") }
                    TextButton(onClick = onReject, enabled = !busy) { Text("Rechazar") }
                }
            }
            if (state.status in setOf("error", "loading", "pending_outgoing"))
                TextButton(onClick = onRefresh, enabled = !busy) { Text("Actualizar") }
        }
    }
}
