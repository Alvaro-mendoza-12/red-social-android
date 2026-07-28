package conectate.com.data.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHandoffPolicyTest {

    @Test
    fun `same default network does not restart media`() {
        assertFalse(
            NetworkHandoffPolicy.shouldRecover(
                previousNetworkHandle = 10L,
                currentNetworkHandle = 10L,
                hasActivePeers = true
            )
        )
    }

    @Test
    fun `wifi to mobile handoff restarts active media`() {
        assertTrue(
            NetworkHandoffPolicy.shouldRecover(
                previousNetworkHandle = 10L,
                currentNetworkHandle = 20L,
                hasActivePeers = true
            )
        )
    }

    @Test
    fun `restored network restarts peers but idle app stays quiet`() {
        assertTrue(
            NetworkHandoffPolicy.shouldRecover(
                previousNetworkHandle = null,
                currentNetworkHandle = 20L,
                hasActivePeers = true
            )
        )
        assertFalse(
            NetworkHandoffPolicy.shouldRecover(
                previousNetworkHandle = null,
                currentNetworkHandle = 20L,
                hasActivePeers = false
            )
        )
    }
}
