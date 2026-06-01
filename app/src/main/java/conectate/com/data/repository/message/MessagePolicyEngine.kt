package conectate.com.data.repository.message

import conectate.com.core.security.GuardOutcome
import conectate.com.data.model.Message
import conectate.com.data.model.Chat
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagePolicyEngine @Inject constructor() {
    companion object {
        private const val MAX_MESSAGE_LENGTH = 5000
        private const val MIN_SEND_INTERVAL_MS = 500 // Anti-spam
        private const val DUPLICATE_WINDOW_MS = 10_000L
    }

    private data class SendFingerprint(
        val contentHash: Int,
        val createdAtMs: Long,
        val messageId: String
    )

    private val lastSendTimestamps = ConcurrentHashMap<String, Long>()
    private val recentMessageFingerprints = ConcurrentHashMap<String, MutableList<SendFingerprint>>()

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

        lastSendTimestamps[sendKey(cleanUserId, chat.id)] = now
        rememberFingerprint(chat.id, message.content, message.id, now)
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
        val cleanChatId = chatId.trim().lowercase()
        val normalizedContent = normalizeContent(content)
        if (cleanChatId.isBlank() || normalizedContent.isBlank()) return emptyList()

        val contentHash = normalizedContent.hashCode()
        val now = System.currentTimeMillis()
        val candidates = recentMessageFingerprints[cleanChatId]
            ?.synchronizedSnapshot()
            ?.filter { fingerprint ->
                fingerprint.contentHash == contentHash &&
                    now - fingerprint.createdAtMs <= DUPLICATE_WINDOW_MS
            }
            ?.take(maxResults.coerceAtLeast(1))
            .orEmpty()

        return candidates.map { fingerprint ->
            Message(
                id = fingerprint.messageId,
                chat_id = cleanChatId,
                content = content,
                created_at = fingerprint.createdAtMs.toString()
            )
        }
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
        return lastSendTimestamps[sendKey(userId, chatId)]
    }

    private fun sendKey(userId: String, chatId: String): String {
        return "${userId.trim().lowercase()}::${chatId.trim().lowercase()}"
    }

    private fun rememberFingerprint(chatId: String, content: String, messageId: String, now: Long) {
        val cleanChatId = chatId.trim().lowercase()
        val normalizedContent = normalizeContent(content)
        if (cleanChatId.isBlank() || normalizedContent.isBlank()) return

        val fingerprints = recentMessageFingerprints.getOrPut(cleanChatId) { mutableListOf() }
        synchronized(fingerprints) {
            fingerprints.removeAll { now - it.createdAtMs > DUPLICATE_WINDOW_MS }
            fingerprints.add(
                SendFingerprint(
                    contentHash = normalizedContent.hashCode(),
                    createdAtMs = now,
                    messageId = messageId
                )
            )
        }
    }

    private fun MutableList<SendFingerprint>.synchronizedSnapshot(): List<SendFingerprint> {
        return synchronized(this) { toList() }
    }

    private fun normalizeContent(content: String): String {
        return content.trim().replace(Regex("\\s+"), " ").lowercase()
    }
}


