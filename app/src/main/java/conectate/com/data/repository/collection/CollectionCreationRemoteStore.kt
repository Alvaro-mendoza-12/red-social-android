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
        membershipPayloads: List<JsonObject>,
        requireCurrent: () -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            requireCurrent()
            supabaseConfig.client.postgrest[collectionTable].insert(collectionPayload)
            requireCurrent()
            if (membershipPayloads.isNotEmpty()) {
                supabaseConfig.client.postgrest[membershipTable].insert(membershipPayloads)
            }
            requireCurrent()
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
