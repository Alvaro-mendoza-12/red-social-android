package conectate.com.data.repository

import conectate.com.data.model.ActiveCall
import conectate.com.data.model.CallConnectionState

/** Delayed work must never revive a closed call or operate on its replacement. */
internal fun canRefreshCallMedia(call: ActiveCall?, expectedCallId: String?=null): Boolean =
    call!=null && call.connectionState!=CallConnectionState.ENDED && call.connectionState!=CallConnectionState.IDLE &&
        (expectedCallId==null || call.callId.equals(expectedCallId,ignoreCase=true))
