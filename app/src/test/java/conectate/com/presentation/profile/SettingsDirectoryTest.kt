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
    @Test fun accountDeletionAndDataRequestsAreDiscoverable() {
        for (query in listOf("eliminar cuenta", "borrar datos", "ELIMINACIÓN", "contacto privacidad")) {
            assertTrue(query, SettingsDirectory.hasMatches(query, includeAdmin = false))
            assertTrue(query, SettingsDirectory.matches("Privacidad y legales", query))
        }
        assertFalse(SettingsDirectory.matches("Administración", "eliminar cuenta"))
    }

    @Test fun logoutIsDirectlyDiscoverableWithoutGeneralCategory() {
        for (query in listOf("cerrar sesion", "salir", "desconectar")) {
            assertTrue(query, SettingsDirectory.hasMatches(query, includeAdmin = false))
            assertTrue(query, SettingsDirectory.matches("Cerrar sesión", query))
        }
        assertFalse(SettingsDirectory.matches("General", "cerrar sesion"))
    }
}
