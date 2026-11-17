package conectate.com.data.repository

import conectate.com.data.model.CreatorPayoutAccountResponse
import conectate.com.data.model.CreatorPayoutRequestResponse
import conectate.com.data.model.CreatorPayoutSummary
import conectate.com.data.payout.CreatorPayoutApiClient
import conectate.com.data.payout.CreatorPayoutApiException
import conectate.com.data.payout.CreatorPayoutRetryStore
import conectate.com.data.payout.PendingCreatorPayout
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreatorPayoutRepository @Inject constructor(
    private val authService: AuthService,
    private val apiClient: CreatorPayoutApiClient,
    private val retries: CreatorPayoutRetryStore,
) {
    private val requestMutex = Mutex()
    internal fun captureSession(): AuthSessionLease = authService.captureAccountLease()

    internal fun observeSessionVisits() = combine(authService.observeSession(), authService.accountVisitChanges) { _, _ -> captureSession() }

    internal fun publish(session: AuthSessionLease, mutation: () -> Unit): Boolean = authService.commitForAccountLease(session, mutation)

    suspend fun summary(): Result<CreatorPayoutSummary> = summary(captureSession())

    internal suspend fun summary(session: AuthSessionLease): Result<CreatorPayoutSummary> = withToken(session, apiClient::summary)

    suspend fun updateAccount(email: String, currency: String): Result<CreatorPayoutAccountResponse> =
        updateAccount(captureSession(), email, currency)

    internal suspend fun updateAccount(session: AuthSessionLease, email: String, currency: String): Result<CreatorPayoutAccountResponse> =
        withToken(session) { token -> apiClient.updateAccount(token, email, currency) }

    suspend fun request(amountMinor: Long): Result<CreatorPayoutRequestResponse> =
        request(captureSession(), amountMinor)

    internal suspend fun request(session: AuthSessionLease, amountMinor: Long): Result<CreatorPayoutRequestResponse> =
        requestMutex.withLock {
            try {
                session.requireCurrent()
                require(amountMinor > 0) { "PAYOUT_AMOUNT_INVALID" }
                val account = session.accountId!!
                val stored = retries.load(account)
                session.requireCurrent()
                // A prior rejection is durable proof of no creation; finish its cleanup
                // before allowing the user to correct the amount or start another intent.
                if (stored?.rejected == true) {
                    retries.clear(account, stored.key)
                    session.requireCurrent()
                }
                val previous = stored?.takeUnless { it.rejected }
                if (previous != null && previous.amountMinor != amountMinor) {
                    return@withLock Result.failure(IllegalStateException("PAYOUT_PREVIOUS_ATTEMPT_UNCONFIRMED"))
                }
                val attempt = previous ?: PendingCreatorPayout(amountMinor, UUID.randomUUID().toString())
                // SharedPreferences can update memory even when commit() reports a disk failure.
                // Confirm durability again on a retry before authorizing any HTTP request.
                retries.save(account, attempt)
                session.requireCurrent()
                val result = withToken(session) { token -> apiClient.requestPayout(token, amountMinor, attempt.key) }
                val confirmed = result.getOrNull()
                if (confirmed != null) {
                    val cleanupPending = try {
                        retries.clear(account, attempt.key)
                        false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { true }
                    session.requireCurrent()
                    return@withLock Result.success(confirmed.copy(localCleanupPending = cleanupPending))
                }
                // Only explicit business rejections establish that this attempt did not create a withdrawal.
                val code = (result.exceptionOrNull() as? CreatorPayoutApiException)?.code
                val rejected = code in setOf("CREATOR_PAYOUTS_DISABLED", "PAYOUT_PROVIDER_NOT_CONFIGURED",
                    "CREATOR_NOT_VERIFIED", "PAYOUT_ACCOUNT_NOT_VERIFIED", "PAYOUT_MINIMUM_NOT_REACHED",
                    "PAYOUT_INSUFFICIENT_BALANCE", "PAYOUT_CURRENCY_MISMATCH")
                // The current server checks several business gates before looking up a
                // duplicate. Those errors cannot disprove an older ambiguous creation.
                val checkedAfterDuplicate = code in setOf("PAYOUT_INSUFFICIENT_BALANCE", "PAYOUT_CURRENCY_MISMATCH")
                if (rejected && (previous == null || checkedAfterDuplicate)) {
                    retries.save(account, attempt.copy(rejected = true))
                    retries.clear(account, attempt.key)
                }
                session.requireCurrent()
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                session.requireCurrent()
                Result.failure(error)
            }
        }

    suspend fun cancel(requestId: String): Result<CreatorPayoutRequestResponse> =
        cancel(captureSession(), requestId)

    internal suspend fun cancel(session: AuthSessionLease, requestId: String): Result<CreatorPayoutRequestResponse> =
        withToken(session) { token -> apiClient.cancel(token, requestId) }

    private suspend fun <T> withToken(session: AuthSessionLease, block: suspend (String) -> Result<T>): Result<T> =
        accountScopedCall(session, { authService.ensureValidAccessToken().getOrThrow() }) { _, token -> block(token) }
}
