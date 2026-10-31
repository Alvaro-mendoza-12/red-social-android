package conectate.com.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedPostLinkRegressionTest {
    @Test fun activeWebsitePostLinkOpensTheOriginalPublication() {
        assertEquals("post-a123", postIdFromSharedLink("https://red-social-android.vercel.app/app/feed/post/post-a123"))
    }

    @Test fun embeddedCredentialsMustNotBeAcceptedAsAnInternalLink() {
        assertNull(postIdFromSharedLink("https://other.example@conectate.com/post/post-a123"))
    }

    @Test fun explicitForeignPortMustNotBeAcceptedAsAnInternalLink() {
        assertNull(postIdFromSharedLink("https://conectate.com:444/post/post-a123"))
    }

    @Test fun existingMessagesKeepTheirLegacyLinks() {
        assertEquals("post-a123", postIdFromSharedLink("https://conectate.com/post/post-a123"))
    }
}
