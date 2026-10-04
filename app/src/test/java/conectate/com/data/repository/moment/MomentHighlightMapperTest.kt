package conectate.com.data.repository.moment

import conectate.com.presentation.social.asMoment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class MomentHighlightMapperTest {
    private val item = Json.parseToJsonElement("""{"id":"item","highlight_id":"highlight",
        "moment_id":"moment","created_at":"2026-09-17T13:00:00Z","sort_order":2}""").jsonObject

    @Test fun `publication time and author belong to original moment not highlight or viewer`() {
        val original = Json.parseToJsonElement("""{"id":"moment","user_id":"author",
            "media_url":"https://example.test/video.mp4","media_type":"video",
            "created_at":"2026-09-16T13:00:00Z"}""").jsonObject
        val preview = requireNotNull(item.toHighlightItemPreview(original))
        assertEquals("author", preview.userId)
        assertEquals("2026-09-16T13:00:00Z", preview.createdAt)
        assertEquals(2, preview.sortOrder)
        val moment = preview.copy(userDisplayName = "Author", userAvatarUrl = "avatar").asMoment()
        assertEquals(preview.createdAt, moment.createdAt)
        assertEquals("Author", moment.userDisplayName)
        assertEquals("avatar", moment.userAvatarUrl)
        assertEquals("author", moment.userId)
    }

    @Test fun `missing original timestamp is not replaced with highlight creation`() {
        val original = Json.parseToJsonElement("""{"media_url":"media"}""").jsonObject
        assertNull(requireNotNull(item.toHighlightItemPreview(original)).createdAt)
        assertNull(item.toHighlightItemPreview(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test fun `soundtrack mix attribution overlay and expiry survive both mappings`() {
        val original = Json.parseToJsonElement("""{"media_url":"video","media_type":"video",
            "music_audio_url":"song","music_url":"legacy","music_id":"song-id","music_title":"Title",
            "music_artist":"Artist","music_presentation":"label","music_artwork_url":"https://example.test/cover.jpg",
            "music_source":"community","music_source_url":"source",
            "music_license":"CC","music_license_url":"license","music_start_ms":30000,
            "music_duration_ms":25000,"music_end_ms":55000,"music_volume":0.8,"original_audio_volume":0.3,
            "music_fade_in_ms":500,"music_fade_out_ms":600,"duck_original_audio":true,"music_embedded":true,
            "overlay_text":"Text","overlay_color":"#FF000000","overlay_font":"serif",
            "overlay_scale":1.5,"overlay_x":0.4,"overlay_y":0.3,"display_duration_ms":25000,
            "expires_at":"2026-09-18T12:00:00Z"}""").jsonObject
        val moment = requireNotNull(item.toHighlightItemPreview(original)).asMoment()
        assertEquals("song", moment.musicAudioUrl)
        assertEquals("legacy", moment.musicUrl)
        assertEquals("song-id", moment.musicId)
        assertEquals("Title", moment.musicTitle)
        assertEquals("Artist", moment.musicArtist)
        assertEquals("label", moment.musicPresentation)
        assertEquals("https://example.test/cover.jpg", moment.musicArtworkUrl)
        assertEquals("community", moment.musicSource)
        assertEquals("source", moment.musicSourceUrl)
        assertEquals("CC", moment.musicLicense)
        assertEquals("license", moment.musicLicenseUrl)
        assertEquals(30000L, moment.musicStartMs)
        assertEquals(25000L, moment.musicDurationMs)
        assertEquals(55000L, moment.musicEndMs)
        assertEquals(0.8f, moment.musicVolume, 0f)
        assertEquals(0.3f, moment.originalAudioVolume, 0f)
        assertEquals(500L, moment.musicFadeInMs)
        assertEquals(600L, moment.musicFadeOutMs)
        assertTrue(moment.duckOriginalAudio)
        assertTrue(moment.musicEmbedded)
        assertEquals("Text", moment.overlayText)
        assertEquals("#FF000000", moment.overlayColor)
        assertEquals("serif", moment.overlayFont)
        assertEquals(1.5f, moment.overlayScale, 0f)
        assertEquals(0.4f, moment.overlayX, 0f)
        assertEquals(0.3f, moment.overlayY, 0f)
        assertEquals(25000L, moment.displayDurationMs)
        assertEquals("2026-09-18T12:00:00Z", moment.expiresAt)
    }
}
