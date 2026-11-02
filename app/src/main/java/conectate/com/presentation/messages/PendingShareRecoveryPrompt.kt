package conectate.com.presentation.messages

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** A stopped batch is offered for explicit recovery; it never sends automatically at startup. */
@Composable
internal fun PendingShareRecoveryPrompt(notice: PendingShareNotice, onContinue: () -> Unit, onLater: () -> Unit) {
    if (notice.dismissed || notice.ids.isEmpty() && !notice.error) return
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(if (notice.error) "Revisar envíos pendientes" else "Recuperar un compartido") },
        text = { Text(if (notice.error) "No pudimos comprobar si quedó algún envío pendiente. Puedes comprobarlo de nuevo."
            else "Quedó una acción de compartir pendiente. Puedes revisar sus destinos y continuar sin repetir lo que ya se aceptó.") },
        confirmButton = { TextButton(onClick = onContinue) { Text(if (notice.error) "Comprobar" else "Continuar") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Más tarde") } },
    )
}
