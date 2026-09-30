package conectate.com.data.webrtc

import org.junit.Assert.*
import org.junit.Test

class MediaSenderIdentityTest {
    @Test fun `distinct wrappers keep the same native microphone sender`() {
        val first=String(charArrayOf('m','i','c'))
        val second=String(charArrayOf('m','i','c'))
        assertFalse(first === second)
        assertTrue(isSameMediaSender(first,second))
    }
    @Test fun `different or absent senders cannot become the primary microphone`() {
        assertFalse(isSameMediaSender("mic-2","mic-1"))
        assertFalse(isSameMediaSender(null,null))
        assertFalse(isSameMediaSender("",""))
    }
}
