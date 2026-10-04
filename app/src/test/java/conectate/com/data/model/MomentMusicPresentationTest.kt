package conectate.com.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MomentMusicPresentationTest {
    @Test fun `wire contract includes timed lyrics and preserves legacy values`() {
        assertEquals(listOf("card", "label", "audio", "lyrics"), MomentMusicPresentation.entries.map { it.wireValue })
        assertEquals(MomentMusicPresentation.CARD, MomentMusicPresentation.fromWire(null))
        assertEquals(MomentMusicPresentation.CARD, MomentMusicPresentation.fromWire("unknown"))
        assertEquals(MomentMusicPresentation.AUDIO_ONLY, MomentMusicPresentation.fromWire("audio"))
    }

    @Test fun `old moments load without new music fields`() {
        val moment = Json.decodeFromString<Moment>("""{"user_id":"owner","media_url":"media","media_type":"image"}""")
        assertEquals("card", moment.musicPresentation)
        assertNull(moment.musicArtworkUrl)
        assertEquals(.5f, moment.musicOverlayX, 0f)
        assertEquals(.5f, moment.musicOverlayY, 0f)
        assertEquals(1f, moment.musicOverlayScale, 0f)
        assertEquals(0f, moment.musicOverlayRotation, 0f)
        assertNull(moment.musicLyrics)
    }

    @Test fun `lyrics and music placement round trip independently of text overlay`() {
        val original = Moment(userId = "owner", mediaUrl = "media", mediaType = "video",
            musicPresentation = "lyrics", musicLyrics = "[00:00]QA A\n[00:02]QA B",
            musicOverlayX = .8f, musicOverlayY = .6f, musicOverlayScale = 1.4f, musicOverlayRotation = -32f,
            overlayX = .1f, overlayY = .2f, musicStartMs = 60_000, musicEmbedded = true)
        assertEquals(original, Json.decodeFromString<Moment>(Json.encodeToString(original)))
    }

    @Test fun `transport normalization rejects nonfinite coordinates and wraps rotation`() {
        assertEquals(.5f, normalizedMomentMusicPosition(Float.NaN), 0f)
        assertEquals(.5f, normalizedMomentMusicPosition(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, normalizedMomentMusicPosition(-7f), 0f)
        assertEquals(1f, normalizedMomentMusicPosition(7f), 0f)
        assertEquals(1f, normalizedMomentMusicScale(Float.NaN), 0f)
        assertEquals(.5f, normalizedMomentMusicScale(-7f), 0f)
        assertEquals(2f, normalizedMomentMusicScale(7f), 0f)
        assertEquals(0f, normalizedMomentMusicRotation(Float.NaN), 0f)
        assertEquals(90f, normalizedMomentMusicRotation(450f), 0f)
        assertEquals(-90f, normalizedMomentMusicRotation(-450f), 0f)
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
