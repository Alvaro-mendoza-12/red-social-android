package conectate.com.presentation.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DocumentSharePreviewDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit = {},
    onConfirmDocument: ((Uri) -> Unit)? = null,
) {
    conectate.com.presentation.documents.InternalDocumentDialog(
        source = uri,
        onDismiss = onDismiss,
        onSend = { selectedUri ->
            if (onConfirmDocument != null) onConfirmDocument(selectedUri) else onConfirm()
        },
    )
}

@Composable
fun ContactSharePreviewDialog(
    name: String,
    phones: List<String>,
    emails: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ContactPhone, contentDescription = null) },
        title = { Text("Compartir contacto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                phones.filter(String::isNotBlank).distinct().take(4).forEach { phone ->
                    Text("📞 $phone", style = MaterialTheme.typography.bodyMedium)
                }
                emails.filter(String::isNotBlank).distinct().take(4).forEach { email ->
                    Text("✉️ $email", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "Solo se enviarán los datos que ves aquí.",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Enviar contacto") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
