package conectate.com.domain.sync

interface CommunitySyncDataSource {
    suspend fun syncCommunities(): Result<Unit>
}

