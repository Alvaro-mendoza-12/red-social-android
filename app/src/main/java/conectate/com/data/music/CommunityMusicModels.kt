package conectate.com.data.music

import kotlinx.serialization.Serializable

@Serializable
data class CommunityMusicTrack(
    val id: String,
    val rawId: String = "",
    val ownerId: String = "",
    val title: String,
    val artist: String,
    val album: String? = null,
    val collaborators: List<String> = emptyList(),
    val coverUrl: String? = null,
    val audioUrl: String,
    val previewUrl: String? = null,
    val durationMs: Long,
    val genre: String? = null,
    val subgenre: String? = null,
    val mood: String? = null,
    val tags: List<String> = emptyList(),
    val language: String? = null,
    val instrumental: Boolean = false,
    val explicit: Boolean = false,
    val year: Int? = null,
    val description: String? = null,
    val source: String = "novvin_community",
    val provider: String = "Novvin Community",
    val sourcePageUrl: String? = null,
    val license: String,
    val licenseUrl: String? = null,
    val attribution: String? = null,
    val waveformUrl: String? = null,
    val visibility: String = "PRIVATE",
    val requestedVisibility: String = "PRIVATE",
    val allowReuse: Boolean = false,
    val allowRemix: Boolean = false,
    val allowUseInMoments: Boolean = false,
    val moderationState: String = "PROCESSING",
    val moderationReason: String? = null,
    val favoriteCount: Int = 0,
    val listenCount: Int = 0,
    val isFavorite: Boolean = false,
    val publishedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class CommunityMusicPage(
    val items: List<CommunityMusicTrack> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class CommunityMusicDeleteResponse(
    val id: String,
    val deleted: Boolean,
    val alreadyDeleted: Boolean = false,
    val existingMomentsPreserved: Boolean = true,
)

@Serializable
data class ApprovedMusicCatalogPage(
    val items: List<ApprovedMomentMusic> = emptyList(),
    val nextCursor: String? = null,
    val section: String = "search",
    val providers: Map<String, Boolean> = emptyMap(),
    val legalPolicy: String = "EXTERNAL_CC0_OR_PUBLIC_DOMAIN_ONLY",
)

@Serializable
data class MusicCatalogCategory(
    val id: String,
    val label: String,
)

@Serializable
data class MusicCatalogCategories(
    val items: List<MusicCatalogCategory> = emptyList(),
    val externalLicensePolicy: List<String> = emptyList(),
)

@Serializable
data class MusicFavoriteResponse(val favorite: Boolean)

@Serializable
data class MusicReportResponse(val reported: Boolean)

@Serializable
data class MusicNotInterestedResponse(val hidden: Boolean)

@Serializable
data class CommunityMusicTrackEnvelope(val track: CommunityMusicTrack)

@Serializable
data class CommunityMusicDeclarations(
    val ownsOrControlsRights: Boolean,
    val acceptsPublication: Boolean,
    val acceptsModeration: Boolean,
    val acceptsTakedown: Boolean,
)

@Serializable
data class CreateCommunityMusicRequest(
    val title: String,
    val artist: String,
    val audioUrl: String,
    val durationMs: Long,
    val album: String? = null,
    val collaborators: List<String> = emptyList(),
    val coverUrl: String? = null,
    val genre: String? = null,
    val subgenre: String? = null,
    val mood: String? = null,
    val tags: List<String> = emptyList(),
    val language: String? = null,
    val instrumental: Boolean = false,
    val explicit: Boolean = false,
    val year: Int? = null,
    val description: String? = null,
    val lyrics: String? = null,
    val rightsType: String,
    val licenseName: String? = null,
    val licenseUrl: String? = null,
    val originalSourceUrl: String? = null,
    val territories: List<String> = emptyList(),
    val attribution: String? = null,
    val visibility: String,
    val allowReuse: Boolean,
    val allowRemix: Boolean,
    val termsVersion: String,
    val declarations: CommunityMusicDeclarations,
    val evidence: Map<String, String> = emptyMap(),
)

data class CommunityMusicUploadDraft(
    val title: String,
    val artist: String,
    val album: String = "",
    val collaborators: List<String> = emptyList(),
    val genre: String,
    val subgenre: String = "",
    val mood: String,
    val tags: List<String>,
    val language: String = "",
    val year: Int? = null,
    val description: String,
    val lyrics: String = "",
    val rightsType: String,
    val licenseUrl: String = "",
    val originalSourceUrl: String = "",
    val territories: List<String> = emptyList(),
    val evidenceDescription: String = "",
    val visibility: String,
    val allowReuse: Boolean,
    val allowRemix: Boolean,
    val explicit: Boolean,
    val instrumental: Boolean,
    val acceptedRights: Boolean,
)

internal fun CommunityMusicTrack.toApprovedMomentMusic(): ApprovedMomentMusic? {
    if (moderationState != "APPROVED" || !allowReuse || visibility !in setOf("PUBLIC_REUSABLE", "PUBLIC_REMIXABLE")) {
        return null
    }
    return ApprovedMomentMusic(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        audioUrl = audioUrl,
        previewUrl = previewUrl,
        sourcePageUrl = sourcePageUrl.orEmpty(),
        source = "novvin_community",
        provider = provider,
        license = license,
        licenseUrl = licenseUrl,
        attribution = attribution,
        artworkUrl = coverUrl,
        categories = listOfNotNull(genre, subgenre, mood) + tags,
        waveformUrl = waveformUrl,
        allowUseInMoments = allowUseInMoments || allowReuse,
        allowRemix = allowRemix,
        isFavorite = isFavorite,
        ownerId = ownerId,
    )
}
