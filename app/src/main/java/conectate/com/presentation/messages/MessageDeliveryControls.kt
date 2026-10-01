package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun MessageDeliveryControls(status: String, uploadProgress: Float?, onRetry: () -> Unit) {
    if (status.lowercase() !in setOf("failed", "sending", "pending")) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("message_delivery")) {
        if (uploadProgress != null) {
            val fraction = uploadProgress.coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                if (fraction >= 1f) "Procesando archivo…" else "Subiendo ${(fraction * 100).roundToInt()} %",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (status == "failed") "No se envió" else "Pendiente de envío",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reintentar") }
            }
        }
    }
}
