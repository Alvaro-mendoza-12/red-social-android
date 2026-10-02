package conectate.com.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class CallEndSignalTargetsTest {
    @Test fun `callee can end before remote participant arrives`() {
        assertEquals(listOf("caller"), callEndSignalTargets("callee", emptyList(), listOf("callee"), "caller", "caller"))
    }

    @Test fun `normalizes targets and excludes self`() {
        assertEquals(listOf("peer", "guest"), callEndSignalTargets(" ME ", listOf("Peer", " ", "ME"), listOf("peer", "Guest"), "PEER", "me"))
    }

    @Test fun `caller cancels all pending invitations`() {
        assertEquals(listOf("a", "b"), callEndSignalTargets("caller", listOf("a", "b"), emptyList(), "a", "caller"))
    }
}
