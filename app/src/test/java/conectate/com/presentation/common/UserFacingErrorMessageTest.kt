package conectate.com.presentation.common

import kotlin.test.Test
import kotlin.test.assertEquals

class UserFacingErrorMessageTest {

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
}
