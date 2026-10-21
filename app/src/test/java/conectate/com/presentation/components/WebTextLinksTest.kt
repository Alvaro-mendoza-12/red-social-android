package conectate.com.presentation.components

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebTextLinksTest {
    @Test fun emailsAreClickableWithoutSplittingWebUrlsContainingEmails() {
        val links = findMessageTextLinks("Escribe a hola+app@example.org o visita https://example.org/user@example.net")
        assertEquals(listOf("mailto:hola+app@example.org", "https://example.org/user@example.net"), links.map { it.url })
    }

    @Test fun recognizesMultipleLinksAndAddsHttpsToBareDomains() {
        val text = "Mira https://example.org/a?q=1 y www.example.com o example.net."
        val links = findWebTextLinks(text)
        assertEquals(listOf("https://example.org/a?q=1", "https://www.example.com", "https://example.net"), links.map { it.url })
        assertEquals(listOf("https://example.org/a?q=1", "www.example.com", "example.net"), links.map { text.substring(it.start, it.end) })
    }

    @Test fun keepsBalancedUrlParenthesesAndOmitsSentencePunctuation() {
        val text = "(https://example.org/wiki/Test_(topic)). https://example.org/a,"
        assertEquals(listOf("https://example.org/wiki/Test_(topic)", "https://example.org/a"), findWebTextLinks(text).map { it.url })
    }

    @Test fun emailsAndNonWebSchemesDoNotTurnIntoWebLinks() {
        assertTrue(findWebTextLinks("user@example.org ftp://example.org rtsp://example.org file:///sdcard/private content://private").isEmpty())
    }

    @Test fun acceptsUppercaseHttpAndPreservesQueryAndFragment() {
        assertEquals("https://example.org/a?q=one%20two#part", findWebTextLinks("HTTPS://example.org/a?q=one%20two#part").single().url)
    }

    @Test fun internalPostsRequireTheOwnedHostAndOneValidId() {
        assertEquals("post_1234", postIdFromSharedLink("https://conectate.com/post/post_1234?source=chat"))
        for (url in listOf("https://evil.example/conectate.com/post/post_1234", "https://conectate.com.evil.example/post/post_1234",
            "https://conectate.com/post/a", "https://conectate.com/post/post_1234/extra", "javascript:conectate.com/post/post_1234")) {
            assertNull(url, postIdFromSharedLink(url))
        }
    }
}
