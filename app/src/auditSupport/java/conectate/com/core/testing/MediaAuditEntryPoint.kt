package conectate.com.core.testing

import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Compiled only into debuggable/isolated audit variants, never the production release. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaAuditEntryPoint {
    fun authService(): AuthService
    fun storageService(): StorageService
}
