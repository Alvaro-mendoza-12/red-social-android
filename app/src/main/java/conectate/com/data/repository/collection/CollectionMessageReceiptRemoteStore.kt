package conectate.com.data.repository.collection

import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class CollectionMessageReceiptRemoteStore(
    private val supabaseConfig: SupabaseConfig
) {
    suspend fun markMessagesReadByIds(
        logTag: String,
        messageTable: String,
        scopeColumn: String,
        scopeId: String,
        messageIds: Collection<String>,
        currentUserId: String,
        token: String?,
        eligibleSourceStatuses: List<String> = emptyList(),
        requireReadAtNull: Boolean = true,
        confirmationStatuses: List<String> = listOf("read")
    ): Set<String> {
        var cleanScopeId = scopeId.trim().lowercase()
        var cleanUserId = currentUserId.trim().lowercase()
        var cleanMessageIds = normalizeIds(messageIds)
        if (cleanScopeId.isBlank() || cleanUserId.isBlank() || cleanMessageIds.isEmpty() || token.isNullOrBlank()) {
            return emptySet()
        }

        var now = java.time.Instant.now().toString()
        val updatedIds = runCatching {
            var response = supabaseConfig.httpClient.patch(restUrl(messageTable)) {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                parameter(scopeColumn, "eq.$cleanScopeId")
                parameter("sender_id", "neq.$cleanUserId")
                parameter("id", "in.(${cleanMessageIds.joinToString(",")})")
                if (requireReadAtNull) {
                    parameter("read_at", "is.null")
                }
                if (eligibleSourceStatuses.isNotEmpty()) {
                    parameter("status", "in.(${normalizeIds(eligibleSourceStatuses).joinToString(",")})")
                }
                parameter("select", "id")
                setBody(
                    buildJsonObject {
                        put("status", "read")
                        put("read_at", now)
                        put("delivered_at", now)
                        put("updated_at", now)
                    }
                )
            }
            if (!response.status.isSuccess()) return@runCatching emptySet<String>()
            CollectionRepositorySupport.extractUpdatedIdsFromPatchBody(response.bodyAsText())
        }.onFailure { e ->
            android.util.Log.w(logTag, "markMessagesReadByIds remote sync failed for $cleanScopeId", e)
        }.getOrDefault(emptySet())

        if (updatedIds.isNotEmpty() || confirmationStatuses.isEmpty()) {
            return updatedIds
        }

        return fetchRemoteMessageIdsByStatus(
            messageTable = messageTable,
            scopeColumn = scopeColumn,
            scopeId = cleanScopeId,
            messageIds = cleanMessageIds,
            currentUserId = cleanUserId,
            token = token,
            statuses = confirmationStatuses
        )
    }

    suspend fun markAllMessagesRead(
        logTag: String,
        messageTable: String,
        scopeColumn: String,
        scopeId: String,
        currentUserId: String,
        token: String?,
        rpcFunctionName: String,
        rpcScopeKey: String,
        eligibleSourceStatuses: List<String> = emptyList(),
        requireReadAtNull: Boolean = true
    ): Boolean {
        var cleanScopeId = scopeId.trim().lowercase()
        var cleanUserId = currentUserId.trim().lowercase()
        if (cleanScopeId.isBlank() || cleanUserId.isBlank() || token.isNullOrBlank()) {
            android.util.Log.w(logTag, "markAllMessagesRead: no session token; remote sync skipped")
            return false
        }

        val rpcSynced = runCatching {
            supabaseConfig.httpClient.post(rpcUrl(rpcFunctionName)) {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put(rpcScopeKey, cleanScopeId) })
            }
        }.fold(
            onSuccess = { response ->
                var success = response.status.isSuccess()
                if (!success) {
                    android.util.Log.w(logTag, "$rpcFunctionName failed for $cleanScopeId: ${response.status}")
                }
                success
            },
            onFailure = { e ->
                android.util.Log.w(logTag, "$rpcFunctionName exception for $cleanScopeId", e)
                false
            }
        )
        if (rpcSynced) return true

        var now = java.time.Instant.now().toString()
        return runCatching {
            var response = supabaseConfig.httpClient.patch(restUrl(messageTable)) {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter(scopeColumn, "eq.$cleanScopeId")
                parameter("sender_id", "neq.$cleanUserId")
                if (eligibleSourceStatuses.isNotEmpty()) {
                    parameter("status", "in.(${normalizeIds(eligibleSourceStatuses).joinToString(",")})")
                }
                if (requireReadAtNull) {
                    parameter("read_at", "is.null")
                }
                setBody(
                    buildJsonObject {
                        put("status", "read")
                        put("read_at", now)
                        put("delivered_at", now)
                        put("updated_at", now)
                    }
                )
            }
            response.status.isSuccess()
        }.onFailure { e ->
            android.util.Log.w(logTag, "markAllMessagesRead fallback patch failed for $cleanScopeId", e)
        }.getOrDefault(false)
    }

    suspend fun markMessageDelivered(
        logTag: String,
        messageTable: String,
        scopeColumn: String,
        scopeId: String,
        messageId: String,
        currentUserId: String,
        token: String?,
        confirmationStatuses: List<String> = listOf("delivered", "read", "received")
    ): Boolean {
        var cleanScopeId = scopeId.trim().lowercase()
        val cleanMessageId = messageId.trim().lowercase()
        var cleanUserId = currentUserId.trim().lowercase()
        if (cleanScopeId.isBlank() || cleanMessageId.isBlank() || cleanUserId.isBlank() || token.isNullOrBlank()) {
            return false
        }

        var now = java.time.Instant.now().toString()
        val patchConfirmed = runCatching {
            var response = supabaseConfig.httpClient.patch(restUrl(messageTable)) {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                parameter(scopeColumn, "eq.$cleanScopeId")
                parameter("id", "eq.$cleanMessageId")
                parameter("sender_id", "neq.$cleanUserId")
                parameter("status", "eq.sent")
                parameter("select", "id")
                setBody(
                    buildJsonObject {
                        put("status", "delivered")
                        put("delivered_at", now)
                        put("updated_at", now)
                    }
                )
            }
            response.status.isSuccess() &&
                CollectionRepositorySupport.extractUpdatedIdsFromPatchBody(response.bodyAsText()).contains(cleanMessageId)
        }.onFailure { e ->
            android.util.Log.w(logTag, "markMessageAsDelivered remote sync failed for $cleanMessageId", e)
        }.getOrDefault(false)

        if (patchConfirmed) return true

        return fetchRemoteMessageIdsByStatus(
            messageTable = messageTable,
            scopeColumn = scopeColumn,
            scopeId = cleanScopeId,
            messageIds = listOf(cleanMessageId),
            currentUserId = cleanUserId,
            token = token,
            statuses = confirmationStatuses
        ).contains(cleanMessageId)
    }

    suspend fun markAllMessagesDelivered(
        logTag: String,
        scopeId: String,
        token: String?,
        rpcFunctionName: String,
        rpcScopeKey: String
    ): Boolean {
        var cleanScopeId = scopeId.trim().lowercase()
        if (cleanScopeId.isBlank() || token.isNullOrBlank()) return false

        return runCatching {
            var response = supabaseConfig.httpClient.post(rpcUrl(rpcFunctionName)) {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put(rpcScopeKey, cleanScopeId) })
            }
            var success = response.status.isSuccess()
            if (!success) {
                android.util.Log.w(logTag, "$rpcFunctionName failed for $cleanScopeId: ${response.status}")
            }
            success
        }.onFailure { e ->
            android.util.Log.w(logTag, "markAllMessagesDelivered remote sync failed for $cleanScopeId", e)
        }.getOrDefault(false)
    }

    private suspend fun fetchRemoteMessageIdsByStatus(
        messageTable: String,
        scopeColumn: String,
        scopeId: String,
        messageIds: Collection<String>,
        currentUserId: String,
        token: String,
        statuses: List<String>
    ): Set<String> {
        var cleanScopeId = scopeId.trim().lowercase()
        var cleanUserId = currentUserId.trim().lowercase()
        var cleanMessageIds = normalizeIds(messageIds)
        val cleanStatuses = normalizeIds(statuses)
        if (cleanScopeId.isBlank() || cleanUserId.isBlank() || cleanMessageIds.isEmpty() || cleanStatuses.isEmpty()) {
            return emptySet()
        }

        return runCatching {
            var response = supabaseConfig.httpClient.get(restUrl(messageTable)) {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "id")
                parameter(scopeColumn, "eq.$cleanScopeId")
                parameter("sender_id", "neq.$cleanUserId")
                parameter("id", "in.(${cleanMessageIds.joinToString(",")})")
                parameter("status", "in.(${cleanStatuses.joinToString(",")})")
            }
            if (!response.status.isSuccess()) return@runCatching emptySet<String>()
            CollectionRepositorySupport.extractUpdatedIdsFromPatchBody(response.bodyAsText())
        }.getOrElse { emptySet() }
    }

    private fun normalizeIds(values: Collection<String>): List<String> {
        return values.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
    }

    private fun restUrl(tableName: String): String = "${supabaseConfig.supabaseUrl}/rest/v1/$tableName"

    private fun rpcUrl(functionName: String): String = "${supabaseConfig.supabaseUrl}/rest/v1/rpc/$functionName"
}



