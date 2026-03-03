package conectate.com.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DateUtils {
    fun formatRelativeDate(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrBlank()) return "Recientemente"
        
        return try {
            val instant = Instant.parse(isoTimestamp)
            val now = Instant.now()
            val duration = Duration.between(instant, now)
            
            when {
                duration.isNegative -> "Recientemente"
                duration.toMinutes() < 1 -> "Ahora mismo"
                duration.toMinutes() < 60 -> "${duration.toMinutes()}m"
                duration.toHours() < 24 -> "${duration.toHours()}h"
                duration.toDays() == 1L -> "Ayer"
                duration.toDays() < 7 -> "${duration.toDays()}d"
                else -> {
                    val dt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                    dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                }
            }
        } catch (e: Exception) {
            "Hace un momento"
        }
    }
}
