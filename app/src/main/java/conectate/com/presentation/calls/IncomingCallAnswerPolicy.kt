package conectate.com.presentation.calls

import conectate.com.data.model.CallInvite

/** A permission result must belong to the invitation and account still on screen. */
internal fun canAnswerAfterPermissions(
    pendingCallId: String?,
    pendingAccountId: String?,
    currentInvite: CallInvite?,
    currentAccountId: String?,
    microphoneGranted: Boolean,
    cameraGranted: Boolean,
): Boolean = !pendingCallId.isNullOrBlank() && !pendingAccountId.isNullOrBlank() &&
    currentInvite != null && currentInvite.callId.equals(pendingCallId, ignoreCase = true) &&
    pendingAccountId.equals(currentAccountId, ignoreCase = true) &&
    microphoneGranted && (!currentInvite.isVideo || cameraGranted)
