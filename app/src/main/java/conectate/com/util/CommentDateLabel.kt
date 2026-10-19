package conectate.com.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Keep stored UTC timestamps intact; only their presentation follows the device. */
fun localCommentDateLabel(raw: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val value=raw?.trim().orEmpty()
    val format=DateTimeFormatter.ofPattern("d MMM yyyy",locale)
    if(Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value))
        return runCatching { LocalDate.parse(value).format(format) }.getOrDefault("Fecha no disponible")
    var normalized=value.replace(' ','T')
    if(Regex(".*[+-]\\d{2}$").matches(normalized))normalized+=":00"
    val instant=runCatching { Instant.parse(normalized) }.getOrNull()
        ?:runCatching { OffsetDateTime.parse(normalized).toInstant() }.getOrNull()
        ?:runCatching { LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?:return "Fecha no disponible"
    return instant.atZone(zone).format(format)
}
