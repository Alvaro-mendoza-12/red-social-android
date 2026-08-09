package conectate.com.data.recommendations

import conectate.com.data.model.AlgorithmCapabilities
import conectate.com.data.model.AlgorithmCapabilityFlags
import conectate.com.data.model.AlgorithmConfig
import conectate.com.data.model.AlgorithmProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeRecommendationMappingsTest {
    @Test
    fun `video adapter preserves server order cursor and public backend fields`() {
        val decoded = AuthoritativeRecommendationPageJson.decode(
            body = """
                {
                  "items": [
                    {
                      "id": "video-b",
                      "user_id": "creator-b",
                      "user_name": "B",
                      "video_url": "https://cdn.example/video-b.mp4",
                      "cover_url": "https://cdn.example/video-b.jpg",
                      "duration_seconds": 19,
                      "views": 9
                    },
                    {
                      "id": "video-a",
                      "user_id": "creator-a",
                      "user_name": "A",
                      "video_url": "https://cdn.example/video-a.mp4",
                      "thumbnail_url": "https://cdn.example/video-a.jpg",
                      "duration_seconds": 7,
                      "views": 3
                    }
                  ],
                  "page": { "nextCursor": "opaque.cursor.value", "hasMore": true },
                  "algorithm": {
                    "authoritative": true,
                    "version": "algorithm-v1",
                    "explanationsEnabled": false
                  }
                }
            """.trimIndent(),
            surface = AuthoritativeRecommendationSurface.VIDEO,
        ) as AuthoritativeRecommendationDecodeResult.Authoritative

        val page = decoded.page.mapVideosExact().getOrThrow()

        assertEquals(listOf("video-b", "video-a"), page.items.map { it.id })
        assertEquals(listOf(19L, 7L), page.items.map { it.duration })
        assertEquals("https://cdn.example/video-b.jpg", page.items.first().thumbnailUrl)
        assertEquals("opaque.cursor.value", page.nextCursor?.asRequestValue())
        assertTrue(page.hasMore)
    }

    @Test
    fun `social adapter maps protected media envelope without reordering`() {
        val decoded = AuthoritativeRecommendationPageJson.decode(
            body = """
                {
                  "items": [
                    {
                      "id": "post-2",
                      "user_id": "creator-2",
                      "user_name": "Dos",
                      "media_urls": [
                        { "url": "https://cdn.example/two.jpg", "media_type": "image" },
                        { "url": "https://cdn.example/two.mp4", "media_type": "video" }
                      ]
                    },
                    {
                      "id": "post-1",
                      "user_id": "creator-1",
                      "user_name": "Uno",
                      "media_urls": ["https://cdn.example/one.jpg"]
                    }
                  ],
                  "page": { "nextCursor": null, "hasMore": false },
                  "algorithm": {
                    "authoritative": true,
                    "version": "algorithm-v1",
                    "explanationsEnabled": false
                  }
                }
            """.trimIndent(),
            surface = AuthoritativeRecommendationSurface.SOCIAL,
        ) as AuthoritativeRecommendationDecodeResult.Authoritative

        val posts = decoded.page.mapSocialPostsExact().getOrThrow().items

        assertEquals(listOf("post-2", "post-1"), posts.map { it.id })
        assertEquals(
            listOf("https://cdn.example/two.jpg", "https://cdn.example/two.mp4"),
            posts.first().mediaUrls,
        )
    }

    @Test
    fun `one malformed authoritative item rejects the complete page`() {
        val decoded = AuthoritativeRecommendationPageJson.decode(
            body = """
                {
                  "items": [
                    { "id": "video-ok", "user_id": "creator", "video_url": "https://cdn.example/ok.mp4" },
                    { "id": "video-broken", "user_id": "creator", "video_url": "" }
                  ],
                  "page": { "nextCursor": null, "hasMore": false },
                  "algorithm": {
                    "authoritative": true,
                    "version": "algorithm-v1",
                    "explanationsEnabled": false
                  }
                }
            """.trimIndent(),
            surface = AuthoritativeRecommendationSurface.VIDEO,
        ) as AuthoritativeRecommendationDecodeResult.Authoritative

        assertTrue(decoded.page.mapVideosExact().isFailure)
    }

    @Test
    fun `authoritative feed requires enabled capability classification and profile`() {
        val enabled = AlgorithmCapabilities(
            enabled = true,
            capabilities = AlgorithmCapabilityFlags(classification = true),
        )
        val config = AlgorithmConfig(
            profile = AlgorithmProfile(id = "profile-1", isEnabled = true),
        )

        assertTrue(canUseAuthoritativeRecommendations(enabled, config))
        assertFalse(canUseAuthoritativeRecommendations(enabled.copy(enabled = false), config))
        assertFalse(
            canUseAuthoritativeRecommendations(
                enabled.copy(capabilities = enabled.capabilities.copy(classification = false)),
                config,
            ),
        )
        assertFalse(
            canUseAuthoritativeRecommendations(
                enabled,
                config.copy(profile = config.profile.copy(isEnabled = false)),
            ),
        )
    }

    @Test
    fun `display reasons use only truthful server stages`() {
        val decoded = AuthoritativeRecommendationPageJson.decode(
            body = """
                {
                  "items": [{ "id": "post-1", "user_id": "creator", "user_name": "C" }],
                  "page": { "nextCursor": null, "hasMore": false },
                  "algorithm": {
                    "authoritative": true,
                    "version": "algorithm-v1",
                    "explanationsEnabled": true
                  },
                  "explanations": [{
                    "candidateId": "post-1",
                    "explanation": {
                      "truthful": true,
                      "stages": [
                        { "stage": "followed_creator", "outcome": "matched" },
                        { "stage": "permitted_inference", "outcome": "neutral" }
                      ]
                    }
                  }]
                }
            """.trimIndent(),
            surface = AuthoritativeRecommendationSurface.SOCIAL,
        ) as AuthoritativeRecommendationDecodeResult.Authoritative

        val reasons = decoded.page.mapSocialPostsExact().getOrThrow()
            .explanationsByCandidateId.getValue("post-1")

        assertEquals(listOf("Sigues a la persona que publicó este contenido."), reasons)
    }
}
