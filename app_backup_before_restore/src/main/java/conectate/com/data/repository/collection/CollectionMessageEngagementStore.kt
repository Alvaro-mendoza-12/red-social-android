package conectate.com.data.repository.collection

import conectate.com.data.model.MessageReadReceipt
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class CollectionReactionToggleResult(
    val messageId: String,
    val reactions: Map<String, List<String>>
)

internal class CollectionMessageEngagementStore(
    private val supabaseConfig: SupabaseConfig,
    private val json: Json
) {
    suspend fun getMessageReadReceipts(
        messageId: String,
        token: String,
        rpcFunctionName: String
    ): Result<List<MessageReadReceipt>> = withContext(Dispatchers.IO) {
        try {
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/$rpcFunctionName") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put("p_message_id", messageId.trim().lowercase()) })
            }
            var body = runCatching { response.bodyAsText() }.getOrDefault("")
            if (!response.status.isSuccess()) {
                return@withContext Result.failure(Exception("Error cargando recibos: ${response.status.value} $body"))
            }
            Result.success(parseReadReceiptList(body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleReaction(
        messageId: String,
        emoji: String,
        currentReactions: Map<String, List<String>>,
        userId: String,
        token: String,
        rpcFunctionName: String,
        tableName: String,
        missingRpcPredicate: (statusCode: Int, body: String) -> Boolean
    ): Result<CollectionReactionToggleResult> = withContext(Dispatchers.IO) {
        try {
            val normalizedEmoji = emoji.trim()
            if (normalizedEmoji.isBlank()) {
                return@withContext Result.failure(Exception("Reacción inválida"))
            }

            val rpcPayload = buildJsonObject {
                put("p_message_id", messageId)
                put("p_emoji", normalizedEmoji.take(16))
            }

            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/$rpcFunctionName") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }

            val updatedReactions = if (rpcResponse.status.isSuccess()) {
                val raw = runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
                parseReactionToggleResponse(raw, messageId)?.reactions
                    ?: toggleReactionMap(currentReactions, normalizedEmoji, userId)
            } else {
                val rpcBody = runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
                if (missingRpcPredicate(rpcResponse.status.value, rpcBody) || isMissingRpcFunctionAlias(rpcBody, rpcFunctionName)) {
                    val patched = toggleReactionMap(currentReactions, normalizedEmoji, userId)
                    val patchPayload = buildJsonObject {
                        put("reactions", reactionsToJsonObject(patched))
                    }
                    val patchResponse = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/$tableName") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        parameter("id", "eq.$messageId")
                        setBody(patchPayload)
                    }
                    if (!patchResponse.status.isSuccess()) {
                        return@withContext Result.failure(Exception("No se pudo guardar reacción"))
                    }
                    patched
                } else {
                    return@withContext Result.failure(Exception("No se pudo reaccionar: $rpcBody"))
                }
            }

            Result.success(CollectionReactionToggleResult(messageId = messageId, reactions = updatedReactions))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseReadReceiptList(body: String): List<MessageReadReceipt> {
        return runCatching { json.decodeFromString<List<MessageReadReceipt>>(body) }
            .getOrElse {
                runCatching {
                    listOf(json.decodeFromString<MessageReadReceipt>(body))
                }.getOrDefault(emptyList())
            }
    }

    private fun isMissingRpcFunctionAlias(body: String, functionName: String): Boolean {
        var normalized = body.lowercase()
        return normalized.contains(functionName.lowercase()) &&
            (normalized.contains("could not find the function") || normalized.contains("undefined function"))
    }

    private fun toggleReactionMap(
        current: Map<String, List<String>>,
        emoji: String,
        userId: String
    ): Map<String, List<String>> {
        var normalized = current.mapValues { (_, users) ->
            users.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().toMutableList()
        }.toMutableMap()

        var hadSameReaction = false
        normalized.keys.toList().forEach { key ->
            var users = normalized[key] ?: mutableListOf()
            val removed = users.remove(userId)
            if (key == emoji && removed) hadSameReaction = true
            if (users.isEmpty()) normalized.remove(key) else normalized[key] = users
        }

        if (!hadSameReaction) {
            val targetUsers = normalized[emoji]?.toMutableList() ?: mutableListOf()
            if (!targetUsers.contains(userId)) targetUsers.add(userId)
            normalized[emoji] = targetUsers
        }

        return normalized.mapValues { (_, users) -> users.distinct() }.filterValues { it.isNotEmpty() }
    }

    private fun reactionsToJsonObject(reactions: Map<String, List<String>>): JsonObject {
        return buildJsonObject {
            reactions.forEach { (emoji, users) ->
                val cleaned = users.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
                if (emoji.isNotBlank() && cleaned.isNotEmpty()) {
                    put(emoji, JsonArray(cleaned.map { JsonPrimitive(it) }))
                }
            }
        }
    }

    private fun parseReactionToggleResponse(raw: String, fallbackMessageId: String): CollectionReactionToggleResult? {
        if (raw.isBlank()) return null
        return runCatching {
            var element = json.parseToJsonElement(raw)
            when (element) {
                is JsonObject -> {
                    var msgId = element["message_id"]?.jsonPrimitive?.contentOrNull ?: fallbackMessageId
                    var reactionsElement = element["reactions"] ?: element
                    CollectionReactionToggleResult(msgId, parseReactionMap(reactionsElement))
                }
                is JsonArray -> {
                    val first = element.firstOrNull() as? JsonObject ?: return@runCatching null
                    var msgId = first["message_id"]?.jsonPrimitive?.contentOrNull ?: fallbackMessageId
                    var reactionsElement = first["reactions"] ?: first
                    CollectionReactionToggleResult(msgId, parseReactionMap(reactionsElement))
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun parseReactionMap(element: JsonElement?): Map<String, List<String>> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (emoji, usersElement) ->
            val usersArray = usersElement as? JsonArray ?: return@mapNotNull null
            var users = usersArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (emoji.isBlank() || users.isEmpty()) null else emoji to users
        }.toMap()
    }
}



