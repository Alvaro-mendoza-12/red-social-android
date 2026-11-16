package conectate.com.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.supabase.AuthService
import conectate.com.services.VoiceForegroundService
import conectate.com.test.UnitTestApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 34], application = UnitTestApplication::class)
class VoiceForegroundStopTest {
    private class Commands(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        val stopped = mutableListOf<Intent>()
        override fun startService(service: Intent): ComponentName? { started += service; return service.component }
        override fun startForegroundService(service: Intent): ComponentName? { started += service; return service.component }
        override fun stopService(service: Intent): Boolean { stopped += service; return false }
    }

    @Test fun stoppingAnAbsentRoomServiceNeverStartsItOrCreatesAForegroundDeadline() {
        val context = Commands(ApplicationProvider.getApplicationContext())
        val auth = mockk<AuthService>(relaxed = true)
        every { auth.observeSession() } returns MutableStateFlow<conectate.com.data.supabase.AuthResponse?>(null)
        val repository = VoiceRoomRepository(context, mockk(relaxed = true), auth,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true))
        try {
            // Isolate the actual stop command; no live room, sockets or account writes are needed.
            val stop = VoiceRoomRepository::class.java.getDeclaredMethod("stopVoiceForegroundService").apply { isAccessible = true }
            repeat(2) { stop.invoke(repository) }
            assertTrue("A stop must not start a service that Android requires to promote", context.started.isEmpty())
            assertEquals(2, context.stopped.size)
            assertTrue(context.stopped.all { it.component?.className == VoiceForegroundService::class.java.name })
        } finally {
            val field = VoiceRoomRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (field.get(repository) as CoroutineScope).cancel()
        }
    }
}
