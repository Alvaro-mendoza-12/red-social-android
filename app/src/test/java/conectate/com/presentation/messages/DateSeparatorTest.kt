package conectate.com.presentation.messages

import conectate.com.data.model.Message
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class DateSeparatorTest {
    private fun inZone(id: String = "America/Bogota", block: () -> Unit) {
        val previous = TimeZone.getDefault()
        try { TimeZone.setDefault(TimeZone.getTimeZone(id)); block() }
        finally { TimeZone.setDefault(previous) }
    }

    private fun messages(vararg dates: String?) = dates.mapIndexed { index, date ->
        Message(id = "date-$index", created_at = date)
    }

    @Test fun `incoming UTC after negative offset history does not split the same day`() = inZone {
        val history = messages("2026-11-15T18:07:00-05:00", "2026-11-15T23:10:00Z")
        assertFalse(shouldShowDateSeparator(history, 1))
        assertFalse(shouldShowDateSeparator(history[1].created_at, history[0].created_at))
    }

    @Test fun `negative offset messages still separate at actual local midnight`() = inZone {
        assertTrue(shouldShowDateSeparator("2026-11-16T00:02:00-05:00", "2026-11-15T23:58:00-05:00"))
    }

    @Test fun `PostgreSQL short offsets and UTC live messages share one date`() = inZone {
        assertFalse(shouldShowDateSeparator("2026-11-15T23:10:00Z", "2026-11-15 23:07:00+00"))
        assertFalse(shouldShowDateSeparator("2026-11-15T23:10:00Z", "2026-11-15 18:07:00-05"))
    }

    @Test fun `compact offset and UTC live messages share one date`() = inZone {
        assertFalse(shouldShowDateSeparator("2026-11-15T23:10:00Z", "2026-11-15T18:07:00-0500"))
        assertFalse(shouldShowDateSeparator("2026-11-15T23:10:00Z", "2026-11-15T23:07:00+0000"))
    }

    @Test fun `legacy epoch and UTC live messages share one date`() = inZone {
        val epoch = java.time.Instant.parse("2026-11-15T23:07:00Z").toEpochMilli().toString()
        assertFalse(shouldShowDateSeparator("2026-11-15T23:10:00Z", epoch))
    }

    @Test fun `undated row between valid messages never invents another day`() = inZone {
        val history = messages("2026-11-15T23:07:00Z", null, "2026-11-15T23:10:00Z")
        assertFalse(shouldShowDateSeparator(history, 1))
        assertFalse(shouldShowDateSeparator(history, 2))
    }

    @Test fun `malformed first row does not display an invented separator`() = inZone {
        assertFalse(shouldShowDateSeparator(messages("invalid"), 0))
    }

    @Test fun `out of bounds rows cannot create separators`() = inZone {
        assertFalse(shouldShowDateSeparator(emptyList(), 0))
        assertFalse(shouldShowDateSeparator(messages("2026-11-15T23:07:00Z"), -1))
        assertFalse(shouldShowDateSeparator(messages("2026-11-15T23:07:00Z"), 1))
    }

    @Test fun `normal UTC messages within a local day stay together`() = inZone {
        assertTrue(shouldShowDateSeparator(messages("2026-11-15T23:07:00Z"), 0))
        assertFalse(shouldShowDateSeparator("2026-11-15T23:10:00Z", "2026-11-15T23:07:00Z"))
    }

    @Test fun `UTC midnight does not split a conversation before local midnight`() = inZone {
        assertFalse(shouldShowDateSeparator("2026-11-16T00:01:00Z", "2026-11-15T23:59:00Z"))
    }

    @Test fun `a real local date boundary remains visible`() = inZone {
        assertTrue(shouldShowDateSeparator("2026-11-16T05:01:00Z", "2026-11-16T04:59:00Z"))
    }

    @Test fun `today and yesterday labels remain meaningful`() = inZone {
        val today = LocalDate.now(ZoneId.of("America/Bogota"))
        assertEquals("Hoy", formatDateSeparatorLabel("${today}T18:10:00Z"))
        assertEquals("Ayer", formatDateSeparatorLabel("${today.minusDays(1)}T18:10:00Z"))
        assertEquals("Sin fecha", formatDateSeparatorLabel("invalid"))
    }

    @Test fun `equivalent instants have the same today label regardless of wire offset`() = inZone {
        val midday = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime()
        listOf("-05:00", "+00:00", "+14:00").forEach { offset ->
            assertEquals("Hoy", formatDateSeparatorLabel(midday.withOffsetSameInstant(java.time.ZoneOffset.of(offset)).toString()))
        }
        assertEquals("Hoy", formatDateSeparatorLabel(midday.toInstant().toEpochMilli().toString()))
    }

    @Test fun `an invalid bridge still preserves a real change of local day`() = inZone {
        val history = messages("2026-11-16T04:59:00Z", "invalid", "2026-11-16T05:01:00Z")
        assertFalse(shouldShowDateSeparator(history, 1))
        assertTrue(shouldShowDateSeparator(history, 2))
        assertTrue(shouldShowDateSeparator(messages(null, history[2].created_at), 1))
    }

    @Test fun `instants outside calendar range fail safely without an invented label`() = inZone {
        val extreme = "+1000000000-12-31T23:59:59Z"
        assertFalse(shouldShowDateSeparator(messages(extreme), 0))
        assertEquals("Sin fecha", formatDateSeparatorLabel(extreme))
    }

    @Test fun `the display zone determines the actual day boundary east of UTC too`() = inZone("Asia/Kolkata") {
        assertFalse(shouldShowDateSeparator("2026-11-15T18:29:00Z", "2026-11-15T23:58:00+05:30"))
        assertTrue(shouldShowDateSeparator("2026-11-16T00:01:00+05:30", "2026-11-15T18:29:00Z"))
    }
}
