package conectate.com.presentation.components

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import conectate.com.presentation.common.AppFeedbackBus
import java.util.Locale

private data class DocumentPreviewInfo(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

@Composable
fun DocumentSharePreviewDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val info = remember(uri) { resolveDocumentPreviewInfo(context, uri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null) },
        title = { Text("Revisar documento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = info.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(
                        info.mimeType.substringAfter('/').uppercase(Locale.getDefault()),
                        formatAttachmentBytes(info.sizeBytes),
                    ).filter(String::isNotBlank).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = {
                        val previewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, info.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(previewIntent) }
                            .onFailure {
                                AppFeedbackBus.showError(
                                    "No hay una aplicación disponible para abrir este documento."
                                )
                            }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Abrir vista previa")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Enviar documento") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
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

private fun resolveDocumentPreviewInfo(
    context: android.content.Context,
    uri: Uri,
): DocumentPreviewInfo {
    var name = ""
    var size = 0L
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    }
    val mime = context.contentResolver.getType(uri)
        ?.takeIf(String::isNotBlank)
        ?: "application/octet-stream"
    return DocumentPreviewInfo(
        name = name.ifBlank { uri.lastPathSegment ?: "Documento" },
        mimeType = mime,
        sizeBytes = size.coerceAtLeast(0L),
    )
}

private fun formatAttachmentBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    return if (unit == 0) {
        "${value.toLong()} ${units[unit]}"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }
}
