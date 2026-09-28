package conectate.com.data.model

import org.junit.Assert.*
import org.junit.Test

class UsernamePolicyTest {
    @Test fun suggestionsNormalizeNamesWithoutEmailOrDeviceData() {
        assertEquals("adolfomendoza03", suggestedPublicUsername(" Adolfo  Mendoza 03 "))
        assertEquals("mirellavasquez", suggestedPublicUsername("Mirella Vásquez"))
        assertEquals("inaki1999", suggestedPublicUsername("Iñaki! 1999"))
        assertEquals("usuario", suggestedPublicUsername("山田"))
        assertEquals(24, suggestedPublicUsername("a".repeat(100)).length)
    }

    @Test fun validationCoversBlankShortLongAndInvalidCharacters() {
        listOf("", "ab", "a".repeat(25), "nombre apellido", "iñaki", "user@example.com")
            .forEach { assertNotNull(publicUsernameFormatError(it)) }
        listOf("mirella_vr07", "adolfo.03", "nombre-03", "User123")
            .forEach { assertNull(publicUsernameFormatError(it)) }
    }

    @Test fun availabilityCannotBeReusedAfterEditingOrRestoringWithoutAResponse() {
        assertTrue(isPublicUsernameVerified("Mirella_vr07", "mirella_vr07"))
        assertTrue(isPublicUsernameVerified(" @mirella_vr07 ", "mirella_vr07"))
        assertFalse(isPublicUsernameVerified("mirella_vr08", "mirella_vr07"))
        assertFalse(isPublicUsernameVerified("mirella_vr07", null))
        assertFalse(isPublicUsernameVerified("", ""))
        assertFalse(isPublicUsernameVerified("mirella vásquez", "mirella vásquez"))
        assertFalse(isPublicUsernameVerified("a".repeat(25), "a".repeat(25)))
    }
}
