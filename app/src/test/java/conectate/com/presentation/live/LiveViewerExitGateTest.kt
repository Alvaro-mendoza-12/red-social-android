package conectate.com.presentation.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveViewerExitGateTest {
    @Test
    fun `manual exit followed by dispose only cleans up once`() {
        val gate = LiveViewerExitGate()
        var cleanupCount = 0

        assertTrue(gate.runOnce { cleanupCount += 1 })
        assertFalse(gate.runOnce { cleanupCount += 1 })

        assertEquals(1, cleanupCount)
    }
}
