package conectate.com.data.repository.collection

import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class CollectionPollMessageRemoteStore(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val json: Json
) {
    suspend fun <T> createPollMessage(
        scopeType: String,
        scopeId: String,
        messageId: String,
        question: String,
        options: List<String>,
        allowsMultiple: Boolean,
        rpcFunctionName: String,
        rpcScopeKey: String,
        messageTable: String,
        messageScopeColumn: String,
        includeStatusSentInFallbackPayload: Boolean,
        parseMessage: (String) -> T?,
        buildFallbackMessage: (pollId: String, senderId: String, createdAt: String) -> T
    ): Result<T> {
        return try {
            var token = authService.getAccessToken()
                ?: return Result.failure(Exception("No token"))

            val payloadAttempts = listOf(
                buildJsonObject {
                    put(rpcScopeKey, scopeId)
                    put("p_message_id", messageId)
                    put("p_question", question)
                    put("p_options", JsonArray(options.map { JsonPrimitive(it) }))
                    put("p_allows_multiple", allowsMultiple)
                },
                buildJsonObject {
                    put(rpcScopeKey, scopeId)
                    put("p_message_id", messageId)
                    put("p_question", question)
                    put("p_options", CollectionRepositorySupport.toPostgresTextArray(options))
                    put("p_allows_multiple", allowsMultiple)
                }
            )

            var rpcError: Exception? = null
            payloadAttempts.forEach { payload ->
                var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/$rpcFunctionName") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    header("Prefer", "return=representation")
                    setBody(payload)
                }

                var body = runCatching { response.bodyAsText() }.getOrDefault("")
                if (response.status.isSuccess()) {
                    var parsed = parseMessage(body)
                    if (parsed != null) {
                        return Result.success(parsed)
                    }
                    rpcError = Exception("Respuesta invalida creando encuesta")
                } else {
                    rpcError = Exception("Error creando encuesta: ${response.status.value} $body")
                }
            }

            val fallback = createFallbackPollMessage(
                scopeType = scopeType,
                scopeId = scopeId,
                messageId = messageId,
                question = question,
                options = options,
                allowsMultiple = allowsMultiple,
                token = token,
                messageTable = messageTable,
                messageScopeColumn = messageScopeColumn,
                includeStatusSentInFallbackPayload = includeStatusSentInFallbackPayload,
                parseMessage = parseMessage,
                buildFallbackMessage = buildFallbackMessage
            )
            if (fallback.isSuccess) {
                fallback
            } else {
                Result.failure(
                    rpcError ?: fallback.exceptionOrNull() ?: Exception("No se pudo crear la encuesta")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun <T> createFallbackPollMessage(
        scopeType: String,
        scopeId: String,
        messageId: String,
        question: String,
        options: List<String>,
        allowsMultiple: Boolean,
        token: String,
        messageTable: String,
        messageScopeColumn: String,
        includeStatusSentInFallbackPayload: Boolean,
        parseMessage: (String) -> T?,
        buildFallbackMessage: (pollId: String, senderId: String, createdAt: String) -> T
    ): Result<T> {
        return try {
            val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("No user"))

            val pollResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/message_polls") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(
                    buildJsonObject {
                        put("scope_type", scopeType)
                        put("scope_id", scopeId)
                        put("creator_id", userId)
                        put("question", question)
                        put("allows_multiple", allowsMultiple)
                    }
                )
            }
            val pollBody = runCatching { pollResponse.bodyAsText() }.getOrDefault("")
            if (!pollResponse.status.isSuccess()) {
                return Result.failure(Exception("Error creando encuesta (fallback): ${pollResponse.status.value} $pollBody"))
            }

            val pollId = CollectionRepositorySupport.extractIdFromRepresentation(pollBody, json)
                ?: return Result.failure(Exception("No se pudo obtener poll_id"))

            val optionsPayload = JsonArray(
                options.mapIndexed { index, value ->
                    buildJsonObject {
                        put("poll_id", pollId)
                        put("option_text", value)
                        put("display_order", index)
                    }
                }
            )

            val optionsResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_options") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=minimal")
                setBody(optionsPayload)
            }
            if (!optionsResponse.status.isSuccess()) {
                val optionsBody = runCatching { optionsResponse.bodyAsText() }.getOrDefault("")
                return Result.failure(Exception("Error creando opciones (fallback): ${optionsResponse.status.value} $optionsBody"))
            }

            val messageResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/$messageTable") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(
                    buildJsonObject {
                        put("id", messageId)
                        put(messageScopeColumn, scopeId)
                        put("sender_id", userId)
                        put("content", question)
                        put("content_type", "poll")
                        put("poll_id", pollId)
                        if (includeStatusSentInFallbackPayload) {
                            put("status", "sent")
                        }
                    }
                )
            }
            val messageBody = runCatching { messageResponse.bodyAsText() }.getOrDefault("")
            if (!messageResponse.status.isSuccess()) {
                return Result.failure(Exception("Error enviando mensaje encuesta (fallback): ${messageResponse.status.value} $messageBody"))
            }

            var parsed = parseMessage(messageBody)
            if (parsed != null) {
                Result.success(parsed)
            } else {
                Result.success(
                    buildFallbackMessage(
                        pollId,
                        userId,
                        java.time.Instant.now().toString()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}



