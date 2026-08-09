package conectate.com.data.repository

import conectate.com.data.gaming.PlayMatchApiClient
import conectate.com.data.model.*
import conectate.com.data.supabase.AuthService
import java.util.UUID
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayMatchRepository @Inject constructor(
    private val authService: AuthService,
    private val api: PlayMatchApiClient,
) {
    private data class CachedCatalogPage(val savedAt: Long, val response: PlayMatchCatalogPageResponse)
    private val catalogCache = object : LinkedHashMap<String, CachedCatalogPage>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedCatalogPage>?): Boolean = size > 24
    }
    private val catalogCacheTtlMs = 5 * 60_000L

    suspend fun capabilities() = withToken(api::capabilities)
    suspend fun catalog(query: String = "") = withToken { api.catalog(it, query) }
    suspend fun popularGames() = withToken { api.popularGames(it) }
    suspend fun searchGames(query: String, cursor: String? = null, force: Boolean = false): Result<PlayMatchCatalogPageResponse> {
        val key = "${query.trim().lowercase()}|${cursor.orEmpty()}"
        synchronized(catalogCache) {
            val cached = catalogCache[key]
            if (!force && cached != null && System.currentTimeMillis() - cached.savedAt < catalogCacheTtlMs) {
                return Result.success(cached.response)
            }
        }
        val result = withToken { api.searchGames(it, query, cursor) }
        result.onSuccess { response -> synchronized(catalogCache) { catalogCache[key] = CachedCatalogPage(System.currentTimeMillis(), response) } }
        return result
    }
    suspend fun gameConfiguration(gameId: String) = withToken { api.gameConfiguration(it, gameId) }
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
