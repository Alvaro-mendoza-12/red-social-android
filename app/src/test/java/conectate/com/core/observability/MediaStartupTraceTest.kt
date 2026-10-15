package conectate.com.core.observability

import org.junit.Assert.*
import org.junit.Test

class MediaStartupTraceTest {
    @Test fun recordsOnlyTheFirstOccurrenceAndResetsBetweenSessions() {
        var now = 1_000_000L
        val trace = MediaStartupTrace { now }
        trace.mark("ignored")
        assertTrue(trace.snapshots.value.isEmpty())
        trace.start()
        now += 3_000_000L
        trace.mark("ready")
        now += 5_000_000L
        trace.mark("ready")
        assertEquals(3L, trace.snapshots.value["ready"])
        trace.start()
        assertTrue(trace.snapshots.value.isEmpty())
    }
}
