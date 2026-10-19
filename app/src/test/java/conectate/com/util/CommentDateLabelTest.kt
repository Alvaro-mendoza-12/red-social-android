package conectate.com.util

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CommentDateLabelTest {
    @Test fun utcTomorrowAppearsAsTodayInTheDeviceZone() {
        val raw="2026-10-19T02:09:00Z";val locale=Locale.forLanguageTag("es-PE")
        assertTrue(localCommentDateLabel(raw,ZoneId.of("America/Lima"),locale).startsWith("18 "))
        assertTrue(localCommentDateLabel(raw,ZoneId.of("UTC"),locale).startsWith("19 "))
        assertTrue(localCommentDateLabel("2026-10-19 02:09:00+00",ZoneId.of("America/Bogota"),locale).startsWith("18 "))
    }
    @Test fun dateOnlyValuesDoNotShiftAndInvalidValuesDoNotLeakRawData() {
        assertTrue(localCommentDateLabel("2026-10-19",ZoneId.of("America/Lima")).startsWith("19 "))
        assertEquals("Fecha no disponible",localCommentDateLabel("invalid"))
    }
}
