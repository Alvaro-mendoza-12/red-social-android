package conectate.com.data.repository

import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.CollectionMembershipPolicy
import conectate.com.data.supabase.AuthService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionMembershipRepository @Inject constructor(
    private val api: LocalFallbackApiClient,
    private val auth: AuthService
) {
    private suspend fun <T> result(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Result.failure(error)
    }

    private suspend fun token(): String = auth.getAccessTokenAwait()?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("No se pudo conectar. Inténtalo de nuevo.")

    suspend fun policy(collection: String, roomId: String): Result<CollectionMembershipPolicy> = result {
        api.loadCollectionMembershipPolicy(token(), collection, roomId).getOrThrow()
    }
    suspend fun setMembersCanAdd(collection: String, roomId: String, enabled: Boolean): Result<CollectionMembershipPolicy> = result {
        api.setCollectionMembersCanAdd(token(), collection, roomId, enabled).getOrThrow()
    }
    suspend fun add(collection: String, roomId: String, userId: String, from: String?, until: String?): Result<Unit> = result {
        api.addCollectionMember(token(), collection, roomId, userId, from, until).getOrThrow()
    }
}
