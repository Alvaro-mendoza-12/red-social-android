package conectate.com.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.temporal.ChronoUnit

object TimeUtils {
    /**
     * Convierte un timestamp a formato de última vez en español
     * Ejemplos:
     * - "últ. vez hoy a la(s) 7:26 p. m."
     * - "últ. vez el 6/1/2026 a la(s) 1:50 a. m."
     */
    fun getRelativeTimeSpanString(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrEmpty() || isoTimestamp == "null") {
            return "Desconectado" 
        }

        return try {
            val cleaned = isoTimestamp.replace(" ", "T")
            val lastSeenInstant = try {
                java.time.OffsetDateTime.parse(cleaned, DateTimeFormatter.ISO_DATE_TIME).toInstant()
            } catch (e: Exception) {
                java.time.LocalDateTime.parse(cleaned, DateTimeFormatter.ISO_DATE_TIME)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            }
            
            val now = Instant.now()
            val duration = Duration.between(lastSeenInstant, now)
            val minutes = duration.toMinutes()

            if (minutes < 60) {
                if (minutes <= 0) {
                    "últ. vez hace un momento"
                } else {
                    "últ. vez hace $minutes ${if (minutes == 1L) "minuto" else "minutos"}"
                }
            } else {
                val zonedDateTime = lastSeenInstant.atZone(ZoneId.systemDefault())
                val today = java.time.LocalDate.now(ZoneId.systemDefault())
                val lastSeenDate = zonedDateTime.toLocalDate()

                val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale("es", "ES"))
                val timeString = zonedDateTime.format(timeFormatter)
                    .replace("AM", "a. m.")
                    .replace("PM", "p. m.")
                    .lowercase()

                if (lastSeenDate == today) {
                    "últ. vez hoy a la(s) $timeString"
                } else {
                    val day = zonedDateTime.dayOfMonth
                    val month = zonedDateTime.monthValue
                    val year = zonedDateTime.year
                    "últ. vez el $day/$month/$year a la(s) $timeString"
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TimeUtils", "FAILED to parse: '$isoTimestamp'. Error: ${e.message}")
            "Desconectado"
        }
    }

    /**
     * Formatea un timestamp ISO a formato legible
     * Ejemplo: "22 Ene, 19:35"
     */
    fun formatTimestamp(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrEmpty()) return ""
        
        return try {
            val instant = Instant.parse(isoTimestamp)
            val zoned = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
            
            val months = arrayOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", 
                               "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")
            
            val day = zoned.dayOfMonth
            val month = months[zoned.monthValue - 1]
            val hour = zoned.hour.toString().padStart(2, '0')
            val minute = zoned.minute.toString().padStart(2, '0')
            
            "$day $month, $hour:$minute"
        } catch (e: Exception) {
            android.util.Log.e("TimeUtils", "Error formatting timestamp: $isoTimestamp", e)
            ""
        }
    }
}
