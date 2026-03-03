package conectate.com.data.supabase

import android.content.Context
import android.util.Log
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val country: String
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

@Singleton
class AuthService @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val database: AppDatabase,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private const val AUTH_CACHE_PREFS = "auth_cache"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }

    // Using SharedPreferences just to track "if logged in" for simple checks if needed,
    // but primarily trusting SDK state.
    private val _currentSession = MutableStateFlow<AuthResponse?>(null)
    val currentSession: StateFlow<AuthResponse?> = _currentSession.asStateFlow()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()
    private val authPrefs by lazy { context.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE) }

    private fun isUsableSession(session: UserSession?): Boolean {
        val sessionUserId = session?.user?.id?.trim()?.lowercase().orEmpty()
        val accessToken = session?.accessToken?.trim().orEmpty()
        return sessionUserId.isNotBlank() && accessToken.isNotBlank()
    }

    private fun mapSession(session: UserSession): AuthResponse {
        val user = session.user
        return AuthResponse(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            tokenType = session.tokenType,
            expiresIn = session.expiresIn.toInt(),
            user = UserData(
                id = (user?.id ?: "").trim().lowercase(),
                email = user?.email?.trim()?.lowercase() ?: ""
            )
        )
    }

    init {
        // Fast-path recovery from SDK cache first.
        var bootSession = runCatching { supabaseConfig.client.auth.currentSessionOrNull() }.getOrNull()
        if (!isUsableSession(bootSession)) {
            bootSession = null
        }

        // Fallback recovery from lightweight local cache (covers slow/offline edge cases).
        if (bootSession == null) {
            bootSession = recoverPersistedSession()
        }

        bootSession?.let { cachedSession ->
            _currentSession.value = mapSession(cachedSession)
            persistSessionSnapshot(cachedSession)
            _isInitializing.value = false
            Log.d("AuthService", "Cached session recovered at startup")
        }

        // Observe SDK session changes with a defensive timeout for slow networks/emulators
        CoroutineScope(Dispatchers.IO).launch {
            val sessionJob = launch {
                supabaseConfig.client.auth.sessionStatus.collect { status ->
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
                            val safeSession = cachedSession ?: return@collect
                            _currentSession.value = mapSession(safeSession)
                            persistSessionSnapshot(safeSession)
                            Log.d("AuthService", "Session status changed but cached session is still valid")
                        } else {
                            _currentSession.value = null
                            clearPersistedSessionSnapshot()
                            Log.d("AuthService", "SDK Session Cleared/Not Authenticated")
                        }
                    }
                    // Signal initialization complete after first status check.
                    _isInitializing.value = false
                }
            }
            
            // TIMEOUT SAFEGUARD: keep as a final fallback only.
            // If Supabase client takes too long to emit first status (e.g. network hang), force finish init
            launch {
                kotlinx.coroutines.delay(20000) // allow slower mobile-data startup before forcing login route
                if (_isInitializing.value) {
                    Log.w("AuthService", "WARNING: Auth initialization timed out. Forcing completion.")
                    _isInitializing.value = false
                }
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
            supabaseConfig.client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                // metadata passing might need generic map
            }
            
            // Return current session if successful
            val session = supabaseConfig.client.auth.currentSessionOrNull() 
                ?: return Result.failure(Exception("Sign up successful but no session returned"))
            
             val authResponse = AuthResponse(
                 accessToken = session.accessToken,
                 refreshToken = session.refreshToken,
                 tokenType = session.tokenType,
                 expiresIn = session.expiresIn.toInt(),
                 user = UserData(
                     (session.user?.id ?: "").trim().lowercase(), 
                     (session.user?.email ?: "").trim().lowercase()
                 )
             )
            Result.success(authResponse)
        } catch (e: Exception) {
            Log.e("AuthService", "Signup error", e)
            Result.failure(e)
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> {
        return try {
            supabaseConfig.client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }

            // Force eager session refresh to avoid blank screen after login until process restart.
            var session = supabaseConfig.client.auth.currentSessionOrNull()
            repeat(10) {
                if (isUsableSession(session)) return@repeat
                delay(150)
                session = supabaseConfig.client.auth.currentSessionOrNull()
            }
            if (isUsableSession(session)) {
                val safeSession = session ?: return Result.success(Unit)
                _currentSession.value = mapSession(safeSession)
                persistSessionSnapshot(safeSession)
            } else {
                // Last-resort read from current user so MainActivity can route correctly.
                val uid = supabaseConfig.client.auth.currentUserOrNull()?.id?.trim()?.lowercase()
                val mail = supabaseConfig.client.auth.currentUserOrNull()?.email?.trim()?.lowercase()
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
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthService", "Login error", e)
            Result.failure(e)
        }
    }

    suspend fun signOut() {
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

    suspend fun updateUserMetadata(fullName: String?, avatarUrl: String?): Result<Unit> {
        return try {
            Log.d("AuthService", "Updating user metadata: name=$fullName, avatar=$avatarUrl")
            supabaseConfig.client.auth.modifyUser {
                data = kotlinx.serialization.json.buildJsonObject {
                    fullName?.let { put("full_name", kotlinx.serialization.json.JsonPrimitive(it)) }
                    avatarUrl?.let { put("avatar_url", kotlinx.serialization.json.JsonPrimitive(it)) }
                }
            }
            Log.d("AuthService", "User metadata updated successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthService", "Update metadata error: ${e.message}", e)
            Result.failure(e)
        }
    }


    suspend fun importSession(accessToken: String, refreshToken: String) {
        try {
            Log.d("AuthService", "Importing session manually...")
            val session = UserSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = 3600, // Default 1 hour validity if unknown
                tokenType = "Bearer",
                user = null,
                type = "" // Empty string for type if required, or omit if default exists
            )
            supabaseConfig.client.auth.importSession(session)
            persistSessionSnapshot(session)
            Log.d("AuthService", "Session imported successfully")
        } catch (e: Exception) {
            Log.e("AuthService", "Error importing session", e)
            throw e
        }
    }

    /* -------------------- HELPERS -------------------- */

    fun getCurrentUserId(): String? {
        val sdkUserId = supabaseConfig.client.auth.currentUserOrNull()?.id?.trim()?.lowercase()
        if (!sdkUserId.isNullOrBlank()) return sdkUserId
        val cachedUserId = _currentSession.value?.user?.id?.trim()?.lowercase()
        return cachedUserId?.takeIf { it.isNotBlank() }
    }

    fun getAccessToken(): String? = supabaseConfig.client.auth.currentAccessTokenOrNull()

    fun getRefreshToken(): String? {
        return supabaseConfig.client.auth.currentSessionOrNull()?.refreshToken
            ?: authPrefs.getString(KEY_REFRESH_TOKEN, null)
    }

    fun isLoggedIn(): Boolean = getCurrentUserId() != null

    fun getCurrentUserEmail(): String? {
        val sdkEmail = supabaseConfig.client.auth.currentUserOrNull()?.email?.trim()?.lowercase()
        if (!sdkEmail.isNullOrBlank()) return sdkEmail
        val cachedEmail = _currentSession.value?.user?.email?.trim()?.lowercase()
        return cachedEmail?.takeIf { it.isNotBlank() }
    }

    fun observeSession(): StateFlow<AuthResponse?> = currentSession

    suspend fun clearLocalDataForAccountSwitch() {
        withContext(Dispatchers.IO) {
            runCatching { database.clearAllTables() }
        }
    }

    private fun recoverPersistedSession(): UserSession? {
        val accessToken = authPrefs.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        val refreshToken = authPrefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
        if (accessToken.isBlank() || refreshToken.isBlank()) return null

        return runCatching {
            val imported = UserSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = 3600,
                tokenType = "Bearer",
                user = null,
                type = ""
            )
            kotlinx.coroutines.runBlocking {
                supabaseConfig.client.auth.importSession(imported)
            }
            val restored = supabaseConfig.client.auth.currentSessionOrNull()
            if (isUsableSession(restored)) restored else null
        }.onFailure {
            Log.e("AuthService", "Persisted session recovery failed", it)
        }.getOrNull()
    }

    private fun persistSessionSnapshot(session: UserSession) {
        val accessToken = session.accessToken.trim()
        val refreshToken = session.refreshToken.trim()
        if (accessToken.isBlank() || refreshToken.isBlank()) return
        authPrefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    private fun clearPersistedSessionSnapshot() {
        authPrefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }
}
