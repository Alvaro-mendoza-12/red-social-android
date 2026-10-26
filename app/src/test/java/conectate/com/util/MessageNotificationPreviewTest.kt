package conectate.com.util

import org.junit.Assert.*
import org.junit.Test

class MessageNotificationPreviewTest {
    @Test fun stickerAndAnimationHaveNonEmptyTypedPreviewsRegardlessOfTheirRawContent() {
        assertEquals("Sticker", typedMessageNotificationPreview("sticker"))
        assertEquals("Sticker", typedMessageNotificationPreview(" STICKER "))
        assertEquals("GIF", typedMessageNotificationPreview("gif"))
    }
    @Test fun textAndControlEventsKeepTheirExistingPrivacyAndPresentationPolicy() {
        assertNull(typedMessageNotificationPreview("text"))
        assertNull(typedMessageNotificationPreview(BACKGROUND_CHANGE_CONTENT_TYPE))
        assertNull(typedMessageNotificationPreview("reaction"))
        assertEquals("Archivo", typedMessageNotificationPreview("document"))
    }
}
