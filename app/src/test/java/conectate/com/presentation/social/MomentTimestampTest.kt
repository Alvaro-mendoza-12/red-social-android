package conectate.com.presentation.social

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MomentTimestampTest {
    private val published = "2026-09-17T12:34:00Z"
    private val instant = Instant.parse(published)
    private val zone = ZoneId.of("America/Bogota")
    private fun at(seconds: Long) = formatMomentTimestamp(published, instant.plusSeconds(seconds), zone)

    @Test fun `recent age includes exact local time and minute boundaries`() {
        assertEquals("Hace un momento · 07:34", at(0))
        assertEquals("Hace un momento · 07:34", at(59))
        assertEquals("Hace 1 min · 07:34", at(60))
        assertEquals("Hace 59 min · 07:34", at(3599))
        assertEquals("Hace 1 hora · 07:34", at(3600))
        assertEquals("Hace 2 horas · 07:34", at(7200))
        assertEquals("Hace 4 horas · 07:34", at(17999))
        assertEquals("17 sept · 07:34", at(18000))
    }

    @Test fun `ISO and postgres offsets describe the same instant`() {
        val now = instant.plusSeconds(90)
        for (date in listOf(published, "2026-09-17 07:34:00-05", "2026-09-17T07:34:00-0500",
            "2026-09-17T07:34:00-05:00", "2026-09-17T12:34:00.000000+00")) {
            assertEquals(date, "Hace 1 min · 07:34", formatMomentTimestamp(date, now, zone))
        }
    }

    @Test fun `missing invalid naive and rolled over dates do not masquerade as new`() {
        for (date in listOf(null, "", "not-a-date", "2026-02-30T12:00:00Z", "2026-09-17T07:34:00")) {
            assertEquals("Fecha no disponible", formatMomentTimestamp(date, instant, zone))
        }
    }

    @Test fun `year and calendar day use the viewing timezone`() {
        assertEquals("31 dic 2025 · 19:30", formatMomentTimestamp("2026-01-01T00:30:00Z",
            Instant.parse("2026-01-02T12:00:00Z"), zone))
    }

    @Test fun `small clock skew tolerated but future dates stay explicit`() {
        assertEquals("Hace un momento · 07:34", at(-30))
        assertFalse(at(-61).contains("Hace"))
        assertEquals("17 sept · 07:34", at(-3600))
    }
}
