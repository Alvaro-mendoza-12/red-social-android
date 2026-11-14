package conectate.com.di

import conectate.com.data.repository.DefaultLiveSessionPlatform
import conectate.com.data.repository.LiveSessionPlatform
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class LiveMediaModule {
    @Binds
    abstract fun bindLiveSessionPlatform(platform: DefaultLiveSessionPlatform): LiveSessionPlatform
}
