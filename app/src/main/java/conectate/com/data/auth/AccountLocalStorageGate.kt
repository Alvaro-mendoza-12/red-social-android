package conectate.com.data.auth

import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares exclusion with account archive/activation. Only local storage belongs in this block. */
internal class AccountLocalStorageGate(
    private val activation: Mutex,
    private val sessions: AuthSessionMutationGuard,
) {
    suspend fun <T> withOwner(owner: AuthSessionLease, write: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        requireOwner(owner)
        return activation.withLock {
            currentCoroutineContext().ensureActive()
            requireOwner(owner)
            val result = write()
            currentCoroutineContext().ensureActive()
            requireOwner(owner)
            result
        }
    }

    private fun requireOwner(owner: AuthSessionLease) {
        if (!sessions.commitForLease(owner) {}) {
            throw CancellationException("La cuenta cambió antes de guardar sus mensajes.")
        }
    }
}
