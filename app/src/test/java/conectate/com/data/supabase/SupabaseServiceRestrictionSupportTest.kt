package conectate.com.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseServiceRestrictionSupportTest {

    @Test
    fun rawStorageQuotaRestriction_mapsToCanonicalFriendlyMessage() {
        val message = mapSupabaseServiceRestrictionMessage(
            """{"message":"Service for this project is restricted due to the following violations: exceed_storage_size_quota"}"""
        )

        assertEquals(
            "El servicio está temporalmente restringido porque el proyecto excedió la cuota de almacenamiento en Supabase. No es un error de tu cuenta. Hay que liberar espacio, revisar facturación o esperar al próximo reinicio del ciclo.",
            message
        )
    }

    @Test
    fun friendlyRestrictionMessage_isDetectedIdempotently() {
        val friendlyMessage =
            "El servicio está temporalmente restringido porque el proyecto excedió la cuota de almacenamiento en Supabase. No es un error de tu cuenta. Hay que liberar espacio, revisar facturación o esperar al próximo reinicio del ciclo."

        assertEquals(
            friendlyMessage,
            mapSupabaseServiceRestrictionMessage(friendlyMessage)
        )
        assertTrue(isSupabaseServiceRestrictionMessage(friendlyMessage))
    }

    @Test
    fun genericEnglishRestriction_mapsToGenericFriendlyMessage() {
        val message = mapSupabaseServiceRestrictionMessage(
            "Service for this project is restricted under the fair use policy. Reach out to Supabase support."
        )

        assertNotNull(message)
        assertTrue(message!!.contains("temporalmente restringido", ignoreCase = true))
        assertTrue(isSupabaseServiceRestrictionMessage(message))
    }

    @Test
    fun unrelatedMessage_isNotMarkedAsRestriction() {
        val message = "Invalid login credentials"

        assertEquals(null, mapSupabaseServiceRestrictionMessage(message))
        assertFalse(isSupabaseServiceRestrictionMessage(message))
    }
}
