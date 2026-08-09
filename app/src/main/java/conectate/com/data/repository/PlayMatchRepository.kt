package conectate.com.data.repository

import conectate.com.data.gaming.PlayMatchApiClient
import conectate.com.data.model.*
import conectate.com.data.supabase.AuthService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayMatchRepository @Inject constructor(
    private val authService: AuthService,
    private val api: PlayMatchApiClient,
) {
    suspend fun capabilities() = withToken(api::capabilities)
    suspend fun catalog(query: String = "") = withToken { api.catalog(it, query) }
    suspend fun profile() = withToken(api::profile)
    suspend fun updateProfile(body: UpdatePlayMatchProfileRequest) = withToken { api.updateProfile(it, body) }
    suspend fun saveGame(body: SavePlayMatchGameRequest) = withToken { api.saveGame(it, body) }
    suspend fun deleteGame(id: String) = withToken { api.deleteGame(it, id) }
    suspend fun saveAvailability(entries: List<PlayMatchAvailability>) = withToken { api.saveAvailability(it, SavePlayMatchAvailabilityRequest(entries)) }
    suspend fun savePreferences(body: SavePlayMatchPreferencesRequest) = withToken { api.savePreferences(it, body) }
    suspend fun players(query: String = "", gameId: String? = null, platform: String? = null, region: String? = null, cursor: String? = null) =
        withToken { api.players(it, query, gameId, platform, region, cursor) }
    suspend fun player(id: String) = withToken { api.player(it, id) }
    suspend fun connections() = withToken(api::connections)
    suspend fun connect(playerId: String, message: String = "") = withToken {
        api.connect(it, CreatePlayMatchConnectionRequest(playerId, message, UUID.randomUUID().toString()))
    }
    suspend fun decideConnection(id: String, decision: String) = withToken { api.decideConnection(it, id, PlayMatchDecisionRequest(decision)) }
    suspend fun start(criteria: PlayMatchCriteria) = withToken {
        api.startMatchmaking(it, StartPlayMatchRequest(
            idempotencyKey = UUID.randomUUID().toString(),
            gameIds = criteria.gameIds,
            platforms = criteria.platforms,
            styles = criteria.styles,
            languages = criteria.languages,
            regions = criteria.regions,
            roles = criteria.roles,
            rankTolerance = criteria.rankTolerance,
            voiceRequired = criteria.voiceRequired,
        ))
    }
    suspend fun session(id: String) = withToken { api.session(it, id) }
    suspend fun cancel(id: String) = withToken { api.cancelSession(it, id) }
    suspend fun matches() = withToken(api::matches)
    suspend fun decideMatch(id: String, decision: String, reason: String = "") = withToken { api.decideMatch(it, id, PlayMatchDecisionRequest(decision, reason)) }

    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> {
        val token = authService.getAccessTokenAwait()
            ?: return Result.failure(IllegalStateException("Necesitas iniciar sesión para usar Juegos."))
        return block(token)
    }
}
