package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*

/**
 * Dialog to guide users on problematic devices to disable battery optimization
 * Shows only once per installation
 */
@Composable
fun BatteryOptimizationDialog(
    brandName: String,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mejora tus Notificaciones") },
        text = { 
            Text(
                "Para recibir notificaciones instantáneas en tu $brandName, " +
                "necesitas desactivar la optimización de batería.\n\n" +
                "Toca 'Abrir Configuración' y selecciona 'No optimizar'."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onOpenSettings()
                onDismiss()
            }) {
                Text("Abrir Configuración")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Más tarde")
            }
        }
    )
}
