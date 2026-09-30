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
fun RoundStatusCard(title: String, status: String?, votingCountdown: Int, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        "voting" -> colors.secondaryContainer to colors.onSecondaryContainer
        "completed" -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.primaryContainer to colors.onPrimaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).testTag("round_status_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (status) {
                        "voting" -> "Votación de la ronda"
                        "completed" -> "Ronda finalizada"
                        else -> "Ronda actual"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh, modifier = Modifier.testTag("round_refresh")) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar ronda")
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("round_title"))
            if (status == "voting") {
                val seconds = votingCountdown.coerceAtLeast(0)
                Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} restantes",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}
