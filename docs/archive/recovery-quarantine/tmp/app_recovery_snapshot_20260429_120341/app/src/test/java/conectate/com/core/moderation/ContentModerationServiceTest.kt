package conectate.com.core.moderation

import conectate.com.core.observability.AppMetrics
import conectate.com.core.observability.MetricAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContentModerationServiceTest {

    private val service = SimpleContentModerationService(NoOpMetrics())

    @Test
    fun `blocked term returns block verdict`() {
        val result = service.moderate(
            text = "this has a terror guide included",
            context = ModerationContext.CHAT_MESSAGE
        )

        assertEquals(ModerationVerdict.BLOCK, result.verdict)
    }

    @Test
    fun `public content redacts contact data`() {
        val result = service.moderate(
            text = "Call me +1 555 123 4567 or write me@test.com",
            context = ModerationContext.POST_CONTENT
        )

        assertEquals(ModerationVerdict.SANITIZE, result.verdict)
        assertFalse(result.sanitizedText.contains("555 123 4567"))
        assertFalse(result.sanitizedText.contains("me@test.com"))
    }

    @Test
    fun `suspicious short links are blocked`() {
        val result = service.moderate(
            text = "open this now https://bit.ly/abc123",
            context = ModerationContext.COMMUNITY_MESSAGE
        )

        assertEquals(ModerationVerdict.BLOCK, result.verdict)
    }
}

private class NoOpMetrics : AppMetrics {
    override val alerts: Flow<MetricAlert> = emptyFlow()
    override fun incrementCounter(metric: String, delta: Long, attributes: Map<String, String>) = Unit
    override fun recordLatency(metric: String, durationMs: Long, attributes: Map<String, String>) = Unit
    override fun recordFailure(metric: String, throwable: Throwable?, attributes: Map<String, String>) = Unit
}
