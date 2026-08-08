@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package conectate.com.data.recommendations

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Surface accepted by the isolated, server-authoritative recommendations API.
 *
 * This is deliberately separate from the legacy feed modes. Adding this type
 * does not opt a screen into server ranking; a future repository must make that
 * decision only after capabilities and its safe candidate provider are live.
 */
enum class AuthoritativeRecommendationSurface(val wireValue: String) {
    VIDEO("video"),
    SOCIAL("social"),
}

/**
 * A cursor issued by the backend. It is intentionally opaque to Android: the
 * client may pass it back unchanged, but must never parse, normalize, derive
 * state from, log, or persist it.
 */
@JvmInline
value class OpaqueRecommendationCursor private constructor(
    private val rawValue: String,
) {
    fun asRequestValue(): String = rawValue

    override fun toString(): String = "OpaqueRecommendationCursor(redacted)"

    companion object {
        private const val MAX_CURSOR_LENGTH = 8_192

        fun fromServer(value: String?): OpaqueRecommendationCursor? {
            if (value == null || value.isBlank() || value.length > MAX_CURSOR_LENGTH) return null
            // Do not trim or otherwise alter a cryptographically protected cursor.
            return OpaqueRecommendationCursor(value)
        }
    }
}

/**
 * Server metadata retained by the transport. Profile/account identifiers are
 * intentionally not exposed here because UI callers do not need them to render
 * a page. The backend remains the sole authority for that association.
 */
data class AuthoritativeRecommendationMetadata(
    val rankingVersion: String,
    val explanationsEnabled: Boolean,
)

/**
 * Public display item returned in the exact order selected by the backend.
 *
 * The payload is intentionally untyped here. A future Video/Social adapter can
 * decode it into its existing public model without re-ranking, de-duplicating,
 * or replacing the server order. This transport neither requests nor retains
 * private messages, location, age, gender, contacts, or inferred signals.
 */
data class AuthoritativeRecommendationItem(
    val id: String?,
    val payload: JsonObject,
)

/**
 * Exact explanation supplied by the backend for a candidate. Android never
 * writes or synthesizes an explanation; a screen must only render this payload
 * when it can associate [candidateId] with a trusted public item identifier.
 */
data class AuthoritativeRecommendationExplanation(
    val candidateId: String,
    val payload: JsonObject,
)

/**
 * Immutable server page. [items] intentionally preserves the backend sequence,
 * including a duplicate if the server ever sends one, so an integration cannot
 * accidentally turn local sorting into an authoritative recommendation feed.
 */
data class AuthoritativeRecommendationPage(
    val surface: AuthoritativeRecommendationSurface,
    val items: List<AuthoritativeRecommendationItem>,
    val nextCursor: OpaqueRecommendationCursor?,
    val hasMore: Boolean,
    val metadata: AuthoritativeRecommendationMetadata,
    val explanations: List<AuthoritativeRecommendationExplanation>,
) {
    fun explanationForCandidate(candidateId: String): AuthoritativeRecommendationExplanation? =
        explanations.firstOrNull { it.candidateId == candidateId }
}

/**
 * The explicit result contract for a future feed integration.
 *
 * A [Fallback] is not an error state to hide: it tells the caller to retain its
 * existing legacy/offline feed rather than pretending unverified content was
 * server-ranked. Reasons contain no response body, token, cursor, or identity.
 */
sealed interface AuthoritativeRecommendationDecodeResult {
    data class Authoritative(
        val page: AuthoritativeRecommendationPage,
    ) : AuthoritativeRecommendationDecodeResult

    data class Fallback(
        val reason: AuthoritativeRecommendationFallbackReason,
    ) : AuthoritativeRecommendationDecodeResult
}

enum class AuthoritativeRecommendationFallbackReason {
    MALFORMED_ENVELOPE,
    NOT_SERVER_AUTHORITATIVE,
    INVALID_PAGINATION,
    INVALID_EXPLANATIONS,
}

/**
 * JSON-only decoder for `/v1/data/recommendations`.
 *
 * It deliberately has no HTTP dependency and is not injected into any current
 * repository. That keeps the legacy Social and Video feeds unchanged until the
 * backend candidate source is safety-reviewed and a feature-gated integration
 * can consume this exact contract.
 */
