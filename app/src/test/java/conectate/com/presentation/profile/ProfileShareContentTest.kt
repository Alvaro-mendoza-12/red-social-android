package conectate.com.presentation.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileShareContentTest {
    @Test fun invitationIncludesPublicIdentityProfileAndDownload() {
        val message = buildProfileInvitation("Álvaro", "alvaro_03", "https://example.invalid/p/alvaro_03")
        assertTrue(message.contains("Álvaro (@alvaro_03)"))
        assertTrue(message.indexOf("/p/alvaro_03") < message.indexOf("play.google.com"))
        assertTrue(message.contains("Descárgala"))
    }
    @Test fun nameCannotInjectAdditionalLinesIntoTheInvitation() {
        assertTrue(buildProfileInvitation("Nombre\notro", "nombre", "https://example.invalid/p/nombre")
            .startsWith("Hola, soy Nombre otro (@nombre)"))
    }
}
