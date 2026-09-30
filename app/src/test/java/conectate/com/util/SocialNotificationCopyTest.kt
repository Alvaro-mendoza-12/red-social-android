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
}
