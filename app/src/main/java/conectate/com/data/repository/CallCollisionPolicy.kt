package conectate.com.data.repository

import conectate.com.data.model.ActiveCall
import conectate.com.data.model.CallConnectionState
import conectate.com.data.model.CallInvite

internal enum class IncomingCallDisposition {
    PRESENT,
    IGNORE_DUPLICATE,
    REJECT_BUSY,
    YIELD_OUTGOING
}

/**
 * Resolves call "glare": two people starting a call to each other at nearly
 * the same time. Both devices compare the same call ids, so exactly one call
 * survives instead of both sides declaring the other one busy.
 */
internal fun resolveIncomingCallDisposition(
    activeCall: ActiveCall?,
    pendingIncoming: CallInvite?,
    currentUserId: String?,
    incoming: CallInvite
): IncomingCallDisposition {
    if (activeCall?.callId.equals(incoming.callId, ignoreCase = true) ||
        pendingIncoming?.callId.equals(incoming.callId, ignoreCase = true)
    ) {
        return IncomingCallDisposition.IGNORE_DUPLICATE
    }

    if (activeCall == null) {
        return if (pendingIncoming == null) {
            IncomingCallDisposition.PRESENT
        } else {
            IncomingCallDisposition.REJECT_BUSY
        }
    }

    val normalizedCurrentUserId = currentUserId?.trim().orEmpty()
    val activeContextType = activeCall.contextType.trim().lowercase().ifBlank { "dm" }
    val incomingContextType = incoming.contextType.trim().lowercase().ifBlank { "dm" }
    val sameDirectPeer = activeContextType == "dm" &&
        incomingContextType == "dm" &&
        activeCall.peerUserId.equals(incoming.callerUserId, ignoreCase = true)
    val sameCollection = activeContextType != "dm" &&
        activeContextType == incomingContextType &&
        !activeCall.contextId.isNullOrBlank() &&
        activeCall.contextId.equals(incoming.contextId, ignoreCase = true)
    val isOutgoingStillRinging = activeCall.connectionState == CallConnectionState.RINGING_OUTGOING &&
        activeCall.initiatorUserId.equals(normalizedCurrentUserId, ignoreCase = true)

    if (isOutgoingStillRinging && (sameDirectPeer || sameCollection)) {
        val incomingWins = incoming.callId.compareTo(activeCall.callId, ignoreCase = true) < 0
        return if (incomingWins) {
            IncomingCallDisposition.YIELD_OUTGOING
        } else {
            IncomingCallDisposition.REJECT_BUSY
        }
    }

    return IncomingCallDisposition.REJECT_BUSY
}
