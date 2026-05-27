package conectate.com.core.testing

import android.content.Context
import conectate.com.BuildConfig
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.model.User
import conectate.com.data.supabase.AuthResponse
import conectate.com.data.supabase.UserData
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class RuntimeSessionOverride(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val displayName: String? = null,
    val avatarUrl: String? = null
) {
    fun toAuthResponse(): AuthResponse {
        return AuthResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = "Bearer",
            expiresIn = 3600,
            user = UserData(
                id = userId,
                email = email
            )
        )
    }

    fun toUser(): User {
        return User(
            id = userId,
            email = email,
            display_name = displayName,
            full_name = displayName,
            avatar_url = avatarUrl,
            privacy_policy_accepted = true
        )
    }
}

object TestRuntimeOverrides {
    const val PREFS_NAME = "test_runtime_prefs"
    const val ENABLE_AUTH_FIXTURES_KEY = "enable_auth_fixtures"
    const val SESSION_OVERRIDE_KEY = "auth_session_override_json"
    const val STORED_ACCOUNTS_OVERRIDE_KEY = "stored_accounts_override_json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun isEnabled(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        return prefs(context).getBoolean(ENABLE_AUTH_FIXTURES_KEY, false)
    }

    fun currentSession(context: Context): RuntimeSessionOverride? {
        if (!isEnabled(context)) return null
        var raw = prefs(context).getString(SESSION_OVERRIDE_KEY, null)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString<RuntimeSessionOverride>(raw) }.getOrNull()
    }

    fun currentUser(context: Context): User? {
        return currentSession(context)?.toUser()
    }

    fun setCurrentSession(context: Context, session: RuntimeSessionOverride?) {
        if (!BuildConfig.DEBUG) return
        val editor = prefs(context).edit()
        if (session == null) {
            editor.remove(SESSION_OVERRIDE_KEY)
        } else {
            editor.putBoolean(ENABLE_AUTH_FIXTURES_KEY, true)
            editor.putString(SESSION_OVERRIDE_KEY, json.encodeToString(session))
        }
        editor.apply()
    }

    fun clearSession(context: Context) {
        if (!BuildConfig.DEBUG) return
        prefs(context).edit().remove(SESSION_OVERRIDE_KEY).apply()
    }

    fun storedAccounts(context: Context): List<StoredAccountSession>? {
        if (!isEnabled(context)) return null
        var raw = prefs(context).getString(STORED_ACCOUNTS_OVERRIDE_KEY, null)?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredAccountSession>>(raw) }.getOrDefault(emptyList())
    }

    fun setStoredAccounts(context: Context, accounts: List<StoredAccountSession>) {
        if (!BuildConfig.DEBUG) return
        prefs(context).edit()
            .putBoolean(ENABLE_AUTH_FIXTURES_KEY, true)
            .putString(STORED_ACCOUNTS_OVERRIDE_KEY, json.encodeToString(accounts))
            .apply()
    }

    fun activateStoredAccount(
        context: Context,
        account: StoredAccountSession
    ): RuntimeSessionOverride {
        val session = RuntimeSessionOverride(
            userId = account.user_id?.trim()?.ifBlank { null }
                ?: account.email.trim().lowercase(),
            email = account.email.trim().lowercase(),
            accessToken = account.access_token,
            refreshToken = account.refresh_token,
            displayName = account.display_name,
            avatarUrl = account.avatar_url
        )
        setCurrentSession(context, session)
        return session
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}



