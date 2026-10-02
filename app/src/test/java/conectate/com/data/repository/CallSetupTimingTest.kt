package conectate.com.data.repository

import org.junit.Assert.*
import org.junit.Test

class CallSetupTimingTest {
    @Test fun elapsedConnectionTimeUsesTheSuppliedMonotonicClock() {
        assertEquals(275L, CallSetupTiming(1_000L, CallSetupOrigin.INCOMING).elapsedMs(1_275L))
    }

    @Test fun staleClockValuesCannotReportNegativeConnectionTime() {
        assertEquals(0L, CallSetupTiming(1_000L, CallSetupOrigin.INCOMING).elapsedMs(999L))
    }

    @Test fun onlyOutgoingMeasurementsIncludeWaitingForAnAnswer() {
        assertTrue(CallSetupOrigin.OUTGOING.includesRinging)
        assertFalse(CallSetupOrigin.INCOMING.includesRinging)
        assertFalse(CallSetupOrigin.JOINING.includesRinging)
    }
}
