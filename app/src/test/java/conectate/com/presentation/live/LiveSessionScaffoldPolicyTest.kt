package conectate.com.presentation.live

import conectate.com.data.model.LiveComment
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSessionScaffoldPolicyTest {
    @Test
    fun `reply keeps a stable mention of the selected live comment author`() {
        val reply = LiveComment(userName = "  Mariana  ")

        assertEquals("@Mariana gracias por estar aquí", buildLiveReplyMessage(reply, "  gracias por estar aquí  "))
    }

    @Test
    fun `ordinary comment is trimmed without an invented mention`() {
        assertEquals("hola live", buildLiveReplyMessage(null, "  hola live  "))
    }

    @Test
    fun `like counter is compact without changing its real value range`() {
        assertEquals("999", compactLiveCount(999))
        assertEquals("1.2K", compactLiveCount(1_234))
        assertEquals("2.3M", compactLiveCount(2_345_678))
    }
}
