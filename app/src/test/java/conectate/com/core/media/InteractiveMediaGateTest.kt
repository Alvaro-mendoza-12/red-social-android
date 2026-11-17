package conectate.com.core.media

import org.junit.Assert.*
import org.junit.Test

class InteractiveMediaGateTest {
    @Test fun `a call excludes live and voice until the call releases its media`() {
        val gate = InteractiveMediaGate()
        assertTrue(gate.acquireExclusive(InteractiveMediaKind.CALL))
        assertFalse(gate.acquireExclusive(InteractiveMediaKind.CALL))
        assertFalse(gate.acquire(InteractiveMediaKind.LIVE))
        assertFalse(gate.acquire(InteractiveMediaKind.VOICE_ROOM))
        gate.withOwner(InteractiveMediaKind.VOICE_ROOM) { fail("A voice control must leave call audio alone") }
        gate.release(InteractiveMediaKind.VOICE_ROOM) { fail("A voice cleanup must leave call audio alone") }
        assertTrue(gate.conflictMessage().contains("llamada activa"))
        gate.release(InteractiveMediaKind.CALL) {
            assertFalse(gate.acquire(InteractiveMediaKind.VOICE_ROOM))
        }
        assertTrue(gate.acquire(InteractiveMediaKind.VOICE_ROOM))
        assertFalse(gate.acquireExclusive(InteractiveMediaKind.CALL))
        gate.release(InteractiveMediaKind.VOICE_ROOM)
        assertTrue(gate.acquire(InteractiveMediaKind.LIVE))
        assertFalse(gate.acquireExclusive(InteractiveMediaKind.CALL))
    }

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
