package conectate.com.data.localfallback

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AccountDeletedException : Exception("Tu cuenta ha sido eliminada.")

object AccountDeletedEvents {
    private val mutableEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events = mutableEvents.asSharedFlow()

    fun notifyAccountDeleted() {
        mutableEvents.tryEmit(Unit)
    }
}
