package conectate.com.presentation.wallet

import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Serializes payout commands at the click, before launching or renewing credentials. */
internal class CreatorPayoutOperations(
    private val scope: CoroutineScope,
    private val captureOwner: () -> AuthSessionLease,
    private val busy: (Boolean) -> Unit,
    private val failure: (Throwable) -> Unit,
) {
    private class Operation(val owner: AuthSessionLease) { var job: Job? = null }
    private var active: Operation? = null

    fun run(action: suspend (AuthSessionLease) -> Unit) {
        val owner = captureOwner()
        if (!owner.isCurrent()) { failure(IllegalStateException("Inicia sesión para continuar.")); return }
        if (active?.owner?.isCurrent() == true) return
        cancel()
        val operation = Operation(owner)
        active = operation
        busy(true)
        operation.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                owner.requireCurrent()
                action(owner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (active === operation && owner.isCurrent()) failure(error)
            } finally {
                if (active === operation) {
                    active = null
                    busy(false)
                }
            }
        }
        if (active === operation) operation.job!!.start() else operation.job!!.cancel()
    }

    fun cancel() {
        val previous = active ?: return
        active = null
        previous.job?.cancel()
        busy(false)
    }
}
