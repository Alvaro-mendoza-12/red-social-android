package conectate.com.data.recommendations

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeRecommendationPageJsonTest {
    @Test
    fun `authoritative envelope preserves exact server order cursor and explanation`() {
        val cursor = "r1.aGVhZGVy.dGFn.Y2lwaGVydGV4dA"
        val result = AuthoritativeRecommendationPageJson.decode(
            body = """
                {
                  "items": [
                    { "id": "video-b", "title": "Segundo" },
                    { "id": "video-a", "title": "Primero" },
                    { "id": "video-b", "title": "Duplicado intencional" }
                  ],
                  "page": { "nextCursor": "$cursor", "hasMore": true },
                  "algorithm": {
                    "authoritative": true,
                    "version": "algorithm-v1",
                    "profileId": "not-exposed-to-ui",
                    "explanationsEnabled": true
                  },
                  "explanations": [
                    {
                      "candidateId": "video-b",
                      "explanation": {
                        "truthful": true,
                        "version": "algorithm-v1",
                        "stages": [{ "stage": "explicit_preferences", "outcome": "matched" }]
                      }
                    }
                  ],
                  "ownerId": "ignored-by-transport"
                }
            """.trimIndent(),
            surface = AuthoritativeRecommendationSurface.VIDEO,
        )

        val page = (result as AuthoritativeRecommendationDecodeResult.Authoritative).page

        assertEquals(
            listOf("video-b", "video-a", "video-b"),
            page.items.map { it.id },
        )
        assertEquals(cursor, page.nextCursor?.asRequestValue())
        assertEquals(AuthoritativeRecommendationSurface.VIDEO, page.surface)
        assertTrue(page.hasMore)
        assertEquals("algorithm-v1", page.metadata.rankingVersion)
        assertTrue(page.metadata.explanationsEnabled)
        assertEquals(
            "matched",
            page.explanationForCandidate("video-b")
                ?.payload
                ?.get("stages")
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("outcome")
                ?.jsonPrimitive
                ?.content,
        )
        assertNull(page.explanationForCandidate("video-a"))
    }

    @Test
    fun `non authoritative envelope explicitly requests legacy fallback`() {
        val result = AuthoritativeRecommendationPageJson.decode(
            body = """
                {
                  "items": [],
                  "page": { "nextCursor": null, "hasMore": false },
                  "algorithm": {
                    "authoritative": false,
                    "version": "algorithm-v1",
                    "explanationsEnabled": false
                  }
                }
            """.trimIndent(),
            surface = AuthoritativeRecommendationSurface.SOCIAL,
        )

        assertEquals(
            AuthoritativeRecommendationFallbackReason.NOT_SERVER_AUTHORITATIVE,
            (result as AuthoritativeRecommendationDecodeResult.Fallback).reason,
        )
    }

    @Test
    fun `pagination without an opaque cursor explicitly falls back`() {
        val result = AuthoritativeRecommendationPageJson.decode(
            body = """
                {
                  "items": [{ "id": "post-1" }],
                  "page": { "hasMore": true },
                  "algorithm": {
                    "authoritative": true,
                    "version": "algorithm-v1",
                    "explanationsEnabled": false
                  }
                }
            """.trimIndent(),
            surface = AuthoritativeRecommendationSurface.SOCIAL,
        )

        assertEquals(
            AuthoritativeRecommendationFallbackReason.INVALID_PAGINATION,
            (result as AuthoritativeRecommendationDecodeResult.Fallback).reason,
        )
    }

    @Test
    fun `untruthful explanation is never surfaced as a real explanation`() {
        val result = AuthoritativeRecommendationPageJson.decode(
            body = """
                {
                  "items": [{ "id": "video-1" }],
                  "page": { "nextCursor": null, "hasMore": false },
                  "algorithm": {
                    "authoritative": true,
                    "version": "algorithm-v1",
                    "explanationsEnabled": true
                  },
                  "explanations": [
                    {
                      "candidateId": "video-1",
                      "explanation": { "truthful": false }
                    }
                  ]
                }
            """.trimIndent(),
            surface = AuthoritativeRecommendationSurface.VIDEO,
        )

        assertFalse(result is AuthoritativeRecommendationDecodeResult.Authoritative)
        assertEquals(
            AuthoritativeRecommendationFallbackReason.INVALID_EXPLANATIONS,
            (result as AuthoritativeRecommendationDecodeResult.Fallback).reason,
        )
    }
}
