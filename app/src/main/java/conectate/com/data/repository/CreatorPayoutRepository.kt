package conectate.com.data.repository

import conectate.com.data.model.CreatorPayoutAccountResponse
import conectate.com.data.model.CreatorPayoutRequestResponse
import conectate.com.data.model.CreatorPayoutSummary
import conectate.com.data.payout.CreatorPayoutApiClient
import conectate.com.data.supabase.AuthService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreatorPayoutRepository @Inject constructor(
    private val authService: AuthService,
    private val apiClient: CreatorPayoutApiClient,
) {
    suspend fun summary(): Result<CreatorPayoutSummary> = withToken(apiClient::summary)

    suspend fun updateAccount(email: String, currency: String): Result<CreatorPayoutAccountResponse> =
        withToken { token -> apiClient.updateAccount(token, email, currency) }

    suspend fun request(amountMinor: Long): Result<CreatorPayoutRequestResponse> =
        withToken { token -> apiClient.requestPayout(token, amountMinor, UUID.randomUUID().toString()) }

    suspend fun cancel(requestId: String): Result<CreatorPayoutRequestResponse> =
        withToken { token -> apiClient.cancel(token, requestId) }

    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> =
        authService.ensureValidAccessToken().fold(
            onSuccess = { token -> block(token) },
            onFailure = { error -> Result.failure(error) },
        )
}
