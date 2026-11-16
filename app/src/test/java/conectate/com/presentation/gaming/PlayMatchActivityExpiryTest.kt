package conectate.com.presentation.gaming

import conectate.com.data.model.PlayMatchMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayMatchActivityExpiryTest {
    @Test fun expiredPendingMatchDoesNotOfferAnotherDecision() {
        val expired = PlayMatchMatch("expired", expiresAt = "2000-01-01T00:00:00Z")
        val future = PlayMatchMatch("future", expiresAt = "2999-01-01T00:00:00Z")
        assertEquals(listOf(future), playMatchActivity(emptyList(), listOf(expired, future)).decisions)
    }

    @Test fun malformedExpirationIsNotPresentedAsAnActionableMatch() {
        val malformed = PlayMatchMatch("invalid", expiresAt = "not-a-date")
        assertTrue(playMatchActivity(emptyList(), listOf(malformed)).decisions.isEmpty())
    }

    @Test fun futureMatchWithAnOffsetRemainsActionable() {
        val future = PlayMatchMatch("future", expiresAt = "2999-01-01T00:00:00-05:00")
        assertEquals(listOf(future), playMatchActivity(emptyList(), listOf(future)).decisions)
    }

    @Test fun olderPayloadWithoutExpirationRetainsItsExistingBehavior() {
        val legacy = PlayMatchMatch("legacy", expiresAt = null)
        assertEquals(listOf(legacy), playMatchActivity(emptyList(), listOf(legacy)).decisions)
    }

    @Test fun terminalMatchNeverReturnsToPendingActions() {
        val terminal = PlayMatchMatch("accepted", status = "MATCHED", expiresAt = "2999-01-01T00:00:00Z")
        assertTrue(playMatchActivity(emptyList(), listOf(terminal)).decisions.isEmpty())
    }
}
