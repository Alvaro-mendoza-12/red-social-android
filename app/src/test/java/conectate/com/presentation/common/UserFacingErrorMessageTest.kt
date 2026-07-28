package conectate.com.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Test

class UserFacingErrorMessageTest {
    @Test
    fun `live moderation errors are explained without backend codes`() {
        assertEquals(
            "El chat está pausado temporalmente por el equipo de moderación.",
            Exception("423 - {\"error\":\"LIVE_CHAT_PAUSED\"}").toUserFacingMessage()
        )
        assertEquals(
            "Ese comentario contiene una palabra que el equipo del directo bloqueó.",
            Exception("422 - {\"error\":\"LIVE_COMMENT_BLOCKED\"}").toUserFacingMessage()
        )
        assertEquals(
            "El equipo de moderación restringió tu acceso a este directo.",
            Exception("403 - {\"error\":\"LIVE_BANNED\"}").toUserFacingMessage()
        )
    }


    @Test
    fun restrictionMessageBecomesFriendly() {
        val result = Exception(
            "Service for this project is restricted due to the following violations: exceed_storage_size_quota"
        ).toUserFacingMessage()

        assertEquals(
            "El servicio está temporalmente restringido porque el proyecto excedió la cuota de almacenamiento en Supabase. No es un error de tu cuenta. Hay que liberar espacio, revisar facturación o esperar al próximo reinicio del ciclo.",
            result
        )
    }

    @Test
    fun invalidCredentialsBecomeFriendly() {
        val result = Exception("Invalid login credentials").toUserFacingMessage()

        assertEquals("Correo o contraseña incorrectos.", result)
    }

    @Test
    fun webLoginPairingTechnicalIssueIsHidden() {
        val result = Exception(
            "Could not find the function public.web-login-pairing in schema cache"
        ).toUserFacingMessage()

        assertEquals(
            "La conexión entre la app y la web no está disponible en este momento. Inténtalo más tarde.",
            result
        )
    }

    @Test
    fun friendlySpanishMessageIsPreserved() {
        val result = Exception("No se pudo crear el grupo porque faltan miembros.").toUserFacingMessage()

        assertEquals("No se pudo crear el grupo porque faltan miembros.", result)
    }

    @Test
    fun storagePathIssueBecomesFriendly() {
        val result = Exception("Invalid storage path").toUserFacingMessage()

        assertEquals(
            "El archivo seleccionado no es válido o su ruta está dañada. Vuelve a intentarlo con otro archivo.",
            result
        )
    }

    @Test
    fun mediaPolicyIssueBecomesFriendly() {
        val result = Exception("media_policy_blocked:403:unsupported file extension").toUserFacingMessage()

        assertEquals(
            "El archivo fue bloqueado por la política de seguridad. Revisa el tipo, tamaño o extensión antes de volver a intentarlo.",
            result
        )
    }

    @Test
    fun missingSessionTokenBecomesFriendly() {
        val result = Exception("No access token available").toUserFacingMessage()

        assertEquals(
            "Tu sesión venció o dejó de ser válida. Inicia sesión de nuevo.",
            result
        )
    }

    @Test
    fun expiredTunnelHostnameBecomesFriendly() {
        val result = java.net.UnknownHostException(
            "Unable to resolve host \"expired.trycloudflare.com\": No address associated with hostname"
        ).toUserFacingMessage()

        assertEquals(
            "No pudimos conectarnos con el servidor. Revisa tu internet e inténtalo otra vez.",
            result
        )
    }

    @Test
    fun cancelledJobDoesNotExposeCoroutineInternals() {
        val result = Exception("Job was cancelled").toUserFacingMessage()

        assertEquals(
            "La operación se interrumpió antes de terminar. Inténtalo nuevamente.",
            result
        )
    }

    @Test
    fun backendJsonDoesNotReachTheUser() {
        val result = Exception("500 - {\"error\":\"INTERNAL_ERROR\"}").toUserFacingMessage()

        assertEquals(
            "El servicio no está disponible en este momento. Inténtalo nuevamente más tarde.",
            result
        )
    }
}
