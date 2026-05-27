package conectate.com.presentation.wallet

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable


@Composable
fun GiftSelectionDialog(
    receiverId: String,
    receiverName: String,
    onDismiss: () -> Unit,
    onGiftSent: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Regalos no disponibles") },
        text = {
            Text(
                "La función de regalos para $receiverName todavía está cerrada mientras estabilizamos la base Android y los contratos de monetización."
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Entendido")
            }
        }
    )
}





