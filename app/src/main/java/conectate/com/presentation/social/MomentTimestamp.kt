package conectate.com.presentation.social

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Publication time, never the time at which the client fetched/highlighted it. */
internal fun formatMomentTimestamp(
    createdAt: String?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val raw = createdAt?.trim()?.replace(' ', 'T') ?: return "Fecha no disponible"
    val normalized = raw.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        .replace(Regex("([+-]\\d{2})$"), "$1:00")
    val instant = runCatching { OffsetDateTime.parse(normalized).toInstant() }.getOrNull()
        ?: return "Fecha no disponible"
    val seconds = Duration.between(instant, now).seconds
    val local = instant.atZone(zone)
    val time = local.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
    // Large clock skew must not mislabel a future date as a fresh publication.
    if (seconds >= 5 * 3_600 || seconds < -60) {
        val pattern = if (local.year == now.atZone(zone).year) "d MMM · HH:mm" else "d MMM yyyy · HH:mm"
        return local.format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("es")))
    }
    val age = when {
        seconds < 60 -> "Hace un momento"
        seconds < 3_600 -> "Hace ${seconds / 60} min"
        seconds < 7_200 -> "Hace 1 hora"
        else -> "Hace ${seconds / 3_600} horas"
    }
    return "$age · $time"
}
