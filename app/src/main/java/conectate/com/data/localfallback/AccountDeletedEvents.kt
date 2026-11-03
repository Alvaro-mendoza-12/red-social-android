package conectate.com.data.localfallback

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import conectate.com.data.auth.AccountDeletionNotice
import conectate.com.data.supabase.AuthSessionLease

class AccountDeletedException : Exception("Tu cuenta ha sido eliminada.")

internal object AccountDeletedEvents {
    private val mutableEvents = MutableSharedFlow<AccountDeletionNotice>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events = mutableEvents.asSharedFlow()

    fun notifyAccountDeleted(lease: AuthSessionLease) {
        if (lease.isCurrent()) mutableEvents.tryEmit(AccountDeletionNotice.fromSession(lease))
    }

    fun notifyAccountDeletedFromAuthorization(header: String?): Boolean {
        val notice = AccountDeletionNotice.fromAuthorization(header) ?: return false
        return mutableEvents.tryEmit(notice)
    }
}
