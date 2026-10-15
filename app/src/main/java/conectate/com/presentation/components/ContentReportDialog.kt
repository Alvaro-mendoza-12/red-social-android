package conectate.com.presentation.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun ContentReportDialog(
    title: String,
    onDismiss: () -> Unit,
    onSubmit: (reason: String, details: String?, evidenceUri: Uri?) -> Unit,
    reasonOptions: List<String>,
    modifier: Modifier = Modifier,
    subjectText: String? = null,
    alreadyReported: Boolean = false,
    alreadyReportedTitle: String = "Contenido reportado",
    alreadyReportedMessage: String = "Ya enviaste un reporte para este contenido.",
    reasonLabel: String = "Motivo *",
    reasonPlaceholder: String = "Selecciona un motivo",
    reasonActionLabel: String = "Elegir",
    detailsLabel: String = "Detalle (opcional)",
    detailsPlaceholder: String = "Describe el problema con más detalle...",
    requiresDetailsReason: String = "Otro",
    minDetailsLength: Int = 8,
    maxDetailsLength: Int = 280,
    attachEvidenceEnabled: Boolean = false,
    attachEvidenceLabel: String = "Adjuntar evidencia",
    attachedEvidenceLabel: String = "Imagen adjunta",
    removeEvidenceLabel: String = "Quitar",
    submitButtonLabel: String = "Enviar reporte",
    dismissButtonLabel: String = "Cancelar",
    inlineSuccessConfirmation: Boolean = false,
    successTitle: String = "Reporte enviado",
    successMessage: String = "Gracias por informarnos. Revisaremos este reporte y tomaremos medidas si corresponde.",
    successButtonLabel: String = "Confirmar"
) {
    if (alreadyReported) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(alreadyReportedTitle) },
            text = { Text(alreadyReportedMessage) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cerrar")
                }
            }
        )
        return
    }

    var selectedReason by remember(reasonOptions) { mutableStateOf<String?>(null) }
    var reasonExpanded by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf("") }
    var evidenceUri by remember { mutableStateOf<Uri?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }

    val trimmedReason = selectedReason?.trim().orEmpty()
    val trimmedDetails = details.trim()
    val requiresDetails = selectedReason == requiresDetailsReason
    val canSubmit = trimmedReason.isNotBlank() &&
        (!requiresDetails || trimmedDetails.length >= minDetailsLength) &&
        !isSubmitting

    val imagePicker = conectate.com.presentation.components.rememberInAppMediaPicker() { uri -> evidenceUri = uri }

    Dialog(
        onDismissRequest = if (showConfirmation) ({}) else onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = !showConfirmation
        )
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            if (showConfirmation) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = successTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = successMessage,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(successButtonLabel)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar")
                        }
                    }

                    subjectText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    OutlinedTextField(
                        value = selectedReason.orEmpty(),
                        onValueChange = {},
                        label = { Text(reasonLabel) },
                        placeholder = { Text(reasonPlaceholder) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = true,
                        trailingIcon = {
                            TextButton(onClick = { reasonExpanded = true }) {
                                Text(reasonActionLabel)
                            }
                        },
                        supportingText = {
                            Text(
                                if (requiresDetails) {
                                    "Añade más contexto para que el reporte se revise mejor."
                                } else {
                                    "Elige el motivo más cercano a lo que ocurrió."
                                }
                            )
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

                    OutlinedTextField(
                        value = details,
                        onValueChange = {
                            if (it.length <= maxDetailsLength) {
                                details = it
                            }
                        },
                        label = { Text(detailsLabel) },
                        placeholder = { Text(detailsPlaceholder) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 5,
                        isError = requiresDetails && details.isNotEmpty() && trimmedDetails.length < minDetailsLength,
                        supportingText = {
                            Text(
                                if (requiresDetails) {
                                    "Describe el problema con al menos $minDetailsLength caracteres. ${details.length}/$maxDetailsLength"
                                } else {
                                    "${details.length}/$maxDetailsLength"
                                }
                            )
                        }
                    )

                    if (attachEvidenceEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                                Icon(Icons.Default.AttachFile, contentDescription = null)
                                Spacer(Modifier.size(4.dp))
                                Text(attachEvidenceLabel)
                            }

                            if (evidenceUri != null) {
                                Text(
                                    text = attachedEvidenceLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                TextButton(onClick = { evidenceUri = null }) {
                                    Text(removeEvidenceLabel)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (!canSubmit) return@Button
                            isSubmitting = true
                            onSubmit(trimmedReason, trimmedDetails.ifBlank { null }, evidenceUri)
                            isSubmitting = false
                            if (inlineSuccessConfirmation) {
                                showConfirmation = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canSubmit,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onError,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(submitButtonLabel)
                        }
                    }

                    if (!inlineSuccessConfirmation) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(dismissButtonLabel)
                        }
                    }
                }
            }
        }
    }
}
