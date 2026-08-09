package conectate.com.presentation.analytics

import conectate.com.data.analytics.CreatorAnalyticsApiException
import conectate.com.data.model.CreatorAnalyticsPrivacy
import conectate.com.data.model.CreatorAnalyticsRange
import conectate.com.data.model.LiveAnalyticsReport
import conectate.com.data.model.LiveAnalyticsTotals
import conectate.com.data.model.VideoAnalyticsInteractions
import conectate.com.data.model.VideoAnalyticsReport
import conectate.com.data.model.VideoAnalyticsTotals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CreatorAnalyticsPresentationTest {
    private val range = CreatorAnalyticsRange("2026-08-01T00:00:00Z", "2026-08-08T00:00:00Z")

    @Test
    fun disabledFeatureUsesSpecificSafeMessage() {
        assertEquals(
            "Las estadísticas privadas todavía no están habilitadas para esta fase de pruebas.",
            creatorAnalyticsErrorMessage(CreatorAnalyticsApiException("CREATOR_ANALYTICS_DISABLED", 404)),
        )
    }

    @Test
    fun activityChecksUseOnlyConfirmedTotals() {
        val emptyVideo = VideoAnalyticsReport(
            contentType = "video",
            contentId = "video",
            range = range,
            privacy = CreatorAnalyticsPrivacy(),
        )
        assertFalse(emptyVideo.hasConfirmedActivity())
        assertTrue(
            emptyVideo.copy(
                totals = VideoAnalyticsTotals(
                    interactions = VideoAnalyticsInteractions(comments = 1),
                ),
            ).hasConfirmedActivity(),
        )

        val emptyLive = LiveAnalyticsReport(contentType = "live", contentId = "live", range = range)
        assertFalse(emptyLive.hasConfirmedActivity())
        assertTrue(emptyLive.copy(totals = LiveAnalyticsTotals(peakConcurrent = 1)).hasConfirmedActivity())
    }
}
