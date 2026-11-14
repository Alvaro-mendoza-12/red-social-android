package conectate.com.di

import conectate.com.data.repository.LiveRepository
import conectate.com.domain.live.LiveForegroundSessionController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class LiveForegroundModule {
    @Binds
    abstract fun bindLiveForegroundSessionController(repository: LiveRepository): LiveForegroundSessionController
}
