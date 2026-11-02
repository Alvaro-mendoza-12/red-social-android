package conectate.com.presentation.messages

import conectate.com.util.IncomingSharedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.UUID

internal sealed interface ShareOutgoingContent {
    data class Text(val content: String) : ShareOutgoingContent
    data class Attachment(val item: IncomingSharedItem, val content: String) : ShareOutgoingContent
    data class Forward(val source: ForwardMessagePayload) : ShareOutgoingContent
}

internal data class ShareBatchItem(val sourceKey: String, val destination: ShareDestination, val content: ShareOutgoingContent) {
    val key: String get() = "${destination.key}:$sourceKey"
}

internal data class ShareBatchResult(val accepted: Int, val total: Int, val lastDestination: ShareDestination?)

/** Freeze each user action and retain receipts; retries never resend accepted items or change their content. */
internal class ShareBatchDelivery(private val intervalMs: Long = 350L, private val journal: ShareBatchJournal? = null) {
    private var request: Pair<String, String>? = null
    private var revision = 0L
    private var plan = emptyList<ShareBatchItem>()
    private val receipts = mutableMapOf<String, ShareDestination>()

    fun reset() { revision++; request = null; plan = emptyList(); receipts.clear() }

    fun frozenPlan(requestId: String, accountId: String): List<ShareBatchItem> =
        plan.takeIf { request == (requestId to accountId) }.orEmpty()

    fun restore(requestId: String, accountId: String, saved: SavedShareBatch) {
        reset()
        request = requestId to accountId
        plan = saved.plan
        receipts.putAll(saved.receipts)
    }

    suspend fun send(
        requestId: String,
        accountId: String,
        proposed: List<ShareBatchItem>,
        requireCurrent: () -> Unit,
        onProgress: (Int, Int) -> Unit,
        onQueued: suspend (ShareDestination) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
        onFrozen: (SavedShareBatch) -> Unit = {},
        deliver: suspend (ShareBatchItem, String, suspend () -> Unit) -> Result<ShareDestination>,
    ): ShareBatchResult {
        requireCurrent()
        val key = requestId to accountId
        if (request != key) {
            reset()
            request = key
            plan = proposed.distinctBy { it.key }.toList()
        }
        val expected = revision
        fun guard() {
            requireCurrent()
            if (revision != expected || request != key) throw CancellationException("La solicitud de compartir cambió.")
        }
        guard()
        journal?.prepare(requestId, accountId, plan, ::guard)?.let { saved ->
            guard()
            if (saved.closed) return ShareBatchResult(0, 0, null)
            plan = saved.plan
            receipts.putAll(saved.receipts)
        }
        val items = plan
        onFrozen(SavedShareBatch(items, receipts.toMap(), false))
        suspend fun accepted(item: ShareBatchItem, target: ShareDestination) {
            guard()
            journal?.accepted(requestId, accountId, item.key, target, ::guard)
            guard()
            receipts[item.key] = target
            onFrozen(SavedShareBatch(items, receipts.toMap(), false))
        }
        var processed = items.count { it.key in receipts }
        onProgress(processed, items.size)
        for (item in items) {
            currentCoroutineContext().ensureActive()
            guard()
            if (item.key in receipts) continue
            val messageId = UUID.nameUUIDFromBytes("$accountId:$requestId:${item.key}".toByteArray(Charsets.UTF_8)).toString()
            val result = try {
                deliver(item, messageId) {
                    guard()
                    if (items.size == 1 && item.content is ShareOutgoingContent.Attachment) {
                        // Save the handoff BEFORE the UI may close and cancel this coroutine.
                        accepted(item, item.destination)
                        onQueued(item.destination)
                    } else if (items.size == 1) onQueued(item.destination)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            currentCoroutineContext().ensureActive()
            guard()
            result.exceptionOrNull()?.let(onFailure)
            result.getOrNull()?.let { accepted(item, it) }
            onProgress(++processed, items.size)
            if (processed < items.size && intervalMs > 0L) delay(intervalMs)
        }
        guard()
        val accepted = items.count { it.key in receipts }
        return ShareBatchResult(accepted, items.size, items.singleOrNull()?.let { receipts[it.key] })
    }
}

/** A durable local outbox entry is not a server acknowledgement of an internal share. */
internal fun confirmedDirectContentShare(message: conectate.com.data.model.Message): Result<Unit> =
    conectate.com.core.messaging.messageSendConfirmation(synced = true, status = message.status,
        deletedForAll = message.deleted_for_all)
