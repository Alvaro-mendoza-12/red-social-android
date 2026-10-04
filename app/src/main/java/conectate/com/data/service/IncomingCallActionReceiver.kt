package conectate.com.data.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import conectate.com.data.model.CallInvite
import conectate.com.data.repository.CallRepository
import conectate.com.presentation.calls.IncomingCallActivity
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.services.IncomingCallForegroundService
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.notificationAccountTargetFromIntent
import conectate.com.util.putNotificationAccountTarget
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class IncomingCallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val invite = intent.toInviteOrNull() ?: return
        val appContext = context.applicationContext

        // Compatibility path for notifications created by an older app build.
        // Launch first; resolving the Hilt graph/repository before opening the
        // activity made cold-start answers visibly slow.
        if (intent.action == ACTION_ACCEPT) {
            appContext.startActivity(
                Intent(appContext, IncomingCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("call_id", invite.callId)
                    putExtra("caller_user_id", invite.callerUserId)
                    putExtra("caller_name", invite.callerName)
                    putExtra("caller_avatar_url", invite.callerAvatarUrl ?: "")
                    putExtra("call_title", invite.title)
                    putExtra("call_context_type", invite.contextType)
                    putExtra("call_context_id", invite.contextId ?: "")
                    putExtra("call_is_video", invite.isVideo)
                    intent.getByteArrayExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES)?.let {
                        putExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES, it)
                    }
                    putExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, true)
                    putNotificationAccountTarget(notificationAccountTargetFromIntent(intent))
                }
            )
            return
        }

        if (intent.action != ACTION_DECLINE) return
        // Local dismissal must complete before session lookup or network work.
        // A late invitation then cannot reopen the screen or auto-answer.
        dismissRejectedCall(appContext, invite.callId)
        val showDeliveryFeedback = intent.getBooleanExtra(EXTRA_DELIVERY_FEEDBACK, false)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    IncomingCallReceiverEntryPoint::class.java
                )

                withTimeout(8_000L) {
                    entryPoint.incomingCallAccountRouter().decline(invite, notificationAccountTargetFromIntent(intent))
                }.onFailure {
                    android.util.Log.w("IncomingCallAction", "Could not deliver decline response")
                    if (showDeliveryFeedback) AppFeedbackBus.showError("Se cerró la llamada, pero no se pudo avisar al llamante.")
                }
            } catch (_: Exception) {
                android.util.Log.w("IncomingCallAction", "Decline response interrupted")
                if (showDeliveryFeedback) AppFeedbackBus.showError("Se cerró la llamada, pero no se pudo avisar al llamante.")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "conectate.com.action.ACCEPT_CALL"
        const val ACTION_DECLINE = "conectate.com.action.DECLINE_CALL"
        private const val EXTRA_DELIVERY_FEEDBACK = "decline_delivery_feedback"

        /** The receiver owns delivery, so closing the activity cannot cancel the reply. */
        fun decline(context: Context, invite: CallInvite, accountTarget: NotificationAccountTarget?) {
            val appContext = context.applicationContext
            dismissRejectedCall(appContext, invite.callId)
            appContext.sendBroadcast(Intent(appContext, IncomingCallActionReceiver::class.java).apply {
                action = ACTION_DECLINE
                putExtra(EXTRA_DELIVERY_FEEDBACK, true)
                putExtra("call_id", invite.callId)
                putExtra("caller_user_id", invite.callerUserId)
                putExtra("caller_name", invite.callerName)
                putExtra("caller_avatar_url", invite.callerAvatarUrl ?: "")
                putExtra("call_title", invite.title)
                putExtra("call_context_type", invite.contextType)
                putExtra("call_context_id", invite.contextId ?: "")
                putExtra("call_is_video", invite.isVideo)
                putNotificationAccountTarget(accountTarget)
            })
        }

        private fun dismissRejectedCall(context: Context, callId: String) {
            CallCancellationGuard(context).mark(callId)
            runCatching {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(callId.hashCode())
            }.onFailure { android.util.Log.w("IncomingCallAction", "Could not remove declined call notification") }
            // A delayed action for call A must not stop the service ringing call B.
            IncomingCallForegroundService.stop(context, callId)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IncomingCallReceiverEntryPoint {
    fun callRepository(): CallRepository
    fun incomingCallAccountRouter(): IncomingCallAccountRouter
}

private fun Intent.toInviteOrNull(): CallInvite? {
    var callId = getStringExtra("call_id")?.trim().orEmpty()
    var callerUserId = getStringExtra("caller_user_id")?.trim().orEmpty()
    var callerName = getStringExtra("caller_name")?.trim().orEmpty()
    var callerAvatarUrl = getStringExtra("caller_avatar_url")?.trim().orEmpty().ifBlank { null }
    var title = getStringExtra("call_title")?.trim().orEmpty().ifBlank { callerName }
    var contextType = getStringExtra("call_context_type")?.trim().orEmpty().ifBlank { "dm" }
    var contextId = getStringExtra("call_context_id")?.trim().orEmpty().ifBlank { null }
    val isVideo = getBooleanExtra("call_is_video", false)

    if (callId.isBlank() || callerUserId.isBlank()) return null
    return CallInvite(
        callId = callId,
        title = title,
        callerUserId = callerUserId,
        callerName = callerName.ifBlank { "Usuario" },
        callerAvatarUrl = callerAvatarUrl,
        contextType = contextType,
        contextId = contextId,
        isVideo = isVideo
    )
}



