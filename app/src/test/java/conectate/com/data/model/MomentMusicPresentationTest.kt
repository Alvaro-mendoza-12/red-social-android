package conectate.com.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MomentMusicPresentationTest {
    @Test fun `wire contract has exactly three choices and a legacy default`() {
        assertEquals(listOf("card", "label", "audio"), MomentMusicPresentation.entries.map { it.wireValue })
        assertEquals(MomentMusicPresentation.CARD, MomentMusicPresentation.fromWire(null))
        assertEquals(MomentMusicPresentation.CARD, MomentMusicPresentation.fromWire("unknown"))
        assertEquals(MomentMusicPresentation.AUDIO_ONLY, MomentMusicPresentation.fromWire("audio"))
    }

    @Test fun `old moments load without new music fields`() {
        val moment = Json.decodeFromString<Moment>("""{"user_id":"owner","media_url":"media","media_type":"image"}""")
        assertEquals("card", moment.musicPresentation)
        assertNull(moment.musicArtworkUrl)
    }

    @Test fun `all styles and cover survive serialization without changing audio or mix`() {
        MomentMusicPresentation.entries.forEach { presentation ->
            val expected = Moment(userId = "owner", mediaUrl = "media", mediaType = "video",
                musicPresentation = presentation.wireValue, musicArtworkUrl = "https://example.test/cover.jpg",
                musicAudioUrl = "audio", musicStartMs = 12_000, musicDurationMs = 6_000,
                musicEmbedded = true, musicVolume = 0.8f, originalAudioVolume = 0.3f)
            assertEquals(expected, Json.decodeFromString<Moment>(Json.encodeToString(expected)))
        }
    }
}
