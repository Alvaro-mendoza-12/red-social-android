package conectate.com.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.data.algorithm.AlgorithmApiClient
import conectate.com.data.model.AlgorithmCapabilities
import conectate.com.data.model.AlgorithmConfig
import conectate.com.data.model.AlgorithmDataPermissions
import conectate.com.data.model.AlgorithmDataPermissionInput
import conectate.com.data.model.AlgorithmFeedMix
import conectate.com.data.model.AlgorithmPreferenceInput
import conectate.com.data.model.AlgorithmProfile
import conectate.com.data.model.CreateAlgorithmProfileRequest
import conectate.com.data.model.CreateTemporaryAlgorithmPreferenceRequest
import conectate.com.data.model.UpdateAlgorithmDataPermissionsRequest
import conectate.com.data.model.UpdateAlgorithmFeedMixRequest
import conectate.com.data.model.UpdateAlgorithmPreferencesRequest
import conectate.com.data.model.UpdateAlgorithmProfileRequest
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-authoritative owner of a user's explicit feed settings.
 *
 * Only the last configuration confirmed by the backend is cached locally. This
 * makes settings visible offline without letting an offline device silently
 * become the source of truth or overwrite a newer revision from another device.
 */
@Singleton
class AlgorithmRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authService: AuthService,
    private val apiClient: AlgorithmApiClient,
    private val supabaseConfig: SupabaseConfig,
) {
    private val _cachedConfig = MutableStateFlow<AlgorithmConfig?>(null)
    val cachedConfig: StateFlow<AlgorithmConfig?> = _cachedConfig.asStateFlow()

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

    fun loadCachedConfig(): AlgorithmConfig? {
        val userId = authService.getCurrentUserId() ?: return null
        val stored = securePrefs?.getString(cacheKey(userId), null) ?: return null
        return runCatching { supabaseConfig.json.decodeFromString<AlgorithmConfig>(stored) }
            .getOrNull()
            ?.also { _cachedConfig.value = it }
    }

    fun clearCachedConfig() {
        authService.getCurrentUserId()?.let { userId -> securePrefs?.edit()?.remove(cacheKey(userId))?.apply() }
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
