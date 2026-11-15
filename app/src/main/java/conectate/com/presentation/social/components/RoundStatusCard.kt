package conectate.com.presentation.social.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZonedDateTime

internal fun roundRemainingSeconds(expiresAt: String?, nowMillis: Long): Int {
    val expiry = expiresAt?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    } ?: return 0
    if (expiry <= nowMillis) return 0
    return ((expiry.toDouble() - nowMillis.toDouble()) / 1_000)
        .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
}

@Composable
fun RoundStatusCard(title: String, status: String, remainingSeconds: Int, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val phase = when (status) {
        "voting" -> "Votación"
        "completed" -> "Resultados"
        else -> "Participación"
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("round_status_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(phase, color = colors.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, modifier = Modifier.testTag("round_refresh")) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar ronda")
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("round_title"))
            if (remainingSeconds > 0) {
                Spacer(Modifier.height(8.dp))
                val minutes = remainingSeconds / 60
                val seconds = (remainingSeconds % 60).toString().padStart(2, '0')
                Text("$minutes:$seconds restantes", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}
