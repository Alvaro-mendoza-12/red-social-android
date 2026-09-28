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
}
