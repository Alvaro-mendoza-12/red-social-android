package conectate.com.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import conectate.com.data.repository.CommunityRepository
import conectate.com.data.repository.GroupRepository
import conectate.com.data.repository.MessageRepository
import conectate.com.data.supabase.AuthService
import conectate.com.util.ConversationNotificationIdentity
import conectate.com.util.MessageNotificationContract
import conectate.com.util.notificationAccountTargetFromIntent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

class MessageNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(MessageNotificationContract.ACTION_REPLY, MessageNotificationContract.ACTION_MARK_AS_READ, MessageNotificationContract.ACTION_MUTE)) return
        val target = notificationAccountTargetFromIntent(intent)?.takeIf { it.isDefined } ?: return
        val type = intent.getStringExtra(MessageNotificationContract.EXTRA_TARGET_TYPE)?.trim()?.lowercase().orEmpty()
        val id = intent.getStringExtra(MessageNotificationContract.EXTRA_CONVERSATION_ID)?.trim()?.lowercase().orEmpty()
        if (type !in setOf("chat", "group", "community") || id.isBlank() || id.length > 200) return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessageNotificationContract.KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
        if (action == MessageNotificationContract.ACTION_REPLY && !ConversationNotificationIdentity.canReply(reply)) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val auth = EntryPointAccessors.fromApplication(app, MessageNotificationReceiverEntryPoint::class.java).authService()
                if (!target.matches(auth.getCurrentUserId(), auth.getCurrentUserEmail())) return@launch
                val replyId = intent.getStringExtra(MessageNotificationContract.EXTRA_REPLY_ID) ?: UUID.randomUUID().toString()
                val data = Data.Builder()
                    .putString("action", action).putString("account_id", target.userId).putString("account_email", target.email)
                    .putString(MessageNotificationContract.EXTRA_TARGET_TYPE, type)
                    .putString(MessageNotificationContract.EXTRA_CONVERSATION_ID, id)
                    .putString(MessageNotificationContract.EXTRA_CHANNEL_ID, intent.getStringExtra(MessageNotificationContract.EXTRA_CHANNEL_ID))
                    .putString(MessageNotificationContract.EXTRA_MESSAGE_ID, intent.getStringExtra(MessageNotificationContract.EXTRA_MESSAGE_ID))
                    .putInt(MessageNotificationContract.EXTRA_NOTIFICATION_ID, intent.getIntExtra(MessageNotificationContract.EXTRA_NOTIFICATION_ID, id.hashCode()))
                    .putString(MessageNotificationContract.KEY_TEXT_REPLY, reply).putString("reply_id", replyId).build()
                val work = OneTimeWorkRequestBuilder<MessageNotificationActionWorker>().setInputData(data).build()
                val scope = ConversationNotificationIdentity.scope(target, type, id, data.getString(MessageNotificationContract.EXTRA_CHANNEL_ID))
                WorkManager.getInstance(app).enqueueUniqueWork("notification:$scope:$action:$replyId", ExistingWorkPolicy.KEEP, work)
                    .result.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Keep the original notification available for opening/retrying.
                android.util.Log.w("MessageNotification", "Could not schedule notification action")
            } finally { pending.finish() }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MessageNotificationReceiverEntryPoint {
    fun messageRepository(): MessageRepository
    fun groupRepository(): GroupRepository
    fun communityRepository(): CommunityRepository
    fun authService(): AuthService
}
