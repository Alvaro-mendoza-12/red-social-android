package conectate.com.presentation.social.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RoundRemainingSecondsTest {
    @Test fun missingAndMalformedDatesDoNotCrashTheScreen() {
        assertEquals(0, roundRemainingSeconds(null, 0))
        assertEquals(0, roundRemainingSeconds("fecha antigua inválida", 0))
    }
    @Test fun expiryAndTimezoneAreRespected() {
        assertEquals(60, roundRemainingSeconds("1970-01-01T00:01:00Z", 0))
        assertEquals(60, roundRemainingSeconds("1969-12-31T19:01:00-05:00", 0))
        assertEquals(0, roundRemainingSeconds("1970-01-01T00:01:00Z", 60_000))
    }
    @Test fun extremeExpiryDoesNotOverflowTheCountdown() {
        assertEquals(Int.MAX_VALUE, roundRemainingSeconds("9999-01-01T00:00:00Z", 0))
    }
}
