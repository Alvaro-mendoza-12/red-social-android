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
    private val shared = "https://red-social-android.vercel.app/post/04253203-9341-4557-9ece-c54da1be335a"

    @Test fun `internal publication identifiers are never mistaken for telephone numbers`() {
        for (context in listOf(ModerationContext.COMMUNITY_MESSAGE, ModerationContext.POST_CONTENT, ModerationContext.COMMENT_CONTENT)) {
            val result = service.moderate(shared, context)
            assertEquals(shared, result.sanitizedText)
            assertFalse(result.flags.contains("contact_redacted"))
        }
    }

    @Test fun `contact details next to a shared link are still redacted`() {
        val result = service.moderate("Mira ($shared). +1 555 123 4567 me@test.com", ModerationContext.COMMUNITY_MESSAGE)
        org.junit.Assert.assertTrue(result.sanitizedText.contains("($shared)."))
        assertFalse(result.sanitizedText.contains("555 123 4567"))
        assertFalse(result.sanitizedText.contains("me@test.com"))
    }

    @Test fun `profile video and voice link identifiers are preserved on public surfaces`() {
        val id = "04253203-9341-4557-9ece-c54da1be335a"
        for (path in listOf("u", "profile", "video", "room", "voice", "app/feed/post", "app/profile")) {
            val link = "https://red-social-android.vercel.app/$path/$id"
            assertEquals(link, service.moderate(link, ModerationContext.POST_CONTENT).sanitizedText)
        }
    }

    @Test fun `repetition cleanup does not truncate a valid identifier`() {
        val link = "https://red-social-android.vercel.app/post/00000000-0000-4000-8000-000000000001"
        assertEquals(link, service.moderate(link, ModerationContext.COMMUNITY_MESSAGE).sanitizedText)
    }

    @Test fun `preserved app links do not bypass spam or suspicious link checks`() {
        assertEquals(ModerationVerdict.BLOCK, service.moderate(List(4) { shared }.joinToString(" "), ModerationContext.COMMUNITY_MESSAGE).verdict)
        assertEquals(ModerationVerdict.BLOCK, service.moderate("$shared https://bit.ly/abc", ModerationContext.COMMUNITY_MESSAGE).verdict)
    }

    @Test fun `unknown origins and contact query parameters retain existing privacy filtering`() {
        val result = service.moderate("$shared?email=me@test.com https://other.example/04253203-9341-4557-9ece-c54da1be335a", ModerationContext.POST_CONTENT)
        org.junit.Assert.assertTrue(result.sanitizedText.startsWith(shared + "?"))
        assertFalse(result.sanitizedText.contains("me@test.com"))
        assertFalse(result.sanitizedText.contains("https://other.example/04253203-9341-4557-9ece-c54da1be335a"))
    }

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
