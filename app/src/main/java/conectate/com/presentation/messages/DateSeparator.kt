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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.Message
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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
    if (index <= 0) return true
    val currentDate = extractLocalDate(messages.getOrNull(index)?.created_at)
    val previousDate = extractLocalDate(messages.getOrNull(index - 1)?.created_at)
    return currentDate != previousDate
}

fun shouldShowDateSeparator(currentIso: String?, previousIso: String?): Boolean {
    return extractLocalDate(currentIso) != extractLocalDate(previousIso)
}

fun formatDateSeparatorLabel(iso: String?): String {
    val date = extractLocalDate(iso) ?: return "Sin fecha"
    val today = LocalDate.now()
    return when (date) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es")))
    }
}

private fun extractLocalDate(rawIso: String?): LocalDate? {
    if (rawIso.isNullOrBlank()) return null
    val clean = rawIso.trim().replace(" ", "T")
    return runCatching {
        Instant.parse(
            if (clean.contains("Z") || clean.contains("+")) clean else "${clean}Z"
        ).atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrElse {
        runCatching { LocalDateTime.parse(clean.removeSuffix("Z")).toLocalDate() }.getOrNull()
    }
}

