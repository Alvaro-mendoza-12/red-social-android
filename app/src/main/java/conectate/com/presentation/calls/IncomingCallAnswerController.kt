package conectate.com.presentation.calls

import conectate.com.data.model.CallInvite
import conectate.com.util.NotificationAccountTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal sealed interface IncomingAnswerState {
    val callId: String?
    data object Idle : IncomingAnswerState { override val callId: String? = null }
    data class Preparing(override val callId: String) : IncomingAnswerState
    data class Ready(override val callId: String) : IncomingAnswerState
    data class Failed(override val callId: String, val message: String) : IncomingAnswerState
}

/** One answer survives Activity recreation; dependency and crypto work never runs on UI. */
internal class IncomingCallAnswerController(
    private val scope: CoroutineScope,
    private val prepare: suspend (CallInvite, NotificationAccountTarget?) -> Result<Unit>,
    private val failureMessage: (Throwable) -> String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = 20_000L,
) {
    private val mutableState = MutableStateFlow<IncomingAnswerState>(IncomingAnswerState.Idle)
    val state = mutableState.asStateFlow()

    @Synchronized
    fun answer(invite: CallInvite, target: NotificationAccountTarget?) {
        val current = mutableState.value
        if (current is IncomingAnswerState.Preparing || current is IncomingAnswerState.Ready) return
        mutableState.value = IncomingAnswerState.Preparing(invite.callId)
        scope.launch(dispatcher) {
            try {
                withTimeout(timeoutMs) { prepare(invite, target).getOrThrow() }
                mutableState.value = IncomingAnswerState.Ready(invite.callId)
            } catch (error: TimeoutCancellationException) {
                mutableState.value = IncomingAnswerState.Failed(invite.callId,
                    "La llamada está tardando en conectar. Puedes volver a intentarlo.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = IncomingAnswerState.Failed(invite.callId, failureMessage(error))
            }
        }
    }
}

/** Unanswered/failed invitations may select another recipient; begun answers are pinned. */
internal fun isIncomingAnswerInProgress(state: IncomingAnswerState, awaitingPermission: Boolean): Boolean =
    awaitingPermission || state is IncomingAnswerState.Preparing || state is IncomingAnswerState.Ready
