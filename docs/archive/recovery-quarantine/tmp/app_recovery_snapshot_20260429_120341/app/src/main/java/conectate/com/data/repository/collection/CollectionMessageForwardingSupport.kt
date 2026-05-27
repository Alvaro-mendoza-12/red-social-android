package conectate.com.data.repository.collection

internal object CollectionMessageForwardingSupport {
    suspend fun forwardMessage(
        sourceDeletedForAll: Boolean,
        sourceScopeId: String,
        targetScopeIds: List<String>,
        maxTargetsPerBatch: Int,
        emptySelectionMessage: String,
        batchLimitMessage: String,
        totalFailureMessage: String,
        sendToTarget: suspend (String) -> Result<Unit>
    ): Result<Int> {
        if (sourceDeletedForAll) {
            return Result.failure(Exception("No se puede reenviar un mensaje eliminado"))
        }

        val cleanSourceScopeId = sourceScopeId.trim().lowercase()
        val targets = targetScopeIds
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { it != cleanSourceScopeId }

        if (targets.isEmpty()) return Result.failure(Exception(emptySelectionMessage))
        if (targets.size > maxTargetsPerBatch) {
            return Result.failure(Exception(batchLimitMessage))
        }

        var success = 0
        targets.forEach { targetId ->
            val result = sendToTarget(targetId)
            if (result.isSuccess) success++
        }

        if (success == 0) return Result.failure(Exception(totalFailureMessage))
        return Result.success(success)
    }
}



