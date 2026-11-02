package conectate.com.presentation.messages

import conectate.com.core.security.InputSecurityPolicy
import org.junit.Assert.*
import org.junit.Test

class SharedTextPolicyTest {
    @Test fun externalTextIsRejectedBeforeRepositorySanitationWouldCutIt() {
        val text = "x".repeat(InputSecurityPolicy.MAX_TEXT_MESSAGE_LENGTH + 1)
        assertNotNull(sharedTextError(text))
        assertNotEquals(text, InputSecurityPolicy.sanitizeMessageText(text))
    }

    @Test fun aTextAtTheActualRepositoryLimitReachesItWithoutLoss() {
        val text = "x".repeat(InputSecurityPolicy.MAX_TEXT_MESSAGE_LENGTH)
        assertNull(sharedTextError(text))
        assertEquals(text, InputSecurityPolicy.sanitizeMessageText(text))
    }

    @Test fun anInternalCommentCannotSilentlyChangeAfterItIsReviewed() {
        assertNull(sharedTextError("x".repeat(MAX_INTERNAL_SHARE_COMMENT), true))
        assertNotNull(sharedTextError("x".repeat(MAX_INTERNAL_SHARE_COMMENT + 1), true))
    }

    @Test fun onlyTheTextThatWouldBeSentCountsAndEmptyAttachmentCommentsRemainAllowed() {
        assertNull(sharedTextError(null))
        assertNull(sharedTextError("   "))
        assertNull(sharedTextError(" " + "x".repeat(InputSecurityPolicy.MAX_TEXT_MESSAGE_LENGTH) + "\n"))
    }
}
