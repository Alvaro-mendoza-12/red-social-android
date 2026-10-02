package conectate.com.data.repository

import org.junit.Assert.*
import org.junit.Test

class CallSignalingTransportPlanTest {
    @Test fun directAttemptAvoidsPollingHandshakeRoundTrips() {
        assertArrayEquals(arrayOf("websocket"), CallSignalingTransportPlan().transports)
        assertEquals(2_500L, CallSignalingTransportPlan().timeoutMs)
    }

    @Test fun restrictiveNetworksRetainPollingAndUpgrade() {
        assertArrayEquals(arrayOf("polling", "websocket"), CallSignalingTransportPlan(true).transports)
        assertEquals(10_000L, CallSignalingTransportPlan(true).timeoutMs)
    }

    @Test fun transportArraysCannotMutateFuturePlans() {
        val plan = CallSignalingTransportPlan()
        plan.transports[0] = "polling"
        assertArrayEquals(arrayOf("websocket"), plan.transports)
    }
}
