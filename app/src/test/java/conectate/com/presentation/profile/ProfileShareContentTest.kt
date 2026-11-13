package conectate.com.presentation.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileShareContentTest {
    @Test fun scansWebAndLegacyProfileCodesWithoutTreatingAPostAsAPerson() {
        assertEquals("person-123", profileUserIdFromQr("https://novvinapp.com/u/person-123"))
        assertEquals("person-123", profileUserIdFromQr("conectate://profile/person-123"))
        assertEquals("person-123", profileUserIdFromQr("https://conectate.com/profile/person-123"))
        assertNull(profileUserIdFromQr("https://novvinapp.com/post/post-123"))
    }
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
