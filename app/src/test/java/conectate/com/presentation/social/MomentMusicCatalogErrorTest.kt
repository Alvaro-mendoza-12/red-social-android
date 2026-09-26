package conectate.com.presentation.social

import conectate.com.core.network.ReadDeadlineExceededException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MomentMusicCatalogErrorTest {
    @Test
    fun `server codes never leak into community music UI`() {
        val message = momentMusicCatalogError(IllegalStateException("NOT_FOUND"))
        assertEquals("El catálogo comunitario todavía no está disponible en este servidor.", message)
        assertFalse(message.contains("NOT_FOUND"))
    }

    @Test
    fun `unknown machine code gets a useful retry message`() {
        assertEquals(
            "No se pudo cargar la música de la comunidad. Revisa la conexión e inténtalo de nuevo.",
            momentMusicCatalogError(IllegalStateException("UPSTREAM_TEMPORARILY_UNAVAILABLE")),
        )
    }

    @Test
    fun `read deadline has a human retry message`() {
        assertEquals(
            "La música está tardando en responder. Puedes volver a intentarlo.",
            momentMusicCatalogError(ReadDeadlineExceededException(10_000L)),
        )
    }
}
