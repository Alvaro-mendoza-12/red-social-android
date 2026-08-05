package conectate.com.presentation.live

import conectate.com.data.model.LiveQuality
import conectate.com.data.model.LiveSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSetupPolicyTest {
    @Test
    fun `requires title category and network before starting`() {
        val result = evaluateLiveSetup(
            title = "",
            category = "",
            source = LiveSource.CAMERA,
            micEnabled = true,
            quality = LiveQuality.P720,
            networkAvailable = false
        )

        assertFalse(result.canStart)
        assertTrue(result.titleMessage?.contains("título") == true)
        assertFalse(result.checks.last().ready)
    }

    @Test
    fun `valid setup can start and explains screen permission`() {
        val result = evaluateLiveSetup(
            title = "Charlamos sobre tecnología",
            category = "Tecnología",
            source = LiveSource.SCREEN,
            micEnabled = false,
            quality = LiveQuality.P480,
            networkAvailable = true
        )

        assertTrue(result.canStart)
        assertTrue(result.checks.first { it.label == "Fuente" }.detail.contains("cada sesión"))
    }

    @Test
    fun `audio only setup does not request video and explains profile fallback`() {
        val result = evaluateLiveSetup(
            title = "Conversación solo voz",
            category = "Charlando",
            source = LiveSource.AUDIO,
            micEnabled = true,
            quality = LiveQuality.P480,
            networkAvailable = true
        )

        assertTrue(result.canStart)
        assertTrue(result.checks.first { it.label == "Fuente" }.detail.contains("foto de perfil"))
        assertEquals(LiveSource.AUDIO, liveSourceFromWire("audio"))
        assertFalse(requiredHostRuntimePermissions(LiveSource.AUDIO, true, false).contains(android.Manifest.permission.CAMERA))
    }

    @Test
    fun `category is included once and hashtag count is bounded`() {
        val parsed = parseLiveHashtags(
            raw = "#gaming gaming uno dos tres cuatro cinco seis siete ocho nueve diez once",
            category = "Gaming"
        )

        assertEquals("gaming", parsed.first())
        assertEquals(10, parsed.size)
        assertEquals(1, parsed.count { it == "gaming" })
    }
}
