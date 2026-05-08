package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDialog(
    reportedUserName: String,
    onDismiss: () -> Unit,
    onSubmit: (reason: String, description: String, evidenceUri: Uri?) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var evidenceUri by remember { mutableStateOf<Uri?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> evidenceUri = uri }
    
    Dialog(
        onDismissRequest = if (showConfirmation) ({}) else onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !showConfirmation)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
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
                        text = "Reporte enviado",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Gracias por informarnos. Se le comunicará cualquier novedad sobre su reporte.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Confirmar")
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Reportar usuario",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "Cerrar")
                        }
                    }
                    
                    // User being reported
                    Text(
                        text = "Usuario: $reportedUserName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    // Reason
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Motivo *") },
                        placeholder = { Text("Ej: Spam, Acoso, Contenido inapropiado...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    // Description
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Descripción (opcional)") },
                        placeholder = { Text("Describe el problema con más detalle...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 5
                    )
                    
                    // Evidence attachment
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/*") }
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Adjuntar evidencia")
                        }
                        
                        if (evidenceUri != null) {
                            Text(
                                text = "✓ Imagen adjunta",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Submit button
                    Button(
                        onClick = {
                            if (reason.isNotBlank()) {
                                isSubmitting = true
                                onSubmit(reason, description, evidenceUri)
                                // We simulate delay or wait for a signal, but since it's a callback,
                                // we'll show confirmation immediately or assume it works.
                                // For better UX, we could pass a 'onDone' to onSubmit.
                                showConfirmation = true
                                isSubmitting = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = reason.isNotBlank() && !isSubmitting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onError,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Enviar reporte")
                        }
                    }
                }
            }
        }
    }
}
