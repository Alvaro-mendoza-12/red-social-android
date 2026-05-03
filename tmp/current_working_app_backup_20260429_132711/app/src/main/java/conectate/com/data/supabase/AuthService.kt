package conectate.com.data.supabase

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.core.testing.RuntimeSessionOverride
import conectate.com.core.testing.TestRuntimeOverrides
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import conectate.com.data.local.AppDatabase
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.user.UserSession
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.SessionStatus

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class SignUpMetadata(
    val full_name: String,
    val display_name: String? = null,
    val country: String,
    val avatar_url: String? = null,
    val privacy_policy_accepted: Boolean = false,
    val privacy_policy_accepted_at: String? = null
)

@Serializable
data class AuthRequest(
    val email: String,
    val password: String,
    val data: SignUpMetadata? = null
)


@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    val user: UserData
)

@Serializable
data class UserData(
    val id: String,
    val email: String,
    @SerialName("user_metadata") val metadata: kotlinx.serialization.json.JsonElement? = null
)

data class CurrentUserProfileSeed(
    val id: String,
    val email: String,
    val fullName: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val country: String? = null,
    val privacyPolicyAccepted: Boolean = false,
    val privacyPolicyAcceptedAt: String? = null
)

@Singleton
class AuthService @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val database: AppDatabase,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private data class SecurePrefsState(
        val prefs: SharedPreferences?,
        val initializationError: Throwable?
    )

    companion object {
        private const val AUTH_CACHE_PREFS = "auth_cache"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_FORCED_LOGOUT = "forced_logout"
    }

    // Using SharedPreferences just to track "if logged in" for simple checks if needed,
    // but primarily trusting SDK state.
    private val _currentSession = MutableStateFlow<AuthResponse?>(null)
    val currentSession: StateFlow<AuthResponse?> = _currentSession.asStateFlow()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()
    private val securePrefsState by lazy { createSecureAuthPrefsState() }
    private val authPrefsOrNull: SharedPreferences?
        get() = securePrefsState.prefs

    private val authScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e("AuthService", "Uncaught auth coroutine exception", throwable)
        }
    )

    fun ensureSecureStorageAvailable(): Result<Unit> {
        var prefs = authPrefsOrNull
        return if (prefs != null) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "El almacenamiento seguro del dispositivo no está disponible. No es seguro iniciar sesión ni guardar sesiones en este dispositivo.",
                    securePrefsState.initializationError
                )
            )
        }
    }

    private fun isForcedLogout(): Boolean = authPrefsOrNull?.getBoolean(KEY_FORCED_LOGOUT, false) == true

    private fun createSecureAuthPrefsState(): SecurePrefsState = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            AUTH_CACHE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.fold(
        onSuccess = { SecurePrefsState(prefs = it, initializationError = null) },
        onFailure = { error ->
            Log.e("AuthService", "Secure auth storage unavailable. Session persistence will be blocked.", error)
            SecurePrefsState(prefs = null, initializationError = error)
        }
    )

    private fun setForcedLogout(enabled: Boolean) {
        authPrefsOrNull?.edit()?.putBoolean(KEY_FORCED_LOGOUT, enabled)?.apply()
    }

    /**
     * Immediate local logout gate.
     * Prevents the app from treating an old SDK session as logged-in (even if signOut() fails).
     */
    fun forceLocalLogout() {
        if (TestRuntimeOverrides.isEnabled(context)) {
            TestRuntimeOverrides.clearSession(context)
        }
        authPrefsOrNull?.edit()
            ?.putBoolean(KEY_FORCED_LOGOUT, true)
            ?.remove(KEY_ACCESS_TOKEN)
            ?.remove(KEY_REFRESH_TOKEN)
            ?.apply()
        _currentSession.value = null
    }

    private fun extractJwtClaim(accessToken: String, claim: String): String? {
        if (accessToken.isBlank()) return null
        val parts = accessToken.split(".")
        if (parts.size < 2) return null
        return runCatching {
            val payloadRaw = parts[1]
            var normalized = payloadRaw
                .replace('-', '+')
                .replace('_', '/')
                .let { token ->
                    when (token.length % 4) {
                        2 -> "$token=="
                        3 -> "$token="
                        else -> token
                    }
                }
            val decoded = String(Base64.decode(normalized, Base64.DEFAULT))
            json.parseToJsonElement(decoded)
                .jsonObject[claim]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun resolveSessionUserId(session: UserSession?): String? {
        var fromSession = session?.user?.id?.trim()?.lowercase()
        if (!fromSession.isNullOrBlank()) return fromSession
        var fromSdk = supabaseConfig.client.auth.currentUserOrNull()?.id?.trim()?.lowercase()
        if (!fromSdk.isNullOrBlank()) return fromSdk
        return extractJwtClaim(session?.accessToken.orEmpty(), "sub")?.lowercase()
    }

    private fun resolveSessionEmail(session: UserSession?): String {
        var fromSession = session?.user?.email?.trim()?.lowercase()
        if (!fromSession.isNullOrBlank()) return fromSession
        var fromSdk = supabaseConfig.client.auth.currentUserOrNull()?.email?.trim()?.lowercase()
        if (!fromSdk.isNullOrBlank()) return fromSdk
        return extractJwtClaim(session?.accessToken.orEmpty(), "email")?.lowercase().orEmpty()
    }

    private fun isUsableSession(session: UserSession?): Boolean {
        var accessToken = session?.accessToken?.trim().orEmpty()
        val sessionUserId = resolveSessionUserId(session).orEmpty()
        return sessionUserId.isNotBlank() && accessToken.isNotBlank()
    }

    private fun isTokenExpiredOrExpiringSoon(
        accessToken: String,
        withinSeconds: Long = 120L
    ): Boolean {
        var expiration = extractJwtClaim(accessToken.trim(), "exp")?.toLongOrNull() ?: return false
        val now = System.currentTimeMillis() / 1000
        return expiration <= now + withinSeconds
    }

    private fun mapSession(session: UserSession): AuthResponse {
        val resolvedUserId = resolveSessionUserId(session).orEmpty()
        return AuthResponse(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            tokenType = session.tokenType,
            expiresIn = session.expiresIn.toInt(),
            user = UserData(
                id = resolvedUserId,
                email = resolveSessionEmail(session)
            )
        )
    }

    init {
        val runtimeSessionOverride = TestRuntimeOverrides.currentSession(context)
        if (runtimeSessionOverride != null) {
            _currentSession.value = runtimeSessionOverride.toAuthResponse()
            _isInitializing.value = false
            Log.d("AuthService", "Auth runtime override enabled for instrumentation tests")
        } else if (isForcedLogout()) {
            // User explicitly logged out: do not auto-recover SDK sessions.
            _currentSession.value = null
            clearPersistedSessionSnapshot()
            _isInitializing.value = false
            Log.w("AuthService", "Forced logout flag set: skipping session recovery")
        } else {
            // IMPORTANT: avoid blocking the app startup thread. Do session recovery on IO scope.
            authScope.launch {
                try {
                    // Fast-path recovery from SDK cache first.
                    var bootSession = runCatching { supabaseConfig.client.auth.currentSessionOrNull() }.getOrNull()
                    if (!isUsableSession(bootSession)) {
                        bootSession = null
                    }

                    // Fallback recovery from lightweight local cache (covers slow/offline edge cases).
                    if (bootSession == null) {
                        bootSession = recoverPersistedSessionFromSnapshot()
                    }

                    bootSession?.let { cachedSession ->
                        _currentSession.value = mapSession(cachedSession)
                        persistSessionSnapshot(cachedSession)
                        _isInitializing.value = false
                        Log.d("AuthService", "Cached session recovered at startup")

                        // PROACTIVE REFRESH: If session is old, trigger background refresh immediately
                        launch {
                            try {
                                var token = cachedSession.accessToken
                                val exp = extractJwtClaim(token, "exp")?.toLongOrNull() ?: 0L
                                if (exp < (System.currentTimeMillis() / 1000) + 600) { // If less than 10 mins left
                                    Log.i("AuthService", "Proactive background refresh triggered for recovered session")
                                    supabaseConfig.client.auth.refreshCurrentSession()
                                }
                            } catch (e: Exception) {
                                Log.e("AuthService", "Proactive refresh failed", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AuthService", "Startup session recovery failed", e)
                }
            }
        }

        // Observe SDK session changes with a defensive timeout for slow networks/emulators
        authScope.launch {
            try {
                supabaseConfig.client.auth.sessionStatus.collect { status ->
                    TestRuntimeOverrides.currentSession(context)?.let { override ->
                        _currentSession.value = override.toAuthResponse()
                        _isInitializing.value = false
                        return@collect
                    }
                    if (isForcedLogout()) {
                        _currentSession.value = null
                        clearPersistedSessionSnapshot()
                        _isInitializing.value = false
                        return@collect
                    }
                    if (status is SessionStatus.Authenticated) {
                        if (isUsableSession(status.session)) {
                            _currentSession.value = mapSession(status.session)
                            persistSessionSnapshot(status.session)
                            Log.d("AuthService", "SDK Session Active: ${status.session.user?.email}")
                        } else {
                            _currentSession.value = null
                            clearPersistedSessionSnapshot()
                            Log.w("AuthService", "Session Authenticated event ignored due to missing user context")
                        }
                    } else {
                        // Defensive fallback: avoid dropping session on transient auth status changes.
                        val cachedSession = runCatching { supabaseConfig.client.auth.currentSessionOrNull() }.getOrNull()
                        if (isUsableSession(cachedSession)) {
                            var safeSession = cachedSession ?: return@collect
                            _currentSession.value = mapSession(safeSession)
                            persistSessionSnapshot(safeSession)
                            Log.d("AuthService", "Session status changed but cached session is still valid")
                        } else {
                            var recovered = recoverPersistedSessionFromSnapshot()
                            if (isUsableSession(recovered)) {
                                val safeRecovered = recovered ?: return@collect
                                _currentSession.value = mapSession(safeRecovered)
                                persistSessionSnapshot(safeRecovered)
                                Log.d("AuthService", "Session recovered from persisted snapshot")
                            } else {
                                _currentSession.value = null
                                clearPersistedSessionSnapshot()
                                Log.d("AuthService", "SDK Session Cleared/Not Authenticated")
                            }
                        }
                    }
                    // Signal initialization complete after first status check.
                    _isInitializing.value = false
                }
            } catch (e: Exception) {
                // Never crash the app due to an auth observer failure.
                Log.e("AuthService", "sessionStatus collector crashed", e)
                _currentSession.value = null
                clearPersistedSessionSnapshot()
                _isInitializing.value = false
            }
        }

        // TIMEOUT SAFEGUARD: keep as a final fallback only.
        // If Supabase client takes too long to emit first status (e.g. network hang), force finish init
        authScope.launch {
            kotlinx.coroutines.delay(20000) // allow slower mobile-data startup before forcing login route
            if (_isInitializing.value) {
                Log.w("AuthService", "WARNING: Auth initialization timed out. Forcing completion.")
                _isInitializing.value = false
            }
        }
    }

    /* -------------------- AUTH -------------------- */

    suspend fun signUp(
        email: String,
        password: String,
        metadata: SignUpMetadata
    ): Result<AuthResponse> {
        return try {
            ensureSecureStorageAvailable().getOrThrow()
            supabaseConfig.client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                data = kotlinx.serialization.json.buildJsonObject {
                    put("full_name", kotlinx.serialization.json.JsonPrimitive(metadata.full_name))
                    metadata.display_name?.takeIf { it.isNotBlank() }?.let {
                        put("display_name", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    put("country", kotlinx.serialization.json.JsonPrimitive(metadata.country))
                    metadata.avatar_url?.takeIf { it.isNotBlank() }?.let {
                        put("avatar_url", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    if (metadata.privacy_policy_accepted) {
                        put("privacy_policy_accepted", kotlinx.serialization.json.JsonPrimitive(true))
                    }
                    metadata.privacy_policy_accepted_at?.takeIf { it.isNotBlank() }?.let {
                        put("privacy_policy_accepted_at", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                }
            }

            var session = awaitUsableSession()

            if (!isUsableSession(session)) {
                runCatching {
                    supabaseConfig.client.auth.signInWith(Email) {
                        this.email = email
                        this.password = password
                    }
                }.onFailure {
                    Log.w("AuthService", "Post-signup sign-in bootstrap failed", it)
                }

                session = awaitUsableSession()
            }

            var safeSession = session
                ?: return Result.failure(Exception("Registro completado pero no se pudo recuperar la sesión"))
            if (!isUsableSession(safeSession)) {
                return Result.failure(Exception("Registro completado pero la sesión aún no está lista"))
            }

            setForcedLogout(false)
            _currentSession.value = mapSession(safeSession)
            persistSessionSnapshot(safeSession)

            Result.success(
                AuthResponse(
                    accessToken = safeSession.accessToken,
                    refreshToken = safeSession.refreshToken,
                    tokenType = safeSession.tokenType,
                    expiresIn = safeSession.expiresIn.toInt(),
                    user = UserData(
                        id = resolveSessionUserId(safeSession).orEmpty(),
                        email = resolveSessionEmail(safeSession)
                    )
                )
            )
        } catch (e: Exception) {
            Log.e("AuthService", "Signup error", e)
            Result.failure(e)
        }
    }

@Composable
@Composable
    suspend fun signIn(email: String, password: String): Result<Unit> {
        return try {
            ensureSecureStorageAvailable().getOrThrow()
            supabaseConfig.client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }

            // Force eager session refresh to avoid blank screen after login until process restart.
            var session = awaitUsableSession(maxAttempts = 10)
            if (isUsableSession(session)) {
                var safeSession = session ?: return Result.success(Unit)
                _currentSession.value = mapSession(safeSession)
                persistSessionSnapshot(safeSession)
            } else {
                // Last-resort read from current user so MainActivity can route correctly.
                val uid = supabaseConfig.client.auth.currentUserOrNull()?.id?.trim()?.lowercase()
                    ?: extractJwtClaim(getAccessToken().orEmpty(), "sub")?.lowercase()
                var mail = supabaseConfig.client.auth.currentUserOrNull()?.email?.trim()?.lowercase()
                    ?: extractJwtClaim(getAccessToken().orEmpty(), "email")?.lowercase()
                if (!uid.isNullOrBlank()) {
                    _currentSession.value = AuthResponse(
                        accessToken = getAccessToken().orEmpty(),
                        refreshToken = getRefreshToken().orEmpty(),
                        tokenType = "Bearer",
                        expiresIn = 3600,
                        user = UserData(id = uid, email = mail.orEmpty())
                    )
                }
            }
            setForcedLogout(false)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthService", "Login error", e)
            Result.failure(e)
        }
    }

@Composable
@Composable
    suspend fun signOut() {
        // Gate logout immediately so navigation/login flow doesn't bounce back due to stale SDK session.
        forceLocalLogout()
        withContext(Dispatchers.IO) {
            try {
                // Nuclear Clean: Wipe local database for privacy between accounts
                Log.w("AuthService", "NUCLEAR CLEAR: Wiping all local data for privacy...")
                database.clearAllTables()
                
                supabaseConfig.client.auth.signOut()
                Log.d("AuthService", "SignOut successful and local data purged")
            } catch (e: Exception) {
                Log.e("AuthService", "SignOut error", e)
            } finally {
                _currentSession.value = null
                clearPersistedSessionSnapshot()
            }
        }
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        return try {
            supabaseConfig.client.auth.resetPasswordForEmail(
                email = email,
                redirectUrl = "conectate://recovery"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthService", "Reset password error", e)
            Result.failure(e)
        }
    }

    suspend fun updateUserPassword(newPassword: String): Result<Unit> {
        return try {
            Log.d("AuthService", "Attempting to update password...")
            supabaseConfig.client.auth.modifyUser {
                password = newPassword
            }
            Log.d("AuthService", "Password updated successfully in Supabase")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthService", "Update password error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun updateUserMetadata(
        fullName: String?,
        avatarUrl: String?,
        displayName: String? = fullName,
        country: String? = null,
        privacyPolicyAccepted: Boolean? = null,
        privacyPolicyAcceptedAt: String? = null
    ): Result<Unit> {
        return try {
            Log.d(
                "AuthService",
                "Updating user metadata: fullName=$fullName, displayName=$displayName, country=$country, avatar=$avatarUrl, privacyAccepted=$privacyPolicyAccepted"
            )
            supabaseConfig.client.auth.modifyUser {
                data = kotlinx.serialization.json.buildJsonObject {
                    fullName?.let { put("full_name", kotlinx.serialization.json.JsonPrimitive(it)) }
                    displayName?.let { put("display_name", kotlinx.serialization.json.JsonPrimitive(it)) }
                    country?.let { put("country", kotlinx.serialization.json.JsonPrimitive(it)) }
                    avatarUrl?.let { put("avatar_url", kotlinx.serialization.json.JsonPrimitive(it)) }
                    privacyPolicyAccepted?.let {
                        put("privacy_policy_accepted", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    privacyPolicyAcceptedAt?.takeIf { it.isNotBlank() }?.let {
                        put("privacy_policy_accepted_at", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                }
            }
            Log.d("AuthService", "User metadata updated successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthService", "Update metadata error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getCurrentUserProfileSeed(): CurrentUserProfileSeed? {
        TestRuntimeOverrides.currentSession(context)?.let { session ->
            return CurrentUserProfileSeed(
                id = session.userId.trim().lowercase(),
                email = session.email.trim().lowercase(),
                fullName = session.displayName,
                displayName = session.displayName,
                avatarUrl = session.avatarUrl,
                privacyPolicyAccepted = true
            )
        }
        if (isForcedLogout()) return null

        val userInfo = runCatching {
            supabaseConfig.client.auth.currentUserOrNull()
                ?: supabaseConfig.client.auth.retrieveUserForCurrentSession(updateSession = false)
        }.getOrNull()

        var userId = userInfo?.id?.trim()?.lowercase() ?: getCurrentUserId()
        var email = userInfo?.email?.trim()?.lowercase() ?: getCurrentUserEmail()
        if (userId.isNullOrBlank() || email.isNullOrBlank()) return null

        val metadata: JsonObject? = userInfo?.userMetadata
        fun metadataValue(key: String): String? {
            return metadata?.get(key)
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        }
        fun metadataBoolean(key: String): Boolean? {
            return metadata?.get(key)
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.lowercase()
                ?.let { raw ->
                    when (raw) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                }
        }

        return CurrentUserProfileSeed(
            id = userId,
            email = email,
            fullName = metadataValue("full_name"),
            displayName = metadataValue("display_name"),
            avatarUrl = metadataValue("avatar_url"),
            country = metadataValue("country"),
            privacyPolicyAccepted = metadataBoolean("privacy_policy_accepted") == true,
            privacyPolicyAcceptedAt = metadataValue("privacy_policy_accepted_at")
        )
    }


    suspend fun importSession(accessToken: String, refreshToken: String) {
        if (TestRuntimeOverrides.isEnabled(context)) {
            val matchedAccount = TestRuntimeOverrides.storedAccounts(context)
                ?.firstOrNull {
                    it.access_token == accessToken && it.refresh_token == refreshToken
                }
            val runtimeSession = matchedAccount?.let { TestRuntimeOverrides.activateStoredAccount(context, it) }
                ?: RuntimeSessionOverride(
                    userId = getCurrentUserId() ?: "test-user",
                    email = getCurrentUserEmail() ?: "test@example.com",
                    accessToken = accessToken,
                    refreshToken = refreshToken
                ).also { TestRuntimeOverrides.setCurrentSession(context, it) }
            _currentSession.value = runtimeSession.toAuthResponse()
            setForcedLogout(false)
            return
        }
        try {
            ensureSecureStorageAvailable().getOrThrow()
            Log.d("AuthService", "Importing session manually...")
            val importedSession = UserSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = 3600, // Default 1 hour validity if unknown
                tokenType = "Bearer",
                user = null,
                type = "" // Empty string for type if required, or omit if default exists
            )

            supabaseConfig.client.auth.importSession(importedSession)

            var activeSession = awaitUsableSession(maxAttempts = 8, intervalMs = 120)
            if (
                activeSession == null ||
                isTokenExpiredOrExpiringSoon(activeSession.accessToken) ||
                resolveSessionUserId(activeSession).isNullOrBlank()
            ) {
                Log.i("AuthService", "Imported session needs refresh before being used")
                supabaseConfig.client.auth.refreshCurrentSession()
                activeSession = awaitUsableSession(maxAttempts = 12, intervalMs = 150)
                    ?: runCatching { supabaseConfig.client.auth.currentSessionOrNull() }.getOrNull()
            }

            var resolvedSession = activeSession
                ?: throw IllegalStateException("No se pudo restaurar la sesión guardada")

            if (!isUsableSession(resolvedSession) || isTokenExpiredOrExpiringSoon(resolvedSession.accessToken, withinSeconds = 30)) {
                throw IllegalStateException("La sesión guardada expiró. Vuelve a iniciar sesión con esa cuenta.")
            }

            _currentSession.value = mapSession(resolvedSession)
            persistSessionSnapshot(resolvedSession)
            setForcedLogout(false)
            Log.d("AuthService", "Session imported successfully")
        } catch (e: Exception) {
            _currentSession.value = null
            clearPersistedSessionSnapshot()
            Log.e("AuthService", "Error importing session", e)
            if (looksLikeInvalidStoredRefreshToken(e.message)) {
                throw IllegalStateException("La sesión guardada expiró. Vuelve a iniciar sesión con esa cuenta.")
            }
            throw e
        }
    }

    private fun looksLikeInvalidStoredRefreshToken(message: String?): Boolean {
        var normalized = message?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        return normalized.contains("invalid refresh token") ||
            normalized.contains("refresh token not found")
    }

    /* -------------------- HELPERS -------------------- */

    fun getCurrentUserId(): String? {
        TestRuntimeOverrides.currentSession(context)?.let { return it.userId.trim().lowercase() }
        if (isForcedLogout()) return null
        val sdkUserId = supabaseConfig.client.auth.currentUserOrNull()?.id?.trim()?.lowercase()
        if (!sdkUserId.isNullOrBlank()) return sdkUserId
        val cachedUserId = _currentSession.value?.user?.id?.trim()?.lowercase()
        if (!cachedUserId.isNullOrBlank()) return cachedUserId
        val liveTokenUserId = extractJwtClaim(getAccessToken().orEmpty(), "sub")?.lowercase()
        if (!liveTokenUserId.isNullOrBlank()) return liveTokenUserId
        return extractJwtClaim(authPrefsOrNull?.getString(KEY_ACCESS_TOKEN, null).orEmpty(), "sub")?.lowercase()
    }

    fun getCurrentUserEmail(): String? {
        TestRuntimeOverrides.currentSession(context)?.let { return it.email.trim().lowercase() }
        if (isForcedLogout()) return null
        val sdkEmail = supabaseConfig.client.auth.currentUserOrNull()?.email?.trim()?.lowercase()
        if (!sdkEmail.isNullOrBlank()) return sdkEmail
        val cachedEmail = _currentSession.value?.user?.email?.trim()?.lowercase()
        if (!cachedEmail.isNullOrBlank()) return cachedEmail
        val liveTokenEmail = extractJwtClaim(getAccessToken().orEmpty(), "email")?.lowercase()
        if (!liveTokenEmail.isNullOrBlank()) return liveTokenEmail
        return extractJwtClaim(authPrefsOrNull?.getString(KEY_ACCESS_TOKEN, null).orEmpty(), "email")?.lowercase()
    }

    fun getAccessToken(): String? =
        TestRuntimeOverrides.currentSession(context)?.accessToken
            ?: if (isForcedLogout()) null else supabaseConfig.client.auth.currentAccessTokenOrNull()

    fun getRefreshToken(): String? {
        TestRuntimeOverrides.currentSession(context)?.refreshToken?.let { return it }
        if (isForcedLogout()) return null
        return supabaseConfig.client.auth.currentSessionOrNull()?.refreshToken
            ?: authPrefsOrNull?.getString(KEY_REFRESH_TOKEN, null)
    }

    fun isLoggedIn(): Boolean = getCurrentUserId() != null

    /**
     * Ensures a valid, non-expired access token is returned.
     * If the current token is expired or near expiration, it triggers a refresh.
     */
    suspend fun getAccessTokenAwait(): String? {
        TestRuntimeOverrides.currentSession(context)?.accessToken?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        var currentToken = getAccessToken()
        if (currentToken.isNullOrBlank() && !isForcedLogout()) {
            repeat(12) {
                val liveSession = awaitUsableSession(maxAttempts = 1, intervalMs = 0)
                if (isUsableSession(liveSession)) {
                    var safeSession = liveSession ?: return@repeat
                    _currentSession.value = mapSession(safeSession)
                    persistSessionSnapshot(safeSession)
                    currentToken = safeSession.accessToken
                    return@repeat
                }
                delay(150)
                currentToken = getAccessToken()
                if (!currentToken.isNullOrBlank()) return@repeat
            }
        }

        val resolvedToken = currentToken?.trim()?.takeIf { it.isNotBlank() } ?: return null
        
        // Check expiration (exp claim)
        val expirationStr = extractJwtClaim(resolvedToken, "exp")
        var expiration = expirationStr?.toLongOrNull() ?: 0L
        val currentTime = System.currentTimeMillis() / 1000
        
        // Force refresh if token expires in less than 30 seconds
        if (expiration > 0 && expiration < (currentTime + 30)) {
            Log.d("AuthService", "Access token is expired or expiring soon (exp: $expiration, now: $currentTime). Refreshing...")
            return try {
                supabaseConfig.client.auth.refreshCurrentSession()
                val refreshed = getAccessToken()?.trim()
                Log.d("AuthService", "Token refreshed successfully")
                when {
                    refreshed.isNullOrBlank() -> if (expiration <= currentTime) null else resolvedToken
                    isTokenExpiredOrExpiringSoon(refreshed, withinSeconds = 5) -> if (expiration <= currentTime) null else resolvedToken
                    else -> refreshed
                }
            } catch (e: Exception) {
                Log.e("AuthService", "Failed to refresh session", e)
                if (expiration <= currentTime) null else resolvedToken
            }
        }
        
        return resolvedToken
    }

    suspend fun ensureValidAccessToken(): Result<String> {
        TestRuntimeOverrides.currentSession(context)?.accessToken?.trim()?.takeIf { it.isNotBlank() }?.let {
            _currentSession.value = TestRuntimeOverrides.currentSession(context)?.toAuthResponse()
            return Result.success(it)
        }
        if (isForcedLogout()) {
            return Result.failure(Exception("No hay una sesión activa"))
        }

        return try {
            var session = awaitUsableSession(maxAttempts = 4, intervalMs = 80)
                ?: runCatching { supabaseConfig.client.auth.currentSessionOrNull() }.getOrNull()
                ?: recoverPersistedSessionFromSnapshot()

            if (!isUsableSession(session)) {
                return Result.failure(Exception("Sesión expirada. Inicia sesión de nuevo."))
            }

            if (session == null) {
                return Result.failure(Exception("Sesión expirada. Inicia sesión de nuevo."))
            }

            if (isTokenExpiredOrExpiringSoon(session.accessToken, withinSeconds = 30)) {
                runCatching { supabaseConfig.client.auth.refreshCurrentSession() }
                    .onFailure {
                        Log.e("AuthService", "Strict session refresh failed", it)
                    }
                session = awaitUsableSession(maxAttempts = 12, intervalMs = 150)
                    ?: runCatching { supabaseConfig.client.auth.currentSessionOrNull() }.getOrNull()
            }

            var resolvedSession = session
                ?: return Result.failure(Exception("Sesión expirada. Inicia sesión de nuevo."))

            if (!isUsableSession(resolvedSession) || isTokenExpiredOrExpiringSoon(resolvedSession.accessToken, withinSeconds = 30)) {
                return Result.failure(Exception("JWT expired"))
            }

            _currentSession.value = mapSession(resolvedSession)
            persistSessionSnapshot(resolvedSession)
            setForcedLogout(false)
            Result.success(resolvedSession.accessToken.trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeSession(): StateFlow<AuthResponse?> = currentSession

@Composable
@Composable
    suspend fun clearLocalDataForAccountSwitch() {
        withContext(Dispatchers.IO) {
            runCatching { database.clearAllTables() }
        }
    }

    private fun readPersistedSessionSnapshot(): UserSession? {
        var prefs = authPrefsOrNull ?: return null
        var accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        var refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
        if (accessToken.isBlank() || refreshToken.isBlank()) return null

        return UserSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = 3600,
            tokenType = "Bearer",
            user = null,
            type = ""
        )
    }

    private suspend fun recoverPersistedSessionFromSnapshot(): UserSession? {
        val imported = readPersistedSessionSnapshot() ?: return null

        return runCatching {
            // Import into SDK for Realtime/Auth integration without blocking main thread.
            supabaseConfig.client.auth.importSession(imported)
            val restored = supabaseConfig.client.auth.currentSessionOrNull() ?: imported
            if (isUsableSession(restored)) restored else null
        }.onFailure {
            Log.e("AuthService", "Persisted session recovery failed", it)
        }.getOrNull()
    }

    private suspend fun awaitUsableSession(
        maxAttempts: Int = 12,
        intervalMs: Long = 150L
    ): UserSession? {
        var session = runCatching { supabaseConfig.client.auth.currentSessionOrNull() }.getOrNull()
        repeat(maxAttempts) { attempt ->
            if (isUsableSession(session)) return session
            if (attempt < maxAttempts - 1 && intervalMs > 0) {
                delay(intervalMs)
            }
            session = runCatching { supabaseConfig.client.auth.currentSessionOrNull() }.getOrNull()
        }
        return if (isUsableSession(session)) session else null
    }

    private fun persistSessionSnapshot(session: UserSession) {
        var accessToken = session.accessToken.trim()
        var refreshToken = session.refreshToken.trim()
        if (accessToken.isBlank() || refreshToken.isBlank()) return
        var prefs = authPrefsOrNull ?: return
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    private fun clearPersistedSessionSnapshot() {
        var prefs = authPrefsOrNull ?: return
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }
}





