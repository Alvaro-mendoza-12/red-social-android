package conectate.com.data.repository

import conectate.com.data.model.ActiveCall
import conectate.com.data.model.CallConnectionState

/** Delayed work must never revive a closed call or operate on its replacement. */
internal fun canRefreshCallMedia(call: ActiveCall?, expectedCallId: String?=null): Boolean =
    call!=null && call.connectionState!=CallConnectionState.ENDED && call.connectionState!=CallConnectionState.IDLE &&
        (expectedCallId==null || call.callId.equals(expectedCallId,ignoreCase=true))

/** Reconnection continues the same call and must not reset its duration. */
internal fun connectedCallStartTime(call: ActiveCall, nowEpochMs: Long): Long =
    call.startedAtEpochMs ?: nowEpochMs

/** A rejoined local socket needs fresh ICE even when remote socket ids stayed the same. */
internal fun shouldRenegotiateCallParticipant(existingRemoteSocket: Boolean, signalingRejoined: Boolean): Boolean =
    !existingRemoteSocket || signalingRejoined
