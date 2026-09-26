package conectate.com.data.signaling

import io.socket.client.IO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
