package conectate.com.util
import org.junit.Assert.*
import org.junit.Test
class MentionNotificationPreviewTest {
    @Test fun mentionHasReadablePrefix() { assertEquals("Te mencionó: Hola @ana", mentionNotificationPreview("Hola @ana", true)) }
    @Test fun ordinaryMessageIsUnchanged() { assertEquals("Hola", mentionNotificationPreview("Hola", false)) }
}
