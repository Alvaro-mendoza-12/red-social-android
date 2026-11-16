package conectate.com.presentation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.Message
import conectate.com.data.model.parseMessageInstant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DateSeparator(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

fun shouldShowDateSeparator(messages: List<Message>, index: Int): Boolean {
    val message = messages.getOrNull(index) ?: return false
    val currentDate = extractLocalDate(message.created_at) ?: return false
    // A missing or malformed timestamp is not a new day. Compare with the
    // last known date so a legacy row cannot split an ongoing conversation.
    for (previousIndex in index - 1 downTo 0) {
        val previousDate = extractLocalDate(messages[previousIndex].created_at) ?: continue
        return currentDate != previousDate
    }
    return true
}

fun shouldShowDateSeparator(currentIso: String?, previousIso: String?): Boolean {
    val currentDate = extractLocalDate(currentIso) ?: return false
    return currentDate != extractLocalDate(previousIso)
}

fun formatDateSeparatorLabel(iso: String?): String {
    val date = extractLocalDate(iso) ?: return "Sin fecha"
    val today = LocalDate.now()
    return when (date) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es")))
    }
}

private fun extractLocalDate(rawIso: String?): LocalDate? {
    if (rawIso.isNullOrBlank()) return null
    // Match timeline ordering and the bubble clock, including PostgreSQL
    // offsets and legacy UTC dates. The displayed day belongs to the device.
    return runCatching {
        parseMessageInstant(rawIso)?.atZone(ZoneId.systemDefault())?.toLocalDate()
    }.getOrNull()
}

