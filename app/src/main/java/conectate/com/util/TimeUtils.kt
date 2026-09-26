package conectate.com.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.temporal.ChronoUnit

object TimeUtils {
    fun getRelativeTime(timestampMillis: Long): String {
        val elapsedMillis = (System.currentTimeMillis() - timestampMillis).coerceAtLeast(0L)
        val minutes = elapsedMillis / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L

        return when {
            elapsedMillis < 60_000L -> "hace ${elapsedMillis / 1000L} s"
            minutes < 60 -> "hace $minutes min"
            hours < 24 -> "hace $hours h"
            days < 7 -> "hace $days d"
            else -> formatTimestamp(Instant.ofEpochMilli(timestampMillis).toString())
        }
    }

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
            val lastSeenInstant = parseFlexibleInstant(isoTimestamp)
            
            var now = Instant.now()
            val duration = Duration.between(lastSeenInstant, now)
            var minutes = duration.toMinutes()

            if (minutes < 60) {
                if (minutes <= 0) {
                    val seconds = Duration.between(lastSeenInstant, now).seconds.coerceAtLeast(0)
                    "últ. vez hace $seconds segundos"
                } else {
                    "últ. vez hace $minutes ${if (minutes == 1L) "minuto" else "minutos"}"
                }
            } else {
                val zonedDateTime = lastSeenInstant.atZone(ZoneId.systemDefault())
                val today = java.time.LocalDate.now(ZoneId.systemDefault())
                var lastSeenDate = zonedDateTime.toLocalDate()

                val timeFormatter = DateTimeFormatter.ofPattern(
                    "h:mm a",
                    Locale.forLanguageTag("es-ES")
                )
                val timeString = zonedDateTime.format(timeFormatter)
                    .replace("AM", "a. m.")
                    .replace("PM", "p. m.")
                    .lowercase()

                if (lastSeenDate == today) {
                    "últ. vez hoy a la(s) $timeString"
                } else {
                    var day = zonedDateTime.dayOfMonth
                    var month = zonedDateTime.monthValue
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
     * Returns true if the lastSeen timestamp is within the recent threshold.
     * Useful to avoid showing "Conectado" when presence data is stale.
     */
    fun isRecentlyOnline(lastSeenIso: String?, thresholdSeconds: Long = 75): Boolean {
        if (lastSeenIso.isNullOrBlank()) return false
        return try {
            val lastSeenInstant = parseFlexibleInstant(lastSeenIso)
            var now = Instant.now()
            val diffSeconds = Duration.between(lastSeenInstant, now).seconds
            diffSeconds in 0..thresholdSeconds
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Formatea un timestamp ISO a formato legible
     * Ejemplo: "22 Ene, 19:35"
     */
    fun formatTimestamp(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrEmpty()) return ""
        
        return try {
            val instant = parseFlexibleInstant(isoTimestamp)
            val zoned = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
            
            val months = arrayOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", 
                               "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")
            
            var day = zoned.dayOfMonth
            var month = months[zoned.monthValue - 1]
            val hour = zoned.hour.toString().padStart(2, '0')
            val minute = zoned.minute.toString().padStart(2, '0')
            
            "$day $month, $hour:$minute"
        } catch (e: Exception) {
            android.util.Log.e("TimeUtils", "Error formatting timestamp: $isoTimestamp", e)
            ""
        }
    }

    private fun parseFlexibleInstant(rawTimestamp: String): Instant {
        val clean = normalizeIsoTimestamp(rawTimestamp)
        return runCatching { Instant.parse(clean) }.getOrElse {
            runCatching { java.time.OffsetDateTime.parse(clean, DateTimeFormatter.ISO_DATE_TIME).toInstant() }
                .getOrElse {
                    java.time.LocalDateTime.parse(clean, DateTimeFormatter.ISO_DATE_TIME)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                }
        }
    }

    private fun normalizeIsoTimestamp(rawTimestamp: String): String {
        var clean = rawTimestamp.trim().replace(" ", "T")
        if (clean.endsWith("+00", ignoreCase = true)) {
            clean = clean.dropLast(3) + "Z"
        } else if (Regex("[+-]\\d{2}$").containsMatchIn(clean)) {
            clean += ":00"
        }
        return clean
    }
}



