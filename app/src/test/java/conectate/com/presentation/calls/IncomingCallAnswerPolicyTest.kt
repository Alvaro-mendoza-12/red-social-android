package conectate.com.presentation.calls

import conectate.com.data.model.CallInvite
import org.junit.Assert.*
import org.junit.Test

class IncomingCallAnswerPolicyTest {
    private val invite = CallInvite("call-qa", "Prueba", "caller-qa", "Auditoría", contextType = "dm")

    @Test fun audioAnswerNeedsMicrophoneButDoesNotRequireCamera() {
        assertTrue(canAnswerAfterPermissions("call-qa", "owner-qa", invite, "owner-qa", true, false))
        assertFalse(canAnswerAfterPermissions("call-qa", "owner-qa", invite, "owner-qa", false, true))
    }

    @Test fun videoAnswerNeedsBothPermissions() {
        val video = invite.copy(isVideo = true)
        assertTrue(canAnswerAfterPermissions("call-qa", "owner-qa", video, "owner-qa", true, true))
        assertFalse(canAnswerAfterPermissions("call-qa", "owner-qa", video, "owner-qa", true, false))
    }

    @Test fun cancelledOrReplacedInvitationCannotResumeFromOldPermissionResult() {
        assertFalse(canAnswerAfterPermissions("call-qa", "owner-qa", null, "owner-qa", true, true))
        assertFalse(canAnswerAfterPermissions("call-qa", "owner-qa", invite.copy(callId = "new-call"), "owner-qa", true, true))
    }

    @Test fun switchedOrLoggedOutAccountCannotAcceptThePreviousInvitation() {
        assertFalse(canAnswerAfterPermissions("call-qa", "owner-qa", invite, "other-account", true, true))
        assertFalse(canAnswerAfterPermissions("call-qa", "owner-qa", invite, null, true, true))
    }

    @Test fun absentPendingAnswerCannotAcceptAnInvitation() {
        assertFalse(canAnswerAfterPermissions(null, "owner-qa", invite, "owner-qa", true, true))
        assertFalse(canAnswerAfterPermissions("call-qa", null, invite, "owner-qa", true, true))
        assertFalse(canAnswerAfterPermissions("", "owner-qa", invite, "owner-qa", true, true))
    }
}
