package conectate.com.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileIdentityTest {
    private val id = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun importedEmailUsernameDoesNotLeakIntoPublicHandle() {
        val user = User(
            id = id,
            email = "private.mail123@example.com",
            username = "private.mail123",
            display_name = "Juan Pérez",
        )

        val handle = profilePublicHandle(user)
        assertEquals("juan_perez", handle)
        assertFalse(handle.contains("private"))
        assertEquals(5, profileShortId(user).length)
    }

    @Test
    fun chosenUsernameTakesPrecedenceAndFullIdCanBeSearched() {
        val user = User(id = id, email = "juan@example.com", username = "Juan_01", display_name = "Juan Pérez")
        val handle = profilePublicHandle(user)

        assertEquals("juan_01", handle)
        assertEquals(id, profileSearchQuery(id))
        assertEquals("446655440000", profileSearchQuery("@juan_01_446655440000"))
    }

    @Test
    fun emailOnlyAccountGetsNeutralHandle() {
        val user = User(id = id, email = "secret@example.com", username = "secret")
        assertEquals("usuario", profilePublicHandle(user))
        assertEquals("usuario", profilePublicHandle(user.copy(display_name = "secret@example.com")))
    }

    @Test
    fun nonLatinNamesStayReadableAndBlankDisplayNameUsesFullName() {
        val user = User(id = id, display_name = "  ", full_name = "山田 太郎")
        assertEquals("山田_太郎", profilePublicHandle(user))
        assertEquals("李_明", profilePublicHandle(user.copy(display_name = "李 明")))
        assertEquals("ガク", profilePublicHandle(user.copy(display_name = "ガク")))
    }

    @Test
    fun shortReferenceIsStableAndUsesExistingNumericIdWhenAvailable() {
        val user = User(id = id, display_name = "Juan Pérez")
        assertEquals(profileShortId(user), profileShortId(user.copy(email = "changed@example.com")))
        assertEquals(5, profileShortId(user).length)
        assertEquals("1234", profileShortId(user.copy(numeric_id = 1234)))
    }

    @Test
    fun publicMentionPatternDoesNotTreatEmailAsMention() {
        assertEquals("keiber_joya", publicMentionPattern.find("Hola @keiber_joya, mira esto")?.groupValues?.get(1))
        assertEquals("keiber", publicMentionPattern.find("Mira @keiber.")?.groupValues?.get(1))
        assertEquals("李", publicMentionPattern.find("Hola @李!")?.groupValues?.get(1))
        assertEquals(null, publicMentionPattern.find("keiber@example.com"))
        assertEquals("keiber_joya", normalizedPublicMentionHandle("@Keibér_Joya"))
    }
}
