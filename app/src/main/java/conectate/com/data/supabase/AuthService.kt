package conectate.com.data.supabase

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.core.testing.RuntimeSessionOverride
import conectate.com.core.testing.TestRuntimeOverrides
import conectate.com.data.auth.AccountSwitchSession
import conectate.com.data.auth.LocalAccountSwitchSession
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.LocalFallbackAuthResponse
import conectate.com.data.localfallback.LocalFallbackPasswordResetResponse
import conectate.com.data.localfallback.LocalFallbackSessionRenewalRequiredException
import conectate.com.data.localfallback.LocalFallbackUser
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import conectate.com.data.local.AccountOutboxVault
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
    val username: String,
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
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val accountOutboxVault: AccountOutboxVault,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
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
        private const val KEY_LOCAL_FALLBACK_SESSION = "local_fallback_session"
        private const val KEY_LOCAL_FALLBACK_LOCAL_USER_ID = "local_fallback_local_user_id"
        private const val KEY_LOCAL_FALLBACK_DISPLAY_USER_ID = "local_fallback_display_user_id"
        private const val KEY_LOCAL_FALLBACK_SUPABASE_USER_ID = "local_fallback_supabase_user_id"
        private const val KEY_LOCAL_FALLBACK_EMAIL = "local_fallback_email"
        private const val KEY_LOCAL_FALLBACK_DISPLAY_NAME = "local_fallback_display_name"
        private const val KEY_LOCAL_FALLBACK_AVATAR_URL = "local_fallback_avatar_url"
        private const val KEY_LOCAL_FALLBACK_COUNTRY = "local_fallback_country"
        private const val KEY_LOCAL_FALLBACK_ACCOUNT_CREATED_AT = "local_fallback_account_created_at"
        private const val KEY_PENDING_LOGOUT_REFRESHES = "pending_logout_refreshes"
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
    private val localSessionRefreshMutex = Mutex()
    private val localSessionActivationMutex = Mutex()
    private val localSessionMutationGuard = AuthSessionMutationGuard()
    private val localCompatibilitySessionMutex = Mutex()
    private val logoutRevocationMutex = Mutex()
    private val logoutQueueLock = Any()
    @Volatile
    private var registrationInProgress: Boolean = false
    @Volatile
    private var pendingRegistrationSession: AuthResponse? = null
    @Volatile
    private var localSessionRefreshLastAttemptAtMs: Long = 0L
    @Volatile
    private var localSessionRefreshLastSuccessAtMs: Long = 0L

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

    private fun createSecureAuthPrefsState(): SecurePrefsState = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            AUTH_CACHE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        SecurePrefsState(prefs = prefs, initializationError = null)
    } catch (error: Throwable) {
        Log.e("AuthService", "CRITICAL: Encrypted session storage is unavailable", error)
        SecurePrefsState(prefs = null, initializationError = error)
    }

    private fun setForcedLogout(enabled: Boolean) {
        authPrefsOrNull?.edit()?.putBoolean(KEY_FORCED_LOGOUT, enabled)?.apply()
    }

    fun isLocalFallbackModeEnabled(): Boolean = localFallbackApiClient.isConfigured

    internal fun usesLocalAccountSwitch(): Boolean =
        isLocalFallbackModeEnabled() && !TestRuntimeOverrides.isEnabled(context)

    internal suspend fun prepareLocalSignIn(email: String, password: String, code: String?): LocalFallbackAuthResponse {
        ensureSecureStorageAvailable().getOrThrow()
        val response = localFallbackApiClient.login(email, password, code).getOrThrow()
        check(response.user.id.isNotBlank() && response.user.email.isNotBlank() &&
            response.accessToken.isNotBlank() && response.refreshToken.isNotBlank()) {
            "No se pudo recibir una sesión completa. Reintenta."
        }
        return response
    }

    fun isLocalFallbackStoredSessionToken(accessToken: String, refreshToken: String): Boolean {
        val access = accessToken.trim()
        val refresh = refreshToken.trim()
        if (!isLocalFallbackModeEnabled() || access.isBlank() || refresh.isBlank()) return false
        return extractJwtClaim(access, "provider") == "local-fallback"
    }

    internal suspend fun refreshLocalFallbackStoredSession(
        accessToken: String,
        refreshToken: String,
        email: String,
        userId: String?,
    ): Result<LocalFallbackAuthResponse> {
        val access = accessToken.trim()
        val refresh = refreshToken.trim()
        if (!isLocalFallbackStoredSessionToken(access, refresh)) {
            return Result.failure(Exception("La cuenta guardada pertenece a la base anterior. Ingresa con correo y contraseña para activarla en Ubuntu."))
        }

        val resolvedEmail = email.trim().lowercase().ifBlank {
            extractJwtClaim(access, "email")?.lowercase().orEmpty()
        }
        val resolvedUserId = userId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: extractJwtClaim(access, "sub")?.lowercase()
        if (resolvedEmail.isBlank() && resolvedUserId.isNullOrBlank()) {
            return Result.failure(Exception("No se pudo restaurar la cuenta local guardada."))
        }

        val refreshed = localFallbackApiClient.refreshSession(
            refreshToken = refresh,
            accessToken = access
        )
            .getOrElse { error ->
                if (error is LocalFallbackSessionRenewalRequiredException) {
                    return Result.failure(
                        IllegalStateException(
                            "Esta cuenta guardada venció. Inicia sesión manualmente una vez para volver a activarla.",
                            error
                        )
                    )
                }
                return Result.failure(error)
            }
        val refreshedUserId = refreshed.user.supabaseUserId
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: refreshed.user.id.trim().lowercase()
        val refreshedLocalUserId = refreshed.user.id.trim().lowercase()
        val refreshedEmail = refreshed.user.email.trim().lowercase()
        val identityMatches = if (!resolvedUserId.isNullOrBlank())
            refreshedUserId == resolvedUserId || refreshedLocalUserId == resolvedUserId
        else refreshedEmail.equals(resolvedEmail, ignoreCase = true)
        if (!identityMatches) {
            return Result.failure(
                IllegalStateException("La sesión guardada no corresponde a la cuenta seleccionada.")
            )
        }

        if (refreshed.accessToken.isBlank() || refreshed.refreshToken.isBlank() ||
            refreshedLocalUserId.isBlank() || refreshedUserId.isBlank() || refreshedEmail.isBlank()) {
            return Result.failure(IllegalStateException("No se pudo renovar la sesión guardada completa."))
        }
        return Result.success(refreshed)
    }

    suspend fun importLocalFallbackStoredSession(
        accessToken: String, refreshToken: String, email: String, userId: String?,
        @Suppress("UNUSED_PARAMETER") displayName: String?,
        @Suppress("UNUSED_PARAMETER") avatarUrl: String?,
    ): Result<Unit> {
        val generation = localSessionMutationGuard.snapshot()
        return try {
            val response = refreshLocalFallbackStoredSession(accessToken, refreshToken, email, userId).getOrThrow()
            persistLocalFallbackSession(response, expectedGeneration = generation)
            Result.success(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    internal fun openLocalAccountSwitch(
        saveCurrent: (StoredAccountSession) -> Unit,
        clearCaches: () -> Unit,
    ): AccountSwitchSession {
        check(isLocalFallbackModeEnabled()) { "El cambio de cuenta local no está disponible." }
        return LocalAccountSwitchSession(
            guard = localSessionMutationGuard, activationMutex = localSessionActivationMutex,
            currentAccount = ::getCurrentUserId, capture = ::readActiveLocalFallbackResponse,
            saveCurrent = { saveCurrent(storedLocalSession(it)) },
            pauseCurrent = {
                check(authPrefsOrNull?.edit()?.putBoolean(KEY_FORCED_LOGOUT, true)?.commit() == true) {
                    "No se pudo detener la sesión anterior de forma segura."
                }
                _currentSession.value = null
                _isInitializing.value = false
                localSessionRefreshLastAttemptAtMs = 0L
                localSessionRefreshLastSuccessAtMs = 0L
            },
            clearCaches = clearCaches, archive = accountOutboxVault::preserveAndClear,
            activateResponse = { response, lease, sync ->
                persistLocalFallbackSession(response, transition = lease, syncCompatibility = sync)
            },
            restore = accountOutboxVault::restore,
            afterPause = {
                authScope.launch {
                    runCatching { localCompatibilitySessionMutex.withLock {
                        if (isForcedLogout()) supabaseConfig.client.auth.clearSession()
                    } }.onFailure { Log.w("AuthService", "Could not clear paused compatibility session", it) }
                }
            },
        )
    }

    private fun storedLocalSession(response: LocalFallbackAuthResponse) = StoredAccountSession(
        user_id = localFallbackDisplayUserId(response.user), email = response.user.email,
        access_token = response.accessToken, refresh_token = response.refreshToken,
        display_name = response.user.displayName, avatar_url = response.user.avatarUrl,
    )

    /** Called under the mutation guard, before pausing the outgoing identity. */
    private fun readActiveLocalFallbackResponse(): LocalFallbackAuthResponse? {
        if (getCurrentUserId() == null) return null
        check(isLocalFallbackSession()) { "Vuelve a iniciar sesión para guardar esta cuenta en el servidor actual." }
        val prefs = checkNotNull(authPrefsOrNull)
        val access = prefs.getString(KEY_ACCESS_TOKEN, null).orEmpty()
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null).orEmpty()
        val localId = prefs.getString(KEY_LOCAL_FALLBACK_LOCAL_USER_ID, null).orEmpty()
        val email = localFallbackEmailFromPrefs().orEmpty()
        check(access.isNotBlank() && refresh.isNotBlank() && localId.isNotBlank() && email.isNotBlank()) {
            "No se pudo guardar la sesión anterior completa."
        }
        return LocalFallbackAuthResponse(
            accessToken = access, refreshToken = refresh,
            user = LocalFallbackUser(
                id = localId, email = email,
                supabaseUserId = prefs.getString(KEY_LOCAL_FALLBACK_SUPABASE_USER_ID, null),
                displayName = prefs.getString(KEY_LOCAL_FALLBACK_DISPLAY_NAME, null),
                avatarUrl = prefs.getString(KEY_LOCAL_FALLBACK_AVATAR_URL, null),
                accountCreatedAt = prefs.getString(KEY_LOCAL_FALLBACK_ACCOUNT_CREATED_AT, null),
            ),
        )
    }

    /**
     * Immediate local logout gate.
     * Prevents the app from treating an old SDK session as logged-in (even if signOut() fails).
     */
    fun forceLocalLogout() {
        localSessionMutationGuard.invalidate {
            val wasLocalFallbackSession = isLocalFallbackSession()
            if (wasLocalFallbackSession) {
                val refreshToken = getRefreshToken()?.trim().orEmpty()
                if (refreshToken.isNotBlank() && !queueLogoutRevocation(refreshToken)) {
                    Log.e("AuthService", "Could not persist pending server logout")
                }
            }
            registrationInProgress = false
            pendingRegistrationSession = null
            if (TestRuntimeOverrides.isEnabled(context)) {
                TestRuntimeOverrides.clearSession(context)
            }
            authPrefsOrNull?.edit()
                ?.putBoolean(KEY_FORCED_LOGOUT, true)
                ?.remove(KEY_ACCESS_TOKEN)
                ?.remove(KEY_REFRESH_TOKEN)
                ?.remove(KEY_LOCAL_FALLBACK_SESSION)
                ?.remove(KEY_LOCAL_FALLBACK_LOCAL_USER_ID)
                ?.remove(KEY_LOCAL_FALLBACK_DISPLAY_USER_ID)
                ?.remove(KEY_LOCAL_FALLBACK_SUPABASE_USER_ID)
                ?.remove(KEY_LOCAL_FALLBACK_EMAIL)
                ?.remove(KEY_LOCAL_FALLBACK_DISPLAY_NAME)
                ?.remove(KEY_LOCAL_FALLBACK_AVATAR_URL)
                ?.remove(KEY_LOCAL_FALLBACK_COUNTRY)
                ?.remove(KEY_LOCAL_FALLBACK_ACCOUNT_CREATED_AT)
                ?.apply()
            _currentSession.value = null
            _isInitializing.value = false
            localSessionRefreshLastAttemptAtMs = 0L
            localSessionRefreshLastSuccessAtMs = 0L
            if (wasLocalFallbackSession) {
                authScope.launch {
                    runCatching { localCompatibilitySessionMutex.withLock {
                        if (isForcedLogout()) supabaseConfig.client.auth.clearSession()
                    } }
                        .onFailure { Log.w("AuthService", "Could not clear compatibility session", it) }
                }
            }
        }
    }

    internal data class CapturedLogout(
        val accountId: String?, val accessToken: String?, val refreshToken: String?,
        val localFallback: Boolean, val generation: Long,
    )

    internal fun forceLocalLogoutAndCapture(beforeLogout: () -> Unit = {}): CapturedLogout =
        localSessionMutationGuard.readCurrent {
            val captured = CapturedLogout(getCurrentUserId(), getAccessToken(), getRefreshToken(),
                isLocalFallbackSession(), generation = 0)
            runCatching(beforeLogout).onFailure { Log.w("AuthService", "Outgoing account cleanup failed", it) }
            forceLocalLogout()
            captured.copy(generation = localSessionMutationGuard.snapshot())
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
        if (isLocalFallbackModeEnabled()) {
            return localFallbackDisplayUserIdFromPrefs()
        }
        var fromSdk = runCatching { supabaseConfig.client.auth.currentUserOrNull()?.id?.trim()?.lowercase() }.getOrNull()
        if (!fromSdk.isNullOrBlank()) return fromSdk
        return extractJwtClaim(session?.accessToken.orEmpty(), "sub")?.lowercase()
    }

    private fun resolveSessionEmail(session: UserSession?): String {
        var fromSession = session?.user?.email?.trim()?.lowercase()
        if (!fromSession.isNullOrBlank()) return fromSession
        if (isLocalFallbackModeEnabled()) {
            return localFallbackEmailFromPrefs().orEmpty()
        }
        var fromSdk = runCatching { supabaseConfig.client.auth.currentUserOrNull()?.email?.trim()?.lowercase() }.getOrNull()
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
        // Readers request a coherent account/generation/token snapshot at each attempt.
        // Do not eagerly create AuthService from SocialApp's cold-start image loader.
        conectate.com.core.media.StorageReadSession.install(credentials = {
            // Media readers check identity before and after every byte copy.
            // Reading EncryptedSharedPreferences at this frequency repeatedly
            // decrypts keys/tokens on the playback worker and causes underruns.
            // The published StateFlow session is immutable and already hydrated;
            // logout/account switch invalidate its generation synchronously.
            val generation = localSessionMutationGuard.snapshot()
            val session = _currentSession.value
            val test = TestRuntimeOverrides.currentSession(context)
            AuthMediaReadSnapshotPolicy.snapshot(
                accountId = test?.userId ?: session?.user?.id,
                accessToken = test?.accessToken ?: session?.accessToken,
                generationBefore = generation,
                generationAfter = localSessionMutationGuard.snapshot(),
                registrationInProgress = registrationInProgress,
            )
        }, prepare = { expected ->
            val readyToken = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(8_000L) { getAccessTokenAwait() }
            }
            conectate.com.core.media.StorageReadSession.snapshot()?.takeIf {
                it.identity == expected && !readyToken.isNullOrBlank() && it.accessToken == readyToken
            }
        })
        // Ubuntu owns authentication, but the legacy PostgREST SDK facade still needs the JWT.
        if (isLocalFallbackModeEnabled()) {
            val startupGeneration = localSessionMutationGuard.snapshot()
            // EncryptedSharedPreferences may initialize Android Keystore on first use.
            // Keep that disk/crypto work off the main thread so Activity creation can draw.
            authScope.launch {
                try {
                    val localSession = withContext(Dispatchers.IO) {
                        recoverPersistedLocalFallbackSession()
                    }
                    if (localSession == null) {
                        withContext(Dispatchers.IO) {
                            localSessionMutationGuard.commitIfCurrent(startupGeneration) {
                                _currentSession.value = null
                                clearPersistedSessionSnapshot()
                                _isInitializing.value = false
                            }
                        }
                        Log.d("AuthService", "Ubuntu backend: No local session, ready for login")
                        return@launch
                    }

                    // Publish the durable session as soon as secure local storage is read. A Play
                    // Store update can restart the process while the backend is reconnecting.
                    val restoredCurrent = localSessionMutationGuard.commitIfCurrent(startupGeneration,
                        eligible = { !isForcedLogout() && authPrefsOrNull?.getString(KEY_ACCESS_TOKEN, null)?.trim() == localSession.accessToken.trim() }) {
                        _currentSession.value = localSession
                        _isInitializing.value = false
                    }
                    if (!restoredCurrent) return@launch
                    authScope.launch {
                        val refreshedToken = refreshPersistedLocalFallbackSessionIfNeeded(force = true)
                            .getOrElse { error ->
                                Log.w("AuthService", "Ubuntu session could not be renewed at startup", error)
                                return@launch
                            }
                        try {
                            val restored = withContext(Dispatchers.IO) {
                                recoverPersistedLocalFallbackSession()
                            }
                            if (
                                restored != null &&
                                !isTokenExpiredOrExpiringSoon(refreshedToken, withinSeconds = 0L)
                            ) {
                                val accepted = localSessionMutationGuard.commitIfCurrent(startupGeneration,
                                    eligible = { !isForcedLogout() && authPrefsOrNull?.getString(KEY_ACCESS_TOKEN, null)?.trim() == refreshedToken }) {
                                    _currentSession.value = restored
                                }
                                if (accepted) syncLocalFallbackCompatibilitySession(refreshedToken)
                            }
                        } catch (error: Exception) {
                            // Keep the locally authenticated account on transient startup failures.
                            Log.w("AuthService", "Ubuntu compatibility session sync deferred", error)
                        }
                    }
                    Log.d("AuthService", "Ubuntu backend: saved session restored; validation continues in background")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("AuthService", "Ubuntu local session recovery failed", error)
                    localSessionMutationGuard.commitIfCurrent(startupGeneration) {
                        _currentSession.value = null
                        _isInitializing.value = false
                    }
                }
            }
            Log.d("AuthService", "Ubuntu backend: Auth initialization started")
        } else {
            // Original Supabase initialization flow (only when NOT using Ubuntu)
            val runtimeSessionOverride = TestRuntimeOverrides.currentSession(context)
            if (runtimeSessionOverride != null) {
                _currentSession.value = runtimeSessionOverride.toAuthResponse()
                _isInitializing.value = false
                Log.d("AuthService", "Auth runtime override enabled for instrumentation tests")
            } else {
                authScope.launch {
                    try {
                        val forcedLogout = withContext(Dispatchers.IO) { isForcedLogout() }
                        if (forcedLogout) {
                            // User explicitly logged out: do not auto-recover SDK sessions.
                            _currentSession.value = null
                            withContext(Dispatchers.IO) { clearPersistedSessionSnapshot() }
                            _isInitializing.value = false
                            Log.w("AuthService", "Forced logout flag set: skipping session recovery")
                            return@launch
                        }

                        val localSession = withContext(Dispatchers.IO) {
                            recoverPersistedLocalFallbackSession()
                        }
                        if (localSession != null) {
                            _currentSession.value = localSession
                            _isInitializing.value = false
                            Log.d("AuthService", "Local fallback session recovered at startup")
                            return@launch
                        }

                        // Fast-path recovery from SDK cache, then the encrypted snapshot.
                        var bootSession = runCatching {
                            supabaseConfig.client.auth.currentSessionOrNull()
                        }.getOrNull()
                        if (!isUsableSession(bootSession)) bootSession = null
                        if (bootSession == null) bootSession = recoverPersistedSessionFromSnapshot()

                        val cachedSession = bootSession
                        if (cachedSession != null) {
                            _currentSession.value = mapSession(cachedSession)
                            withContext(Dispatchers.IO) { persistSessionSnapshot(cachedSession) }
                            _isInitializing.value = false
                            Log.d("AuthService", "Cached session recovered at startup")

                            // Refresh older cached sessions in the background.
                            launch {
                                try {
                                    val expiration = extractJwtClaim(cachedSession.accessToken, "exp")
                                        ?.toLongOrNull() ?: 0L
                                    if (expiration < System.currentTimeMillis() / 1000 + 600) {
                                        supabaseConfig.client.auth.refreshCurrentSession()
                                    }
                                } catch (error: Exception) {
                                    Log.e("AuthService", "Proactive refresh failed", error)
                                }
                            }
                        } else {
                            // A missing cached session is a valid state; do not wait for the SDK
                            // observer timeout before making sign-in and registration available.
                            _currentSession.value = null
                            _isInitializing.value = false
                            Log.d("AuthService", "No cached session; login is available")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e("AuthService", "Startup session recovery failed", error)
                        _isInitializing.value = false
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
                        if (registrationInProgress) {
                            if (status is SessionStatus.Authenticated && isUsableSession(status.session)) {
                                persistSessionSnapshot(status.session)
                            }
                            _isInitializing.value = false
                            return@collect
                        }
                        recoverPersistedLocalFallbackSession()?.let { localSession ->
                            _currentSession.value = localSession
                            _isInitializing.value = false
                            return@collect
                        }
                        if (isLocalFallbackModeEnabled()) {
                            _currentSession.value = null
                            clearPersistedSessionSnapshot()
                            _isInitializing.value = false
                            return@collect
                        }
                        if (status is SessionStatus.Authenticated) {
                            if (isUsableSession(status.session)) {
                                _currentSession.value = mapSession(status.session)
                                persistSessionSnapshot(status.session)
                                Log.d("AuthService", "SDK session active")
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

        }
        // A slow keystore or SDK observer must not keep the app at the logo indefinitely.
        // Recovery and sessionStatus continue in the background after the entry UI is shown.
        authScope.launch {
            kotlinx.coroutines.delay(4_000)
            if (_isInitializing.value) {
                Log.w("AuthService", "Auth state is still restoring; showing the entry screen while recovery continues.")
                _isInitializing.value = false
            }
        }
        if (isLocalFallbackModeEnabled()) {
            authScope.launch { retryPendingLogoutRevocations() }
        }
    }

    /* -------------------- AUTH -------------------- */

    suspend fun signUp(
        email: String,
        password: String,
        metadata: SignUpMetadata
    ): Result<AuthResponse> {
        val requestGeneration = localSessionMutationGuard.snapshot()
        registrationInProgress = true
        pendingRegistrationSession = null
        return try {
            ensureSecureStorageAvailable().getOrThrow()
            if (localFallbackApiClient.isConfigured) {
                val localResult = localFallbackApiClient.register(
                    email = email,
                    password = password,
                    displayName = metadata.display_name ?: metadata.full_name,
                    username = metadata.username,
                )
                val localSession = localResult.getOrElse { error ->
                    registrationInProgress = false
                    pendingRegistrationSession = null
                    return Result.failure(error)
                }
                persistLocalFallbackSession(localSession, publishSession = false, expectedGeneration = requestGeneration)
                val mapped = mapLocalFallbackSession(localSession)
                pendingRegistrationSession = mapped
                return Result.success(mapped)
            }

            supabaseConfig.client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                data = kotlinx.serialization.json.buildJsonObject {
                    put("full_name", kotlinx.serialization.json.JsonPrimitive(metadata.full_name))
                    metadata.display_name?.takeIf { it.isNotBlank() }?.let {
                        put("display_name", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    put("username", kotlinx.serialization.json.JsonPrimitive(metadata.username))
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

            var safeSession = session ?: run {
                registrationInProgress = false
                return Result.failure(Exception("Registro completado pero no se pudo recuperar la sesión"))
            }
            if (!isUsableSession(safeSession)) {
                registrationInProgress = false
                return Result.failure(Exception("Registro completado pero la sesión aún no está lista"))
            }

            setForcedLogout(false)
            persistSessionSnapshot(safeSession)
            pendingRegistrationSession = mapSession(safeSession)

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
            registrationInProgress = false
            pendingRegistrationSession = null
            Log.e("AuthService", "Signup error", e)
            Result.failure(e)
        }
    }

    fun pendingRegistrationFor(email: String): AuthResponse? {
        val normalizedEmail = email.trim().lowercase()
        return pendingRegistrationSession
            ?.takeIf {
                registrationInProgress &&
                    it.user.email.trim().equals(normalizedEmail, ignoreCase = true)
            }
    }

    suspend fun completePendingRegistration() {
        val pending = pendingRegistrationSession
        pendingRegistrationSession = null
        registrationInProgress = false
        if (pending == null) return

        _currentSession.value = if (isLocalFallbackModeEnabled()) {
            recoverPersistedLocalFallbackSession() ?: pending
        } else {
            val sdkSession = runCatching { supabaseConfig.client.auth.currentSessionOrNull() }.getOrNull()
            if (isUsableSession(sdkSession)) mapSession(requireNotNull(sdkSession)) else pending
        }
    }

    suspend fun signIn(email: String, password: String, secondFactorCode: String? = null): Result<Unit> {
        val requestGeneration = localSessionMutationGuard.snapshot()
        return try {
            ensureSecureStorageAvailable().getOrThrow()
            if (localFallbackApiClient.isConfigured) {
                val localResult = localFallbackApiClient.login(email, password, secondFactorCode)
                val localSession = localResult.getOrElse { return Result.failure(it) }
                persistLocalFallbackSession(localSession, expectedGeneration = requestGeneration)
                return Result.success(Unit)
            }

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

    suspend fun signOut(expectedGeneration: Long? = null) {
        val outgoing = localSessionMutationGuard.readCurrent {
            if (expectedGeneration != null && expectedGeneration != localSessionMutationGuard.snapshot())
                throw CancellationException("La cuenta cambió durante la operación de seguridad.")
            forceLocalLogoutAndCapture()
        }
        withContext(Dispatchers.IO) {
            try {
                clearLocalDataForAccountSwitch(outgoing.accountId, outgoing.generation)
                Log.d("AuthService", "Account cache cleared and pending work saved")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e("AuthService", "Could not archive pending work during signOut", e)
            }
            try {
                if (outgoing.localFallback) {
                    if (!outgoing.refreshToken.isNullOrBlank()) {
                        revokeCapturedSession(outgoing.refreshToken)
                    }
                } else if (isForcedLogout()) {
                    supabaseConfig.client.auth.signOut()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e("AuthService", "Server signOut failed", e)
            } finally {
                localSessionMutationGuard.commitIfCurrent(outgoing.generation, eligible = { isForcedLogout() }) {
                    _currentSession.value = null
                    clearPersistedSessionSnapshot()
                }
            }
        }
    }

    suspend fun serverTwoFactorStatus(): Result<conectate.com.data.localfallback.ServerTwoFactorStatus> {
        val token = getAccessTokenAwait()
            ?: return Result.failure(IllegalStateException("Inicia sesión para consultar la verificación."))
        return localFallbackApiClient.twoFactorStatus(token)
    }

    suspend fun passwordRecoveryCodeStatus(): Result<conectate.com.data.localfallback.PasswordRecoveryCodeStatus> {
        val token = getAccessTokenAwait()
            ?: return Result.failure(IllegalStateException("Inicia sesión para consultar tus códigos de recuperación."))
        return localFallbackApiClient.passwordRecoveryCodeStatus(token)
    }

    suspend fun rotatePasswordRecoveryCodes(password: String, secondFactorCode: String?): Result<List<String>> {
        val token = getAccessTokenAwait()
            ?: return Result.failure(IllegalStateException("Inicia sesión para crear códigos de recuperación."))
        return localFallbackApiClient.rotatePasswordRecoveryCodes(token, password, secondFactorCode)
    }

    suspend fun setupServerTwoFactor(password: String): Result<conectate.com.data.localfallback.ServerTwoFactorSetup> {
        val token = getAccessTokenAwait()
            ?: return Result.failure(IllegalStateException("Inicia sesión para activar la verificación."))
        return localFallbackApiClient.setupTwoFactor(token, password)
    }

    suspend fun enableServerTwoFactor(password: String, code: String): Result<List<String>> {
        val requestGeneration = localSessionMutationGuard.snapshot()
        val token = getAccessTokenAwait()
            ?: return Result.failure(IllegalStateException("Inicia sesión para activar la verificación."))
        val enabled = localFallbackApiClient.enableTwoFactor(token, password, code)
            .getOrElse { return Result.failure(it) }
        return try {
            persistLocalFallbackSession(enabled.session, expectedGeneration = requestGeneration)
            Result.success(enabled.recoveryCodes)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun disableServerTwoFactor(password: String, code: String): Result<Unit> {
        val requestGeneration = localSessionMutationGuard.snapshot()
        val token = getAccessTokenAwait()
            ?: return Result.failure(IllegalStateException("Inicia sesión para desactivar la verificación."))
        val result = localFallbackApiClient.disableTwoFactor(token, password, code)
        if (result.isSuccess) signOut(expectedGeneration = requestGeneration)
        return result
    }

    private fun pendingLogoutRevocations(): List<String> {
        val raw = authPrefsOrNull?.getString(KEY_PENDING_LOGOUT_REFRESHES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .onFailure { Log.e("AuthService", "Could not read pending server logouts", it) }
            .getOrDefault(emptyList())
    }

    private fun queueLogoutRevocation(refreshToken: String): Boolean = synchronized(logoutQueueLock) {
        val prefs = authPrefsOrNull ?: return@synchronized false
        val pending = (pendingLogoutRevocations() + refreshToken).distinct()
        prefs.edit().putString(KEY_PENDING_LOGOUT_REFRESHES, json.encodeToString(pending)).commit()
    }

    private fun finishLogoutRevocation(refreshToken: String) = synchronized(logoutQueueLock) {
        val prefs = authPrefsOrNull ?: return@synchronized
        val remaining = pendingLogoutRevocations().filterNot { it == refreshToken }
        val edit = prefs.edit()
        if (remaining.isEmpty()) edit.remove(KEY_PENDING_LOGOUT_REFRESHES)
        else edit.putString(KEY_PENDING_LOGOUT_REFRESHES, json.encodeToString(remaining))
        if (!edit.commit()) Log.e("AuthService", "Could not persist server logout acknowledgement")
    }

    private suspend fun revokeCapturedSession(refreshToken: String) {
        val result = withTimeoutOrNull(3_000L) { localFallbackApiClient.revokeSession(refreshToken) }
        if (result?.isSuccess == true) {
            finishLogoutRevocation(refreshToken)
        } else {
            result?.onFailure { Log.w("AuthService", "Server logout will retry later", it) }
                ?: Log.w("AuthService", "Server logout timed out; will retry later")
        }
    }

    private suspend fun retryPendingLogoutRevocations() {
        if (!isLocalFallbackModeEnabled()) return
        logoutRevocationMutex.withLock {
            for (token in pendingLogoutRevocations()) revokeCapturedSession(token)
        }
    }

    /** Completes an immediate UI logout without clearing a newly signed-in session. */
    fun rememberServerLogout(refreshToken: String?) {
        val token = refreshToken?.trim().orEmpty()
        if (token.isNotBlank() && !queueLogoutRevocation(token)) {
            Log.e("AuthService", "Could not persist pending server logout")
        }
    }

    suspend fun finishForcedLogout(outgoingRefreshToken: String?, outgoingWasLocalFallback: Boolean) {
        if (outgoingWasLocalFallback) {
            val token = outgoingRefreshToken?.trim().orEmpty()
            if (token.isNotBlank()) {
                rememberServerLogout(token)
                revokeCapturedSession(token)
            }
        } else if (isForcedLogout()) {
            supabaseConfig.client.auth.signOut()
        }
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        return try {
            if (localFallbackApiClient.isConfigured) {
                return localFallbackApiClient.requestPasswordReset(email).fold(
                    onSuccess = { response ->
                        if (response.delivery == "disabled") {
                            Result.failure(IllegalStateException(
                                "La recuperación por correo no está disponible. Usa un código de recuperación guardado."
                            ))
                        } else {
                            Result.success(Unit)
                        }
                    },
                    onFailure = { Result.failure(it) }
                )
            }

            Result.failure(IllegalStateException("El servidor de recuperación no está configurado."))
        } catch (e: Exception) {
            Log.e("AuthService", "Reset password error", e)
            Result.failure(e)
        }
    }

    suspend fun requestLocalFallbackPasswordReset(email: String): Result<LocalFallbackPasswordResetResponse> {
        if (!localFallbackApiClient.isConfigured) {
            return Result.failure(Exception("Servidor Ubuntu no configurado."))
        }
        return localFallbackApiClient.requestPasswordReset(email)
    }

    suspend fun confirmLocalFallbackPasswordReset(
        resetToken: String,
        newPassword: String
    ): Result<Unit> {
        val requestGeneration = localSessionMutationGuard.snapshot()
        if (!localFallbackApiClient.isConfigured) {
            return Result.failure(Exception("Servidor Ubuntu no configurado."))
        }
        val localSession = localFallbackApiClient.confirmPasswordReset(resetToken, newPassword)
            .getOrElse { return Result.failure(it) }
        persistLocalFallbackSession(localSession, expectedGeneration = requestGeneration)
        return Result.success(Unit)
    }

    suspend fun confirmSavedPasswordRecoveryCode(
        email: String,
        recoveryCode: String,
        newPassword: String,
        secondFactorCode: String?
    ): Result<Unit> {
        val requestGeneration = localSessionMutationGuard.snapshot()
        val localSession = localFallbackApiClient.confirmSavedPasswordRecoveryCode(
            email, recoveryCode, newPassword, secondFactorCode
        ).getOrElse { return Result.failure(it) }
        persistLocalFallbackSession(localSession, expectedGeneration = requestGeneration)
        return Result.success(Unit)
    }

    suspend fun completeMigratedLocalPasswordReset(
        migrationToken: String,
        newPassword: String
    ): Result<Unit> {
        val requestGeneration = localSessionMutationGuard.snapshot()
        if (!localFallbackApiClient.isConfigured) {
            return Result.failure(Exception("Servidor Ubuntu no configurado."))
        }
        val localSession = localFallbackApiClient.completeMigratedPasswordReset(migrationToken, newPassword)
            .getOrElse { return Result.failure(it) }
        persistLocalFallbackSession(localSession, expectedGeneration = requestGeneration)
        return Result.success(Unit)
    }

    suspend fun directPasswordReset(
        email: String,
        newPassword: String
    ): Result<Unit> {
        val requestGeneration = localSessionMutationGuard.snapshot()
        if (!localFallbackApiClient.isConfigured) {
            return Result.failure(Exception("Servidor Ubuntu no configurado."))
        }
        val localSession = localFallbackApiClient.directPasswordReset(email, newPassword)
            .getOrElse { return Result.failure(it) }
        persistLocalFallbackSession(localSession, expectedGeneration = requestGeneration)
        return Result.success(Unit)
    }

    suspend fun changePassword(
        currentPassword: String,
        newPassword: String
    ): Result<Unit> {
        val requestGeneration = localSessionMutationGuard.snapshot()
        if (!isLocalFallbackModeEnabled()) {
            return updateUserPassword(newPassword)
        }
        val accessToken = ensureValidAccessToken()
            .getOrElse { return Result.failure(it) }
        val localSession = localFallbackApiClient.changePassword(
            accessToken = accessToken,
            currentPassword = currentPassword,
            newPassword = newPassword
        ).getOrElse { return Result.failure(it) }
        persistLocalFallbackSession(localSession, expectedGeneration = requestGeneration)
        return Result.success(Unit)
    }

    suspend fun updateUserPassword(newPassword: String): Result<Unit> {
        // Ubuntu backend handles password changes via changePassword endpoint
        if (isLocalFallbackModeEnabled()) {
            return Result.failure(Exception("Usa changePassword en LocalFallbackApiClient para Ubuntu."))
        }
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
        privacyPolicyAcceptedAt: String? = null,
        expectedUserId: String? = null,
    ): Result<Unit> {
        val owner = expectedUserId ?: getCurrentUserId()
        val generation = localSessionMutationGuard.snapshot()
        return try {
            if (isLocalFallbackSession() || isLocalFallbackModeEnabled()) {
                val prefs = authPrefsOrNull
                    ?: return Result.failure(Exception("El almacenamiento seguro no está disponible."))
                val stored = withContext(Dispatchers.IO) {
                    var stored = false
                    val accepted = localSessionMutationGuard.commitIfCurrent(generation,
                        eligible = { !owner.isNullOrBlank() && owner.equals(getCurrentUserId(), ignoreCase = true) }) {
                        stored = prefs.edit().apply {
                            (displayName ?: fullName)?.let { putString(KEY_LOCAL_FALLBACK_DISPLAY_NAME, it) }
                            avatarUrl?.let { putString(KEY_LOCAL_FALLBACK_AVATAR_URL, it) }
                            country?.let { putString(KEY_LOCAL_FALLBACK_COUNTRY, it) }
                        }.commit()
                        if (stored) {
                            val recovered = recoverPersistedLocalFallbackSession()
                            if (recovered != null && !registrationInProgress) _currentSession.value = recovered
                        }
                    }
                    if (!accepted) throw CancellationException("La cuenta cambió antes de guardar el perfil.")
                    stored
                }
                if (!stored) {
                    return Result.failure(Exception("No se pudieron guardar los datos del perfil en el dispositivo."))
                }
                return Result.success(Unit)
            }

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
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
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
        getLocalFallbackProfileSeed()?.let { return it }

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
        localFallbackDisplayUserIdFromPrefs()?.let { return it }
        if (isLocalFallbackModeEnabled()) return null
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
        localFallbackEmailFromPrefs()?.let { return it }
        if (isLocalFallbackModeEnabled()) return null
        val sdkEmail = supabaseConfig.client.auth.currentUserOrNull()?.email?.trim()?.lowercase()
        if (!sdkEmail.isNullOrBlank()) return sdkEmail
        val cachedEmail = _currentSession.value?.user?.email?.trim()?.lowercase()
        if (!cachedEmail.isNullOrBlank()) return cachedEmail
        val liveTokenEmail = extractJwtClaim(getAccessToken().orEmpty(), "email")?.lowercase()
        if (!liveTokenEmail.isNullOrBlank()) return liveTokenEmail
        return extractJwtClaim(authPrefsOrNull?.getString(KEY_ACCESS_TOKEN, null).orEmpty(), "email")?.lowercase()
    }

    fun getCurrentUserDisplayName(): String? {
        (if (isLocalFallbackSession()) authPrefsOrNull?.getString(KEY_LOCAL_FALLBACK_DISPLAY_NAME, null) else null)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val metadata = _currentSession.value?.user?.metadata as? kotlinx.serialization.json.JsonObject
        return listOf("display_name", "full_name", "name")
            .firstNotNullOfOrNull { key ->
                (metadata?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
    }

    fun getAccessToken(): String? =
        TestRuntimeOverrides.currentSession(context)?.accessToken
            ?: if (isForcedLogout()) null else readLocalFallbackAccessToken()
                ?: if (isLocalFallbackModeEnabled()) null else supabaseConfig.client.auth.currentAccessTokenOrNull()

    fun getRefreshToken(): String? {
        TestRuntimeOverrides.currentSession(context)?.refreshToken?.let { return it }
        if (isForcedLogout()) return null
        if (isLocalFallbackSession()) {
            return authPrefsOrNull
                ?.getString(KEY_REFRESH_TOKEN, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        if (isLocalFallbackModeEnabled()) return null
        return supabaseConfig.client.auth.currentSessionOrNull()?.refreshToken
            ?: authPrefsOrNull?.getString(KEY_REFRESH_TOKEN, null)
    }

    fun isLoggedIn(): Boolean = !registrationInProgress && getCurrentUserId() != null

    /** Keep a snapshot/store operation on one account and credential pair. No suspension inside. */
    internal fun commitForCurrentAccount(
        accountId: String?,
        accessToken: String? = null,
        mutation: () -> Unit,
    ): Boolean = localSessionMutationGuard.commitForAccount(
        accountId, accessToken, ::getCurrentUserId, ::getAccessToken, mutation,
    )

    internal suspend fun getAccessTokenForAccountAwait(accountId: String): String? {
        val token = getAccessTokenAwait() ?: return null
        if (!commitForCurrentAccount(accountId, token) {})
            throw CancellationException("La cuenta cambió durante la operación.")
        return token
    }

    /**
     * Ensures a valid, non-expired access token is returned.
     * If the current token is expired or near expiration, it triggers a refresh.
     */
    suspend fun getAccessTokenAwait(): String? {
        TestRuntimeOverrides.currentSession(context)?.accessToken?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        if (isLocalFallbackSession()) {
            return refreshPersistedLocalFallbackSessionIfNeeded()
                .getOrElse { error ->
                    Log.w("AuthService", "Ubuntu session refresh failed", error)
                    null
                }
        }
        var currentToken = getAccessToken()
        if (currentToken.isNullOrBlank()) {
            if (isLocalFallbackModeEnabled()) return null
            if (!isForcedLogout()) {
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
        if (isLocalFallbackSession()) {
            val requestGeneration = localSessionMutationGuard.snapshot()
            return refreshPersistedLocalFallbackSessionIfNeeded().map { token ->
                val accepted = localSessionMutationGuard.commitIfCurrent(requestGeneration,
                    eligible = { !isForcedLogout() && authPrefsOrNull?.getString(KEY_ACCESS_TOKEN, null)?.trim() == token }) {
                    recoverPersistedLocalFallbackSession()?.let { _currentSession.value = it }
                }
                if (!accepted) throw CancellationException("La cuenta cambió durante la renovación.")
                token
            }
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

    suspend fun clearLocalDataForAccountSwitch(
        accountId: String? = getCurrentUserId(), expectedGeneration: Long? = null,
    ) = withContext(Dispatchers.IO) {
        localSessionActivationMutex.withLock {
            if (expectedGeneration != null && !localSessionMutationGuard.commitIfCurrent(expectedGeneration) {})
                return@withLock
            accountOutboxVault.preserveAndClear(accountId)
        }
    }

    /** Stops writes from the outgoing identity while its outbox is being archived. */
    fun beginAccountSwitch() {
        localSessionMutationGuard.invalidate {
            if (TestRuntimeOverrides.isEnabled(context)) TestRuntimeOverrides.clearSession(context)
            setForcedLogout(true)
            _currentSession.value = null
            _isInitializing.value = false
            localSessionRefreshLastAttemptAtMs = 0L
            localSessionRefreshLastSuccessAtMs = 0L
            authScope.launch {
                runCatching { localCompatibilitySessionMutex.withLock {
                    if (isForcedLogout()) supabaseConfig.client.auth.clearSession()
                } }.onFailure { Log.w("AuthService", "Could not clear outgoing compatibility session", it) }
            }
        }
    }

    suspend fun restorePendingWorkForCurrentAccount() = withContext(Dispatchers.IO) {
        localSessionActivationMutex.withLock {
            val generation = localSessionMutationGuard.snapshot()
            val accountId = getCurrentUserId()?.trim()?.lowercase()
                ?: throw IllegalStateException("No se pudo identificar la cuenta para restaurar mensajes")
            if (!localSessionMutationGuard.commitIfCurrent(generation) {})
                throw CancellationException("La cuenta cambió antes de restaurar sus mensajes.")
            accountOutboxVault.restore(accountId)
            if (!localSessionMutationGuard.commitIfCurrent(generation) {})
                throw CancellationException("La cuenta cambió al restaurar sus mensajes.")
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
            .remove(KEY_LOCAL_FALLBACK_SESSION)
            .remove(KEY_LOCAL_FALLBACK_LOCAL_USER_ID)
            .remove(KEY_LOCAL_FALLBACK_DISPLAY_USER_ID)
            .remove(KEY_LOCAL_FALLBACK_SUPABASE_USER_ID)
            .remove(KEY_LOCAL_FALLBACK_EMAIL)
            .remove(KEY_LOCAL_FALLBACK_DISPLAY_NAME)
            .remove(KEY_LOCAL_FALLBACK_AVATAR_URL)
            .remove(KEY_LOCAL_FALLBACK_COUNTRY)
            .remove(KEY_LOCAL_FALLBACK_ACCOUNT_CREATED_AT)
            .apply()
    }

    private fun clearPersistedSessionSnapshot() {
        var prefs = authPrefsOrNull ?: return
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_LOCAL_FALLBACK_SESSION)
            .remove(KEY_LOCAL_FALLBACK_LOCAL_USER_ID)
            .remove(KEY_LOCAL_FALLBACK_DISPLAY_USER_ID)
            .remove(KEY_LOCAL_FALLBACK_SUPABASE_USER_ID)
            .remove(KEY_LOCAL_FALLBACK_EMAIL)
            .remove(KEY_LOCAL_FALLBACK_DISPLAY_NAME)
            .remove(KEY_LOCAL_FALLBACK_AVATAR_URL)
            .remove(KEY_LOCAL_FALLBACK_COUNTRY)
            .remove(KEY_LOCAL_FALLBACK_ACCOUNT_CREATED_AT)
            .apply()
    }

    fun isLocalFallbackSession(): Boolean {
        val prefs = authPrefsOrNull ?: return false
        if (isForcedLogout()) return false
        val hasPersistedLocalSession = prefs.getBoolean(KEY_LOCAL_FALLBACK_SESSION, false) &&
            !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank() &&
            !prefs.getString(KEY_LOCAL_FALLBACK_EMAIL, null).isNullOrBlank()
        if (hasPersistedLocalSession) return true

        val activeSession = _currentSession.value
        if (
            activeSession != null &&
            isLocalFallbackStoredSessionToken(activeSession.accessToken, activeSession.refreshToken)
        ) {
            return true
        }

        return isLocalFallbackStoredSessionToken(
            prefs.getString(KEY_ACCESS_TOKEN, null).orEmpty(),
            prefs.getString(KEY_REFRESH_TOKEN, null).orEmpty()
        ) && !prefs.getString(KEY_LOCAL_FALLBACK_EMAIL, null).isNullOrBlank()
    }

    private suspend fun refreshPersistedLocalFallbackSessionIfNeeded(
        force: Boolean = false
    ): Result<String> = localSessionRefreshMutex.withLock {
        val requestGeneration = localSessionMutationGuard.snapshot()
        if (!isLocalFallbackModeEnabled() || !isLocalFallbackSession()) {
            return@withLock Result.failure(Exception("No hay una sesión activa."))
        }

        val prefs = authPrefsOrNull
            ?: return@withLock Result.failure(Exception("El almacenamiento seguro no está disponible."))
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
            .ifBlank { accessToken }
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            return@withLock Result.failure(Exception("No hay una sesión activa."))
        }

        val now = System.currentTimeMillis()
        val accessNeedsRefresh = isTokenExpiredOrExpiringSoon(accessToken, withinSeconds = 10 * 60L)
        val recentlyValidated = now - localSessionRefreshLastSuccessAtMs < 12 * 60 * 60_000L
        val recentlyAttempted = now - localSessionRefreshLastAttemptAtMs < 60_000L
        if (!force && !accessNeedsRefresh && (recentlyValidated || recentlyAttempted)) {
            return@withLock Result.success(accessToken)
        }

        localSessionRefreshLastAttemptAtMs = now
        val refreshed = localFallbackApiClient.refreshSession(
            refreshToken = refreshToken,
            accessToken = accessToken
        )
        val refreshedResponse = refreshed.getOrNull()
        // The HTTP request can finish after logout or a switch to another account.
        // Neither its success nor its failure may update that account's state.
        val requestStillCurrent = localSessionMutationGuard.commitIfCurrent(
            requestGeneration,
            eligible = { !isForcedLogout() && prefs.getString(KEY_ACCESS_TOKEN, null)?.trim() == accessToken },
            mutation = {},
        )
        if (!requestStillCurrent) return@withLock Result.failure(CancellationException("La cuenta cambió durante la renovación."))
        if (refreshedResponse != null) {
            val response = refreshedResponse
            persistLocalFallbackSession(response, expectedGeneration = requestGeneration, expectedAccessToken = accessToken)
            localSessionMutationGuard.commitIfCurrent(requestGeneration,
                eligible = { !isForcedLogout() && prefs.getString(KEY_ACCESS_TOKEN, null)?.trim() == response.accessToken.trim() }) {
                localSessionRefreshLastSuccessAtMs = System.currentTimeMillis()
            }
        }
        return@withLock refreshed.fold(
            onSuccess = { response ->
                val current = localSessionMutationGuard.commitIfCurrent(requestGeneration,
                    eligible = { !isForcedLogout() && prefs.getString(KEY_ACCESS_TOKEN, null)?.trim() == response.accessToken.trim() },
                    mutation = {})
                if (current) Result.success(response.accessToken.trim())
                else Result.failure(CancellationException("La cuenta cambió durante la renovación."))
            },
            onFailure = { error ->
                if (error is LocalFallbackSessionRenewalRequiredException) {
                    localSessionMutationGuard.commitIfCurrent(requestGeneration,
                        eligible = { !isForcedLogout() && prefs.getString(KEY_ACCESS_TOKEN, null)?.trim() == accessToken },
                        mutation = { forceLocalLogout() })
                    Result.failure(
                        Exception(
                            "Por seguridad, esta sesión antigua no pudo renovarse. Inicia sesión una sola vez.",
                            error
                        )
                    )
                } else if (isTokenExpiredOrExpiringSoon(accessToken, withinSeconds = 0L)) {
                    Result.failure(Exception("No pudimos renovar tu sesión. Revisa tu conexión e inténtalo otra vez.", error))
                } else {
                    // A transient outage must not log out a user whose access token
                    // is still locally valid. The next foreground request retries.
                    Result.success(accessToken)
                }
            }
        )
    }

    private suspend fun persistLocalFallbackSession(
        response: LocalFallbackAuthResponse,
        publishSession: Boolean = true,
        expectedGeneration: Long = localSessionMutationGuard.snapshot(),
        expectedAccessToken: String? = null,
        transition: AuthSessionMutationGuard.Transition? = null,
        syncCompatibility: Boolean = true,
    ) {
        val user = response.user
        val accessToken = response.accessToken.trim()
        val refreshToken = response.refreshToken.trim().ifBlank { accessToken }
        val localUserId = user.id.trim().lowercase()
        val displayUserId = localFallbackDisplayUserId(user)
        val email = user.email.trim().lowercase()
        check(accessToken.isNotBlank() && response.refreshToken.isNotBlank() && localUserId.isNotBlank() &&
            displayUserId.isNotBlank() && email.isNotBlank()) { "No se pudo guardar una sesión completa." }

        val prefs = authPrefsOrNull
            ?: throw IllegalStateException("El almacenamiento seguro no está disponible.")
        val stored = withContext(Dispatchers.IO) {
          localSessionActivationMutex.withLock {
            val eligible = { expectedAccessToken == null ||
                (!isForcedLogout() && prefs.getString(KEY_ACCESS_TOKEN, null)?.trim() == expectedAccessToken) }
            val requestCurrent = if (transition != null) localSessionMutationGuard.isCurrent(transition)
                else localSessionMutationGuard.commitIfCurrent(expectedGeneration, eligible) {}
            if (!requestCurrent) throw CancellationException("La cuenta cambió antes de abrir sus mensajes.")
            accountOutboxVault.requireCompatibleAccount(displayUserId)
            var persisted = false
            val write = {
                persisted = prefs.edit()
                .apply {
                    if (!displayUserId.equals(prefs.getString(KEY_LOCAL_FALLBACK_DISPLAY_USER_ID, null), ignoreCase = true))
                        remove(KEY_LOCAL_FALLBACK_COUNTRY)
                }
                .putBoolean(KEY_FORCED_LOGOUT, false)
                .putBoolean(KEY_LOCAL_FALLBACK_SESSION, true)
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putString(KEY_LOCAL_FALLBACK_LOCAL_USER_ID, localUserId)
                .putString(KEY_LOCAL_FALLBACK_DISPLAY_USER_ID, displayUserId)
                .putString(KEY_LOCAL_FALLBACK_SUPABASE_USER_ID, user.supabaseUserId?.trim()?.lowercase())
                .putString(KEY_LOCAL_FALLBACK_EMAIL, email)
                .putString(KEY_LOCAL_FALLBACK_DISPLAY_NAME, user.displayName?.trim())
                .putString(KEY_LOCAL_FALLBACK_AVATAR_URL, user.avatarUrl?.trim())
                .putString(KEY_LOCAL_FALLBACK_ACCOUNT_CREATED_AT, user.accountCreatedAt ?: user.createdAt)
                .commit()
                if (persisted && publishSession && !registrationInProgress) {
                    _currentSession.value = mapLocalFallbackSession(response)
                }
            }
            val accepted = if (transition != null) localSessionMutationGuard.advanceTransition(transition, mutation = write)
                else localSessionMutationGuard.commitIfCurrent(expectedGeneration, eligible) {
                    // An explicit login also invalidates any pending switch or background result.
                    if (expectedAccessToken == null) localSessionMutationGuard.invalidate {}
                    write()
                }
            if (!accepted) throw CancellationException("La cuenta cambió antes de guardar la sesión.")
            persisted
          }
        }
        if (!stored) {
            throw IllegalStateException("No se pudo guardar la sesión de forma segura en este dispositivo.")
        }

        if (syncCompatibility) syncLocalFallbackCompatibilitySession(accessToken)
        else authScope.launch {
            runCatching { syncLocalFallbackCompatibilitySession(accessToken) }
                .onFailure { Log.w("AuthService", "Could not restore compatibility session in background", it) }
        }
        authScope.launch { retryPendingLogoutRevocations() }
    }

    private suspend fun syncLocalFallbackCompatibilitySession(accessToken: String) = localCompatibilitySessionMutex.withLock {
        val importGeneration = localSessionMutationGuard.snapshot()
        val token = accessToken.trim()
        if (!isLocalFallbackModeEnabled() || token.isBlank() || isForcedLogout() ||
            authPrefsOrNull?.getString(KEY_ACCESS_TOKEN, null)?.trim() != token) return@withLock

        val expiresAtSeconds = extractJwtClaim(token, "exp")?.toLongOrNull()
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val expiresInSeconds = (expiresAtSeconds?.minus(nowSeconds) ?: 7 * 24 * 60 * 60L)
            .coerceAtLeast(60L)
        val compatibilitySession = UserSession(
            accessToken = token,
            refreshToken = token,
            expiresIn = expiresInSeconds,
            tokenType = "Bearer",
            user = null,
            type = "local-fallback"
        )
        supabaseConfig.client.auth.importSession(compatibilitySession, autoRefresh = false)
        // importSession suspends. A switch/logout can invalidate it after the
        // pre-check; clear the outgoing SDK JWT before any new import gets the mutex.
        val accepted = localSessionMutationGuard.commitIfCurrent(importGeneration,
            eligible = { !isForcedLogout() && authPrefsOrNull?.getString(KEY_ACCESS_TOKEN, null)?.trim() == token },
            mutation = {})
        if (!accepted) supabaseConfig.client.auth.clearSession()
    }

    private fun mapLocalFallbackSession(response: LocalFallbackAuthResponse): AuthResponse {
        val user = response.user
        val displayUserId = localFallbackDisplayUserId(user)
        val email = user.email.trim().lowercase()
        return AuthResponse(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken.trim().ifBlank { response.accessToken },
            tokenType = response.tokenType.ifBlank { "Bearer" },
            expiresIn = 7 * 24 * 60 * 60,
            user = UserData(
                id = displayUserId,
                email = email,
                metadata = kotlinx.serialization.json.buildJsonObject {
                    put("fallback_auth", kotlinx.serialization.json.JsonPrimitive(true))
                    put("local_fallback_id", kotlinx.serialization.json.JsonPrimitive(user.id))
                    user.supabaseUserId?.takeIf { it.isNotBlank() }?.let {
                        put("supabase_user_id", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    user.displayName?.takeIf { it.isNotBlank() }?.let {
                        put("display_name", kotlinx.serialization.json.JsonPrimitive(it))
                        put("full_name", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    user.avatarUrl?.takeIf { it.isNotBlank() }?.let {
                        put("avatar_url", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    user.accountCreatedAt?.takeIf { it.isNotBlank() }?.let {
                        put("account_created_at", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                }
            )
        )
    }

    private fun recoverPersistedLocalFallbackSession(): AuthResponse? {
        if (!isLocalFallbackSession()) return null
        val prefs = authPrefsOrNull ?: return null
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
        val displayUserId = localFallbackDisplayUserIdFromPrefs() ?: return null
        val email = localFallbackEmailFromPrefs() ?: return null
        val displayName = prefs.getString(KEY_LOCAL_FALLBACK_DISPLAY_NAME, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val avatarUrl = prefs.getString(KEY_LOCAL_FALLBACK_AVATAR_URL, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val country = prefs.getString(KEY_LOCAL_FALLBACK_COUNTRY, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val accountCreatedAt = prefs.getString(KEY_LOCAL_FALLBACK_ACCOUNT_CREATED_AT, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return AuthResponse(
            accessToken = accessToken,
            refreshToken = refreshToken.ifBlank { accessToken },
            tokenType = "Bearer",
            expiresIn = 7 * 24 * 60 * 60,
            user = UserData(
                id = displayUserId,
                email = email,
                metadata = kotlinx.serialization.json.buildJsonObject {
                    put("fallback_auth", kotlinx.serialization.json.JsonPrimitive(true))
                    displayName?.let {
                        put("display_name", kotlinx.serialization.json.JsonPrimitive(it))
                        put("full_name", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    avatarUrl?.let {
                        put("avatar_url", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    country?.let {
                        put("country", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    accountCreatedAt?.let {
                        put("account_created_at", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                }
            )
        )
    }

    private fun getLocalFallbackProfileSeed(): CurrentUserProfileSeed? =
        localSessionMutationGuard.readCurrent { readLocalFallbackProfileSeed() }

    private fun readLocalFallbackProfileSeed(): CurrentUserProfileSeed? {
        if (!isLocalFallbackSession()) return null
        val prefs = authPrefsOrNull ?: return null
        val displayName = prefs.getString(KEY_LOCAL_FALLBACK_DISPLAY_NAME, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val email = localFallbackEmailFromPrefs() ?: return null
        return CurrentUserProfileSeed(
            id = localFallbackDisplayUserIdFromPrefs() ?: return null,
            email = email,
            fullName = displayName,
            displayName = displayName ?: email.substringBefore("@"),
            avatarUrl = prefs.getString(KEY_LOCAL_FALLBACK_AVATAR_URL, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() },
            country = prefs.getString(KEY_LOCAL_FALLBACK_COUNTRY, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() },
            privacyPolicyAccepted = true,
            privacyPolicyAcceptedAt = prefs.getString(KEY_LOCAL_FALLBACK_ACCOUNT_CREATED_AT, null)
        )
    }

    private fun localFallbackDisplayUserId(user: LocalFallbackUser): String {
        return user.supabaseUserId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: user.id.trim().lowercase()
    }

    private fun localFallbackDisplayUserIdFromPrefs(): String? {
        if (!isLocalFallbackSession()) return null
        val prefs = authPrefsOrNull ?: return null
        return prefs.getString(KEY_LOCAL_FALLBACK_DISPLAY_USER_ID, null)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_LOCAL_FALLBACK_SUPABASE_USER_ID, null)
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_LOCAL_FALLBACK_LOCAL_USER_ID, null)
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
    }

    private fun localFallbackEmailFromPrefs(): String? {
        if (!isLocalFallbackSession()) return null
        return authPrefsOrNull
            ?.getString(KEY_LOCAL_FALLBACK_EMAIL, null)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
    }

    private fun readLocalFallbackAccessToken(): String? {
        if (!isLocalFallbackSession()) return null
        return authPrefsOrNull
            ?.getString(KEY_ACCESS_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}





