package conectate.com.presentation.messages

import kotlinx.coroutines.CancellationException

internal data class InternalContentShareResult(val sent: Int, val total: Int, val lastDestination: ShareDestination?)

/** Per-request receipts survive a retry; a new account or share starts with no receipts. */
internal class InternalContentShareSender {
    private var request: Pair<String, String>? = null
    private val completed = mutableSetOf<String>()
    fun reset() { request = null; completed.clear() }
    suspend fun send(
        requestId: String, account: String, targets: List<ShareDestination>, content: String,
        currentAccount: () -> String?, resolvePerson: suspend (String) -> ShareDestination?,
        send: suspend (ShareDestination, String, String) -> Result<Unit>,
    ): InternalContentShareResult {
        val key = requestId to account
        if (request != key) { request = key; completed.clear() }
        var destination: ShareDestination? = null
        fun checkAccount() {
            check(account == currentAccount() && request == key) { "La cuenta o la solicitud cambió. Abre Compartir de nuevo." }
        }
        for (target in targets.distinctBy { it.key }) {
            checkAccount()
            if (target.key in completed) continue
            val resolved = if (target.type == ShareDestinationType.CHAT && target.id.startsWith("person:")) {
                resolvePerson(target.id.removePrefix("person:"))
            } else target
            checkAccount()
            if (resolved != null) {
                val messageId = java.util.UUID.nameUUIDFromBytes("$account:$requestId:${target.key}".toByteArray(Charsets.UTF_8)).toString()
                val result = send(resolved, content, messageId)
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                checkAccount()
                if (result.isSuccess) { completed += target.key; destination = resolved }
            }
        }
        val total = targets.distinctBy { it.key }.size
        return InternalContentShareResult(targets.distinctBy { it.key }.count { it.key in completed }, total,
            destination.takeIf { total == 1 })
    }
}

/** A durable local outbox entry is not a server acknowledgement of an internal share. */
internal fun confirmedDirectContentShare(message: conectate.com.data.model.Message): Result<Unit> =
    conectate.com.core.messaging.messageSendConfirmation(synced = true, status = message.status,
        deletedForAll = message.deleted_for_all)
