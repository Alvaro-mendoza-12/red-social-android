package conectate.com.data.repository.message

import android.net.Uri
import conectate.com.data.model.Chat
import conectate.com.data.model.Message
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRemoteStore @Inject constructor() {
    suspend fun sendTextMessage(
        chatId: String,
        content: String,
        replyToMessageId: String? = null
    ): MessageRemoteResult<Message> = MessageRemoteResult.Error(
        "MessageRemoteStore aún no está conectado en la fase 1."
    )

    suspend fun sendMediaMessage(
        chatId: String,
        mediaUri: Uri,
        mimeType: String?,
        caption: String? = null
    ): MessageRemoteResult<Message> = MessageRemoteResult.Error(
        "El envío remoto delegado sigue deshabilitado en la fase 1."
    )

    suspend fun editMessage(
        messageId: String,
        newContent: String
    ): MessageRemoteResult<Message> = MessageRemoteResult.Error(
        "La edición remota delegada sigue deshabilitada en la fase 1."
    )

    suspend fun deleteMessage(
        messageId: String,
        deleteForEveryone: Boolean = false
    ): MessageRemoteResult<Unit> = MessageRemoteResult.Error(
        "La eliminación remota delegada sigue deshabilitada en la fase 1."
    )

    suspend fun markMessageRead(
        messageId: String,
        chatId: String
    ): MessageRemoteResult<Unit> = MessageRemoteResult.Error(
        "La sincronización remota delegada de lectura sigue deshabilitada en la fase 1."
    )

    suspend fun loadChatHistory(
        chatId: String,
        limit: Int = 50,
        before: String? = null
    ): MessageRemoteResult<List<Message>> = MessageRemoteResult.Success(emptyList())

    suspend fun getUserChats(): MessageRemoteResult<List<Chat>> =
        MessageRemoteResult.Success(emptyList())
}

/**
 * Resultado sellado de operaciones remotas.
 */
sealed class MessageRemoteResult<out T> {
    data class Success<T>(val data: T) : MessageRemoteResult<T>()
    data class Error(val message: String) : MessageRemoteResult<Nothing>()
}
