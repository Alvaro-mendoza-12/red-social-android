package conectate.com.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveStartedRouteTest {
    @Test
    fun `live started notification always opens viewer route`() {
        assertEquals("live_viewer/live-123", resolveLiveStartedRoute(" live-123 "))
    }

    @Test
    fun `blank live started notification falls back safely`() {
        assertNull(resolveLiveStartedRoute("   "))
        assertNull(resolveLiveStartedRoute(null))
    }
}
