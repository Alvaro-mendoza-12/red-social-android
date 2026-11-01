package conectate.com.data.auth

import conectate.com.data.localfallback.LocalFallbackAuthResponse
import conectate.com.data.model.StoredAccountSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class PreparedLocalAccount(
    val stored: StoredAccountSession,
    val response: LocalFallbackAuthResponse,
)

internal interface AccountSwitchSession {
    val isCurrent: Boolean
    val hasPaused: Boolean
    fun needsPause(account: PreparedLocalAccount): Boolean = true
    fun requireCurrent() {
        if (!isCurrent) throw CancellationException("La sesión cambió durante el cambio de cuenta.")
    }
    suspend fun pause()
    suspend fun activate(account: PreparedLocalAccount)
    suspend fun restorePending()
    suspend fun rollback()
    fun close()
}

/** Preparation retains refreshed credentials before any outgoing session or outbox is changed. */
internal class AccountSwitchCoordinator<T>(
    private val open: () -> AccountSwitchSession,
    private val prepare: suspend (String) -> PreparedLocalAccount,
    private val complete: suspend (PreparedLocalAccount, AccountSwitchSession) -> T,
    private val onRollbackFailure: (Exception) -> Unit,
    private val rollbackTimeoutMillis: Long = 20_000,
) {
    suspend fun switch(accountId: String): Result<T> {
        val session = try { open() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return Result.failure(error) }
        try {
            val prepared = prepare(accountId)
            currentCoroutineContext().ensureActive()
            session.requireCurrent()
            if (session.needsPause(prepared)) session.pause()
            currentCoroutineContext().ensureActive()
            session.requireCurrent()
            session.activate(prepared)
            currentCoroutineContext().ensureActive()
            session.requireCurrent()
            session.restorePending()
            currentCoroutineContext().ensureActive()
            session.requireCurrent()
            val result = complete(prepared, session)
            currentCoroutineContext().ensureActive()
            session.requireCurrent()
            return Result.success(result)
        } catch (cancelled: CancellationException) {
            rollbackIfOwned(session)
            throw cancelled
        } catch (error: Exception) {
            if (!session.isCurrent) throw CancellationException("La sesión cambió durante el cambio de cuenta.", error)
            rollbackIfOwned(session)
            session.requireCurrent()
            return Result.failure(error)
        } finally { session.close() }
    }

    private suspend fun rollbackIfOwned(session: AccountSwitchSession) {
        if (!session.hasPaused || !session.isCurrent) return
        // Only local recovery is allowed here; no refresh/network requests in rollback.
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                withTimeout(rollbackTimeoutMillis) {
                    session.requireCurrent()
                    session.rollback()
                }
            } catch (cancelled: CancellationException) {
                if (session.isCurrent) onRollbackFailure(cancelled)
            } catch (error: Exception) { if (session.isCurrent) onRollbackFailure(error) }
        }
    }
}
