@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/**
 * Public, explicit configuration of a user's recommendation experience.
 *
 * These models intentionally contain no private messages, precise location, age,
 * gender or inferred sensitive attributes. The server remains authoritative and
 * uses the revision supplied by the client to reject conflicting updates.
 */
@Serializable
data class AlgorithmCapabilities(
    val enabled: Boolean = false,
    val capabilities: AlgorithmCapabilityFlags = AlgorithmCapabilityFlags(),
) {
    val classificationEnabled: Boolean get() = capabilities.classification
    val preferencesEnabled: Boolean get() = capabilities.preferences
    val feedMixEnabled: Boolean get() = capabilities.feedMix
    val dataPermissionsEnabled: Boolean get() = capabilities.dataPermissions
    val explanationsEnabled: Boolean get() = capabilities.explanations
}

@Serializable
data class AlgorithmCapabilityFlags(
    val classification: Boolean = false,
    val preferences: Boolean = false,
    val feedMix: Boolean = false,
    val dataPermissions: Boolean = false,
    val explanations: Boolean = false,
    val socialDiscovery: Boolean = false,
    val socialRooms: Boolean = false,
)

@Serializable
data class AlgorithmProfile(
    val id: String = "",
    val name: String = "Para mí",
    val slug: String = "default",
    val mode: String = AlgorithmMode.MANUAL.wireName,
    val isEnabled: Boolean = false,
    val isDefault: Boolean = false,
    val revision: Long = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class AlgorithmPreference(
    val id: String = "",
    val surface: String = AlgorithmSurface.GLOBAL.wireName,
    val dimension: String = AlgorithmDimension.TOPIC.wireName,
    val subjectKey: String = "",
    val subjectLabel: String? = null,
    val score: Int = 0,
    val isExplicit: Boolean = true,
    val startsAt: String? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    val isBlocked: Boolean get() = score <= BLOCKED_SCORE
    val isTemporary: Boolean get() = !expiresAt.isNullOrBlank()

    companion object {
        const val MIN_SCORE = -100
        const val MAX_SCORE = 100
        const val BLOCKED_SCORE = -100

        fun normalizedScore(value: Int): Int = value.coerceIn(MIN_SCORE, MAX_SCORE)
    }
}

@Serializable
data class AlgorithmFeedMix(
    val surface: String = AlgorithmSurface.VIDEO.wireName,
    val following: Int = 30,
    val interests: Int = 40,
    val communities: Int = 0,
    val newContent: Int = 20,
    val trends: Int = 10,
    val exploration: Int = 0,
    val revision: Long = 0,
    val updatedAt: String? = null,
) {
    val total: Int
        get() = following + interests + communities + newContent + trends + exploration

    fun isValid(): Boolean = listOf(
        following,
        interests,
        communities,
        newContent,
        trends,
        exploration,
    ).all { it in 0..100 } && total == 100
}

@Serializable
data class AlgorithmDataPermissions(
    val likes: Boolean = false,
    val follows: Boolean = false,
    val searches: Boolean = false,
    val watchTime: Boolean = false,
    val scrollVelocity: Boolean = false,
    val profileVisits: Boolean = false,
    val publicInteractions: Boolean = false,
    val communityActivity: Boolean = false,
    val liveActivity: Boolean = false,
    val revision: Long = 0,
    val updatedAt: String? = null,
)

@Serializable
data class AlgorithmConfig(
    val profile: AlgorithmProfile = AlgorithmProfile(),
    val preferences: List<AlgorithmPreference> = emptyList(),
    val feedMixes: List<AlgorithmFeedMix> = emptyList(),
    val dataPermissions: AlgorithmDataPermissions = AlgorithmDataPermissions(),
    val temporaryPreferences: List<AlgorithmPreference> = emptyList(),
)

@Serializable
data class CreateAlgorithmProfileRequest(
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val slug: String? = null,
    val mode: String = AlgorithmMode.MANUAL.wireName,
)

@Serializable
data class UpdateAlgorithmProfileRequest(
    val expectedVersion: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val name: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val mode: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val isEnabled: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val makeDefault: Boolean? = null,
)

@Serializable
data class AlgorithmPreferenceInput(
    val surface: String = AlgorithmSurface.GLOBAL.wireName,
    val dimension: String,
    val subjectKey: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val subjectLabel: String? = null,
    val score: Int,
)

@Serializable
data class UpdateAlgorithmPreferencesRequest(
    val expectedVersion: Long,
    val preferences: List<AlgorithmPreferenceInput>,
)

@Serializable
data class UpdateAlgorithmFeedMixRequest(
    val expectedVersion: Long,
    val following: Int,
    val interests: Int,
    val communities: Int,
    val newContent: Int,
    val trends: Int,
    val exploration: Int,
)

@Serializable
data class UpdateAlgorithmDataPermissionsRequest(
    val expectedVersion: Long,
    val permissions: AlgorithmDataPermissionInput,
)

@Serializable
data class AlgorithmDataPermissionInput(
    val likes: Boolean = false,
    val follows: Boolean = false,
    val searches: Boolean = false,
    val watchTime: Boolean = false,
    val scrollVelocity: Boolean = false,
    val profileVisits: Boolean = false,
    val publicInteractions: Boolean = false,
    val communityActivity: Boolean = false,
    val liveActivity: Boolean = false,
)

@Serializable
data class CreateTemporaryAlgorithmPreferenceRequest(
    val expectedVersion: Long,
    val surface: String = AlgorithmSurface.GLOBAL.wireName,
    val dimension: String,
    val subjectKey: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val subjectLabel: String? = null,
    val score: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val startsAt: String? = null,
    val expiresAt: String,
)

enum class AlgorithmMode(val wireName: String) {
    MANUAL("manual"),
    ASSISTED("assisted"),
    AUTOMATIC("automatic");

    companion object {
        fun fromWire(value: String?): AlgorithmMode =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: MANUAL
    }
}

enum class AlgorithmSurface(val wireName: String, val label: String) {
    GLOBAL("global", "Toda Novvin"),
    VIDEO("video", "Videos"),
    SOCIAL("social", "Social"),
    LIVE("live", "Directos"),
    COMMUNITY("community", "Comunidades"),
    PEOPLE("people", "Personas"),
    CHALLENGE("challenge", "Retos"),
    EVENT("event", "Eventos");

    companion object {
        fun fromWire(value: String?): AlgorithmSurface =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: GLOBAL
    }
}

enum class AlgorithmDimension(val wireName: String, val label: String) {
    CONTENT("content", "Esta publicación"),
    TOPIC("topic", "Este tema"),
    SUBTOPIC("subtopic", "Este subtema"),
    CREATOR("creator", "Este creador"),
    FORMAT("format", "Este tipo de contenido"),
    AUDIO("audio", "Este audio o tendencia"),
    LANGUAGE("language", "Este idioma"),
    COMMUNITY("community", "Esta comunidad"),
    RELATIONSHIP("relationship", "Esta relación social");

    companion object {
        fun fromWire(value: String?): AlgorithmDimension =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: TOPIC
    }
}
