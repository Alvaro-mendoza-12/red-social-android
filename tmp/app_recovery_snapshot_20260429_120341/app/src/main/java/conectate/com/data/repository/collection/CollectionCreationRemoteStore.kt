package conectate.com.data.repository.collection

import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal class CollectionCreationRemoteStore(
    private val supabaseConfig: SupabaseConfig
) {
    suspend fun createCollectionWithMemberships(
        collectionTable: String,
        collectionPayload: JsonObject,
        membershipTable: String,
        membershipPayloads: List<JsonObject>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseConfig.client.postgrest[collectionTable].insert(collectionPayload)
            if (membershipPayloads.isNotEmpty()) {
                supabaseConfig.client.postgrest[membershipTable].insert(membershipPayloads)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
