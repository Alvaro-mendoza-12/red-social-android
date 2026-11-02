package conectate.com.presentation.messages

import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ShareActionState {
    val requestId: String?
    data object Idle : ShareActionState { override val requestId: String? = null }
    data class Running(val completed: Int, val total: Int, override val requestId: String? = null) : ShareActionState
    data class Queued(val destination: ShareDestination, override val requestId: String) : ShareActionState
    data class Completed(val message: String, val hadFailures: Boolean, val openDestination: ShareDestination? = null,
        override val requestId: String? = null) : ShareActionState
    data class Failed(val message: String, val retryPending: Boolean = false, override val requestId: String? = null) : ShareActionState
}

internal fun ShareActionState.forRequest(id: String): ShareActionState = takeIf { requestId == id } ?: ShareActionState.Idle

/** A UI recreation keeps its request; replacing/closing it invalidates all old callbacks. */
internal class ShareRequestSession(
    private val scope: CoroutineScope,
    private val captureAccountLease: () -> AuthSessionLease,
    private val onFailure: (Throwable) -> Unit = {},
) {
    internal class Binding(val id: String, val account: AuthSessionLease)
    internal class Ticket(val binding: Binding) {
        val id get() = binding.id
        val account get() = binding.account
        var operationJob: Job? = null
    }
    private val _state = MutableStateFlow<ShareActionState>(ShareActionState.Idle)
    val state: StateFlow<ShareActionState> = _state
    private var current: Binding? = null
    private var attempt: Ticket? = null
    private var job: Job? = null
    private var closing = false

    /** Returns true only for a new request, so its batch receipts can be reset exactly once. */
    fun bind(id: String): Boolean {
        require(id.isNotBlank())
        if (current?.id == id) { validateSession(); return false }
        val old = job
        current = Binding(id, captureAccountLease())
        attempt = null
        job = null
        closing = false
        _state.value = ShareActionState.Idle
        old?.cancel()
        validateSession()
        return true
    }

    fun close(id: String): Boolean {
        if (current?.id != id) return false
        current = null
        attempt = null
        val old = job
        job = null
        closing = false
        _state.value = ShareActionState.Idle
        old?.cancel()
        return true
    }

    fun accountForRequest(id: String): AuthSessionLease? = current?.takeIf { it.id == id && it.account.isCurrent() }?.account
    fun isBound(id: String, account: AuthSessionLease): Boolean = current?.id == id && current?.account === account && account.isCurrent()

    fun beginClosing(id: String): AuthSessionLease? {
        val account = accountForRequest(id) ?: return null
        if (closing) return null
        closing = true
        attempt = null
        job?.cancel(); job = null
        _state.value = ShareActionState.Running(0, 0, id)
        return account
    }

    fun restoreState(id: String, account: AuthSessionLease, state: ShareActionState) {
        if (isBound(id, account) && !closing && job?.isActive != true) _state.value = state
    }

    fun validateSession() {
        val ticket = current ?: return
        if (!ticket.account.isCurrent()) {
            job?.cancel()
            _state.value = ShareActionState.Failed("La cuenta cambió. Cierra y vuelve a abrir Compartir.", requestId = ticket.id)
        }
    }

    fun fail(id: String, message: String) {
        val ticket = current?.takeIf { it.id == id } ?: return
        if (job?.isActive == true) return
        validateSession()
        if (ticket.account.isCurrent()) _state.value = ShareActionState.Failed(message, requestId = id)
    }

    fun requireCurrent(ticket: Ticket) {
        ticket.account.requireCurrent()
        if (current !== ticket.binding || attempt !== ticket || ticket.operationJob?.isActive == false) throw CancellationException("La solicitud de compartir cambió.")
    }

    fun report(ticket: Ticket, state: ShareActionState) {
        requireCurrent(ticket)
        _state.value = when (state) {
            is ShareActionState.Running -> state.copy(requestId = ticket.id)
            is ShareActionState.Queued -> state.copy(requestId = ticket.id)
            is ShareActionState.Completed -> state.copy(requestId = ticket.id)
            is ShareActionState.Failed -> state.copy(requestId = ticket.id)
            ShareActionState.Idle -> state
        }
    }

    fun start(id: String, total: Int, delivery: suspend (Ticket) -> ShareActionState): Boolean {
        val binding = current?.takeIf { it.id == id } ?: return false
        validateSession()
        if (!binding.account.isCurrent() || closing || job?.isActive == true || _state.value is ShareActionState.Queued) return false
        val ticket = Ticket(binding)
        attempt = ticket
        _state.value = ShareActionState.Running(0, total, id) // Reserve before launch to reject double taps.
        job = scope.launch {
            ticket.operationJob = currentCoroutineContext()[Job]
            try {
                requireCurrent(ticket)
                report(ticket, delivery(ticket))
            } catch (cancelled: CancellationException) {
                if (current === ticket.binding && attempt === ticket) {
                    if (!ticket.account.isCurrent()) validateSession()
                    else _state.value = ShareActionState.Failed("El envío se interrumpió. Puedes reintentar los pendientes.", true, id)
                }
                throw cancelled
            } catch (error: Exception) {
                if (current === ticket.binding && attempt === ticket && ticket.account.isCurrent() && ticket.operationJob?.isActive == true) {
                    onFailure(error)
                    _state.value = ShareActionState.Failed("No se pudo completar el envío. Puedes reintentar los pendientes.", true, id)
                }
            }
        }
        return true
    }
}
