package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun MessageDeliveryControls(status: String, uploadProgress: Float?, onRetry: () -> Unit) {
    val state = messageDeliveryState(status, uploadProgress)
    if (state == MessageDeliveryState.COMPLETE) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("message_delivery")) {
        if (state == MessageDeliveryState.UPLOADING) {
            val fraction = uploadProgress!!.coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                if (fraction >= 1f) "Procesando archivo…" else "Subiendo ${(fraction * 100).roundToInt()} %",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            // A row with a weighted label and a wide button crushes narrow messages.
            Text(
                if (state == MessageDeliveryState.FAILED) "No se envió" else "Esperando envío",
                style = MaterialTheme.typography.labelMedium,
                color = if (state == MessageDeliveryState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state == MessageDeliveryState.FAILED) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reintentar") }
            }
        }
    }
}

/** Compact controls over the artwork. Waiting is represented by the timestamp's clock. */
@Composable
fun StickerDeliveryOverlay(status: String, uploadProgress: Float?, onRetry: () -> Unit) {
    val state = messageDeliveryState(status, uploadProgress)
    if (state != MessageDeliveryState.FAILED && state != MessageDeliveryState.UPLOADING) return
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.78f)) {
        if (state == MessageDeliveryState.FAILED) {
            IconButton(onClick = onRetry, modifier = Modifier.size(48.dp).testTag("sticker_retry")) {
                Icon(Icons.Default.Refresh, "Reintentar envío de sticker", tint = Color.White)
            }
        } else {
            Box(Modifier.size(48.dp).testTag("sticker_upload"), contentAlignment = Alignment.Center) {
                val fraction = uploadProgress!!.coerceIn(0f, 1f)
                if (fraction >= 1f) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), color = Color.White, strokeWidth = 3.dp)
                } else {
                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(30.dp), color = Color.White, strokeWidth = 3.dp)
                }
            }
        }
    }
}
