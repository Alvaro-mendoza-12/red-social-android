package conectate.com.core.messaging

import conectate.com.data.model.parseMessageInstant
import java.time.LocalDate
import java.time.ZoneId

/** The same device-local calendar day for message headers and media albums. */
internal fun messageLocalDate(raw: String?, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        parseMessageInstant(raw)?.atZone(zoneId)?.toLocalDate()
    }.getOrNull()
}
