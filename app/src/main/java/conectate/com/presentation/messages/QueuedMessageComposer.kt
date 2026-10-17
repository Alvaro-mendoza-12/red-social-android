package conectate.com.presentation.messages

import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the draft until durable local storage accepts it, independently of HTTP delivery.
 * All calls, including the repository's onQueued callback, must run on the main thread.
 */
@MainThread
internal class QueuedMessageComposer<Reply> {
    private val mutableText = MutableStateFlow("")
    val text = mutableText.asStateFlow()
    private val mutableReply = MutableStateFlow<Reply?>(null)
    val reply = mutableReply.asStateFlow()

    private var textRevision = 0L
    private var replyRevision = 0L
    private var enqueuing: Submission<Reply>? = null
    private var saveDraft: ((String) -> Unit)? = null
    private var draftBeforeEdit: String? = null

    fun bindDraft(initial: String, save: (String) -> Unit) {
        reset()
        updateText(initial)
        saveDraft = save
    }

    fun beginEditing(value: String) {
        if (draftBeforeEdit == null) draftBeforeEdit = text.value
        updateText(value)
    }

    /** Empty optional navigation parameters must never erase a restored draft. */
    fun applyInitialText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        updateText(value)
        return true
    }

    fun finishEditing() {
        val previous = draftBeforeEdit ?: return
        draftBeforeEdit = null
        updateText(previous)
    }

    class Submission<Reply> internal constructor(
        val text: String,
        val replyTo: Reply?,
        internal val textRevision: Long,
        internal val replyRevision: Long,
        internal val consumesDraft: Boolean
    )

    class ReplySnapshot<Reply> internal constructor(val replyTo: Reply?, internal val revision: Long)

    fun captureReply(): ReplySnapshot<Reply> = ReplySnapshot(reply.value, replyRevision)

    /** Media preserves the typed draft and clears only the reply it actually queued. */
    fun queuedReply(snapshot: ReplySnapshot<Reply>) {
        if (replyRevision == snapshot.revision) updateReply(null)
    }

    fun updateText(value: String) {
        textRevision++
        mutableText.value = value
        if (draftBeforeEdit == null) saveDraft?.invoke(value)
    }

    fun updateReply(value: Reply?) {
        replyRevision++
        mutableReply.value = value
    }

    /** Prevent duplicate taps only while saving locally, never while awaiting delivery. */
    fun beginSend(forcedContent: String? = null): Submission<Reply>? {
        val content = forcedContent ?: text.value
        if (content.isBlank() || enqueuing != null) return null
        return Submission(
            content, reply.value, textRevision, replyRevision, forcedContent == null
        ).also { enqueuing = it }
    }

    fun queued(submission: Submission<Reply>) {
        if (enqueuing !== submission) return
        enqueuing = null
        // Revisions also protect a new draft/reply whose value equals the previous one.
        if (submission.consumesDraft && textRevision == submission.textRevision) updateText("")
        if (replyRevision == submission.replyRevision) updateReply(null)
    }

    /** A pre-queue failure leaves the draft intact. A delivery result never rewrites it. */
    fun finished(submission: Submission<Reply>) {
        if (enqueuing === submission) enqueuing = null
    }

    /** Detach pending callbacks when changing account, conversation or channel. */
    fun invalidatePendingSend() {
        enqueuing = null
        textRevision++
        replyRevision++
    }

    fun reset() {
        saveDraft = null
        draftBeforeEdit = null
        invalidatePendingSend()
        updateText("")
        updateReply(null)
    }
}
