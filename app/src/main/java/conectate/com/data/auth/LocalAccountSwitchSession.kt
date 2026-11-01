package conectate.com.data.auth

import conectate.com.data.localfallback.LocalFallbackAuthResponse
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local storage operations share the activation mutex with every other local login. */
internal class LocalAccountSwitchSession(
    private val guard: AuthSessionMutationGuard,
    private val activationMutex: Mutex,
    private val currentAccount: () -> String?,
    private val capture: () -> LocalFallbackAuthResponse?,
    private val saveCurrent: (LocalFallbackAuthResponse) -> Unit,
    private val pauseCurrent: () -> Unit,
    private val clearCaches: () -> Unit,
    private val archive: suspend (String?) -> Unit,
    private val activateResponse: suspend (LocalFallbackAuthResponse, AuthSessionMutationGuard.Transition, Boolean) -> Unit,
    private val restore: suspend (String) -> Unit,
    private val afterPause: () -> Unit,
) : AccountSwitchSession {
    private val lease = guard.openTransition()
    private var previous: LocalFallbackAuthResponse? = null
    private var archiveOwner: String? = null
    private var archivePending = false
    override var hasPaused = false
        private set
    override val isCurrent: Boolean get() = guard.isCurrent(lease)

    override fun needsPause(account: PreparedLocalAccount): Boolean = guard.readCurrent {
        requireCurrent()
        !currentAccount().equals(account.stored.user_id, ignoreCase = true)
    }

    override suspend fun pause() = withContext(Dispatchers.IO) {
        activationMutex.withLock {
            var outgoing: LocalFallbackAuthResponse? = null
            val accepted = guard.advanceTransition(lease, beforeMutation = {
                outgoing = capture()
                outgoing?.let(saveCurrent)
                if (!hasPaused) previous = outgoing
            }) {
                hasPaused = true
                archiveOwner = outgoing?.ownerId()
                archivePending = true
                pauseCurrent()
            }
            if (!accepted) throw CancellationException("La cuenta cambió antes de guardar sus mensajes.")
            clearCaches()
            afterPause()
            archive(archiveOwner)
            archivePending = false
            requireCurrent()
        }
    }

    override suspend fun activate(account: PreparedLocalAccount) {
        requireCurrent()
        activateResponse(account.response, lease, true)
        requireCurrent()
    }

    override suspend fun restorePending() = withContext(Dispatchers.IO) {
        activationMutex.withLock {
            requireCurrent()
            val owner = currentAccount() ?: error("No se pudo identificar la cuenta para restaurar mensajes.")
            restore(owner)
            requireCurrent()
        }
    }

    override suspend fun rollback() {
        requireCurrent()
        if (currentAccount() != null) pause()
        val original = previous
        // If the initial archive failed, the original account can retain its own
        // cache. A different account's pending work must be archived first.
        if (archivePending && archiveOwner != original?.ownerId()) {
            withContext(Dispatchers.IO) {
                activationMutex.withLock {
                    requireCurrent()
                    archive(archiveOwner)
                    archivePending = false
                    requireCurrent()
                }
            }
        }
        if (original != null) {
            activateResponse(original, lease, false)
            requireCurrent()
            restorePending()
        }
    }

    override fun close() = guard.closeTransition(lease)

    private fun LocalFallbackAuthResponse.ownerId(): String =
        user.supabaseUserId?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: user.id.trim().lowercase()
}
