package conectate.com.presentation.messages

import conectate.com.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxMessagePreviewTest {
    @Test fun `question descriptor is shown as human readable preview`() {
        assertEquals("Pregunta: 2+2?", inboxMessagePreview(Message(content_type="question",content="""{"questionId":"id","prompt":"2+2?"}""")))
        assertEquals("Pregunta de la comunidad", readableCollectionPreview("""{"questionId":"id"}"""))
    }

    @Test fun `deleted text never reveals raw content`() {
        assertEquals("Mensaje eliminado", inboxMessagePreview(Message(content = "secret", deleted_for_all = true)))
    }
    @Test fun `deleted media does not reveal its original type`() {
        for (type in listOf("image", "video", "audio", "document")) {
            assertEquals("Mensaje eliminado", inboxMessagePreview(Message(content_type = type, deleted_for_all = true)))
        }
    }
    @Test fun `media labels are readable and empty chat is distinct from deletion`() {
        assertEquals("Foto", inboxMessagePreview(Message(content_type = "image")))
        assertEquals("Video", inboxMessagePreview(Message(content_type = "video")))
        assertEquals("Sin actividad reciente", inboxMessagePreview(null))
        assertEquals("hello", inboxMessagePreview(Message(content = "hello")))
    }
}
