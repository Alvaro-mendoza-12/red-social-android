package conectate.com.data.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import conectate.com.data.model.CallInvite
import conectate.com.data.repository.CallRepository
import conectate.com.presentation.calls.IncomingCallActivity
import conectate.com.services.IncomingCallForegroundService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IncomingCallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val invite = intent.toInviteOrNull() ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    IncomingCallReceiverEntryPoint::class.java
                )
                val callRepository = entryPoint.callRepository()

                val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(invite.callId.hashCode())
                IncomingCallForegroundService.stop(appContext)

                when (intent.action) {
                    ACTION_ACCEPT -> {
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
                            }
                        )
                    }

                    ACTION_DECLINE -> {
                        callRepository.declineIncomingCall(invite)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "conectate.com.action.ACCEPT_CALL"
        const val ACTION_DECLINE = "conectate.com.action.DECLINE_CALL"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IncomingCallReceiverEntryPoint {
    fun callRepository(): CallRepository
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



