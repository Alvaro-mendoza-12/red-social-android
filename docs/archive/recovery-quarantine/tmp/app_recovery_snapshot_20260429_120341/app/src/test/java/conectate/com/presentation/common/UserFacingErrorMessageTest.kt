package conectate.com.presentation.common

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingErrorMessageTest {

    @Test
    fun jwtExpired_isMappedToSessionMessage() {
        val message = Exception("JWT expired").toUserFacingMessage()
        assertEquals("Tu sesión venció o dejó de ser válida. Inicia sesión de nuevo.", message)
    }

    @Test
    fun savedAccountIssue_isMappedToQuickSwitchMessage() {
        val message = Exception("La sesión guardada expiró. Vuelve a iniciar sesión con esa cuenta.").toUserFacingMessage()
        assertTrue(message.contains("cuenta guardada", ignoreCase = true))
    }

    @Test
    fun networkError_isMappedToConnectivityMessage() {
        val message = IOException("Software caused connection abort").toUserFacingMessage()
        assertTrue(message.contains("internet", ignoreCase = true))
    }
}
