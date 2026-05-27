package conectate.com.presentation.videos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun VideoReportDialog(
    alreadyReported: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (reason: String, details: String?) -> Unit
) {
    if (alreadyReported) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Video reportado") },
            text = { Text("Ya enviaste un reporte para este video.") },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cerrar")
                }
            }
        )
        return
    }

    val reasonOptions = remember {
        listOf(
            "Contenido inapropiado",
            "Acoso o bullying",
            "Spam o estafa",
            "Violencia explícita",
            "Desinformación",
            "Otro"
        )
    }
    var reasonExpanded by remember { mutableStateOf(false) }
    var selectedReason by remember { mutableStateOf(reasonOptions.first()) }
    var details by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reportar video") },
        text = {
            Column {
                OutlinedTextField(
                    value = selectedReason,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    label = { Text("Motivo") },
                    trailingIcon = {
                        TextButton(onClick = { reasonExpanded = true }) {
                            Text("Cambiar")
                        }
                    }
                )
                DropdownMenu(
                    expanded = reasonExpanded,
                    onDismissRequest = { reasonExpanded = false }
                ) {
                    reasonOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                selectedReason = option
                                reasonExpanded = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Detalle (opcional)") },
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(selectedReason, details.ifBlank { null })
                }
            ) {
                Text("Enviar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
