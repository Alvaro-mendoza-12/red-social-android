package conectate.com.data.repository.collection

internal object CollectionMembershipMutationSupport {
    suspend fun leaveCurrentUserCollection(
        scopeId: String,
        currentUserId: String,
        applyLocalOptimistic: suspend (cleanScopeId: String, cleanUserId: String) -> Unit,
        remoteDelete: suspend (cleanScopeId: String, cleanUserId: String) -> Result<Unit>,
        applyLocalAfterRemote: (suspend (cleanScopeId: String, cleanUserId: String) -> Unit)? = null
    ): Result<Unit> {
        return try {
            var cleanScopeId = scopeId.trim().lowercase()
            var cleanUserId = currentUserId.trim().lowercase()
            applyLocalOptimistic(cleanScopeId, cleanUserId)
            remoteDelete(cleanScopeId, cleanUserId).getOrThrow()
            applyLocalAfterRemote?.invoke(cleanScopeId, cleanUserId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinCurrentUserCollection(
        scopeId: String,
        currentUserId: String,
        joinedAt: String,
        applyLocalOptimistic: suspend (cleanScopeId: String, cleanUserId: String, joinedAt: String) -> Unit,
        remoteInsert: suspend (cleanScopeId: String, cleanUserId: String, joinedAt: String) -> Result<Unit>,
        afterRemote: suspend () -> Unit = {}
    ): Result<Unit> {
        return try {
            var cleanScopeId = scopeId.trim().lowercase()
            var cleanUserId = currentUserId.trim().lowercase()
            applyLocalOptimistic(cleanScopeId, cleanUserId, joinedAt)
            remoteInsert(cleanScopeId, cleanUserId, joinedAt).getOrThrow()
            afterRemote()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}



