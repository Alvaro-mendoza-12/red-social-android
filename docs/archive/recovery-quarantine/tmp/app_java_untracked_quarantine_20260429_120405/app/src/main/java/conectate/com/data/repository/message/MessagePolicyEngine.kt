package conectate.com.data.repository.message

import conectate.com.core.security.GuardOutcome
import conectate.com.data.model.Message
import conectate.com.data.model.Chat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagePolicyEngine @Inject constructor() {
    companion object {
        private const val MAX_MESSAGE_LENGTH = 5000
        private const val MIN_SEND_INTERVAL_MS = 500 // Anti-spam
    }

    /**
     * Valida si un mensaje puede ser enviado según políticas de negocio.
     * Retorna GuardOutcome con motivo si falla.
     */
    fun validateSendMessage(
        chat: Chat?,
        message: Message,
        userId: String?
    ): GuardOutcome {
        val cleanUserId = userId?.trim()?.lowercase()
        if (cleanUserId.isNullOrBlank()) {
            return GuardOutcome.BLOCKED
        }

        if (chat == null) {
            return GuardOutcome.BLOCKED
        }

        val isParticipant = chat.participants.any { participant ->
            participant.trim().lowercase() == cleanUserId
        }
        if (!isParticipant) {
            return GuardOutcome.BLOCKED
        }

        if (message.content.isBlank() && message.media_url.isNullOrBlank()) {
            return GuardOutcome.BLOCKED
        }

        if (message.content.length > MAX_MESSAGE_LENGTH) {
            return GuardOutcome.BLOCKED
        }

        val lastSend = getLastSendTimestamp(cleanUserId, chat.id)
        val now = System.currentTimeMillis()
        if (lastSend != null && now - lastSend < MIN_SEND_INTERVAL_MS) {
            return GuardOutcome.QUEUED
        }

        return GuardOutcome.SUCCESS
    }

    /**
     * Deduplica mensajes: encuentra mensajes similares recientes para evitar duplicados.
     * Retorna lista de mensajes candidatos a duplicado.
     */
    fun findPotentialDuplicates(
        chatId: String,
        content: String,
        maxResults: Int = 5
    ): List<Message> {
        if (content.isBlank()) return emptyList()
        // Esta lógica debería delegar a un DeduplicationService especializado
        // Por simplicidad, retornamos vacío por ahora
        return emptyList()
    }

    /**
     * Calcula el contador de mensajes no leídos para un chat.
     */
    fun calculateUnreadCount(
        messages: List<Message>,
        lastReadTimestamp: String?,
        currentUserId: String
    ): Int {
        if (lastReadTimestamp == null) return messages.size

        return messages.count { msg ->
            val createdAt = msg.created_at ?: return@count false
            msg.sender_id != currentUserId &&
                createdAt > lastReadTimestamp &&
                msg.status != "deleted"
        }
    }

    /**
     * Normaliza el estado de un mensaje (ej: sent → delivered → read).
     */
    fun normalizeMessageStatus(message: Message, context: String? = null): Message {
        return when {
            message.status == "sending" && !message.media_url.isNullOrBlank() -> {
                message.copy(status = "failed")
            }
            message.status == "sent" -> {
                // Si ya pasó tiempo y no hay error, marcar como delivered
                // (el servidor debería actualizar esto)
                message
            }
            else -> message
        }
    }

    /**
     * Obtiene timestamp del último envío (anti-spam).
     * Esto debería almacenarse en cache (Redis/SharedPrefs).
     */
    private fun getLastSendTimestamp(userId: String, chatId: String): Long? {
        // Placeholder: en producción, leer de cache
        return null
    }
}


