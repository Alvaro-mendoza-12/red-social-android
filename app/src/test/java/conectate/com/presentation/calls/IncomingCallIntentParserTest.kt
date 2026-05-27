package conectate.com.presentation.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingCallIntentParserTest {

    @Test
    fun buildIncomingCallInvite_returnsNullWhenRequiredFieldsAreMissing() {
        assertNull(
            buildIncomingCallInvite(
                callId = null,
                callerUserId = "user-1",
                callerName = null,
                callerAvatarUrl = null,
                title = null,
                contextType = null,
                contextId = null
            )
        )
        assertNull(
            buildIncomingCallInvite(
                callId = "call-1",
                callerUserId = " ",
                callerName = null,
                callerAvatarUrl = null,
                title = null,
                contextType = null,
                contextId = null
            )
        )
    }

    @Test
    fun buildIncomingCallInvite_normalizesFallbackValues() {
        val invite = requireNotNull(
            buildIncomingCallInvite(
                callId = " call-42 ",
                callerUserId = " user-7 ",
                callerName = " ",
                callerAvatarUrl = " ",
                title = " ",
                contextType = " ",
                contextId = " "
            )
        )

        assertEquals("call-42", invite.callId)
        assertEquals("user-7", invite.callerUserId)
        assertEquals("Usuario", invite.callerName)
        assertNull(invite.callerAvatarUrl)
        assertEquals("Usuario", invite.title)
        assertEquals("dm", invite.contextType)
        assertNull(invite.contextId)
    }
}
