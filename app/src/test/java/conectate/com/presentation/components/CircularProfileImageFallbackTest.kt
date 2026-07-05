package conectate.com.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CircularProfileImageFallbackTest {

    @Test
    fun `avatar fallback uses first normalized initial`() {
        assertEquals("M", avatarFallbackInitial("Mendoza"))
        assertEquals("A", avatarFallbackInitial("Alvaro"))
        assertEquals("A", avatarFallbackInitial("Álvaro"))
    }

    @Test
    fun `avatar fallback uses question mark for missing names`() {
        assertEquals("?", avatarFallbackInitial(null))
        assertEquals("?", avatarFallbackInitial(""))
        assertEquals("?", avatarFallbackInitial("   "))
        assertEquals("?", avatarFallbackInitial("null"))
    }
}
