package conectate.com.util

import org.junit.Assert.*
import org.junit.Test

class UnreadPushPolicyTest {
    @Test fun onlyAnotherPersonsActualMessageCreatesAHint() {
        assertTrue(shouldRecordUnreadPush("new_message", "m", "other", "me"))
        assertTrue(shouldRecordUnreadPush("text", "m", "other", "me"))
        assertFalse(shouldRecordUnreadPush("new_message", "m", " ME ", "me"))
        for (type in listOf("message_reaction", "voice_invite", "call_invite", "call_cancel", "live_started", "mention"))
            assertFalse(shouldRecordUnreadPush(type, "m", "other", "me"))
        assertFalse(shouldRecordUnreadPush("text", null, "other", "me"))
    }
}
