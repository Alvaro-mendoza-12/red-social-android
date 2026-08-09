package conectate.com.presentation.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedVideoNavigationTest {

    @Test
    fun blankRecommendationDoesNotNavigate() {
        var navigated = false

        val result = attemptRecommendedVideoNavigation(
            videoId = "   ",
            navigate = { navigated = true },
            routeFor = { "video_discovery/$it" }
        )

        assertEquals(RecommendedVideoNavigationResult.InvalidVideoId, result)
        assertFalse(navigated)
    }

    @Test
    fun oversizedRecommendationDoesNotNavigate() {
        var navigated = false

        val result = attemptRecommendedVideoNavigation(
            videoId = "v".repeat(513),
            navigate = { navigated = true },
            routeFor = { "video_discovery/$it" }
        )

        assertEquals(RecommendedVideoNavigationResult.InvalidVideoId, result)
        assertFalse(navigated)
    }

    @Test
    fun validRecommendationUsesTrimmedIdAndNavigatesOnce() {
        var receivedRoute: String? = null

        val result = attemptRecommendedVideoNavigation(
            videoId = "  video-123  ",
            navigate = { receivedRoute = it },
            routeFor = { "video_discovery/$it" }
        )

        assertEquals(RecommendedVideoNavigationResult.Navigated, result)
        assertEquals("video_discovery/video-123", receivedRoute)
    }

    @Test
    fun navigationFailureIsContainedForTheCallerToShowFallback() {
        val failure = IllegalArgumentException("destination unavailable")

        val result = attemptRecommendedVideoNavigation(
            videoId = "video-123",
            navigate = { throw failure },
            routeFor = { "video_discovery/$it" }
        )

        assertTrue(result is RecommendedVideoNavigationResult.Failed)
        assertSame(failure, (result as RecommendedVideoNavigationResult.Failed).cause)
    }

    @Test
    fun routeConstructionFailureIsAlsoContained() {
        val failure = IllegalStateException("route factory failed")

        val result = attemptRecommendedVideoNavigation(
            videoId = "video-123",
            navigate = { throw AssertionError("navigation must not run") },
            routeFor = { throw failure }
        )

        assertTrue(result is RecommendedVideoNavigationResult.Failed)
        assertSame(failure, (result as RecommendedVideoNavigationResult.Failed).cause)
    }
}
