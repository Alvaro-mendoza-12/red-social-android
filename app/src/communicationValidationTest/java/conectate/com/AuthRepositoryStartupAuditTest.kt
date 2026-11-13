package conectate.com

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.auth.twoFactor.TwoFactorAuthManager
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.repository.*
import conectate.com.data.service.MessageNotificationReceiverEntryPoint
import conectate.com.data.supabase.SupabaseConfig
import dagger.Lazy
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** Reads real restored auth state without creating graphs unrelated to first draw. */
@RunWith(AndroidJUnit4::class)
class AuthRepositoryStartupAuditTest {
    private fun <T> unused(name: String): Lazy<T> = Lazy {
        throw AssertionError("Auth-state access eagerly resolved $name")
    }

    @Test fun firstAuthStateReadsDoNotResolveSecondaryRepositoriesOrKeystores() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The production Hilt component cannot implement entry points declared
        // only in a separate test APK. Reuse its existing production entry point.
        val service = EntryPointAccessors.fromApplication(
            context, MessageNotificationReceiverEntryPoint::class.java
        ).authService()
        fun dependency(name: String): Any = checkNotNull(
            service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
        )
        val repository = AuthRepository(
            authService = service,
            accountSessionRepositoryProvider = unused<AccountSessionRepository>("saved accounts"),
            supabaseConfig = dependency("supabaseConfig") as SupabaseConfig,
            userRepositoryProvider = unused<UserRepository>("profiles"),
            messageRepositoryProvider = unused<MessageRepository>("messages"),
            groupRepositoryProvider = unused<GroupRepository>("groups"),
            communityRepositoryProvider = unused<CommunityRepository>("communities"),
            notificationRepositoryProvider = unused<NotificationRepository>("notifications"),
            walletRepositoryProvider = unused<WalletRepository>("wallet"),
            algorithmRepositoryProvider = unused<AlgorithmRepository>("algorithm"),
            linkPreviewRepositoryProvider = unused<LinkPreviewRepository>("link previews"),
            twoFactorAuthManagerProvider = unused<TwoFactorAuthManager>("second factor"),
            localFallbackApiClient = dependency("localFallbackApiClient") as LocalFallbackApiClient
        )
        assertSame(service.currentSession, repository.getAuthStateFlow())
        assertSame(service.isInitializing, repository.isInitializing)
    }
}
