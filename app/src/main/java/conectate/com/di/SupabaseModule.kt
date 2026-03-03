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
    
    // provideSupabaseConfig removed to avoid duplicate binding with @Inject constructor
}