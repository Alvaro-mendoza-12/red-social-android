package conectate.com.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class InboxPreferenceChangesTest {
    private fun store(): SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("inbox-observation-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    @Test fun `a hint and its acknowledgement repaint without another network or account emission`() = runTest {
        val prefs = store()
        val values = mutableListOf<Int>()
        val observing = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeInboxPreferenceChanges { prefs }.collect {
                values += prefs.getStringSet("pending_conversation_a", emptySet()).orEmpty().size
            }
        }
        runCurrent()
        assertEquals(listOf(0), values)
        prefs.edit().putStringSet("pending_conversation_a", setOf("chat:one", "group:two")).apply()
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(2, values.last())
        prefs.edit().remove("pending_conversation_a").apply()
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(0, values.last())
        observing.cancelAndJoin()
    }

    @Test fun `rapid preference updates converge to the latest saved counter`() = runTest {
        val prefs = store()
        val values = mutableListOf<Int>()
        val observing = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeInboxPreferenceChanges { prefs }.collect { values += prefs.getInt("cached_direct_unread_a", 0) }
        }
        runCurrent()
        repeat(50) { prefs.edit().putInt("cached_direct_unread_a", it + 1).apply() }
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(50, values.last())
        observing.cancelAndJoin()
    }

    @Test fun `closing observation unregisters its strong preference listener`() = runTest {
        val delegate = store()
        var registered = 0
        var removed = 0
        val tracking = object : SharedPreferences by delegate {
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
                registered++
                delegate.registerOnSharedPreferenceChangeListener(listener)
            }
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
                removed++
                delegate.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }
        val observing = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeInboxPreferenceChanges { tracking }.collect { }
        }
        runCurrent()
        assertEquals(1, registered)
        assertEquals(0, removed)
        observing.cancelAndJoin()
        assertEquals(1, removed)
    }
}
