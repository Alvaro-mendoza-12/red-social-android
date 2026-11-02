package conectate.com.data.repository.collection

import kotlinx.coroutines.ensureActive

internal object CollectionMessageForwardingSupport {
    suspend fun forwardMessage(
        sourceDeletedForAll: Boolean,
        sourceScopeId: String,
        targetScopeIds: List<String>,
        maxTargetsPerBatch: Int,
        emptySelectionMessage: String,
        batchLimitMessage: String,
        totalFailureMessage: String,
        sourceContentType: String = "text",
        sourceStatus: String = "sent",
        sourceMediaUrl: String? = null,
        sendToTarget: suspend (String) -> Result<Unit>
    ): Result<Int> {
        conectate.com.core.messaging.messageForwardingError(sourceDeletedForAll, sourceContentType, sourceStatus, sourceMediaUrl)
            ?.let { return Result.failure(IllegalStateException(it)) }

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
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val result = sendToTarget(targetId)
            result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (result.isSuccess) success++
        }

        if (success == 0) return Result.failure(Exception(totalFailureMessage))
        return Result.success(success)
    }
}



