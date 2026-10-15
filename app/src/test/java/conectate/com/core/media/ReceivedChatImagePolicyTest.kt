package conectate.com.core.media

import org.junit.Assert.*
import org.junit.Test

class ReceivedChatImagePolicyTest {
    @Test fun receivedOrdinaryImageIsEligible() = assertTrue(shouldSaveReceivedChatImage("receiver", "sender", "image"))
    @Test fun ownImageIsExcluded() = assertFalse(shouldSaveReceivedChatImage("receiver", "receiver", "image"))
    @Test fun absentAccountIsExcluded() = assertFalse(shouldSaveReceivedChatImage(null, "sender", "image"))
    @Test fun absentSenderIsExcluded() = assertFalse(shouldSaveReceivedChatImage("receiver", "", "image"))
    @Test fun viewOnceAndOtherTypesAreExcluded() {
        listOf("view_once_image", "video", "gif", "sticker", "text").forEach {
            assertFalse(it, shouldSaveReceivedChatImage("receiver", "sender", it))
        }
    }
    @Test fun deletedForAllIsExcluded() = assertFalse(shouldSaveReceivedChatImage("receiver", "sender", "image", true))
    @Test fun deletedForCurrentAccountIsExcluded() = assertFalse(shouldSaveReceivedChatImage("receiver", "sender", "image", deletedBy = listOf(" Receiver ")))
    @Test fun normalizedIdentitiesAndOtherAccountDeletionAreHandled() {
        assertTrue(shouldSaveReceivedChatImage(" Receiver ", "SENDER", " IMAGE ", deletedBy = listOf("someone-else")))
        assertFalse(shouldSaveReceivedChatImage(" Receiver ", "RECEIVER", "image"))
    }
}
