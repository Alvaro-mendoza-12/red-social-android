package conectate.com.data.repository

import conectate.com.data.groupvoice.*
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupVoiceRepository @Inject constructor(
    private val auth: AuthService,
    private val api: GroupVoiceApi,
    private val store: GroupVoiceIntentStore,
) {
    private val creation = Mutex()
    internal fun capture() = auth.captureAccountLease()
    internal fun visits() = combine(auth.observeSession(), auth.accountVisitChanges) { _, _ -> capture() }
    internal fun publish(lease: AuthSessionLease, update: () -> Unit) = auth.commitForAccountLease(lease, update)
    internal suspend fun pending(lease: AuthSessionLease, group: String): GroupVoiceIntent? {
        lease.requireCurrent()
        return store.load(lease.accountId ?: error("Inicia sesión."), group).also { lease.requireCurrent() }
    }
    internal suspend fun roster(lease: AuthSessionLease, group: String) = call(lease) { api.roster(it, group) }
    internal suspend fun create(lease: AuthSessionLease, group: String, intent: GroupVoiceIntent): Result<GroupVoiceCreated> = creation.withLock {
        try {
            lease.requireCurrent()
            val account = lease.accountId ?: error("Inicia sesión.")
            val previous = pending(lease, group)
            check(previous == null || previous == intent) { "Reintenta la invitación pendiente antes de cambiar los integrantes." }
            store.save(account, group, intent)
            lease.requireCurrent()
            val result = call(lease) { api.create(it, group, intent) }
            val code = (result.exceptionOrNull() as? GroupVoiceApiException)?.code
            if (result.isSuccess || code in setOf("GROUP_VOICE_MEMBERS_CHANGED", "GROUP_VOICE_INVITEES_REQUIRED", "GROUP_VOICE_ROOM_ENDED", "GROUP_VOICE_CREATE_LIMIT")) {
                // Cleanup failure retains the same immutable intent. A retry returns the same room.
                try { store.clear(account, group, intent.requestId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the server ACK; the journal remains safe for replay. */ }
            }
            lease.requireCurrent()
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { lease.requireCurrent(); Result.failure(error) }
    }
    private suspend fun <T> call(lease: AuthSessionLease, action: suspend (String) -> T): Result<T> =
        withTimeout(20000) {
            accountScopedCall(lease, { owner ->
                lease.requireCurrent()
                auth.ensureValidAccessToken().getOrThrow()
                lease.requireCurrent()
                auth.getAccessTokenForAccountAwait(owner)
            }) { _, token -> Result.success(action(token)) }
        }
}
