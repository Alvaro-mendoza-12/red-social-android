package conectate.com.core.messaging

import org.junit.Assert.*
import org.junit.Test

class MessageUploadProgressTest {
    @Test fun `progress is clamped and isolated between accounts and finishes immediately`() {
        try {
            MessageUploadProgress.update("account-a", "photo", .42f)
            MessageUploadProgress.update("account-b", "photo", 2f)
            assertEquals(.42f, MessageUploadProgress.transfers.value[MessageUploadProgress.key("account-a", "photo")]!!, .001f)
            assertEquals(1f, MessageUploadProgress.transfers.value[MessageUploadProgress.key("account-b", "photo")]!!, .001f)
            MessageUploadProgress.finish("account-a", "photo")
            assertNull(MessageUploadProgress.transfers.value[MessageUploadProgress.key("account-a", "photo")])
            assertNotNull(MessageUploadProgress.transfers.value[MessageUploadProgress.key("account-b", "photo")])
        } finally {
            MessageUploadProgress.finish("account-a", "photo")
            MessageUploadProgress.finish("account-b", "photo")
        }
    }
}
