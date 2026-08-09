package conectate.com.data.recommendations

import conectate.com.data.model.AlgorithmCapabilities
import conectate.com.data.model.AlgorithmConfig
import conectate.com.data.model.Post
import conectate.com.data.model.Video
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A mapped page whose item sequence is still exactly the server sequence. */
data class MappedAuthoritativeRecommendationPage<T>(
    val items: List<T>,
    val nextCursor: OpaqueRecommendationCursor?,
    val hasMore: Boolean,
    val rankingVersion: String,
    val explanationsEnabled: Boolean,
    val explanationsByCandidateId: Map<String, List<String>>,
)

/**
 * A feed may opt into the authoritative endpoint only after both the global
 * capability and the selected owned profile are confirmed by the backend.
 */
fun canUseAuthoritativeRecommendations(
    capabilities: AlgorithmCapabilities,
    config: AlgorithmConfig,
): Boolean = capabilities.enabled &&
    capabilities.classificationEnabled &&
    config.profile.isEnabled &&
    config.profile.id.isNotBlank()

fun AuthoritativeRecommendationPage.mapSocialPostsExact(): Result<MappedAuthoritativeRecommendationPage<Post>> =
    mapExact(AuthoritativeRecommendationSurface.SOCIAL, ::postFromPublicPayload)

fun AuthoritativeRecommendationPage.mapVideosExact(): Result<MappedAuthoritativeRecommendationPage<Video>> =
    mapExact(AuthoritativeRecommendationSurface.VIDEO, ::videoFromPublicPayload)

private fun <T> AuthoritativeRecommendationPage.mapExact(
    expectedSurface: AuthoritativeRecommendationSurface,
    mapper: (AuthoritativeRecommendationItem) -> T,
): Result<MappedAuthoritativeRecommendationPage<T>> = runCatching {
    require(surface == expectedSurface) { "RECOMMENDATION_SURFACE_MISMATCH" }

    // map() is intentional. Never sort, shuffle, group or distinct this list.
    val mapped = items.map(mapper)
    val reasons = if (metadata.explanationsEnabled) {
        explanations.associate { explanation ->
            explanation.candidateId to explanation.toTruthfulDisplayReasons(metadata.rankingVersion)
        }
    } else {
        emptyMap()
    }
    MappedAuthoritativeRecommendationPage(
        items = mapped,
        nextCursor = nextCursor,
        hasMore = hasMore,
        rankingVersion = metadata.rankingVersion,
        explanationsEnabled = metadata.explanationsEnabled,
        explanationsByCandidateId = reasons,
    )
}

private fun postFromPublicPayload(item: AuthoritativeRecommendationItem): Post {
    val payload = item.payload
    val id = requiredMatchingId(item)
    val userId = payload.string("user_id").required("RECOMMENDATION_POST_CREATOR_INVALID")
    return Post(
        id = id,
        userId = userId,
        userName = payload.string("user_name").orEmpty().ifBlank { "Usuario" },
        userProfileImage = payload.string("user_profile_image"),
        content = payload.string("content").orEmpty(),
        mediaUrls = payload.mediaUrls("media_urls"),
        mediaUrlLegacy = payload.string("media_url"),
        createdAt = payload.string("created_at").orEmpty(),
        likeCount = payload.nonNegativeInt("like_count"),
        commentCount = payload.nonNegativeInt("comment_count"),
        shareCount = payload.nonNegativeInt("share_count"),
        likedByMe = payload.boolean("liked_by_me"),
        sharedByMe = payload.boolean("shared_by_me"),
        myReaction = payload.string("my_reaction"),
        reactionCounts = payload.intMap("reaction_counts"),
        isVerified = payload.boolean("is_verified"),
        visibility = payload.string("visibility").orEmpty().ifBlank { "public" },
        remixAllowed = payload.boolean("remix_allowed"),
        remixAudience = payload.string("remix_audience").orEmpty().ifBlank { "public" },
        remixRevokedAt = payload.string("remix_revoked_at"),
        remixSourcePostId = payload.string("remix_source_post_id"),
        remixRootPostId = payload.string("remix_root_post_id"),
        remixSourceUserId = payload.string("remix_source_user_id"),
        remixSourceUserName = payload.string("remix_source_user_name"),
        remixDepth = payload.nonNegativeInt("remix_depth"),
    )
}

