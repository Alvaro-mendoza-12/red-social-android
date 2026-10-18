package conectate.com.core.media

import org.junit.Assert.*
import org.junit.Test

class InteractiveMediaGateTest {
    @Test fun `direct and voice cannot own audio at the same time`() {
        val gate = InteractiveMediaGate()
        assertTrue(gate.acquire(InteractiveMediaKind.LIVE))
        assertFalse(gate.acquire(InteractiveMediaKind.VOICE_ROOM))
        assertTrue(gate.conflictMessage().contains("directo activo"))
        gate.release(InteractiveMediaKind.LIVE)
        assertTrue(gate.acquire(InteractiveMediaKind.VOICE_ROOM))
        assertFalse(gate.acquire(InteractiveMediaKind.LIVE))
    }
    @Test fun `navigation and stale cleanup cannot release another kind of session`() {
        val gate = InteractiveMediaGate()
        assertTrue(gate.acquire(InteractiveMediaKind.LIVE))
        assertTrue(gate.acquire(InteractiveMediaKind.LIVE))
        gate.release(InteractiveMediaKind.VOICE_ROOM)
        assertFalse(gate.canEnter(InteractiveMediaKind.VOICE_ROOM))
        gate.release(InteractiveMediaKind.LIVE)
        assertTrue(gate.canEnter(InteractiveMediaKind.VOICE_ROOM))
    }
}
