package conectate.com.data.repository.message

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import conectate.com.R
import conectate.com.data.local.dao.ChatDao
import conectate.com.data.model.Chat
import conectate.com.data.model.Message
import conectate.com.util.ChatSoundPlayer
import conectate.com.util.ConversationNotificationPrefs
import conectate.com.util.MessageNotificationFactory
import conectate.com.util.MessageNotificationPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageNotificationService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatSoundPlayer: ChatSoundPlayer,
    private val chatDao: ChatDao,
    private val sessionStateStore: MessageSessionStateStore,
    private val authService: conectate.com.data.supabase.AuthService,
    private val userRepository: conectate.com.data.repository.UserRepository
) {
    companion object {
        private const val TAG = "MessageNotificationService"
        const val NOTIFICATION_CHANNEL_ID = "messages"
        const val NOTIFICATION_CHANNEL_NAME = "Mensajes"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Muestra notificación push para un mensaje recibido.
     * Se llama desde FCM receiver o realtime handler.
     */
    fun showNotificationForMessage(
        message: Message,
        chat: Chat?,
        senderName: String
    ) {
        val receivingAccount = conectate.com.util.NotificationAccountTarget(userId = authService.getCurrentUserId(), email = authService.getCurrentUserEmail())
        scope.launch {
            try {
                if (!receivingAccount.matches(authService.getCurrentUserId(), authService.getCurrentUserEmail())) return@launch
                val conversationId = chat?.id?.trim()?.lowercase().orEmpty()
                    .ifBlank { message.chat_id.trim().lowercase() }

                val identity = conectate.com.util.DeletedMessageIdentity.create(authService.getCurrentUserId(), "chat", conversationId, message.id)
                if (message.deleted_for_all || (identity != null && conectate.com.util.DeletedMessageStore(context).isSuppressed(identity))) return@launch

                // 1. Verificar si el chat está activo (visible) → no notificar
                if (conversationId.isNotBlank() && sessionStateStore.isActiveChatVisible(conversationId)) {
                    Log.d(TAG, "Chat activo, no se muestra notificación para ${message.id}")
                    // Reproducir sonido suave
                    chatSoundPlayer.playReceive()
                    return@launch
                }

                // 2. Verificar si el chat está muteado
                val localChat = chat?.id?.let { chatDao.getChatById(it) }
                if (chat?.is_muted == true || localChat?.is_muted == true) {
                    Log.d(TAG, "Chat muteado, no se notifica: ${chat?.id}")
                    return@launch
                }
                if (conversationId.isNotBlank()) {
                    val isMutedFromNotificationAction = ConversationNotificationPrefs(context)
                        .isMuted(targetType = "chat", conversationId = conversationId, accountId = authService.getCurrentUserId())
                    if (isMutedFromNotificationAction) {
                        Log.d(TAG, "Chat silenciado desde acciones de notificación: $conversationId")
                        return@launch
                    }
                }

                // 3. Construir notificación
                val notificationTitle = senderName
                val notificationText = conectate.com.util.typedMessageNotificationPreview(message.content_type)
                    ?: conectate.com.util.buildConversationBackgroundPreview(message.content_type, message.content)
                    ?: message.content.take(100).ifBlank { "Nuevo mensaje" }

                // 4. Mostrar notificación
                val presented = sendNotification(
                    chatId = conversationId,
                    title = notificationTitle,
                    text = notificationText,
                    messageId = message.id,
                    receivingAccount = receivingAccount,
                    senderId = message.sender_id
                )

                // 5. Play only when the atomic publication accepted this message.
                if (presented) chatSoundPlayer.playReceive()
            } catch (e: Exception) {
                Log.e(TAG, "Error mostrando notificación", e)
            }
        }
    }

    /**
     * Envía notificación nativa de Android.
     */
    private suspend fun sendNotification(
        chatId: String,
        title: String,
        text: String,
        messageId: String,
        receivingAccount: conectate.com.util.NotificationAccountTarget,
        senderId: String
    ): Boolean {
        val conversationId = chatId.trim().lowercase().ifBlank { messageId.trim().lowercase() }
        if (conversationId.isBlank()) {
            Log.w(TAG, "Notificación omitida: chatId y messageId vacíos")
            return false
        }

        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!notificationsGranted) {
            Log.w(TAG, "Notificación omitida: POST_NOTIFICATIONS no concedido")
            return false
        }

        val photo = conectate.com.core.media.NotificationAvatarLoader.senderPhoto(context, userRepository, senderId)
        if (!receivingAccount.matches(authService.getCurrentUserId(), authService.getCurrentUserEmail())) return false
        if (sessionStateStore.isActiveChatVisible(conversationId)) return false
        if (ConversationNotificationPrefs(context).isMuted("chat", conversationId, authService.getCurrentUserId())) return false
        if (conectate.com.core.media.NotificationAvatarLoader.optionalValue { chatDao.getChatById(conversationId) }?.is_muted == true) return false
        val notificationId = notificationIdForChat(conversationId)
        return MessageNotificationFactory.post(
            context = context,
            payload = MessageNotificationPayload(
                channelId = context.getString(R.string.chat_notification_channel_id),
                channelName = NOTIFICATION_CHANNEL_NAME,
                channelDescription = "Notificaciones de chats privados",
                conversationId = conversationId,
                targetType = "chat",
                conversationTitle = title.ifBlank { "Chat" },
                senderName = title.ifBlank { "Usuario" },
                messageBody = text.ifBlank { "Nuevo mensaje" },
                notificationId = notificationId,
                messageId = messageId,
                senderId = senderId,
                largeIcon = photo,
                isGroupConversation = false,
                notificationAccountTarget = receivingAccount,
                allowInlineActions = true
            )
        )

    }

    /**
     * Cancela notificación para un chat específico.
     */
    fun cancelNotificationForChat(chatId: String) {
        scope.launch {
            try {
                MessageNotificationFactory.cancelConversation(context,
                    conectate.com.util.NotificationAccountTarget(userId = authService.getCurrentUserId()), "chat", chatId.trim().lowercase())
                Log.d(TAG, "Notificación cancelada para chat $chatId")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelando notificación", e)
            }
        }
    }

    private fun notificationIdForChat(chatId: String): Int {
        return chatId.trim().lowercase().ifBlank { "messages" }.hashCode()
    }

    /**
     * Scheduling de notificaciones pendientes para mensajes fallidos.
     */
    fun schedulePendingNotification(message: Message, retryCount: Int = 0) {
        if (retryCount >= 3) {
            Log.w(TAG, "Máximo reintentos de notificación alcanzado para ${message.id}")
            return
        }

        scope.launch {
            delay(5000L * (retryCount + 1))
            try {
                // Reintentar notificación
                showNotificationForMessage(
                    message = message,
                    chat = null, // se obtiene de DB
                    senderName = "Usuario"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error en reintento de notificación", e)
                schedulePendingNotification(message, retryCount + 1)
            }
        }
    }
}


