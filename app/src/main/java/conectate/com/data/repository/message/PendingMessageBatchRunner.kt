package conectate.com.data.repository.message

import conectate.com.data.local.entity.MessageEntity
import conectate.com.domain.sync.PendingSyncRetryException
import kotlinx.coroutines.CancellationException

internal enum class PendingSendOutcome { SENT, SKIPPED, RETRY_LATER, TERMINAL_FAILURE }

/** Coordinates a bounded outbox pass independently of Android lifecycle and transport. */
internal class PendingMessageBatchRunner(
    private val loadPending: suspend () -> List<MessageEntity>,
    private val accessToken: suspend () -> Result<String>,
    private val send: suspend (MessageEntity, String) -> PendingSendOutcome,
    private val markTerminalFailure: suspend (MessageEntity) -> Unit
) {
    suspend fun run(
        accountId: String,
        maxBatch: Int,
        isCurrentSession: () -> Boolean
    ): Result<Int> {
        val owner = accountId.trim().lowercase()
        if (owner.isBlank()) return Result.success(0)
        fun requireCurrentSession() {
            if (!isCurrentSession()) throw PendingSyncRetryException("Outbox session changed")
        }
        fun MessageEntity.belongsToAccount() = sender_id.trim().lowercase() == owner
        return try {
            requireCurrentSession()
            // Filter BEFORE limiting: older rows from inactive accounts cannot starve this one.
            val pending = loadPending().filter { it.belongsToAccount() }.take(maxBatch.coerceAtLeast(1))
            requireCurrentSession()
            if (pending.isEmpty()) return Result.success(0)
            // Local reads do not wait for a token when there is nothing to send.
            val token = accessToken().getOrThrow()
            requireCurrentSession()
            if (token.isBlank()) throw PendingSyncRetryException("Outbox token unavailable")
            var sent = 0
            for (message in pending) {
                requireCurrentSession()
                val outcome = send(message, token)
                requireCurrentSession()
                when (outcome) {
                    PendingSendOutcome.SENT -> sent++
                    PendingSendOutcome.SKIPPED -> Unit
                    // Leave the durable row intact; do not overwrite an acknowledgement
                    // that may have arrived through realtime while this request failed.
                    PendingSendOutcome.RETRY_LATER -> Unit
                    PendingSendOutcome.TERMINAL_FAILURE -> markTerminalFailure(message)
                }
            }
            val remaining = loadPending().any { it.belongsToAccount() }
            requireCurrentSession()
            if (remaining) throw PendingSyncRetryException("Outbox still has pending messages")
            Result.success(sent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(if (error is PendingSyncRetryException) error else
                PendingSyncRetryException("Outbox pass could not finish", error))
        }
    }
}
