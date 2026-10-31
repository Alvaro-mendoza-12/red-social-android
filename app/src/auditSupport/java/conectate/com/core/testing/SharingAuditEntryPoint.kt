package conectate.com.core.testing

import conectate.com.data.repository.*
import conectate.com.data.supabase.AuthService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Audit builds only; lets device tests follow sharing through the real repositories. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SharingAuditEntryPoint {
    fun authService(): AuthService
    fun authRepository(): AuthRepository
    fun messageRepository(): MessageRepository
    fun groupRepository(): GroupRepository
    fun communityRepository(): CommunityRepository
    fun postRepository(): PostRepository
    fun momentRepository(): MomentRepository
    fun userRepository(): UserRepository
    fun shareRepository(): ShareRepository
}
