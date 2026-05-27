package conectate.com.di

import conectate.com.data.supabase.SupabaseConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {
    
    @Provides
    @Singleton
    fun provideSupabaseClient(config: SupabaseConfig): io.github.jan.supabase.SupabaseClient {
        return config.client
    }
}