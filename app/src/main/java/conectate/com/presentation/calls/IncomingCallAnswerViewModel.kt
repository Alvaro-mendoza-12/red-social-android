package conectate.com.presentation.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.CallInvite
import conectate.com.data.repository.CallRepository
import conectate.com.data.service.IncomingCallAccountRouter
import conectate.com.presentation.common.toUserFacingMessage
import conectate.com.util.NotificationAccountTarget
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@HiltViewModel
class IncomingCallAnswerViewModel @Inject constructor(
    private val accountRouter: Lazy<IncomingCallAccountRouter>,
    private val calls: Lazy<CallRepository>,
) : ViewModel() {
    private val controller = IncomingCallAnswerController(viewModelScope,
        prepare = { invite, target ->
            // Lazy.get() belongs inside the IO operation too: constructing the graph
            // opens encrypted account storage and several messaging repositories.
            withTimeout(12_000L) { accountRouter.get().prepareAnswer(target).getOrThrow() }
            val repository = calls.get()
            try {
                repository.acceptIncomingCall(invite).also { result ->
                    if (result.isSuccess) repository.refreshAudioSessionRepeatedly(attempts = 3, delayMs = 400)
                }
            } catch (error: Exception) {
                if (repository.activeCall.value?.callId.equals(invite.callId, ignoreCase = true)) {
                    withContext(NonCancellable) { repository.hangup() }
                }
                if (error is CancellationException) throw error
                Result.failure(error)
            }
        },
        failureMessage = { it.toUserFacingMessage("No se pudo conectar la llamada. Inténtalo nuevamente.") },
    )
    internal val state = controller.state
    fun answer(invite: CallInvite, target: NotificationAccountTarget?) = controller.answer(invite, target)
}
