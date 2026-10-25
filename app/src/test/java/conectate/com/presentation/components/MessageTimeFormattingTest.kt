package conectate.com.presentation.components

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTimeFormattingTest {
    @Test fun `bubble clocks agree for ISO PostgreSQL and legacy UTC timestamps`() {
        val zone = ZoneId.of("America/Bogota")
        listOf(
            "2026-10-24T00:23:40Z", "2026-10-24T00:23:40+00:00",
            "2026-10-23T19:23:40-05:00", "2026-10-24T05:53:40+05:30",
            "2026-10-24 00:23:40+00", "2026-10-23 19:23:40-05",
            "2026-10-24T00:23:40+0000", "2026-10-23T19:23:40-0500",
            "2026-10-24 00:23:40",
        ).forEach { timestamp -> assertEquals(timestamp, "19:23", formatIsoTimestamp(timestamp, zone)) }
    }

    @Test fun `changing display zone never changes the original message instant`() {
        val timestamp = "2026-10-23T19:23:40-05:00"
        assertEquals("00:23", formatIsoTimestamp(timestamp, ZoneId.of("UTC")))
        assertEquals("05:53", formatIsoTimestamp(timestamp, ZoneId.of("Asia/Kolkata")))
        assertEquals("19:23", formatIsoTimestamp(timestamp, ZoneId.of("America/Bogota")))
    }
}
