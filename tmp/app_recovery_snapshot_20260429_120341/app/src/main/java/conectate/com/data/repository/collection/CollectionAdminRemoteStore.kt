package conectate.com.data.repository.collection

import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class CollectionAdminRemoteStore(
    private val supabaseConfig: SupabaseConfig
) {
    suspend fun getMemberRole(
        membershipTable: String,
        scopeColumn: String,
        scopeId: String,
        userId: String
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            supabaseConfig.client.postgrest[membershipTable].select {
                filter {
                    eq(scopeColumn, scopeId.trim().lowercase())
                    eq("user_id", userId.trim().lowercase())
                }
            }.decodeSingleOrNull<JsonObject>()?.get("role")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    suspend fun insertMember(
        membershipTable: String,
        scopeColumn: String,
        scopeId: String,
        userId: String,
        role: String = "member",
        joinedAt: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var payload = buildJsonObject {
                put(scopeColumn, scopeId.trim().lowercase())
                put("user_id", userId.trim().lowercase())
                put("role", role)
                put("joined_at", joinedAt)
            }
            supabaseConfig.client.postgrest[membershipTable].insert(payload)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteMember(
        membershipTable: String,
        scopeColumn: String,
        scopeId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseConfig.client.postgrest[membershipTable].delete {
                filter {
                    eq(scopeColumn, scopeId.trim().lowercase())
                    eq("user_id", userId.trim().lowercase())
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateMemberRole(
        membershipTable: String,
        scopeColumn: String,
        scopeId: String,
        userId: String,
        newRole: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseConfig.client.postgrest[membershipTable].update({
                set("role", newRole)
            }) {
                filter {
                    eq(scopeColumn, scopeId.trim().lowercase())
                    eq("user_id", userId.trim().lowercase())
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun softDeleteCollection(
        collectionTable: String,
        scopeId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var payload = buildJsonObject {
                put("is_deleted", true)
            }
            supabaseConfig.client.postgrest[collectionTable].update(payload) {
                filter { eq("id", scopeId.trim().lowercase()) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateCollectionInfo(
        collectionTable: String,
        scopeId: String,
        payload: JsonObject
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseConfig.client.postgrest[collectionTable].update(payload) {
                filter { eq("id", scopeId.trim().lowercase()) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}



