package conectate.com.domain.sync

interface GroupSyncDataSource {
    suspend fun syncGroups(): Result<Unit>
}

