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
        assertEquals("juan_perez_446655440000", handle)
        assertFalse(handle.contains("private"))
    }

    @Test
    fun chosenUsernameAndFullIdCanBeSharedAndSearched() {
        val user = User(id = id, email = "juan@example.com", username = "Juan_01")
        val handle = profilePublicHandle(user)

        assertEquals("juan_01_446655440000", handle)
        assertEquals("446655440000", profileSearchQuery("@$handle"))
        assertEquals(id, profileSearchQuery(id))
    }

    @Test
    fun emailOnlyAccountGetsNeutralHandle() {
        val user = User(id = id, email = "secret@example.com", username = "secret")
        assertEquals("usuario_446655440000", profilePublicHandle(user))
    }
}
