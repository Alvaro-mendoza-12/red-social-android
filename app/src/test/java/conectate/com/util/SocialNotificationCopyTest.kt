package conectate.com.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialNotificationCopyTest {
    @Test fun `acceptance shows what happened even with legacy name-only payload`() {
        assertEquals("Aceptó tu solicitud de amistad",socialNotificationBody("Ana"," ana ","Aceptó tu solicitud de amistad"))
        assertEquals("Te envió una solicitud de amistad",socialNotificationBody("Ana",null,"Te envió una solicitud de amistad"))
    }
    @Test fun `specific notification content and unknown event bodies are preserved`() {
        assertEquals("Te invitó al grupo Cine",socialNotificationBody("Ana","Te invitó al grupo Cine","Acción"))
        assertEquals("Texto",socialNotificationBody("Ana"," Texto ",null))
    }
    @Test fun `publication mention uses the publication wording`() {
        val event = conectate.com.data.model.Notification(recipientId="recipient",actorId="actor",type="mention_post",
            extraData=kotlinx.serialization.json.JsonObject(mapOf("mention_context" to kotlinx.serialization.json.JsonPrimitive("publication"))))
        assertEquals("Te mencionó en esta publicación",postMentionAction(event))
    }
    @Test fun `legacy comment mentions and malformed context remain readable`() {
        val event = conectate.com.data.model.Notification(recipientId="recipient",actorId="actor",type="mention_post")
        assertEquals("Te mencionó en un comentario",postMentionAction(event))
        assertEquals("Te mencionó en un comentario",postMentionAction(event.copy(extraData=kotlinx.serialization.json.JsonObject(mapOf("mention_context" to kotlinx.serialization.json.JsonObject(emptyMap()))))))
    }
}
