package conectate.com.domain.sync

interface ChatSyncDataSource {
    suspend fun syncChats(): Result<Unit>
    suspend fun retryPendingMessages(maxBatch: Int = 50): Result<Int>
    suspend fun retryPendingDeletes(maxBatch: Int = 50): Result<Int>
}

