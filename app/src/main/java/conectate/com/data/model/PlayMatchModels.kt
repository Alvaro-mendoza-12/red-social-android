package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayMatchCapabilities(
    val enabled: Boolean = false,
    val testerAccess: Boolean = false,
    val adultConfirmationRequired: Boolean = true,
    val preciseLocationUsed: Boolean = false,
    val privateMessagesUsedForMatching: Boolean = false,
    val matchmakingRealtime: Boolean = true,
    val photosEnabled: Boolean = false,
)

@Serializable
data class PlayMatchGameCatalogItem(
    val id: String,
    val externalProviderId: String? = null,
    val name: String,
    val slug: String = "",
    val shortName: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    val coverUrl: String? = null,
    val logoUrl: String? = null,
    val iconUrl: String? = null,
    val platforms: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val ranks: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val onlineSupported: Boolean = false,
    val multiplayerSupported: Boolean = true,
    val competitive: Boolean = false,
    val rankedSupported: Boolean = false,
    val rolesSupported: Boolean = false,
    val crossPlay: Boolean = false,
    val active: Boolean = true,
    val releaseDate: String? = null,
    val provider: String = "manual",
    val supportLevel: String = "CATALOG_SUPPORTED",
    val gameUserIdLabel: String = "ID del juego",
    val sourceUrl: String? = null,
    val coverLicense: String? = null,
    val coverAttribution: String? = null,
    val coverSourceUrl: String? = null,
    val userCount: Int = 0,
    val activeSearchCount: Int = 0,
    val rankSchemas: List<PlayMatchRankSchema> = emptyList(),
    val roleDefinitions: List<PlayMatchRoleDefinition> = emptyList(),
)

@Serializable
data class PlayMatchRankSchema(
    val id: String,
    val key: String,
    val displayName: String,
    val version: String,
    val season: String? = null,
    val regionCode: String? = null,
    val platform: String? = null,
    val pointsLabel: String? = null,
    val officialSourceUrl: String? = null,
    val ranks: List<PlayMatchRankDefinition> = emptyList(),
)

@Serializable
data class PlayMatchRankDefinition(
    val id: String,
    val key: String,
    val tier: String = "",
    val division: String = "",
    val displayName: String,
    val order: Int,
    val iconUrl: String? = null,
    val minRating: Int? = null,
    val maxRating: Int? = null,
)

@Serializable
data class PlayMatchRoleDefinition(
    val id: String,
    val key: String,
    val displayName: String,
    val description: String = "",
    val iconUrl: String? = null,
    val officialSourceUrl: String? = null,
)

@Serializable
data class PlayMatchUserGame(
    val id: String,
    val gameId: String,
    val gameName: String,
    val imageUrl: String? = null,
    val platform: String,
    val rank: String = "",
    val level: String = "",
    val role: String = "",
    val gameUserId: String = "",
    val gameUserIdVisible: Boolean = false,
    val visibility: String = "connections",
    val isPrimary: Boolean = false,
)

@Serializable
data class PlayMatchAvailability(
    val id: String? = null,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val timezone: String,
    val crossesMidnight: Boolean = endMinute <= startMinute,
)

@Serializable
data class PlayMatchCompatibilityReason(
    val code: String,
    val points: Int = 0,
    val label: String,
)

@Serializable
data class PlayMatchProfile(
    val userId: String,
    val displayName: String = "Jugador",
    val avatarUrl: String? = null,
    val gamerBio: String = "",
    val regionCode: String = "",
    val languages: List<String> = emptyList(),
    val playStyle: String = "casual",
    val voicePreference: Boolean = false,
    val discoveryLevel: Int = 50,
    val discoverable: Boolean = false,
    val adultConfirmed: Boolean? = null,
    val gameIdVisibility: String? = null,
    val photosVisibility: String? = null,
    val availabilityVisibility: String? = null,
    val timezone: String = "UTC",
    val version: Long = 1,
    val games: List<PlayMatchUserGame> = emptyList(),
    val photos: List<String> = emptyList(),
    val availability: List<PlayMatchAvailability> = emptyList(),
    val compatibilityScore: Int? = null,
    val compatibilityReasons: List<PlayMatchCompatibilityReason> = emptyList(),
    val connected: Boolean = false,
)

@Serializable
data class PlayMatchPreferences(
    @SerialName("game_ids") val gameIds: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    @SerialName("rank_tolerance") val rankTolerance: Int = 2,
    val roles: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    @SerialName("voice_required") val voiceRequired: Boolean = false,
    @SerialName("discovery_level") val discoveryLevel: Int = 50,
    val version: Long = 1,
)