object AuthoritativeRecommendationPageJson {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun decode(
        body: String,
        surface: AuthoritativeRecommendationSurface,
    ): AuthoritativeRecommendationDecodeResult {
        val envelope = runCatching {
            json.decodeFromString<AuthoritativeRecommendationEnvelope>(body)
        }.getOrElse {
            return AuthoritativeRecommendationDecodeResult.Fallback(
                AuthoritativeRecommendationFallbackReason.MALFORMED_ENVELOPE,
            )
        }

        val algorithm = envelope.algorithm
            ?: return fallback(AuthoritativeRecommendationFallbackReason.MALFORMED_ENVELOPE)
        if (algorithm.authoritative != true) {
            return fallback(AuthoritativeRecommendationFallbackReason.NOT_SERVER_AUTHORITATIVE)
        }
        val rankingVersion = algorithm.version?.takeIf { it.isNotBlank() }
            ?: return fallback(AuthoritativeRecommendationFallbackReason.MALFORMED_ENVELOPE)
        val rawItems = envelope.items
            ?: return fallback(AuthoritativeRecommendationFallbackReason.MALFORMED_ENVELOPE)
        val rawPage = envelope.page
            ?: return fallback(AuthoritativeRecommendationFallbackReason.MALFORMED_ENVELOPE)
        val hasMore = rawPage.hasMore
            ?: return fallback(AuthoritativeRecommendationFallbackReason.MALFORMED_ENVELOPE)
        val nextCursor = OpaqueRecommendationCursor.fromServer(rawPage.nextCursor)
        if ((hasMore && nextCursor == null) || (!hasMore && nextCursor != null)) {
            return fallback(AuthoritativeRecommendationFallbackReason.INVALID_PAGINATION)
        }

        val explanations = when {
            algorithm.explanationsEnabled == true -> parseExplanations(envelope.explanations)
                ?: return fallback(AuthoritativeRecommendationFallbackReason.INVALID_EXPLANATIONS)
            else -> emptyList()
        }

        // map() is deliberate: it preserves the exact server sequence and never
        // de-duplicates, sorts, scores, or derives replacement candidates.
        return AuthoritativeRecommendationDecodeResult.Authoritative(
            AuthoritativeRecommendationPage(
                surface = surface,
                items = rawItems.map { item ->
                    AuthoritativeRecommendationItem(
                        id = item.stringValue("id")?.takeIf { it.isNotBlank() },
                        payload = item,
                    )
                },
                nextCursor = nextCursor,
                hasMore = hasMore,
                metadata = AuthoritativeRecommendationMetadata(
                    rankingVersion = rankingVersion,
                    explanationsEnabled = algorithm.explanationsEnabled == true,
                ),
                explanations = explanations,
            ),
        )
    }

    private fun parseExplanations(
        rawExplanations: List<AuthoritativeRecommendationExplanationEnvelope>?,
    ): List<AuthoritativeRecommendationExplanation>? {
        val values = rawExplanations ?: return null
        return values.map { raw ->
            val candidateId = raw.candidateId?.takeIf { it.isNotBlank() } ?: return null
            val payload = raw.explanation ?: return null
            // The ranking module marks its own payload truthful. Do not expose a
            // locally guessed or server-unverified reason as an explanation.
            if (payload.booleanValue("truthful") != true) return null
            AuthoritativeRecommendationExplanation(candidateId = candidateId, payload = payload)
        }
    }

    private fun fallback(
        reason: AuthoritativeRecommendationFallbackReason,
    ): AuthoritativeRecommendationDecodeResult.Fallback =
        AuthoritativeRecommendationDecodeResult.Fallback(reason)

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.booleanValue(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}

@Serializable
private data class AuthoritativeRecommendationEnvelope(
    val items: List<JsonObject>? = null,
    val page: AuthoritativeRecommendationPageEnvelope? = null,
    val algorithm: AuthoritativeRecommendationAlgorithmEnvelope? = null,
    val explanations: List<AuthoritativeRecommendationExplanationEnvelope>? = null,
)

@Serializable
private data class AuthoritativeRecommendationPageEnvelope(
    val nextCursor: String? = null,
    val hasMore: Boolean? = null,
)

@Serializable
private data class AuthoritativeRecommendationAlgorithmEnvelope(
    val authoritative: Boolean? = null,
    val version: String? = null,
    val explanationsEnabled: Boolean? = null,
)

@Serializable
private data class AuthoritativeRecommendationExplanationEnvelope(
    val candidateId: String? = null,
    val explanation: JsonObject? = null,
)
