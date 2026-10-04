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
    }
}
