package conectate.com.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.data.model.SocializationCapabilities
import conectate.com.data.model.SocializationPreferenceKey
import conectate.com.data.model.SocializationPreferencePatch
import conectate.com.data.model.SocializationPreferencesResponse
import conectate.com.data.model.SocializationCardsResponse
import conectate.com.data.model.UpdateSocializationPreferencesRequest
import conectate.com.data.socialization.SocializationApiClient
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds only the last Socializar preference response confirmed by the backend.
 * Offline mode is read-only: changes are never queued or optimistically treated
 * as consent from this device.
 */
@Singleton
class SocializationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authService: AuthService,
    private val apiClient: SocializationApiClient,
    private val supabaseConfig: SupabaseConfig,
) {
    private val securePrefs by lazy { createSecurePrefsOrNull() }

    suspend fun loadCapabilities(): Result<SocializationCapabilities> = withToken(apiClient::capabilities)

    suspend fun loadPreferences(): Result<SocializationPreferencesResponse> = withToken {
        apiClient.preferences(it).onSuccess(::cacheConfirmed)
    }

    suspend fun updatePreference(
        currentVersion: Long,
        key: SocializationPreferenceKey,
        enabled: Boolean,
    ): Result<SocializationPreferencesResponse> = withToken { token ->
        apiClient.updatePreferences(
            token,
            UpdateSocializationPreferencesRequest(
                expectedVersion = currentVersion,
                preferences = SocializationPreferencePatch.forKey(key, enabled),
            ),
        ).onSuccess(::cacheConfirmed)
    }

    suspend fun loadCards(
        surface: String,
        placement: String = "after_12",
        limit: Int = 4,
    ): Result<SocializationCardsResponse> = withToken { token ->
        apiClient.cards(token, surface, placement, limit)
    }

    fun loadCachedPreferences(): SocializationPreferencesResponse? {
        val userId = authService.getCurrentUserId() ?: return null
        val raw = securePrefs?.getString(cacheKey(userId), null) ?: return null
        return runCatching {
            supabaseConfig.json.decodeFromString<SocializationPreferencesResponse>(raw)
        }.getOrNull()
    }

    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> {
        val token = authService.getAccessTokenAwait()
            ?: return Result.failure(IllegalStateException("Necesitas iniciar sesión para administrar Socializar."))
        return block(token)
    }

    private fun cacheConfirmed(value: SocializationPreferencesResponse) {
        val userId = authService.getCurrentUserId() ?: return
        val encoded = runCatching { supabaseConfig.json.encodeToString(value) }.getOrNull() ?: return
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
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(userId.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
        return "socialization_confirmed_$digest"
    }

    private companion object {
        const val PREFS_NAME = "socialization_confirmed_cache"
    }
}
