package conectate.com.presentation.analytics

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class CreatorAnalyticsPeriodTest {
    @Test fun selectablePeriodsProduceBoundedServerRanges() {
        val now = Instant.parse("2026-09-24T12:00:00Z")
        CreatorAnalyticsPeriod.entries.forEach { period ->
            val range = period.rangeEndingAt(now)
            assertEquals(period.days, ChronoUnit.DAYS.between(Instant.parse(range.from), Instant.parse(range.to)))
            assertEquals(now, Instant.parse(range.to))
        }
    }
}
