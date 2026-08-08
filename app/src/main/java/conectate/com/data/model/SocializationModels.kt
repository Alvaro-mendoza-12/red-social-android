@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package conectate.com.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Server-confirmed Socializar controls. Android never infers eligibility or
 * stores a pending mutation; every visible value comes from the authenticated
 * backend response.
 */
@Serializable
data class SocializationCapabilities(
    val version: Int = 1,
    val enabled: Boolean = false,
    val features: SocializationFeatureCapabilities = SocializationFeatureCapabilities(),
    val preferences: SocializationPreferences = SocializationPreferences(),
) {
    fun canEdit(key: SocializationPreferenceKey): Boolean = enabled && features.isEnabled(key)
}

@Serializable
data class SocializationFeatureCapabilities(
    val discovery: Boolean = false,
    val connectionRequests: Boolean = false,
    val communityDiscovery: Boolean = false,
    val icebreakers: Boolean = false,
    val socialRooms: Boolean = false,
) {
    fun isEnabled(key: SocializationPreferenceKey): Boolean = when (key) {
        SocializationPreferenceKey.DISCOVERY -> discovery
        SocializationPreferenceKey.CONNECTION_REQUESTS -> connectionRequests
        SocializationPreferenceKey.COMMUNITY_DISCOVERY -> communityDiscovery
        SocializationPreferenceKey.ICEBREAKERS -> icebreakers
        SocializationPreferenceKey.SOCIAL_ROOMS -> socialRooms
    }
}

@Serializable
data class SocializationPreferences(
    val discoveryOptIn: Boolean = false,
    val connectionRequestsOptIn: Boolean = false,
    val communityDiscoveryOptIn: Boolean = false,
    val icebreakersOptIn: Boolean = false,
    val socialRoomsOptIn: Boolean = false,
    val revision: Long = 0,
    val updatedAt: String? = null,
) {
    fun isEnabled(key: SocializationPreferenceKey): Boolean = when (key) {
        SocializationPreferenceKey.DISCOVERY -> discoveryOptIn
        SocializationPreferenceKey.CONNECTION_REQUESTS -> connectionRequestsOptIn
        SocializationPreferenceKey.COMMUNITY_DISCOVERY -> communityDiscoveryOptIn
        SocializationPreferenceKey.ICEBREAKERS -> icebreakersOptIn
        SocializationPreferenceKey.SOCIAL_ROOMS -> socialRoomsOptIn
    }
}

@Serializable
data class SocializationPreferencesResponse(
    val preferences: SocializationPreferences = SocializationPreferences(),
    val capabilities: SocializationFeatureCapabilities = SocializationFeatureCapabilities(),
)

enum class SocializationPreferenceKey(
    val label: String,
    val description: String,
) {
    DISCOVERY(
        label = "Descubrimiento",
        description = "Permite mostrarte opciones sociales públicas cuando el servidor habilite esta experiencia.",
    ),
    CONNECTION_REQUESTS(
        label = "Solicitudes de conexión",
        description = "Permite recibir solicitudes explícitas dentro de las reglas de seguridad de Novvin.",
    ),
    COMMUNITY_DISCOVERY(
        label = "Comunidades",
        description = "Permite recomendar comunidades públicas que cumplan las políticas de la plataforma.",
    ),
    ICEBREAKERS(
        label = "Inicios de conversación",
        description = "Permite usar propuestas de inicio aprobadas cuando estén disponibles para tu cuenta.",
    ),
    SOCIAL_ROOMS(
        label = "Salas sociales",
        description = "Permite descubrir salas públicas que el servidor haya habilitado para tu cuenta.",
    ),
}

@Serializable
data class SocializationPreferencePatch(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val discoveryOptIn: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val connectionRequestsOptIn: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val communityDiscoveryOptIn: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val icebreakersOptIn: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val socialRoomsOptIn: Boolean? = null,
) {
    companion object {
        fun forKey(key: SocializationPreferenceKey, enabled: Boolean): SocializationPreferencePatch = when (key) {
            SocializationPreferenceKey.DISCOVERY -> SocializationPreferencePatch(discoveryOptIn = enabled)
            SocializationPreferenceKey.CONNECTION_REQUESTS -> SocializationPreferencePatch(connectionRequestsOptIn = enabled)
            SocializationPreferenceKey.COMMUNITY_DISCOVERY -> SocializationPreferencePatch(communityDiscoveryOptIn = enabled)
            SocializationPreferenceKey.ICEBREAKERS -> SocializationPreferencePatch(icebreakersOptIn = enabled)
            SocializationPreferenceKey.SOCIAL_ROOMS -> SocializationPreferencePatch(socialRoomsOptIn = enabled)
        }
    }
}

@Serializable
data class UpdateSocializationPreferencesRequest(
    val expectedVersion: Long,
    val preferences: SocializationPreferencePatch,
)
