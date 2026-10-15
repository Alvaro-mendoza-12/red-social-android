package conectate.com.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRouteEncodingTest {
    @Test
    fun `safe ids keep host and viewer routes unchanged`() {
        assertEquals("live_host/live-123", Screen.LiveHost.createRoute("live-123"))
        assertEquals("live_viewer/live-123", Screen.LiveViewer.createRoute("live-123"))
    }

    @Test
    fun `reserved characters stay within one path segment`() {
        val id = "live/one?room=1#frag% x+"
        val encoded = "live%2Fone%3Froom%3D1%23frag%25%20x%2B"
        assertEquals("live_host/$encoded", Screen.LiveHost.createRoute(id))
        assertEquals("live_viewer/$encoded", Screen.LiveViewer.createRoute(id))
    }

    @Test
    fun `unicode ids use utf8 percent encoding`() {
        val encoded = "Per%C3%BA%F0%9F%8E%A5"
        assertEquals("live_host/$encoded", Screen.LiveHost.createRoute("Perú🎥"))
        assertEquals("live_viewer/$encoded", Screen.LiveViewer.createRoute("Perú🎥"))
    }
}
