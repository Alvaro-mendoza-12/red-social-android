package conectate.com.data.repository

import conectate.com.data.supabase.AuthSessionLease
import java.util.UUID

/** No credentials are included in notification intents. Every call entry gets a new local visit. */
internal data class CallNotificationTarget(val callId: String, val visitId: String)

internal class CallNotificationVisit private constructor(
    val target: CallNotificationTarget,
    private val account: AuthSessionLease,
) {
    fun accountForCall(callId: String): AuthSessionLease? =
        account.takeIf { target.callId.equals(callId.trim(), ignoreCase = true) }

    fun matches(requested: CallNotificationTarget?, activeCallId: String?): Boolean =
        requested != null && target.visitId == requested.visitId &&
            target.callId.equals(requested.callId.trim(), ignoreCase = true) &&
            target.callId.equals(activeCallId?.trim(), ignoreCase = true) && account.isCurrent()

    companion object {
        fun capture(callId: String, account: AuthSessionLease): CallNotificationVisit? {
            val key = callId.trim().takeIf(String::isNotEmpty) ?: return null
            if (!account.isCurrent()) return null
            return CallNotificationVisit(CallNotificationTarget(key, UUID.randomUUID().toString()), account)
        }
    }
}
