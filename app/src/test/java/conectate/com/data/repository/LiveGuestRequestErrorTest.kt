package conectate.com.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveGuestRequestErrorTest {
    @Test fun closedRequestsExplainTheHostDecision() {
        assertEquals("El creador ha cerrado las solicitudes para unirse.",
            liveGuestRequestErrorMessage(Exception("LIVE_GUEST_REQUESTS_DISABLED")))
    }
    @Test fun closedRequestsCanBeReportedByAWrappedException() {
        assertEquals("El creador ha cerrado las solicitudes para unirse.",
            liveGuestRequestErrorMessage(Exception("http failed", Exception("LIVE_GUEST_REQUESTS_DISABLED"))))
    }
    @Test fun missingOrEndedLiveDoesNotSuggestAnInfiniteRetry() {
        for (code in listOf("LIVE_NOT_ACTIVE", "LIVE_NOT_FOUND")) {
            assertEquals("Este directo ya no está disponible.", liveGuestRequestErrorMessage(Exception(code)))
        }
    }
    @Test fun fullSeatsAndConflictsHaveDifferentRecoveryActions() {
        assertEquals("Todos los lugares están ocupados. Intenta cuando quede uno libre.",
            liveGuestRequestErrorMessage(Exception("LIVE_GUEST_CAPACITY")))
        assertEquals("Tu solicitud ya cambió. Cierra este panel y vuelve a abrirlo.",
            liveGuestRequestErrorMessage(Exception("LIVE_GUEST_REQUEST_CONFLICT")))
    }
    @Test fun unknownErrorsDoNotExposeServerDetails() {
        val message = liveGuestRequestErrorMessage(Exception("secret database hostname:password"))
        assertFalse(message.contains("secret"))
        assertEquals("No se pudo enviar la solicitud. Revisa tu conexión y vuelve a intentarlo.", message)
    }
}
