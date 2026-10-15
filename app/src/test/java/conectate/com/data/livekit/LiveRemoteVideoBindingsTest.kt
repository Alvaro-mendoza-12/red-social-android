package conectate.com.data.livekit

import org.junit.Assert.*
import org.junit.Test

class LiveRemoteVideoBindingsTest {
    @Test fun newSubscriptionWithTheSameSidReplacesItsNativeBinding() {
        val bindings=LiveRemoteVideoBindings()
        val old=bindings.version("camera")
        bindings.subscribed("camera")
        assertTrue(bindings.version("camera")>old)
    }
    @Test fun oneNewGuestDoesNotRecreateOtherRenderers() {
        val bindings=LiveRemoteVideoBindings()
        val host=bindings.version("host")
        bindings.subscribed("guest")
        assertEquals(host,bindings.version("host"))
    }
    @Test fun reconnectReplacesEachRetainedTrackBinding() {
        val bindings=LiveRemoteVideoBindings()
        val first=bindings.version("first");val second=bindings.version("second")
        bindings.reconnected(listOf("first","second"))
        assertTrue(bindings.version("first")>first)
        assertTrue(bindings.version("second")>second)
    }
    @Test fun removalAndDisconnectDoNotReuseAnOldRendererVersion() {
        val bindings=LiveRemoteVideoBindings()
        val first=bindings.version("camera")
        bindings.retain(emptySet())
        assertTrue(bindings.version("camera")>first)
        val second=bindings.version("camera")
        bindings.clear()
        assertTrue(bindings.version("camera")>second)
        assertEquals(0L,bindings.version(""))
    }
}
