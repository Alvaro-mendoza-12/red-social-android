package conectate.com.data.repository

import conectate.com.data.model.LiveRole
import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One route's receipt. It never rebinds to another account visit or media lifetime. */
internal class LiveScreenEntry(val account: AuthSessionLease) {
    private val lock = Any()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    @Volatile var media: LiveMediaVisit? = null
        private set
    @Volatile var closed: Boolean = false
        private set
    private var target: Pair<String, LiveRole>? = null
    private var startup: Job? = null
    private var closing: CompletableDeferred<Result<Unit>>? = null

    fun accepts(liveId: String, role: LiveRole): Boolean = synchronized(lock) {
        if (closed || (target != null && target != (liveId to role))) false
        else { target = liveId to role; true }
    }

    /** Caller has already committed the account lease and owns the media-state lock. */
    fun bind(owner: LiveMediaVisit): Boolean {
        val bound = synchronized(lock) {
            if (closed || !account.sameVisit(owner.account) || (media != null && media !== owner)) false
            else { media = owner; true }
        }
        return bound
    }

    /** Publish after the repository has installed owner.screenEntry and released its locks. */
    fun notifyMediaBound() { revision.update { it + 1 } }

    fun registerStartup(job: Job): Boolean = synchronized(lock) {
        if (closed) false else { startup = job; true }
    }

    fun clearStartup(job: Job?) = synchronized(lock) {
        if (startup === job) startup = null
    }

    /** Abandoning a minimized route cancels pending entry, not established background media. */
    fun abandon() {
        val pending = synchronized(lock) {
            closed = true
            startup.also { startup = null }
        }
        revision.update { it + 1 }
        pending?.cancel()
    }

    data class CloseRequest(
        val result: CompletableDeferred<Result<Unit>>,
        val startup: Job?,
        val media: LiveMediaVisit?,
        val launch: Boolean,
    )

    fun requestClose(): CloseRequest {
        val request = synchronized(lock) {
            closing?.let { return@synchronized CloseRequest(it, null, null, false) }
            val result = CompletableDeferred<Result<Unit>>()
            closing = result
            if (closed) {
                result.complete(Result.success(Unit))
                CloseRequest(result, null, null, false)
            } else {
                closed = true
                CloseRequest(result, startup.also { startup = null }, media, true)
            }
        }
        if (request.launch) revision.update { it + 1 }
        return request
    }
}
