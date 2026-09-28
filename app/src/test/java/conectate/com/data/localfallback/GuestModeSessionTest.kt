package conectate.com.data.localfallback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.presentation.auth.GuestModeViewModel
import conectate.com.test.UnitTestApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class GuestModeSessionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var config: SupabaseConfig
    private lateinit var api: GuestModeApi
    private val profileId = "12345678-1234-4321-8765-123456789abc"

    @Before fun setup() {
        clearPreferences()
        config = SupabaseConfig(context)
        api = GuestModeApi(config, context)
    }

    @After fun teardown() { config.httpClient.close(); clearPreferences() }

    private fun clearPreferences() {
        listOf("guest_session", "guest_conversion", "guest_preview").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun seedIdentity(expiresAt: Long = System.currentTimeMillis() + 60_000L) {
        context.getSharedPreferences("guest_session", Context.MODE_PRIVATE).edit()
            .putString("id", "guest_12345678-1234-4321-8765-123456789abc")
            .putString("username", "guest123456")
            .putString("display_name", "Visitante123456")
            .putString("token", "synthetic-guest-token")
            .putLong("expires_at", expiresAt).commit()
    }

    @Test fun reopeningPreservesAnUnexpiredAnonymousIdentity() {
        seedIdentity()
        val initial = api.savedIdentity()!!
        val reopened = GuestModeApi(config, context).savedIdentity()!!
        assertEquals(initial.id, reopened.id)
        assertEquals(initial.username, reopened.username)
        assertEquals("guest", reopened.role)
    }

    @Test fun expiredOrNearlyExpiredSessionsAreNotRestored() {
        seedIdentity(System.currentTimeMillis() - 1)
        assertFalse(api.hasActiveGuest())
        assertNull(api.savedIdentity())
        seedIdentity(System.currentTimeMillis() + 20_000)
        assertNull(api.savedIdentity())
    }

    @Test fun conversionClearsGuestCredentialsButPreservesTheProfileDestinationOnce() {
        seedIdentity()
        api.rememberPendingProfile(profileId)
        api.clear()
        assertNull(api.savedIdentity())
        assertEquals(profileId, api.consumePendingProfile())
        assertNull(api.consumePendingProfile())
    }

    @Test fun invalidProfileIdsCannotReplaceTheConversionDestination() {
        listOf("-".repeat(36), "1-1-1-1-1", "../profile/admin", "not-a-uuid").forEach {
            api.rememberPendingProfile(profileId)
            api.rememberPendingProfile(it)
            assertNull(api.consumePendingProfile())
        }
        context.getSharedPreferences("guest_conversion", Context.MODE_PRIVATE).edit()
            .putString("profile_id", "-".repeat(36)).commit()
        assertNull(api.consumePendingProfile())
    }

    @Test fun pendingPublicPreviewsAreConsumedOnlyOnce() {
        api.rememberPendingPreview("video", profileId)
        assertEquals(GuestPendingPreview("video", profileId), api.consumePendingPreview())
        assertNull(api.consumePendingPreview())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun anotherDeepLinkIsDeliveredWhileTheGuestScreenIsAlreadyOpen() = runTest {
        val events = mutableListOf<GuestPendingPreview?>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            api.pendingPreview.collect { events += it }
        }
        val action = GuestPendingPreview("crear momentos", "")
        api.rememberPendingPreview(action.type)
        assertEquals(action, api.consumePendingPreview())
        api.rememberPendingPreview(action.type)
        assertEquals(listOf(null, action, null, action), events)
        collector.cancel()
    }

    @Test fun leavingGuestModeClearsIdentityAndPendingNavigation() {
        seedIdentity()
        api.rememberPendingPreview("video", profileId)
        api.rememberPendingProfile(profileId)
        val viewModel = GuestModeViewModel(api)
        assertNotNull(viewModel.identity.value)
        viewModel.leave()
        assertNull(viewModel.identity.value)
        assertNull(api.savedIdentity())
        assertNull(api.consumePendingProfile())
        assertNull(api.consumePendingPreview())
    }

    @Test fun aLateSessionResponseCannotRestoreGuestAfterLogoutOrConversion() {
        val startedRevision = api.currentSessionRevision()
        val lateIdentity = GuestIdentity("guest", "guest_$profileId", "guest123456", "Visitante123456",
            "synthetic-guest-token", 3600)
        api.clear()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            api.persistIdentity(lateIdentity, startedRevision)
        }
        assertNull(api.savedIdentity())
        api.persistIdentity(lateIdentity, api.currentSessionRevision())
        assertEquals(lateIdentity.id, api.savedIdentity()!!.id)
    }
}
