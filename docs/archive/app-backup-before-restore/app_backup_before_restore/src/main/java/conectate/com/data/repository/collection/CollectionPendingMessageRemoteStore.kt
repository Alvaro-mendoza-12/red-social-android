package conectate.com.data.repository.collection

import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class CollectionPendingMessageRemoteStore(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService
) {
    suspend fun sendSecureOrFallback(
        rpcFunctionName: String,
        scopePayloadKey: String,
        scopeRpcKey: String,
        tableName: String,
        messageId: String,
        payload: JsonObject,
        includeCreatedAtInLegacyPayload: Boolean = false
    ): Result<Unit> {
        val token = authService.ensureValidAccessToken()
            .getOrElse { return Result.failure(it) }

        val scopeId = payload[scopePayloadKey]?.jsonPrimitive?.contentOrNull
            ?: return Result.failure(Exception("Missing $scopePayloadKey payload"))
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val contentType = payload["content_type"]?.jsonPrimitive?.contentOrNull ?: "text"
        val mediaUrl = payload["media_url"]?.jsonPrimitive?.contentOrNull
        val replyToId = payload["reply_to_id"]?.jsonPrimitive?.contentOrNull
        val isForwarded = payload["is_forwarded"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val forwardedFromMessageId = payload["forwarded_from_message_id"]?.jsonPrimitive?.contentOrNull
        val forwardedFromUserId = payload["forwarded_from_user_id"]?.jsonPrimitive?.contentOrNull

        val rpcPayload = buildJsonObject {
            put(scopeRpcKey, scopeId)
            put("p_message_id", messageId)
            put("p_content", content)
            put("p_content_type", contentType)
            put("p_media_url", mediaUrl)
            put("p_reply_to_id", replyToId)
            put("p_is_forwarded", isForwarded)
            put("p_forwarded_from_message_id", forwardedFromMessageId)
            put("p_forwarded_from_user_id", forwardedFromUserId)
        }

        return try {
            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/$rpcFunctionName") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(rpcPayload)
            }

            if (rpcResponse.status.isSuccess()) {
                return Result.success(Unit)
            }

            val rpcError = runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
            if (isMissingRpcFunction(rpcResponse.status.value, rpcError, rpcFunctionName)) {
                return fallbackInsert(
                    tableName = tableName,
                    payload = payload,
                    scopePayloadKey = scopePayloadKey,
                    includeCreatedAtInLegacyPayload = includeCreatedAtInLegacyPayload,
                    forceLegacy = isForwarded || !forwardedFromMessageId.isNullOrBlank() || !forwardedFromUserId.isNullOrBlank()
                )
            }

            Result.failure(Exception("RPC $rpcFunctionName failed ${rpcResponse.status.value}: $rpcError"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fallbackInsert(
        tableName: String,
        payload: JsonObject,
        scopePayloadKey: String,
        includeCreatedAtInLegacyPayload: Boolean,
        forceLegacy: Boolean
    ): Result<Unit> {
        return if (forceLegacy) {
            val firstTry = runCatching {
                supabaseConfig.client.postgrest[tableName].insert(payload)
            }
            if (firstTry.isSuccess) {
                Result.success(Unit)
            } else {
                val errMsg = firstTry.exceptionOrNull()?.message.orEmpty()
                if (CollectionRepositorySupport.isMissingForwardingColumnError(errMsg)) {
                    val legacyPayload = buildLegacyPayload(
                        payload = payload,
                        scopePayloadKey = scopePayloadKey,
                        includeCreatedAt = includeCreatedAtInLegacyPayload
                    )
                    runCatching { supabaseConfig.client.postgrest[tableName].insert(legacyPayload) }
                        .fold(
                            onSuccess = { Result.success(Unit) },
                            onFailure = { Result.failure(it as? Exception ?: Exception(it.message ?: "Fallback insert failed")) }
                        )
                } else {
                    Result.failure(firstTry.exceptionOrNull() as? Exception ?: Exception(errMsg.ifBlank { "Fallback insert failed" }))
                }
            }
        } else {
            runCatching { supabaseConfig.client.postgrest[tableName].insert(payload) }
                .fold(
                    onSuccess = { Result.success(Unit) },
                    onFailure = { Result.failure(it as? Exception ?: Exception(it.message ?: "Fallback insert failed")) }
                )
        }
    }

    private fun buildLegacyPayload(
        payload: JsonObject,
        scopePayloadKey: String,
        includeCreatedAt: Boolean
    ): JsonObject {
        return buildJsonObject {
            payload["id"]?.let { put("id", it) }
            payload[scopePayloadKey]?.let { put(scopePayloadKey, it) }
            payload["sender_id"]?.let { put("sender_id", it) }
            payload["content"]?.let { put("content", it) }
            payload["content_type"]?.let { put("content_type", it) }
            payload["media_url"]?.let { put("media_url", it) }
            payload["reply_to_id"]?.let { put("reply_to_id", it) }
            payload["poll_id"]?.let { put("poll_id", it) }
            if (includeCreatedAt) {
                payload["created_at"]?.let { put("created_at", it) }
            }
        }
    }

    private fun isMissingRpcFunction(statusCode: Int, body: String, functionName: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val cleanBody = body.lowercase()
        return cleanBody.contains(functionName.lowercase()) &&
            (cleanBody.contains("could not find the function") || cleanBody.contains("undefined function"))
    }
}


