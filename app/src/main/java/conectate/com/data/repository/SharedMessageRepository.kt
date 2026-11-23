package conectate.com.data.repository

import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.model.SharedMessageDestination
import conectate.com.data.model.SharedMessagePreview
import conectate.com.data.model.SharedMessageReference
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class SharedMessageRepository @Inject constructor(
    private val api: LocalFallbackApiClient,
    private val auth: AuthService,
) {
    private val previewRequests = Semaphore(4)

    internal val accountVisits get() = auth.accountVisitChanges
    internal fun captureAccount() = auth.captureAccountLease()

    internal suspend fun supportsPostShares(lease: AuthSessionLease): Boolean {
        lease.requireCurrent()
        if (!api.isConfigured || !auth.isLocalFallbackSession()) return false
        return authenticated(lease, api::supportsPostMessageShares)
    }

    internal suspend fun sendPost(
        lease: AuthSessionLease,
        destination: SharedMessageDestination,
        scopeId: String,
        messageId: String,
        reference: SharedMessageReference,
        note: String,
    ) = authenticated(lease) { token -> api.sendSharedPostMessage(token, destination, scopeId, messageId, reference, note) }

    internal suspend fun resolve(
        lease: AuthSessionLease,
        destination: SharedMessageDestination,
        scopeId: String,
        messageId: String,
    ): SharedMessagePreview = previewRequests.withPermit {
        authenticated(lease) { token -> api.resolveSharedPostMessage(token, destination, scopeId, messageId) }
    }

    private suspend fun <T : Any> authenticated(lease: AuthSessionLease, operation: suspend (String) -> T): T {
        lease.requireCurrent()
        return withTimeoutOrNull(12_000L) {
            check(RuntimeBackendEndpoint.ensureFreshEndpoint() != null) { "Servidor temporalmente no disponible." }
            lease.requireCurrent()
            val token = checkNotNull(auth.getAccessTokenForAccountAwait(checkNotNull(lease.accountId))) { "Inicia sesión de nuevo." }
            lease.requireCurrent()
            operation(token).also { lease.requireCurrent() }
        } ?: error("El servidor tardó demasiado. Inténtalo de nuevo.")
    }
}