package conectate.com.presentation.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordChangeValidationTest {

    @Test
    fun rejectsMissingCurrentPassword() {
        assertEquals(
            "Escribe tu contraseña actual.",
            passwordChangeValidationError("", "NuevaClave123", "NuevaClave123")
        )
    }

    @Test
    fun rejectsShortNewPassword() {
        assertEquals(
            "La nueva contraseña debe tener al menos 8 caracteres.",
            passwordChangeValidationError("Anterior123", "corta", "corta")
        )
    }

    @Test
    fun rejectsDifferentConfirmation() {
        assertEquals(
            "Las contraseñas no coinciden.",
            passwordChangeValidationError("Anterior123", "NuevaClave123", "OtraClave123")
        )
    }

    @Test
    fun rejectsReusingCurrentPassword() {
        assertEquals(
            "La contraseña nueva debe ser diferente de la actual.",
            passwordChangeValidationError("MismaClave123", "MismaClave123", "MismaClave123")
        )
    }

    @Test
    fun acceptsValidChange() {
        assertNull(
            passwordChangeValidationError("Anterior123", "NuevaClave123", "NuevaClave123")
        )
    }
}
