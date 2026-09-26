package conectate.com.data.repository

import conectate.com.data.analytics.CreatorAnalyticsApiClient
import conectate.com.data.model.CreatorAnalyticsCatalog
import conectate.com.data.model.CreatorAnalyticsRange
import conectate.com.data.model.LiveAnalyticsReport
import conectate.com.data.model.VideoAnalyticsReport
import conectate.com.data.supabase.AuthService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreatorAnalyticsRepository @Inject constructor(
    private val authService: AuthService,
    private val apiClient: CreatorAnalyticsApiClient,
) {
    suspend fun loadContent(): Result<CreatorAnalyticsCatalog> = withToken(apiClient::content)

    suspend fun loadVideo(contentId: String, range: CreatorAnalyticsRange): Result<VideoAnalyticsReport> =
        withToken { token -> apiClient.video(token, contentId, range) }

    suspend fun loadLive(contentId: String, range: CreatorAnalyticsRange): Result<LiveAnalyticsReport> =
        withToken { token -> apiClient.live(token, contentId, range) }

    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> =
        authService.ensureValidAccessToken().fold(
            onSuccess = { token -> block(token) },
            onFailure = { error -> Result.failure(error) },
        )
}
