package conectate.com.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.data.algorithm.AlgorithmApiClient
import conectate.com.data.algorithm.AlgorithmApiException
import conectate.com.data.model.AlgorithmCapabilities
import conectate.com.data.model.AlgorithmConfig
import conectate.com.data.model.AlgorithmDataPermissions
import conectate.com.data.model.AlgorithmDataPermissionInput
import conectate.com.data.model.AlgorithmFeedMix
import conectate.com.data.model.AlgorithmPreferenceInput
import conectate.com.data.model.AlgorithmPreference
import conectate.com.data.model.AlgorithmProfile
import conectate.com.data.model.CreateAlgorithmProfileRequest
import conectate.com.data.model.CreateTemporaryAlgorithmPreferenceRequest
import conectate.com.data.model.UpdateAlgorithmDataPermissionsRequest
import conectate.com.data.model.UpdateAlgorithmFeedMixRequest
import conectate.com.data.model.UpdateAlgorithmPreferencesRequest
import conectate.com.data.model.UpdateAlgorithmProfileRequest
import conectate.com.data.model.Post
import conectate.com.data.model.Video
import conectate.com.data.recommendations.AuthoritativeRecommendationDecodeResult
import conectate.com.data.recommendations.AuthoritativeRecommendationPage
import conectate.com.data.recommendations.AuthoritativeRecommendationSurface
import conectate.com.data.recommendations.MappedAuthoritativeRecommendationPage
import conectate.com.data.recommendations.OpaqueRecommendationCursor
import conectate.com.data.recommendations.mapSocialPostsExact
import conectate.com.data.recommendations.mapVideosExact
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ExplicitPreferenceChange(
    val accountId: String,
    val profileId: String,
    val appliedProfileRevision: Long,
    val surface: String,
    val dimension: String,
    val subjectKey: String,
    val appliedScore: Int,
    val appliedId: String,
    val appliedUpdatedAt: String?,
    val previous: AlgorithmPreferenceInput?,
)

internal fun canUndoExplicitPreference(
    current: AlgorithmPreference?,
    change: ExplicitPreferenceChange,
): Boolean = current != null && change.appliedId.isNotBlank() &&
    current.id == change.appliedId && current.surface == change.surface &&
    current.dimension == change.dimension &&
    current.subjectKey.equals(change.subjectKey, ignoreCase = true) &&
    current.score == change.appliedScore && current.updatedAt != null &&
    current.updatedAt == change.appliedUpdatedAt

/**
 * Server-authoritative owner of a user's explicit feed settings.
 *
 * Only the last configuration confirmed by the backend is cached locally. This
 * makes settings visible offline without letting an offline device silently
 * become the source of truth or overwrite a newer revision from another device.
 */
