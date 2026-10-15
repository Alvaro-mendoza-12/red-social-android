package conectate.com.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class ConversationNotificationPrefsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = ConversationNotificationPrefs(context)
    @Before fun clear() { context.getSharedPreferences("conversation_notification_prefs", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun timedMuteIsVisibleAcrossInstancesAndClearsOnExplicitUnmute() {
        prefs.muteConversationUntil("chat", "room", System.currentTimeMillis()+28_800_000L, "a")
        assertTrue(prefs.isMuted("chat","room","a"))
        prefs.unmuteConversation("chat","room","a")
        assertFalse(prefs.isMuted("chat","room","a"))
    }
    @Test fun accountAndSurfaceDoNotInheritAnotherMute() {
        prefs.muteConversationUntil("chat","room",System.currentTimeMillis()+28_800_000L,"a")
        assertFalse(prefs.isMuted("chat","room","b"))
        assertFalse(prefs.isMuted("group","room","a"))
        assertFalse(prefs.isMuted("community","room","a"))
    }
    @Test fun expiredMuteIsRemoved() {
        val raw=context.getSharedPreferences("conversation_notification_prefs",Context.MODE_PRIVATE)
        raw.edit().putStringSet("muted_conversations",setOf("a:chat:room"))
            .putLong("mute_until_a:chat:room",System.currentTimeMillis()-1).commit()
        assertFalse(prefs.isMuted("chat","room","a"))
        assertFalse(raw.contains("mute_until_a:chat:room"))
    }
    @Test fun permanentChoiceRemovesOldExpiry() {
        prefs.muteConversationUntil("chat","room",System.currentTimeMillis()+28_800_000L,"a")
        prefs.muteConversation("chat","room","a")
        assertFalse(context.getSharedPreferences("conversation_notification_prefs",Context.MODE_PRIVATE).contains("mute_until_a:chat:room"))
        assertTrue(prefs.isMuted("chat","room","a"))
    }
    @Test fun oldGlobalKeysCannotSilenceOtherSignedInAccounts() {
        prefs.muteConversation("chat","room")
        assertTrue(prefs.isMuted("chat","room"))
        assertFalse(prefs.isMuted("chat","room","a"))
        assertFalse(prefs.isMuted("chat","room","b"))
    }
    @Test fun idsAreNormalizedAndBlankInputsIgnored() {
        prefs.muteConversation(" CHAT "," ROOM "," A ")
        assertTrue(prefs.isMuted("chat","room","a"))
        prefs.muteConversation("","room","a");prefs.muteConversation("chat","","a")
        assertFalse(prefs.isMuted("chat","","a"))
    }
    @Test fun observerSeesAnotherInstancesMuteAndManualUnmute() = runBlocking {
        withTimeout(5000) { prefs.observeChanges().first() }
        val muted=async(start=CoroutineStart.UNDISPATCHED) { withTimeout(5000) { prefs.observeChanges().first { prefs.isMuted("chat","room","a") } } }
        yield();prefs.muteConversation("chat","room","a");muted.await()
        val unmuted=async(start=CoroutineStart.UNDISPATCHED) { withTimeout(5000) { prefs.observeChanges().first { !prefs.isMuted("chat","room","a") } } }
        yield();prefs.unmuteConversation("chat","room","a");unmuted.await()
        assertFalse(prefs.isMuted("chat","room","a"))
    }
    @Test fun observerEmitsAtExpiryWithoutPollingOrAnotherPreferenceWrite() = runBlocking {
        prefs.muteConversationUntil("chat","room",System.currentTimeMillis()+150L,"a")
        withTimeout(5000) { prefs.observeChanges().first { !prefs.isMuted("chat","room","a") } }
        assertFalse(prefs.isMuted("chat","room","a"))
    }
}
