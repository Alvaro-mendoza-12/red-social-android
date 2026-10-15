package conectate.com.util

import java.security.MessageDigest

/** Shared stable identity for the OS conversation and its private action intents. */
object ConversationNotificationIdentity {
    fun scope(account: NotificationAccountTarget?, type: String, id: String, channel: String? = null): String {
        val identity = account?.userId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: account?.email?.trim()?.lowercase().orEmpty()
        val subject = listOf(identity, type.trim().lowercase(), id.trim().lowercase(), channel?.trim()?.lowercase().orEmpty())
            .joinToString("\u0000")
        return "conversation_" + MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(40)
    }

    fun canReply(text: String): Boolean = text.isNotBlank() && text.length <= 2_000 && text.toByteArray(Charsets.UTF_8).size <= 6_000

    fun title(type: String, supplied: String?, cached: String?): String =
        sequenceOf(supplied, cached).mapNotNull { it?.trim()?.takeIf { value ->
            value.isNotBlank() && !value.equals("Grupo/Comunidad", true)
        } }.firstOrNull()?.take(160) ?: if (type == "community") "Comunidad" else "Grupo"
}
