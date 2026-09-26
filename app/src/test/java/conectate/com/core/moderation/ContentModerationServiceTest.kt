package conectate.com.core.moderation

import conectate.com.core.observability.AppMetrics
import conectate.com.core.observability.MetricAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContentModerationServiceTest {

    private val service = SimpleContentModerationService(NoOpMetrics(), ModerationNoticeCenter())

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

    @Test
    fun `spanish insults are masked before a comment is published`() {
        val result = service.moderate(
            text = "eres un imbécil y un estúpido",
            context = ModerationContext.COMMENT_CONTENT
        )

        assertEquals(ModerationVerdict.SANITIZE, result.verdict)
        assertFalse(result.sanitizedText.contains("imbécil", ignoreCase = true))
        assertFalse(result.sanitizedText.contains("estúpido", ignoreCase = true))
    }

    @Test
    fun `obfuscated insults are masked but benign compliments remain`() {
        val obfuscated = service.moderate(
            text = "eres p.v.t.4 e 1mb3c1l",
            context = ModerationContext.COMMENT_CONTENT
        )
        val benign = service.moderate(
            text = "Qué bonito, quedó muy rico.",
            context = ModerationContext.COMMENT_CONTENT
        )

        assertEquals(ModerationVerdict.SANITIZE, obfuscated.verdict)
        assertFalse(obfuscated.sanitizedText.contains("p.v.t.4", ignoreCase = true))
        assertFalse(obfuscated.sanitizedText.contains("1mb3c1l", ignoreCase = true))
        assertEquals(ModerationVerdict.ALLOW, benign.verdict)
    }
}

private class NoOpMetrics : AppMetrics {
    override val alerts: Flow<MetricAlert> = emptyFlow()
    override fun incrementCounter(metric: String, delta: Long, attributes: Map<String, String>) = Unit
    override fun recordLatency(metric: String, durationMs: Long, attributes: Map<String, String>) = Unit
    override fun recordFailure(metric: String, throwable: Throwable?, attributes: Map<String, String>) = Unit
}
