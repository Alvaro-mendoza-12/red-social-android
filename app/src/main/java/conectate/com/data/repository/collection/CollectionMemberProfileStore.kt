package conectate.com.data.repository.collection

import androidx.compose.runtime.Composable

import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class CollectionMemberProfile(
    val userName: String,
    val userImage: String?
)

internal class CollectionMemberProfileStore(
    private val supabaseConfig: SupabaseConfig
) {
    suspend fun loadProfiles(userIds: Collection<String>): Map<String, CollectionMemberProfile> = withContext(Dispatchers.IO) {
        val cleanIds = userIds
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (cleanIds.isEmpty()) return@withContext emptyMap()

        runCatching {
            supabaseConfig.client.postgrest["profiles"].select {
                filter { isIn("id", cleanIds) }
            }.decodeList<JsonObject>().associateNotNull { profile ->
                val id = profile["id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                if (id.isNullOrBlank()) return@associateNotNull null

                val displayName = profile["display_name"]?.jsonPrimitive?.contentOrNull
                    ?: profile["full_name"]?.jsonPrimitive?.contentOrNull
                    ?: profile["username"]?.jsonPrimitive?.contentOrNull
                    ?: "Usuario"
                val avatarUrl = profile["avatar_url"]?.jsonPrimitive?.contentOrNull
                id to CollectionMemberProfile(
                    userName = displayName,
                    userImage = avatarUrl
                )
            }
        }.getOrDefault(emptyMap())
    }

    private inline fun <T, K, V> Iterable<T>.associateNotNull(transform: (T) -> Pair<K, V>?): Map<K, V> {
        val destination = LinkedHashMap<K, V>()
        for (element in this) {
            val pair = transform(element) ?: continue
            destination[pair.first] = pair.second
        }
        return destination
    }
}




