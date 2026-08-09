package conectate.com.presentation.gaming

import conectate.com.data.gaming.PlayMatchApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayMatchUiPolicyTest {
    @Test
    fun `responsive grid never forces two columns on 320dp`() {
        assertEquals(1, PlayMatchUiPolicy.gameGridColumns(320))
        assertEquals(2, PlayMatchUiPolicy.gameGridColumns(360))
        assertEquals(2, PlayMatchUiPolicy.gameGridColumns(480))
        assertEquals(3, PlayMatchUiPolicy.gameGridColumns(600))
        assertEquals(4, PlayMatchUiPolicy.gameGridColumns(840))
        assertEquals(1, PlayMatchUiPolicy.gameGridColumns(360, 1.5f))
        assertEquals(1, PlayMatchUiPolicy.gameGridColumns(480, 2.0f))
        assertEquals(3, PlayMatchUiPolicy.gameGridColumns(600, 2.0f))
    }

    @Test
    fun `backend errors are mapped without leaking internal details`() {
        val message = PlayMatchUiPolicy.safeError(PlayMatchApiException("RATE_LIMITED", 429))
        assertEquals("Has realizado varias acciones. Espera un momento y vuelve a intentarlo.", message)
        assertFalse(message.contains("429"))
    }

    @Test
    fun `unknown long error is replaced by safe generic copy`() {
        val message = PlayMatchUiPolicy.safeError(IllegalStateException("x".repeat(500)))
        assertEquals("No se pudo completar la acción. Inténtalo nuevamente.", message)
    }
}
