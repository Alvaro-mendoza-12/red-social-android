package conectate.com.di

import conectate.com.data.repository.CommunityRepository
import conectate.com.data.repository.GroupRepository
import conectate.com.data.repository.MessageRepository
import conectate.com.domain.sync.ChatSyncDataSource
import conectate.com.domain.sync.CommunitySyncDataSource
import conectate.com.domain.sync.GroupSyncDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncDataSourceModule {

    @Binds
    @Singleton
    abstract fun bindChatSyncDataSource(impl: MessageRepository): ChatSyncDataSource

    @Binds
    @Singleton
    abstract fun bindGroupSyncDataSource(impl: GroupRepository): GroupSyncDataSource

    @Binds
    @Singleton
    abstract fun bindCommunitySyncDataSource(impl: CommunityRepository): CommunitySyncDataSource
}
