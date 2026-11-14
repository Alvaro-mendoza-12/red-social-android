package conectate.com.di

import conectate.com.data.repository.CallRepository
import conectate.com.domain.calls.CallForegroundSessionController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CallForegroundModule {
    @Binds
    abstract fun callForegroundController(repository: CallRepository): CallForegroundSessionController
}
