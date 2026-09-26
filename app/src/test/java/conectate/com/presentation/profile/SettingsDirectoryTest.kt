package conectate.com.presentation.profile

import org.junit.Assert.*
import org.junit.Test

class SettingsDirectoryTest {
    @Test fun findsAliasesIgnoringAccentsAndCase() {
        assertTrue(SettingsDirectory.matches("Pantalla", " IDIOMA "))
        assertTrue(SettingsDirectory.matches("Copia de seguridad", "restaurar backup"))
        assertTrue(SettingsDirectory.matches("Correos de Inicio de Sesión", "sesion"))
        assertFalse(SettingsDirectory.matches("Pantalla", "privacidad"))
    }
    @Test fun excludesAdministrativeResultsForOrdinaryAccounts() {
        assertFalse(SettingsDirectory.hasMatches("moderacion", includeAdmin = false))
        assertTrue(SettingsDirectory.hasMatches("moderacion", includeAdmin = true))
    }
}
