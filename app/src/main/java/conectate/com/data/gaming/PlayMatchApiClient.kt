package conectate.com.data.gaming

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.model.*
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayMatchApiClient @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
) {
    suspend fun capabilities(token: String) = get<PlayMatchCapabilities>(token, "/v1/data/playmatch/capabilities", "capabilities")
    suspend fun catalog(token: String, query: String = "") = execute(token) { base ->
        supabaseConfig.httpClient.get("$base/v1/data/playmatch/catalog") {
            playMatchHeaders(token)
            if (query.isNotBlank()) parameter("q", query)
            parameter("limit", 100)
        }
    }.decode<PlayMatchCatalogResponse>()
    suspend fun popularGames(token: String, limit: Int = 12) = execute(token) { base ->
        supabaseConfig.httpClient.get("$base/v1/data/playmatch/games/popular") {
            playMatchHeaders(token)
            parameter("limit", limit.coerceIn(1, 20))
        }
    }.decode<PlayMatchCatalogResponse>()
    suspend fun searchGames(token: String, query: String, cursor: String? = null, limit: Int = 30) = execute(token) { base ->
        supabaseConfig.httpClient.get("$base/v1/data/playmatch/games/search") {
            playMatchHeaders(token)
            parameter("q", query.trim().take(100))
            parameter("limit", limit.coerceIn(1, 50))
            cursor?.takeIf(String::isNotBlank)?.let { parameter("cursor", it) }
        }
    }.decode<PlayMatchCatalogPageResponse>()
    suspend fun gameConfiguration(token: String, gameId: String) =
        get<PlayMatchGameConfigurationResponse>(token, "/v1/data/playmatch/games/${encodePath(gameId)}/config")
    suspend fun profile(token: String) = get<PlayMatchProfileResponse>(token, "/v1/data/playmatch/profile")
    suspend fun updateProfile(token: String, body: UpdatePlayMatchProfileRequest) = execute(token) { base ->
        supabaseConfig.httpClient.patch("$base/v1/data/playmatch/profile") { playMatchHeaders(token); contentType(ContentType.Application.Json); setBody(body) }
    }.decode<PlayMatchProfileResponse>()
    suspend fun saveGame(token: String, body: SavePlayMatchGameRequest) = execute(token) { base ->
        supabaseConfig.httpClient.post("$base/v1/data/playmatch/games") { playMatchHeaders(token); contentType(ContentType.Application.Json); setBody(body) }
    }.decode<SavePlayMatchGameResponse>()
    suspend fun deleteGame(token: String, id: String) = execute(token) { base ->
        supabaseConfig.httpClient.delete("$base/v1/data/playmatch/games/${encodePath(id)}") { playMatchHeaders(token) }
    }.map { Unit }
    suspend fun saveAvailability(token: String, body: SavePlayMatchAvailabilityRequest) = execute(token) { base ->
        supabaseConfig.httpClient.put("$base/v1/data/playmatch/availability") { playMatchHeaders(token); contentType(ContentType.Application.Json); setBody(body) }
    }.decode<SavePlayMatchAvailabilityResponse>()
    suspend fun savePreferences(token: String, body: SavePlayMatchPreferencesRequest) = execute(token) { base ->
        supabaseConfig.httpClient.put("$base/v1/data/playmatch/preferences") { playMatchHeaders(token); contentType(ContentType.Application.Json); setBody(body) }
    }.decode<SavePlayMatchPreferencesResponse>()
    suspend fun players(token: String, query: String, gameId: String?, platform: String?, region: String?, cursor: String?) = execute(token) { base ->
        supabaseConfig.httpClient.get("$base/v1/data/playmatch/players") {
            playMatchHeaders(token)
            if (query.isNotBlank()) parameter("q", query)
            gameId?.takeIf(String::isNotBlank)?.let { parameter("gameId", it) }
            platform?.takeIf(String::isNotBlank)?.let { parameter("platform", it) }
            region?.takeIf(String::isNotBlank)?.let { parameter("region", it) }
            cursor?.takeIf(String::isNotBlank)?.let { parameter("cursor", it) }
            parameter("limit", 20)
        }
    }.decode<PlayMatchPlayersResponse>()
    suspend fun player(token: String, id: String) = get<PlayMatchProfile>(token, "/v1/data/playmatch/players/${encodePath(id)}", "player")
    suspend fun connections(token: String) = get<PlayMatchConnectionsResponse>(token, "/v1/data/playmatch/connections")
    suspend fun connect(token: String, body: CreatePlayMatchConnectionRequest) = execute(token) { base ->
        supabaseConfig.httpClient.post("$base/v1/data/playmatch/connections") { playMatchHeaders(token); contentType(ContentType.Application.Json); setBody(body) }
    }.decode<PlayMatchConnectionResponse>()
    suspend fun decideConnection(token: String, id: String, body: PlayMatchDecisionRequest) = execute(token) { base ->
        supabaseConfig.httpClient.post("$base/v1/data/playmatch/connections/${encodePath(id)}/decision") { playMatchHeaders(token); contentType(ContentType.Application.Json); setBody(body) }
    }.decode<PlayMatchConnectionResponse>()
    suspend fun startMatchmaking(token: String, body: StartPlayMatchRequest) = execute(token) { base ->
        supabaseConfig.httpClient.post("$base/v1/data/playmatch/matchmaking/sessions") { playMatchHeaders(token); contentType(ContentType.Application.Json); setBody(body) }
    }.decode<PlayMatchSessionResponse>()
    suspend fun session(token: String, id: String) = get<PlayMatchSessionResponse>(token, "/v1/data/playmatch/matchmaking/sessions/${encodePath(id)}")
    suspend fun cancelSession(token: String, id: String) = execute(token) { base ->
        supabaseConfig.httpClient.post("$base/v1/data/playmatch/matchmaking/sessions/${encodePath(id)}/cancel") { playMatchHeaders(token) }
    }.decode<PlayMatchSessionResponse>()
    suspend fun matches(token: String) = get<PlayMatchMatchesResponse>(token, "/v1/data/playmatch/matches")
    suspend fun decideMatch(token: String, id: String, body: PlayMatchDecisionRequest) = execute(token) { base ->
        supabaseConfig.httpClient.post("$base/v1/data/playmatch/matches/${encodePath(id)}/decision") { playMatchHeaders(token); contentType(ContentType.Application.Json); setBody(body) }
    }.decode<PlayMatchDecisionResponse>()

    private suspend inline fun <reified T> get(token: String, path: String, key: String? = null): Result<T> = execute(token) { base ->
        supabaseConfig.httpClient.get("$base$path") { playMatchHeaders(token) }
    }.decode(key)

    private suspend fun execute(token: String, block: suspend (String) -> HttpResponse): Result<String> = runCatching {
        if (token.isBlank()) throw PlayMatchApiException("AUTH_REQUIRED", 401)
        val base = RuntimeBackendEndpoint.ensureFreshEndpoint()?.trimEnd('/')
            ?: throw PlayMatchApiException("BACKEND_UNAVAILABLE", 503)
        val response = block(base)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw parseError(response, body)
        body
    }

    private inline fun <reified T> Result<String>.decode(key: String? = null): Result<T> = mapCatching { body ->
        val root = supabaseConfig.json.parseToJsonElement(body)
        val value = if (key == null) root else (root as? JsonObject)?.get(key) ?: root
        supabaseConfig.json.decodeFromJsonElement(value)
    }

    private fun HttpRequestBuilder.playMatchHeaders(token: String) {
        header("ngrok-skip-browser-warning", "1")
        header("X-Novvin-App-Version", BuildConfig.VERSION_NAME)
        header("X-Novvin-Version-Code", BuildConfig.VERSION_CODE.toString())
        header("X-Novvin-Build-Commit", BuildConfig.BUILD_COMMIT)
        header("X-Novvin-Environment", BuildConfig.BUILD_ENVIRONMENT)
        header("X-Request-ID", UUID.randomUUID().toString())
        header(HttpHeaders.Authorization, "Bearer ${token.trim()}")
    }

    private fun parseError(response: HttpResponse, body: String): PlayMatchApiException {
        val envelope = runCatching { supabaseConfig.json.parseToJsonElement(body).jsonObject }.getOrNull()
        val code = envelope?.get("error")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: "PLAYMATCH_REQUEST_FAILED"
        val currentVersion = envelope?.get("currentVersion")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return PlayMatchApiException(code, response.status.value, currentVersion)
    }

    private fun encodePath(value: String): String = java.net.URLEncoder.encode(value.trim(), Charsets.UTF_8.name()).replace("+", "%20")
}

class PlayMatchApiException(
    val code: String,
    val statusCode: Int,
    val currentVersion: Long? = null,
) : IllegalStateException(code)
