package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import conectate.com.data.repository.MultiAccountInboxSummary

@Composable
internal fun MessageAccountsDialog(
    accounts: List<MultiAccountInboxSummary>,
    switching: Boolean,
    error: String?,
    onSwitch: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!switching) onDismiss() },
        title = { Text("Centro multi-cuenta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (accounts.isEmpty()) Text("Guarda o añade una cuenta en Ajustes para usar el cambio rápido.")
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(accounts, key = { it.account.id }) { summary ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(summary.account.display_name ?: summary.account.email, style = MaterialTheme.typography.titleMedium)
                                Text(summary.account.email, style = MaterialTheme.typography.bodySmall)
                                val count = summary.unreadConversationCount
                                if (count > 0) Text(
                                    if (count == 1) "1 conversacion sin revisar" else "$count conversaciones sin revisar",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { onSwitch(summary.account.id) }, enabled = !switching && !summary.isActive) {
                                    Text(if (summary.isActive) "Cuenta actual" else "Entrar a esta cuenta")
                                }
                            }
                        }
                    }
                }
                if (switching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !switching) { Text("Cerrar") } },
        dismissButton = { TextButton(onClick = onManage, enabled = !switching) { Text("Gestionar cuentas") } }
    )
}
