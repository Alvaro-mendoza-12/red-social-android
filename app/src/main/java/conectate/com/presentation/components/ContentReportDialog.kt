package conectate.com.presentation.components

import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
    reportKey: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (reason: String, details: String?, evidenceUri: Uri?) -> Result<Unit>,
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
    val account = LocalProfileIdentityAccount.current
    val ownerAtOpen = remember(reportKey) { account }
    val currentAccount by rememberUpdatedState(account)
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(account, ownerAtOpen) {
        if (account.isNullOrBlank() || account != ownerAtOpen) latestDismiss()
    }
    if (account.isNullOrBlank() || account != ownerAtOpen) return

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

    var selectedReason by rememberSaveable(reportKey, ownerAtOpen) { mutableStateOf<String?>(null) }
    var reasonExpanded by remember(reportKey) { mutableStateOf(false) }
    var details by rememberSaveable(reportKey, ownerAtOpen) { mutableStateOf("") }
    var evidencePath by rememberSaveable(reportKey, ownerAtOpen) { mutableStateOf<String?>(null) }
    val evidenceUri = evidencePath?.let(Uri::parse)
    val scope = rememberCoroutineScope()
    val submission = remember(reportKey, ownerAtOpen, scope) {
        ReportSubmission(scope, isOwnerCurrent = { currentAccount == ownerAtOpen })
    }
    val state by submission.state.collectAsState()
    val isSubmitting = state.isSubmitting
    val showConfirmation = state.confirmed
    LaunchedEffect(showConfirmation) {
        if (showConfirmation && !inlineSuccessConfirmation) {
            conectate.com.presentation.common.AppFeedbackBus.showSuccess(successTitle)
            latestDismiss()
        }
    }

    val trimmedReason = selectedReason?.trim().orEmpty()
    val trimmedDetails = details.trim()
    val requiresDetails = selectedReason == requiresDetailsReason
    val canSubmit = trimmedReason.isNotBlank() &&
        (!requiresDetails || trimmedDetails.length >= minDetailsLength) &&
        !isSubmitting

    val imagePicker = rememberInAppMediaPicker { uri -> if (uri != null) evidencePath = uri.toString() }

    Dialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = !isSubmitting,
            dismissOnBackPress = !isSubmitting,
        )
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth(0.95f)
                .imePadding()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            if (showConfirmation) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
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
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDismiss, enabled = !isSubmitting) {
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
                        enabled = !isSubmitting,
                        trailingIcon = {
                            TextButton(onClick = { reasonExpanded = true }, enabled = !isSubmitting) {
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
                            .heightIn(min = 120.dp),
                        enabled = !isSubmitting,
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
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            OutlinedButton(onClick = { imagePicker.launch("image/*") }, enabled = !isSubmitting) {
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
                                TextButton(onClick = { evidencePath = null }, enabled = !isSubmitting) {
                                    Text(removeEvidenceLabel)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    state.error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }

                    Button(
                        onClick = {
                            if (!canSubmit) return@Button
                            submission.submit {
                                onSubmit(trimmedReason, trimmedDetails.ifBlank { null }, evidenceUri)
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
                            enabled = !isSubmitting,
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