private fun videoFromPublicPayload(item: AuthoritativeRecommendationItem): Video {
    val payload = item.payload
    val id = requiredMatchingId(item)
    val userId = payload.string("user_id").required("RECOMMENDATION_VIDEO_CREATOR_INVALID")
    val videoUrl = payload.string("video_url").required("RECOMMENDATION_VIDEO_URL_INVALID")
    return Video(
        id = id,
        userId = userId,
        userName = payload.string("user_name").orEmpty().ifBlank { "Usuario" },
        userProfileImage = payload.string("user_profile_image").orEmpty(),
        avatarMediaType = payload.string("avatar_media_type"),
        avatarVideoMuted = payload.booleanOrDefault("avatar_video_muted", true),
        title = payload.string("title").orEmpty(),
        description = payload.string("description").orEmpty(),
        videoUrl = videoUrl,
        thumbnailUrl = payload.string("thumbnail_url")
            ?: payload.string("cover_url")
            ?: "",
        duration = payload.long("duration")
            ?: payload.long("duration_seconds")
            ?: 0L,
        views = payload.nonNegativeInt("views"),
        likeCount = payload.nonNegativeInt("like_count"),
        commentCount = payload.nonNegativeInt("comment_count"),
        shareCount = payload.nonNegativeInt("share_count"),
        country = payload.string("country").orEmpty(),
        city = payload.string("city").orEmpty(),
        created_at = payload.string("created_at").orEmpty(),
        updated_at = payload.string("updated_at").orEmpty(),
        isPublic = payload.booleanOrDefault("is_public", true),
        tags = payload.stringList("tags"),
        likedByMe = payload.boolean("liked_by_me"),
        sharedByMe = payload.boolean("shared_by_me"),
        favoriteCount = payload.nonNegativeInt("favorite_count"),
        favoritedByMe = payload.boolean("favorited_by_me"),
        isVerified = payload.boolean("is_verified"),
    )
}

private fun requiredMatchingId(item: AuthoritativeRecommendationItem): String {
    val itemId = item.id.required("RECOMMENDATION_ITEM_ID_INVALID")
    val payloadId = item.payload.string("id").required("RECOMMENDATION_PAYLOAD_ID_INVALID")
    require(itemId == payloadId) { "RECOMMENDATION_ITEM_ID_MISMATCH" }
    return itemId
}

/**
 * Translates only explicit stages emitted by the trusted ranking service. It
 * never guesses an interest, topic or private signal from the content itself.
 */
internal fun AuthoritativeRecommendationExplanation.toTruthfulDisplayReasons(
    rankingVersion: String,
): List<String> {
    val stages = payload["stages"] as? JsonArray ?: return emptyList()
    val reasons = stages.mapNotNull { element ->
        val stage = (element as? JsonObject) ?: return@mapNotNull null
        val name = stage.string("stage") ?: return@mapNotNull null
        val outcome = stage.string("outcome") ?: return@mapNotNull null
        when (name to outcome) {
            "explicit_preferences" to "matched" ->
                "Coincidió con una preferencia explícita que configuraste."
            "followed_creator" to "matched" ->
                "Sigues a la persona que publicó este contenido."
            "feed_mix" to "applied" ->
                "Se aplicó la composición de feed de tu perfil de Mi algoritmo."
            "permitted_inference" to "matched" ->
                "Coincidió con señales de actividad que autorizaste usar."
            "diversity" to "applied" ->
                "Se aplicó diversidad para evitar contenido demasiado repetitivo."
            "diversity" to "overridden_by_explicit_preference" ->
                "Una preferencia explícita tuvo prioridad sobre el límite de repetición."
            "trends" to "matched" ->
                "El componente de tendencias configurado influyó en esta selección."
            "exploration" to "matched" ->
                "El componente de exploración configurado influyó en esta selección."
            else -> null
        }
    }.distinct()
    return if (reasons.isNotEmpty()) reasons else {
        listOf("Mi algoritmo seleccionó este contenido en el servidor ($rankingVersion).")
    }
}

private fun String?.required(code: String): String =
    this?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException(code)

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.booleanOrDefault(key: String, fallback: Boolean): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: fallback

private fun JsonObject.long(key: String): Long? =
    (get(key) as? JsonPrimitive)?.longOrNull

private fun JsonObject.nonNegativeInt(key: String): Int =
    ((get(key) as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)

private fun JsonObject.stringList(key: String): List<String> =
    ((get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }) ?: emptyList()

private fun JsonObject.mediaUrls(key: String): List<String> =
    ((get(key) as? JsonArray)?.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> element.string("url")
            else -> null
        }
    }) ?: emptyList()

private fun JsonObject.intMap(key: String): Map<String, Int> =
    (get(key) as? JsonObject)?.entries?.mapNotNull { (name, value) ->
        val count = (value as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        name to count.coerceAtLeast(0)
    }?.toMap() ?: emptyMap()
