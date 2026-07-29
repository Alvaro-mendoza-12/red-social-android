package conectate.com.presentation.messages

import conectate.com.util.ChatContactPayload
import conectate.com.util.IncomingShareKind
import conectate.com.util.classifyIncomingShareKind
import conectate.com.util.decodeContactPayload
import conectate.com.util.encodeContactPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareFlowUtilsTest {

    @Test
    fun contactPayload_preservesEveryPhoneAndEmail() {
        val original = ChatContactPayload(
            name = "Contacto de prueba",
            phone = "+573001112233",
            email = "UNO@EXAMPLE.COM",
            phones = listOf("+573001112233", "+573004445566"),
            emails = listOf("UNO@EXAMPLE.COM", "dos@example.com")
        )

        val decoded = decodeContactPayload(encodeContactPayload(original))

        assertEquals("Contacto de prueba", decoded?.name)
        assertEquals(listOf("+573001112233", "+573004445566"), decoded?.phones)
        assertEquals(listOf("uno@example.com", "dos@example.com"), decoded?.emails)
    }

    @Test
    fun incomingAnimatedMedia_keepsGifAndStickerTypes() {
        assertEquals(IncomingShareKind.GIF, classifyIncomingShareKind("image/gif", "animado.gif"))
        assertEquals(IncomingShareKind.GIF, classifyIncomingShareKind("application/octet-stream", "animado.GIF"))
        assertEquals(IncomingShareKind.STICKER, classifyIncomingShareKind("image/webp", "sticker.webp"))
    }

    @Test
    fun globalForwardValidation_appliesSelectionLimits() {
        assertNull(validateForwardSelection(selectedMessageCount = 1, selectedDestinationCount = 3))
        assertEquals(
            "Selecciona al menos un destino",
            validateForwardSelection(selectedMessageCount = 1, selectedDestinationCount = 0)
        )
        assertEquals(
            "Reduce mensajes o destinos; el máximo es 100 envíos por lote",
            validateForwardSelection(selectedMessageCount = 10, selectedDestinationCount = 20)
        )
    }
}
