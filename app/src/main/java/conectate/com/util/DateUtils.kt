package conectate.com.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DateUtils {
    fun formatRelativeDate(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrBlank()) return "sin fecha"
        
        return try {
            val instant = parseInstant(isoTimestamp)
            val now = Instant.now()
            val duration = Duration.between(instant, now).takeIf { !it.isNegative } ?: Duration.ZERO
            
            when {
                duration.seconds < 60 -> "hace ${duration.seconds} s"
                duration.toMinutes() < 60 -> "hace ${duration.toMinutes()} min"
                duration.toHours() < 24 -> "hace ${duration.toHours()} h"
                duration.toDays() == 1L -> "ayer"
                duration.toDays() < 7 -> "hace ${duration.toDays()} d"
                else -> {
                    val dt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                    dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                }
            }
        } catch (e: Exception) {
            "sin fecha"
        }
    }

    private fun parseInstant(raw: String): Instant {
        val clean = raw.trim()
        return runCatching { Instant.parse(clean) }.getOrElse {
            runCatching { OffsetDateTime.parse(clean).toInstant() }.getOrElse {
                LocalDateTime.parse(clean.replace(" ", "T"))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            }
        }
    }
}


