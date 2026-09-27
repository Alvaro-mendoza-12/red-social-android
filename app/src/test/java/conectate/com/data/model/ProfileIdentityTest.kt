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
    fun displayNameTakesPrecedenceAndFullIdCanBeSearched() {
        val user = User(id = id, email = "juan@example.com", username = "Juan_01", display_name = "Juan Pérez")
        val handle = profilePublicHandle(user)

        assertEquals("juan_perez", handle)
        assertEquals(id, profileSearchQuery(id))
        assertEquals("446655440000", profileSearchQuery("@juan_01_446655440000"))
    }

    @Test
    fun emailOnlyAccountGetsNeutralHandle() {
        val user = User(id = id, email = "secret@example.com", username = "secret")
        assertEquals("usuario", profilePublicHandle(user))
    }

    @Test
    fun shortReferenceIsStableAndUsesExistingNumericIdWhenAvailable() {
        val user = User(id = id, display_name = "Juan Pérez")
        assertEquals(profileShortId(user), profileShortId(user.copy(email = "changed@example.com")))
        assertEquals(5, profileShortId(user).length)
        assertEquals("1234", profileShortId(user.copy(numeric_id = 1234)))
    }
}
