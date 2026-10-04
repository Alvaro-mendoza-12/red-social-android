package conectate.com.data.localfallback

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalFallbackMomentMusicRequestTest {
    @Test fun createRequestIncludesEachMusicPresentationAndArtwork() {
        for (style in listOf("card", "label", "audio")) {
            val request = LocalFallbackApiClient.LocalFallbackCreateMomentRequest(
                mediaUrl = "https://example.invalid/moment.png", mediaType = "image",
                musicPresentation = style, musicArtworkUrl = "https://example.invalid/cover.png",
            )
            val payload = Json { encodeDefaults = true }.parseToJsonElement(
                Json { encodeDefaults = true }.encodeToString(request)
            ).jsonObject
            assertEquals(style, payload.getValue("musicPresentation").jsonPrimitive.content)
            assertEquals("https://example.invalid/cover.png", payload.getValue("musicArtworkUrl").jsonPrimitive.content)
        }
    }

    @Test fun aLegacyCreateRequestDefaultsToCard() {
        val request = Json.decodeFromString<LocalFallbackApiClient.LocalFallbackCreateMomentRequest>(
            """{"mediaUrl":"https://example.invalid/moment.png","mediaType":"image"}"""
        )
        assertEquals("card", request.musicPresentation)
        assertEquals(null, request.musicArtworkUrl)
        assertEquals(.5f, request.musicOverlayX, 0f)
        assertEquals(.5f, request.musicOverlayY, 0f)
        assertEquals(1f, request.musicOverlayScale, 0f)
        assertEquals(0f, request.musicOverlayRotation, 0f)
        assertEquals(null, request.musicLyrics)
    }

    @Test fun createRequestPreservesPlacementAndRealLyrics() {
        val request = LocalFallbackApiClient.LocalFallbackCreateMomentRequest(
            mediaUrl = "https://example.invalid/moment.png", mediaType = "image",
            musicPresentation = "lyrics", musicLyrics = "[00:00]QA A\n[00:02]QA B",
            musicOverlayX = .8f, musicOverlayY = .7f, musicOverlayScale = 1.3f, musicOverlayRotation = -20f,
        )
        val json = Json { encodeDefaults = true }
        assertEquals(request, json.decodeFromString<LocalFallbackApiClient.LocalFallbackCreateMomentRequest>(json.encodeToString(request)))
        val payload = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        assertEquals("0.8", payload.getValue("musicOverlayX").jsonPrimitive.content)
        assertEquals(request.musicLyrics, payload.getValue("musicLyrics").jsonPrimitive.content)
    }
}