@Serializable data class PlayMatchCatalogResponse(val items: List<PlayMatchGameCatalogItem> = emptyList())
@Serializable data class PlayMatchCatalogPageResponse(
    val items: List<PlayMatchGameCatalogItem> = emptyList(),
    val nextCursor: String? = null,
    val query: String = "",
)
@Serializable data class PlayMatchGameConfigurationResponse(val game: PlayMatchGameCatalogItem)
@Serializable data class PlayMatchProfileResponse(val profile: PlayMatchProfile, val preferences: PlayMatchPreferences? = null)
@Serializable data class PlayMatchPlayersResponse(
    val items: List<PlayMatchProfile> = emptyList(),
    val nextCursor: String? = null,
    val safeguards: PlayMatchSafeguards = PlayMatchSafeguards(),
)
@Serializable data class PlayMatchSafeguards(
    val preciseLocationUsed: Boolean = false,
    val privateMessagesUsed: Boolean = false,
    val ageOrGenderUsed: Boolean = false,
)

@Serializable
data class PlayMatchConnection(
    val id: String,
    val status: String,
    val direction: String = "OUTGOING",
    val otherUserId: String = "",
    val otherName: String = "Jugador",
    val otherAvatarUrl: String? = null,
    val message: String = "",
    val updatedAt: String? = null,
)

@Serializable data class PlayMatchConnectionsResponse(val items: List<PlayMatchConnection> = emptyList())

@Serializable
data class PlayMatchMatch(
    val id: String,
    val status: String = "PENDING",
    val score: Int = 0,
    val reasons: List<PlayMatchCompatibilityReason> = emptyList(),
    val expiresAt: String? = null,
    val player: PlayMatchProfile? = null,
)

@Serializable
data class PlayMatchSession(
    val id: String,
    val status: String = "SEARCHING",
    val criteria: PlayMatchCriteria = PlayMatchCriteria(),
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val match: PlayMatchMatch? = null,
)

@Serializable data class PlayMatchSessionResponse(val session: PlayMatchSession)
@Serializable data class PlayMatchMatchesResponse(val items: List<PlayMatchMatch> = emptyList())

@Serializable
data class PlayMatchCriteria(
    val gameIds: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val rankTolerance: Int = 2,
    val voiceRequired: Boolean = false,
)

@Serializable
data class UpdatePlayMatchProfileRequest(
    val expectedVersion: Long,
    val gamerBio: String,
    val regionCode: String,
    val languages: List<String>,
    val playStyle: String,
    val voicePreference: Boolean,
    val discoveryLevel: Int,
    val discoverable: Boolean,
    val adultConfirmed: Boolean,
    val gameIdVisibility: String,
    val photosVisibility: String,
    val availabilityVisibility: String,
    val photos: List<String>,
    val timezone: String,
)

@Serializable
data class SavePlayMatchGameRequest(
    val gameId: String,
    val gameUserId: String,
    val platform: String,
    val rank: String,
    val level: String,
    val role: String,
    val visibility: String,
    val isPrimary: Boolean,
)

@Serializable data class SavePlayMatchGameResponse(val game: PlayMatchUserGame)
@Serializable data class SavePlayMatchAvailabilityRequest(val entries: List<PlayMatchAvailability>)
@Serializable data class SavePlayMatchAvailabilityResponse(val items: List<PlayMatchAvailability> = emptyList())

@Serializable
data class SavePlayMatchPreferencesRequest(
    val expectedVersion: Long = 0,
    val gameIds: List<String>,
    val platforms: List<String>,
    val rankTolerance: Int,
    val roles: List<String>,
    val styles: List<String>,
    val languages: List<String>,
    val regions: List<String>,
    val voiceRequired: Boolean,
    val discoveryLevel: Int,
)

@Serializable data class SavePlayMatchPreferencesResponse(val preferences: PlayMatchPreferences)
@Serializable data class CreatePlayMatchConnectionRequest(val playerId: String, val message: String, val idempotencyKey: String)
@Serializable data class PlayMatchConnectionResponse(val connection: PlayMatchConnection)
@Serializable data class PlayMatchDecisionRequest(val decision: String, val reason: String = "")
@Serializable data class PlayMatchDecisionResponse(val ok: Boolean = true, val status: String = "")
@Serializable data class StartPlayMatchRequest(
    val idempotencyKey: String,
    val gameIds: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val rankTolerance: Int = 2,
    val voiceRequired: Boolean = false,
)
