package conectate.com.util

import conectate.com.data.model.Message
import org.junit.Assert.*
import org.junit.Test

class QuotedMessagePreviewTest {
    @Test fun emptyStickerVideoAndGifQuotesAlwaysIdentifyTheAttachment() {
        assertEquals("Sticker", quotedMessagePreview(Message(content_type = "sticker")))
        assertEquals("📹 Video", quotedMessagePreview(Message(content_type = "video")))
        assertEquals("GIF", quotedMessagePreview(Message(content_type = "gif")))
    }
    @Test fun deletedContentNeverLeaksIntoThePreview() {
        assertEquals("Mensaje eliminado", quotedMessagePreview(Message(content = "private", content_type = "document", deleted_for_all = true)))
    }
    @Test fun structuredContactAndLocationPayloadsDoNotAppearAsRawData() {
        assertEquals("Contacto", quotedMessagePreview(Message(content = "{\"phone\":\"fixture\"}", content_type = "contact")))
        assertEquals("Ubicación", quotedMessagePreview(Message(content = "https://example.invalid/map", content_type = "location")))
    }
    @Test fun filesAndPollsRetainReadableDescriptions() {
        assertEquals("report.pdf", quotedMessagePreview(Message(content = "report.pdf", content_type = "document")))
        assertEquals("Archivo adjunto", quotedMessagePreview(Message(content_type = "file")))
        assertEquals("Encuesta", quotedMessagePreview(Message(content_type = "poll")))
    }
    @Test fun audioIncludesOnlyAValidDuration() {
        assertEquals("🎤 Audio 1:23", quotedMessagePreview(Message(content = "1:23", content_type = "audio")))
        assertEquals("🎤 Audio", quotedMessagePreview(Message(content = "recording.m4a", content_type = "audio")))
    }
    @Test fun viewOnceAttachmentsAndEmptyTextHaveClearFallbacks() {
        assertEquals("📷 Foto para ver una vez", quotedMessagePreview(Message(content_type = "view_once_image")))
        assertEquals("📹 Video para ver una vez", quotedMessagePreview(Message(content_type = "view_once_video")))
        assertEquals("Mensaje", quotedMessagePreview(Message()))
        assertEquals("Hola", quotedMessagePreview(Message(content = " Hola ")))
    }
}
