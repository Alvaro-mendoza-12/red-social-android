package conectate.com.data.signaling

import io.socket.client.IO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test

class SocketAuthConfigTest {

    @Test
    fun `affinity keeps all members of a room on the same signaling replica`() {
        val options = IO.Options()

        options.attachSignalingAffinity("voice", " room-1 ")

        assertEquals("affinity=voice%3Aroom-1", options.query)
    }

    @Test
    fun `affinity rejects invalid scope ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            IO.Options().attachSignalingAffinity("voice", " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IO.Options().attachSignalingAffinity("unknown", "room-1")
        }
    }

    @Test
    fun `all signaling namespaces share a polling-safe client`() {
        listOf("call", "live", "voice").forEach { scope ->
            val options = IO.Options()
            options.attachSignalingAffinity(scope, "test-room")
            assertSame(SignalingNetwork.client, options.callFactory)
            assertSame(SignalingNetwork.client, options.webSocketFactory)
        }
        assertEquals(60_000, SignalingNetwork.client.readTimeoutMillis)
        assertEquals(0, SignalingNetwork.client.callTimeoutMillis)
        assertEquals(16, SignalingNetwork.client.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun `transport changes preserve authentication and affinity`() {
        val options = IO.Options()
        options.attachBearerToken(" test-token ")
        options.attachSignalingAffinity("call", "test-call")
        options.transports = arrayOf("polling", "websocket")
        assertEquals(mapOf("token" to "test-token"), options.auth)
        assertEquals(listOf("Bearer test-token"), options.extraHeaders["Authorization"])
        assertEquals("affinity=call%3Atest-call", options.query)
    }
}