@Singleton
class AlgorithmRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authService: AuthService,
    private val apiClient: AlgorithmApiClient,
    private val supabaseConfig: SupabaseConfig,
) {
    private val _cachedConfig = MutableStateFlow<AlgorithmConfig?>(null)
    val cachedConfig: StateFlow<AlgorithmConfig?> = _cachedConfig.asStateFlow()
    private val explicitPreferenceMutex = Mutex()

    private val securePrefs by lazy { createSecurePrefsOrNull() }

    suspend fun loadCapabilities(): Result<AlgorithmCapabilities> = withToken { token ->
        apiClient.capabilities(token)
    }

    suspend fun loadConfig(profileId: String? = null): Result<AlgorithmConfig> = withToken { token ->
        apiClient.config(token, profileId).onSuccess(::cacheConfirmed)
    }

    suspend fun loadProfiles(): Result<List<AlgorithmProfile>> = withToken { token ->
        apiClient.profiles(token)
    }

    suspend fun loadSocialRecommendationPage(
        cursor: OpaqueRecommendationCursor? = null,
        limit: Int = 60,
        refreshSeed: String? = null,
    ): Result<MappedAuthoritativeRecommendationPage<Post>> = loadRecommendationPage(
        surface = AuthoritativeRecommendationSurface.SOCIAL,
        cursor = cursor,
        limit = limit,
        refreshSeed = refreshSeed,
        mapper = AuthoritativeRecommendationPage::mapSocialPostsExact,
    )

    suspend fun loadVideoRecommendationPage(
        cursor: OpaqueRecommendationCursor? = null,
        limit: Int = 20,
        refreshSeed: String? = null,
    ): Result<MappedAuthoritativeRecommendationPage<Video>> = loadRecommendationPage(
        surface = AuthoritativeRecommendationSurface.VIDEO,
        cursor = cursor,
        limit = limit,
        refreshSeed = refreshSeed,
        mapper = AuthoritativeRecommendationPage::mapVideosExact,
    )

    fun loadCachedConfig(): AlgorithmConfig? {
        val userId = authService.getCurrentUserId() ?: return null
        val stored = securePrefs?.getString(cacheKey(userId), null) ?: return null
        return runCatching { supabaseConfig.json.decodeFromString<AlgorithmConfig>(stored) }
            .getOrNull()
            ?.also { _cachedConfig.value = it }
    }

    fun clearCachedConfig() {
        val currentUser = authService.getCurrentUserId()
        if (!currentUser.isNullOrBlank()) {
            securePrefs?.edit()?.remove(cacheKey(currentUser))?.apply()
        }
        _cachedConfig.value = null
    }

    suspend fun createProfile(request: CreateAlgorithmProfileRequest): Result<AlgorithmConfig> = withToken { token ->
        apiClient.createProfile(token, request).flatMapConfig(token) { it.id }
    }

    suspend fun updateProfile(
        profileId: String,
        expectedVersion: Long,
        name: String? = null,
        mode: String? = null,
        isEnabled: Boolean? = null,
        makeDefault: Boolean? = null,
    ): Result<AlgorithmConfig> = withToken { token ->
        apiClient.updateProfile(
            token,
            profileId,
            UpdateAlgorithmProfileRequest(expectedVersion, name, mode, isEnabled, makeDefault),
        ).flatMapConfig(token) { profileId }
    }

    suspend fun replacePreferences(
        profileId: String,
        expectedVersion: Long,
        preferences: List<AlgorithmPreferenceInput>,
    ): Result<AlgorithmConfig> = withToken { token ->
        apiClient.replacePreferences(
            token,
            profileId,
            UpdateAlgorithmPreferencesRequest(expectedVersion, preferences),
        ).flatMapConfig(token) { profileId }
    }

    suspend fun deletePreference(
        profileId: String,
        preferenceId: String,
        expectedVersion: Long,
    ): Result<AlgorithmConfig> = withToken { token ->
        apiClient.deletePreference(token, profileId, preferenceId, expectedVersion)
            .flatMapConfig(token) { profileId }
    }

    suspend fun updateFeedMix(
        profileId: String,
        expectedVersion: Long,
        mix: AlgorithmFeedMix,
    ): Result<AlgorithmConfig> {
        if (!mix.isValid()) return Result.failure(IllegalArgumentException("La composición debe sumar 100%."))
        return withToken { token ->
            apiClient.updateFeedMix(
                token,
                profileId,
                mix.surface,
                UpdateAlgorithmFeedMixRequest(
                    expectedVersion = expectedVersion,
                    following = mix.following,
                    interests = mix.interests,
                    communities = mix.communities,
                    newContent = mix.newContent,
                    trends = mix.trends,
                    exploration = mix.exploration,
                ),
            ).flatMapConfig(token) { profileId }
        }
    }

    suspend fun updateDataPermissions(
        profileId: String,
        expectedVersion: Long,
        permissions: AlgorithmDataPermissions,
    ): Result<AlgorithmConfig> = withToken { token ->
        apiClient.updateDataPermissions(
            token,
            profileId,
            UpdateAlgorithmDataPermissionsRequest(
                expectedVersion,
                AlgorithmDataPermissionInput(
                    likes = permissions.likes,
                    follows = permissions.follows,
                    searches = permissions.searches,
                    watchTime = permissions.watchTime,
                    scrollVelocity = permissions.scrollVelocity,
                    profileVisits = permissions.profileVisits,
                    publicInteractions = permissions.publicInteractions,
                    communityActivity = permissions.communityActivity,
                    liveActivity = permissions.liveActivity,
                ),
            ),
        ).flatMapConfig(token) { profileId }
    }

    suspend fun addTemporaryPreference(
        profileId: String,
        request: CreateTemporaryAlgorithmPreferenceRequest,
    ): Result<AlgorithmConfig> = withToken { token ->
        apiClient.createTemporaryPreference(token, profileId, request).flatMapConfig(token) { profileId }
    }

    suspend fun deleteTemporaryPreference(
        profileId: String,
        preferenceId: String,
        expectedVersion: Long,
    ): Result<AlgorithmConfig> = withToken { token ->
        apiClient.deleteTemporaryPreference(token, profileId, preferenceId, expectedVersion)
            .flatMapConfig(token) { profileId }
    }

    /**
     * Applies an explicit feed instruction from a content overflow menu.
     *
     * The write is serialized per process, revision checked by the backend and
     * retried once after a genuine cross-device conflict. The selected profile
     * is enabled before the rule is stored so "No me interesa" affects the next
     * server page instead of becoming a decorative, inactive preference.
     */
    suspend fun applyExplicitPreference(
        surface: String,
        dimension: String,
        subjectKey: String,
        subjectLabel: String?,
        score: Int,
    ): Result<ExplicitPreferenceChange> = explicitPreferenceMutex.withLock {
        val normalizedSurface = surface.trim().lowercase()
        val normalizedDimension = dimension.trim().lowercase()
        val normalizedKey = subjectKey.trim().lowercase()
        if (normalizedSurface !in setOf("video", "social") ||
            normalizedDimension !in setOf("content", "topic", "subtopic", "creator", "format") ||
            normalizedKey.isBlank() || normalizedKey.length > 160 || score !in -100..100
        ) {
            return@withLock Result.failure(IllegalArgumentException("La preferencia no es válida."))
        }
        val requestAccountId = authService.getCurrentUserId()
            ?: return@withLock Result.failure(IllegalStateException("Necesitas iniciar sesión."))
        withToken { token ->
            suspend fun persist(): Result<ExplicitPreferenceChange> {
                val capabilities = apiClient.capabilities(token).getOrElse { return Result.failure(it) }
                if (!capabilities.enabled || !capabilities.preferencesEnabled || !capabilities.classificationEnabled) {
                    return Result.failure(
                        AuthoritativeRecommendationsUnavailableException("USER_ALGORITHM_DISABLED"),
                    )
                }
                var config = apiClient.config(token).getOrElse { return Result.failure(it) }
                if (!config.profile.isEnabled) {
                    val enabledProfile = apiClient.updateProfile(
                        token,
                        config.profile.id,
                        UpdateAlgorithmProfileRequest(
                            expectedVersion = config.profile.revision,
                            isEnabled = true,
                        ),
                    ).getOrElse { return Result.failure(it) }
                    config = apiClient.config(token, enabledProfile.id).getOrElse { return Result.failure(it) }
                }
                val input = AlgorithmPreferenceInput(
                    surface = normalizedSurface,
                    dimension = normalizedDimension,
                    subjectKey = normalizedKey,
                    subjectLabel = subjectLabel?.trim()?.take(120)?.takeIf(String::isNotBlank),
                    score = score,
                )
                val previous = config.preferences.firstOrNull {
                    it.surface == input.surface && it.dimension == input.dimension &&
                        it.subjectKey.equals(input.subjectKey, ignoreCase = true)
                }?.let {
                    AlgorithmPreferenceInput(it.surface, it.dimension, it.subjectKey, it.subjectLabel, it.score)
                }
                val merged = config.preferences
                    .filterNot {
                        it.surface == input.surface &&
                            it.dimension == input.dimension &&
                            it.subjectKey.equals(input.subjectKey, ignoreCase = true)
                    }
                    .map {
                        AlgorithmPreferenceInput(
                            surface = it.surface,
                            dimension = it.dimension,
                            subjectKey = it.subjectKey,
                            subjectLabel = it.subjectLabel,
                            score = it.score,
                        )
                    } + input
                return apiClient.replacePreferences(
                    token,
                    config.profile.id,
                    UpdateAlgorithmPreferencesRequest(config.profile.revision, merged),
                ).flatMapConfig(token) { config.profile.id }.map { saved ->
                    val applied = saved.preferences.firstOrNull {
                        it.surface == input.surface && it.dimension == input.dimension &&
                            it.subjectKey.equals(input.subjectKey, ignoreCase = true)
                    }
                    ExplicitPreferenceChange(
                        accountId = requestAccountId,
                        profileId = saved.profile.id,
                        appliedProfileRevision = saved.profile.revision,
                        surface = input.surface,
                        dimension = input.dimension,
                        subjectKey = input.subjectKey,
                        appliedScore = input.score,
                        appliedId = applied?.id.orEmpty(),
                        appliedUpdatedAt = applied?.updatedAt,
                        previous = previous,
                    )
                }
            }

            val first = persist()
            val conflict = (first.exceptionOrNull() as? AlgorithmApiException)?.code == "ALGORITHM_PROFILE_CONFLICT"
            if (conflict) persist() else first
        }
    }

    suspend fun undoExplicitPreference(change: ExplicitPreferenceChange): Result<AlgorithmConfig> =
        explicitPreferenceMutex.withLock {
            if (change.accountId.isBlank() || authService.getCurrentUserId() != change.accountId ||
                change.appliedId.isBlank()) {
                return@withLock Result.failure(IllegalStateException("La cuenta o la preferencia cambió."))
            }
            withToken { token ->
                val current = apiClient.config(token, change.profileId)
                    .getOrElse { return@withToken Result.failure(it) }
                if (current.profile.revision != change.appliedProfileRevision) {
                    return@withToken Result.failure(
                        IllegalStateException("Mi algoritmo cambió en otra sesión."),
                    )
                }
                val applied = current.preferences.firstOrNull { it.id == change.appliedId }
                if (!canUndoExplicitPreference(applied, change)) {
                    return@withToken Result.failure(
                        IllegalStateException("La preferencia ya cambió en otra sesión."),
                    )
                }
                val currentApplied = checkNotNull(applied)
                val previous = change.previous
                if (previous == null) {
                    apiClient.deletePreference(token, current.profile.id, currentApplied.id,
                        current.profile.revision).flatMapConfig(token) { current.profile.id }
                } else {
                    apiClient.replacePreferences(token, current.profile.id,
                        UpdateAlgorithmPreferencesRequest(current.profile.revision, listOf(previous)))
                        .flatMapConfig(token) { current.profile.id }
                }
            }
        }

    private suspend fun <T> loadRecommendationPage(
        surface: AuthoritativeRecommendationSurface,
        cursor: OpaqueRecommendationCursor?,
        limit: Int,
        refreshSeed: String? = null,
        mapper: (AuthoritativeRecommendationPage) -> Result<MappedAuthoritativeRecommendationPage<T>>,
    ): Result<MappedAuthoritativeRecommendationPage<T>> = withToken { token ->
        // The recommendations endpoint is authoritative for capability and
        // profile checks. Calling capabilities + config before every first
        // page added two network round trips to the hottest app path.
        when (
            val decoded = apiClient.recommendations(
                accessToken = token,
                surface = surface,
                profileId = null,
                cursor = cursor,
                limit = limit,
                refreshSeed = refreshSeed,
            ).getOrElse { error -> return@withToken Result.failure(error) }
        ) {
            is AuthoritativeRecommendationDecodeResult.Authoritative -> mapper(decoded.page)
            is AuthoritativeRecommendationDecodeResult.Fallback -> Result.failure(
                AuthoritativeRecommendationsUnavailableException(decoded.reason.name),
            )
        }
    }

    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> {
        val token = authService.getAccessTokenAwait()
            ?: return Result.failure(IllegalStateException("Necesitas iniciar sesión para administrar Mi algoritmo."))
        return block(token)
    }

    private suspend fun <T> Result<T>.flatMapConfig(
        token: String,
        profileId: (T) -> String,
    ): Result<AlgorithmConfig> = fold(
        onSuccess = { apiClient.config(token, profileId(it)).onSuccess(::cacheConfirmed) },
        onFailure = { Result.failure(it) },
    )

    private fun cacheConfirmed(config: AlgorithmConfig) {
        _cachedConfig.value = config
        val userId = authService.getCurrentUserId() ?: return
        val encoded = runCatching { supabaseConfig.json.encodeToString(config) }.getOrNull() ?: return
        securePrefs?.edit()?.putString(cacheKey(userId), encoded)?.apply()
    }

    private fun createSecurePrefsOrNull() = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private fun cacheKey(userId: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(userId.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "confirmed_config_$hash"
    }

    private companion object {
        const val PREFS_NAME = "algorithm_confirmed_cache"
    }
}

class AuthoritativeRecommendationsUnavailableException(
    val reasonCode: String,
) : IllegalStateException(reasonCode)
