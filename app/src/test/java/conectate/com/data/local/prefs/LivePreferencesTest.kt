package conectate.com.data.local.prefs

import conectate.com.data.webrtc.LiveCameraFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePreferencesTest {

    private class InMemoryStorage : LivePreferenceStorage {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    @Test
    fun `selected filter survives a new preferences instance`() {
        val storage = InMemoryStorage()
        LivePreferences(storage).setCameraFilter(LiveCameraFilter.VIVID)

        val recreated = LivePreferences(storage)

        assertEquals(LiveCameraFilter.VIVID, recreated.cameraFilter)
    }

    @Test
    fun `unknown stored filter safely falls back to natural`() {
        val storage = InMemoryStorage()
        storage.putString("camera_filter", "future_filter")

        assertEquals(LiveCameraFilter.NONE, LivePreferences(storage).cameraFilter)
    }
}
