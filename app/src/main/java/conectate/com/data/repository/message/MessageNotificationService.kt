package conectate.com.data.repository.message

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import conectate.com.R
import conectate.com.data.local.dao.ChatDao
import conectate.com.data.local.dao.MessageDao
import conectate.com.data.model.Chat
import conectate.com.data.model.Message
import conectate.com.util.ChatSoundPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageNotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatSoundPlayer: ChatSoundPlayer,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao
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
        scope.launch {
            try {
                // 1. Verificar si el chat está activo (visible) → no notificar
                val activeChatId = getActiveChatId()
                if (chat?.id == activeChatId) {
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

                // 3. Construir notificación
                val notificationTitle = senderName
                val notificationText = message.content.take(100).ifBlank { "Media" }

                // 4. Mostrar notificación
                sendNotification(
                    chatId = chat?.id ?: "",
                    title = notificationTitle,
                    text = notificationText,
                    messageId = message.id
                )

                // 5. Reproducir sonido
                chatSoundPlayer.playReceive()
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
        messageId: String
    ) {
        // Implementar con NotificationCompat.Builder
        // Incluye: channel, small icon, content intent (abre chat),
        // deleteIntent (marcar como leído), actions (reply, mark read)
        // Usar NotificationManagerCompat
        TODO("Implement NotificationCompat Builder con canal para Android 8+")
    }

    /**
     * Cancela notificación para un chat específico.
     */
    fun cancelNotificationForChat(chatId: String) {
        scope.launch {
            try {
                // NotificationManagerCompat.cancel(chatId.hashCode())
                Log.d(TAG, "Notificación cancelada para chat $chatId")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelando notificación", e)
            }
        }
    }

    /**
     * Obtiene ID del chat actualmente visible (si está en foreground).
     */
    private suspend fun getActiveChatId(): String? {
        // Esto debería venir de un state holder compartido (SessionStateStore)
        return null // Placeholder
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


