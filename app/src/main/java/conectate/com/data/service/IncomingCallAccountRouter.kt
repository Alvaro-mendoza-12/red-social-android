package conectate.com.data.service

import conectate.com.data.model.CallInvite
import conectate.com.data.repository.AccountSessionRepository
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.CallRepository
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.supabase.AuthService
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.resolveStoredAccountForTarget
import dagger.Lazy
import javax.inject.Inject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An explicit answer may switch account; declining never changes the active session. */
class IncomingCallAccountRouter @Inject constructor(
    private val auth: AuthService,
    private val accounts: AccountSessionRepository,
    private val authRepository: Lazy<AuthRepository>,
    private val calls: Lazy<CallRepository>,
    private val api: LocalFallbackApiClient,
) {
    suspend fun prepareAnswer(target: NotificationAccountTarget?): Result<Unit> {
        if (target == null || target.matches(auth.getCurrentUserId(), auth.getCurrentUserEmail())) {
            return Result.success(Unit)
        }
        val account = resolveStoredAccountForTarget(target, accounts.accounts.value)
            ?: return Result.failure(IllegalStateException("Inicia sesión en la cuenta que recibió esta llamada."))
        if (calls.get().activeCall.value != null) {
            return Result.failure(IllegalStateException("Termina la llamada actual antes de cambiar de cuenta."))
        }
        return authRepository.get().switchAccount(account.id).mapCatching {
            check(target.matches(auth.getCurrentUserId(), auth.getCurrentUserEmail())) {
                "No se pudo activar la cuenta que recibió esta llamada."
            }
        }
    }

    suspend fun decline(invite: CallInvite, target: NotificationAccountTarget?): Result<Unit> {
        if (target == null || target.matches(auth.getCurrentUserId(), auth.getCurrentUserEmail())) {
            return calls.get().declineIncomingCall(invite)
        }
        val account = resolveStoredAccountForTarget(target, accounts.accounts.value)
            ?: return Result.failure(IllegalStateException("La cuenta que recibió la llamada ya no está guardada."))
        if (!auth.isLocalFallbackStoredSessionToken(account.access_token, account.refresh_token)) {
            return Result.failure(IllegalStateException("Abre esa cuenta para responder a la llamada."))
        }
        return api.createNotification(
            accessToken = account.access_token,
            recipientId = invite.callerUserId,
            type = "call_cancel",
            targetId = invite.callId,
            content = "Llamada rechazada",
            extraData = buildJsonObject {
                put("call_id", invite.callId)
                put("call_action", "decline")
                account.user_id?.let { put("from_user_id", it) }
            },
        ).map { Unit }
    }
}
