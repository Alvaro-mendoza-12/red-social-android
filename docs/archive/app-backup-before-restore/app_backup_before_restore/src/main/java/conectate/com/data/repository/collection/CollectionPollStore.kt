package conectate.com.data.repository.collection

import conectate.com.data.model.MessagePollOptionState
import conectate.com.data.model.PollVoteUser
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class CollectionPollStore(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val json: Json
) {
    suspend fun getPollState(pollId: String): Result<List<MessagePollOptionState>> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var cleanPollId = pollId.trim().lowercase()
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_get_poll_state") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put("p_poll_id", cleanPollId) })
            }
            var body = runCatching { response.bodyAsText() }.getOrDefault("")
            if (response.status.isSuccess()) {
                val parsed = parsePollStateList(body)
                if (parsed.isNotEmpty()) {
                    return@withContext Result.success(parsed.sortedBy { it.displayOrder })
                }
            }
            val fallback = loadPollStateFallback(cleanPollId, token)
            if (fallback.isNotEmpty()) {
                Result.success(fallback.sortedBy { it.displayOrder })
            } else if (!response.status.isSuccess()) {
                Result.failure(Exception("Error cargando encuesta: ${response.status.value} $body"))
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun votePollOption(
        pollId: String,
        optionId: String,
        selected: Boolean = true
    ): Result<List<MessagePollOptionState>> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var cleanPollId = pollId.trim().lowercase()
            var cleanOptionId = optionId.trim().lowercase()
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_vote_poll_option") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(
                    buildJsonObject {
                        put("p_poll_id", cleanPollId)
                        put("p_option_id", cleanOptionId)
                        put("p_selected", selected)
                    }
                )
            }
            var body = runCatching { response.bodyAsText() }.getOrDefault("")
            if (!response.status.isSuccess()) {
                if (isAmbiguousPollIdRpcError(response.status.value, body)) {
                    return@withContext votePollOptionFallback(
                        pollId = cleanPollId,
                        optionId = cleanOptionId,
                        selected = selected,
                        token = token
                    )
                }
                return@withContext Result.failure(Exception("Error votando encuesta: ${response.status.value} $body"))
            }
            Result.success(parsePollStateList(body).sortedBy { it.displayOrder })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPollOptionVoters(
        pollId: String,
        optionId: String
    ): Result<List<PollVoteUser>> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken()
                ?: return@withContext Result.failure(Exception("No token"))
            var cleanPollId = pollId.trim().lowercase()
            var cleanOptionId = optionId.trim().lowercase()
            if (cleanPollId.isBlank() || cleanOptionId.isBlank()) {
                return@withContext Result.success(emptyList())
            }

            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_votes") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("poll_id", "eq.$cleanPollId")
                parameter("option_id", "eq.$cleanOptionId")
                parameter("select", "user_id,profiles(username,full_name,avatar_url)")
            }

            var body = runCatching { response.bodyAsText() }.getOrDefault("")
            if (!response.status.isSuccess()) {
                return@withContext Result.failure(Exception("Error cargando votantes: ${response.status.value} $body"))
            }

            var rows = runCatching { json.decodeFromString<List<JsonObject>>(body) }.getOrDefault(emptyList())
            val voters = rows.mapNotNull { row ->
                var userId = row["user_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (userId.isBlank()) return@mapNotNull null
                val profileElement = row["profiles"]
                val profile = when (profileElement) {
                    is JsonObject -> profileElement
                    is JsonArray -> profileElement.firstOrNull() as? JsonObject
                    else -> null
                }
                PollVoteUser(
                    userId = userId,
                    username = profile?.get("username")?.jsonPrimitive?.contentOrNull,
                    fullName = profile?.get("full_name")?.jsonPrimitive?.contentOrNull,
                    avatarUrl = profile?.get("avatar_url")?.jsonPrimitive?.contentOrNull
                )
            }.distinctBy { it.userId.trim().lowercase() }

            Result.success(voters)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun loadPollStateFallback(
        pollId: String,
        token: String
    ): List<MessagePollOptionState> {
        if (pollId.isBlank()) return emptyList()
        var cleanUserId = authService.getCurrentUserId()?.trim()?.lowercase()

        var metaResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/message_polls") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("id", "eq.$pollId")
            parameter("select", "question,allows_multiple,is_closed")
            parameter("limit", "1")
        }
        val metaRows = if (metaResponse.status.isSuccess()) {
            runCatching { json.decodeFromString<List<JsonObject>>(metaResponse.bodyAsText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val meta = metaRows.firstOrNull()
        var question = meta?.get("question")?.jsonPrimitive?.contentOrNull.orEmpty()
        var allowsMultiple = meta?.get("allows_multiple")?.jsonPrimitive?.booleanOrNull ?: false
        var isClosed = meta?.get("is_closed")?.jsonPrimitive?.booleanOrNull ?: false

        val optionsResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_options") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("poll_id", "eq.$pollId")
            parameter("select", "id,option_text,display_order")
            parameter("order", "display_order.asc")
        }
        if (!optionsResponse.status.isSuccess()) return emptyList()

        val options = runCatching { json.decodeFromString<List<JsonObject>>(optionsResponse.bodyAsText()) }.getOrDefault(emptyList())
        if (options.isEmpty()) return emptyList()

        val votesResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_votes") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("poll_id", "eq.$pollId")
            parameter("select", "option_id,user_id")
        }
        val voteRows = if (votesResponse.status.isSuccess()) {
            runCatching { json.decodeFromString<List<JsonObject>>(votesResponse.bodyAsText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val voteCountByOption = mutableMapOf<String, Long>()
        val votedByMeOptionIds = mutableSetOf<String>()
        var totalVotes = 0L
        voteRows.forEach { row ->
            val votedOptionId = row["option_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (votedOptionId.isBlank()) return@forEach
            totalVotes += 1
            voteCountByOption[votedOptionId] = (voteCountByOption[votedOptionId] ?: 0L) + 1L

            var voterId = row["user_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            if (!cleanUserId.isNullOrBlank() && voterId == cleanUserId) {
                votedByMeOptionIds += votedOptionId
            }
        }

        return options.mapIndexedNotNull { index, row ->
            var optionId = row["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            var optionText = row["option_text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (optionId.isBlank() || optionText.isBlank()) return@mapIndexedNotNull null

            MessagePollOptionState(
                pollId = pollId,
                optionId = optionId,
                optionText = optionText,
                displayOrder = row["display_order"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: index,
                voteCount = voteCountByOption[optionId] ?: 0L,
                votedByMe = votedByMeOptionIds.contains(optionId),
                totalVotes = totalVotes,
                allowsMultiple = allowsMultiple,
                isClosed = isClosed,
                question = question
            )
        }
    }

    private suspend fun votePollOptionFallback(
        pollId: String,
        optionId: String,
        selected: Boolean,
        token: String
    ): Result<List<MessagePollOptionState>> {
        var cleanUserId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return Result.failure(Exception("No user"))

        if (selected) {
            var allowsMultiple = loadPollAllowsMultiple(pollId, token) ?: false
            if (!allowsMultiple) {
                val clearResponse = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_votes") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("poll_id", "eq.$pollId")
                    parameter("user_id", "eq.$cleanUserId")
                    parameter("option_id", "neq.$optionId")
                }
                if (!clearResponse.status.isSuccess()) {
                    val clearBody = runCatching { clearResponse.bodyAsText() }.getOrDefault("")
                    return Result.failure(Exception("Error limpiando voto anterior: ${clearResponse.status.value} $clearBody"))
                }
            }

            val insertResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_votes") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "resolution=ignore-duplicates,return=minimal")
                setBody(
                    buildJsonObject {
                        put("poll_id", pollId)
                        put("option_id", optionId)
                        put("user_id", cleanUserId)
                    }
                )
            }
            val insertBody = runCatching { insertResponse.bodyAsText() }.getOrDefault("")
            val isDuplicateConflict = insertResponse.status.value == 409 &&
                (insertBody.contains("23505") || insertBody.lowercase().contains("duplicate key"))
            if (!insertResponse.status.isSuccess() && !isDuplicateConflict) {
                return Result.failure(Exception("Error guardando voto: ${insertResponse.status.value} $insertBody"))
            }
        } else {
            val deleteResponse = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_votes") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("poll_id", "eq.$pollId")
                parameter("option_id", "eq.$optionId")
                parameter("user_id", "eq.$cleanUserId")
            }
            if (!deleteResponse.status.isSuccess()) {
                val deleteBody = runCatching { deleteResponse.bodyAsText() }.getOrDefault("")
                return Result.failure(Exception("Error quitando voto: ${deleteResponse.status.value} $deleteBody"))
            }
        }

        return getPollState(pollId)
    }

    private suspend fun loadPollAllowsMultiple(pollId: String, token: String): Boolean? {
        var metaResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/message_polls") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("id", "eq.$pollId")
            parameter("select", "allows_multiple")
            parameter("limit", "1")
        }
        if (!metaResponse.status.isSuccess()) return null
        var rows = runCatching { json.decodeFromString<List<JsonObject>>(metaResponse.bodyAsText()) }.getOrDefault(emptyList())
        return rows.firstOrNull()
            ?.get("allows_multiple")
            ?.jsonPrimitive
            ?.booleanOrNull
    }

    private fun parsePollStateList(body: String): List<MessagePollOptionState> {
        return runCatching { json.decodeFromString<List<MessagePollOptionState>>(body) }
            .getOrElse {
                runCatching {
                    listOf(json.decodeFromString<MessagePollOptionState>(body))
                }.getOrDefault(emptyList())
            }
    }

    private fun isAmbiguousPollIdRpcError(statusCode: Int, body: String): Boolean {
        val lower = body.lowercase()
        return statusCode == 400 &&
            lower.contains("42702") &&
            lower.contains("poll_id") &&
            lower.contains("ambiguous")
    }
}





