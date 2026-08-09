package conectate.com.presentation.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveDrawGuessErrorMessageTest {
    @Test
    fun `known backend codes become safe actionable messages`() {
        assertEquals(
            "No compartas datos personales en las respuestas.",
            Exception("422 - {\"error\":\"LIVE_DRAW_GUESS_PII_BLOCKED\"}")
                .toLiveDrawGuessMessage("fallback")
        )
        assertEquals(
            "La ronda ya terminó. Espera la siguiente.",
            Exception("LIVE_DRAW_ROUND_EXPIRED").toLiveDrawGuessMessage("fallback")
        )
    }

    @Test
    fun `unknown technical backend details are not exposed`() {
        val message = Exception("500 - {\"error\":\"INTERNAL_ERROR\"}")
            .toLiveDrawGuessMessage("No se pudo continuar.")

        assertFalse(message.contains("INTERNAL_ERROR"))
        assertFalse(message.contains("{\"error\""))
    }
}
