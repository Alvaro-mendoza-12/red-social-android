package conectate.com.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authService: AuthService
) {
    companion object {
        private const val PREFS = "account_switch_secure_prefs"
        private const val KEY_ACCOUNTS = "stored_accounts_json"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs by lazy { createSecurePrefs() }

    private val _accounts = MutableStateFlow(loadAccounts())
    val accounts: StateFlow<List<StoredAccountSession>> = _accounts.asStateFlow()

    suspend fun captureCurrentAccount(
        displayName: String? = null,
        avatarUrl: String? = null
    ): Result<StoredAccountSession> = withContext(Dispatchers.IO) {
        val access = authService.getAccessToken()?.trim().orEmpty()
        val refresh = authService.getRefreshToken()?.trim().orEmpty()
        if (access.isBlank() || refresh.isBlank()) {
            return@withContext Result.failure(Exception("No hay sesión activa para guardar"))
        }

        val userId = authService.getCurrentUserId()?.trim()?.lowercase()
        val email = authService.getCurrentUserEmail()?.trim()?.lowercase().orEmpty()
        if (email.isBlank()) {
            return@withContext Result.failure(Exception("No se pudo resolver email de sesión"))
        }

        val entry = StoredAccountSession(
            user_id = userId,
            email = email,
            access_token = access,
            refresh_token = refresh,
            display_name = displayName?.trim()?.ifBlank { null },
            avatar_url = avatarUrl?.trim()?.ifBlank { null },
            last_used_at = System.currentTimeMillis()
        )

        val updated = upsert(entry, _accounts.value)
            .sortedByDescending { it.last_used_at }
            .take(6)
        _accounts.value = updated
        persist(updated)
        Result.success(entry)
    }

    suspend fun switchAccount(accountId: String): Result<StoredAccountSession> = withContext(Dispatchers.IO) {
        val clean = accountId.trim()
        if (clean.isBlank()) return@withContext Result.failure(Exception("Cuenta inválida"))
        val target = _accounts.value.firstOrNull { it.id == clean }
            ?: return@withContext Result.failure(Exception("Cuenta no encontrada"))

        return@withContext runCatching {
            authService.importSession(target.access_token, target.refresh_token)

            val activeUserId = authService.getCurrentUserId()?.trim()?.lowercase()
            val activeEmail = authService.getCurrentUserEmail()?.trim()?.lowercase()
            val isExpectedSession =
                (!target.user_id.isNullOrBlank() && !activeUserId.isNullOrBlank() && target.user_id == activeUserId) ||
                    (!activeEmail.isNullOrBlank() && target.email.equals(activeEmail, ignoreCase = true))

            if (!isExpectedSession) {
                throw IllegalStateException("La sesión importada no coincide con la cuenta solicitada")
            }

            val touched = target.copy(
                user_id = activeUserId ?: target.user_id,
                email = activeEmail ?: target.email,
                access_token = authService.getAccessToken()?.trim()?.ifBlank { target.access_token } ?: target.access_token,
                refresh_token = authService.getRefreshToken()?.trim()?.ifBlank { target.refresh_token } ?: target.refresh_token,
                last_used_at = System.currentTimeMillis()
            )
            val updated = upsert(touched, _accounts.value).sortedByDescending { it.last_used_at }
            _accounts.value = updated
            persist(updated)
            touched
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it as? Exception ?: Exception("No se pudo cambiar de cuenta")) }
        )
    }

    fun removeAccount(accountId: String) {
        val clean = accountId.trim()
        if (clean.isBlank()) return
        val updated = _accounts.value.filterNot { it.id == clean }
        _accounts.value = updated
        persist(updated)
    }

    fun removeCurrentAccount() {
        val uid = authService.getCurrentUserId()?.trim()?.lowercase()
        val email = authService.getCurrentUserEmail()?.trim()?.lowercase()
        val updated = _accounts.value.filterNot { entry ->
            (!uid.isNullOrBlank() && entry.user_id == uid) ||
                (!email.isNullOrBlank() && entry.email.equals(email, ignoreCase = true))
        }
        _accounts.value = updated
        persist(updated)
    }

    private fun upsert(entry: StoredAccountSession, source: List<StoredAccountSession>): List<StoredAccountSession> {
        val updated = source.toMutableList()
        val idx = updated.indexOfFirst {
            (!entry.user_id.isNullOrBlank() && it.user_id == entry.user_id) ||
                it.email.equals(entry.email, ignoreCase = true)
        }
        if (idx >= 0) {
            val old = updated[idx]
            updated[idx] = entry.copy(
                id = old.id,
                display_name = entry.display_name ?: old.display_name,
                avatar_url = entry.avatar_url ?: old.avatar_url
            )
        } else {
            updated.add(entry)
        }
        return updated
    }

    private fun loadAccounts(): List<StoredAccountSession> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StoredAccountSession>>(raw) }.getOrDefault(emptyList())
    }

    private fun persist(list: List<StoredAccountSession>) {
        prefs.edit().putString(KEY_ACCOUNTS, json.encodeToString(list)).apply()
    }

    private fun createSecurePrefs() = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
