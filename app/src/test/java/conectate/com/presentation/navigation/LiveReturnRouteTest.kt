package conectate.com.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveReturnRouteTest {
    @Test
    fun `host notification returns to host route`() {
        assertEquals("live_host/live-123", resolveLiveReturnRoute(" live-123 ", "HOST"))
    }

    @Test
    fun `viewer notification returns to viewer route`() {
        assertEquals("live_viewer/live-123", resolveLiveReturnRoute("live-123", "viewer"))
    }

    @Test
    fun `invalid live notification is ignored`() {
        assertNull(resolveLiveReturnRoute("", "host"))
        assertNull(resolveLiveReturnRoute("live-123", "guest"))
    }
}
