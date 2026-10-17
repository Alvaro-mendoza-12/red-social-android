package conectate.com.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.core.testing.TestRuntimeOverrides
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountSessionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authService: AuthService
) {
    private data class ActiveSessionSnapshot(
        val userId: String?,
        val email: String,
        val accessToken: String,
        val refreshToken: String,
        val displayName: String?,
        val avatarUrl: String?
    )

    private data class ImportedSessionIdentity(
        val userId: String?,
        val email: String?,
        val accessToken: String,
        val refreshToken: String
    )

    private data class SecurePrefsState(
        val prefs: SharedPreferences?,
        val initializationError: Throwable?
    )

    companion object {
        private const val PREFS = "account_switch_secure_prefs"
        private const val KEY_ACCOUNTS = "stored_accounts_json"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val securePrefsState by lazy { createSecurePrefsState() }
    private val prefsOrNull: SharedPreferences?
        get() = securePrefsState.prefs

    private val _accounts = MutableStateFlow(loadAccounts())
    val accounts: StateFlow<List<StoredAccountSession>> = _accounts.asStateFlow()
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("AccountSessionRepository", "Stored account sync failed", throwable)
        }
    )

    init {
        repositoryScope.launch {
            authService.observeSession().collect { session ->
                val snapshot = buildActiveSessionSnapshot(session) ?: return@collect
                syncActiveSessionSnapshot(snapshot)
            }
        }
    }

    fun ensureSecureStorageAvailable(): Result<Unit> {
        if (TestRuntimeOverrides.isEnabled(context)) {
            return Result.success(Unit)
        }
        val prefs = prefsOrNull
        return if (prefs != null) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "El almacenamiento seguro no está disponible para el cambio rápido de cuenta.",
                    securePrefsState.initializationError
                )
            )
        }
    }

    suspend fun captureCurrentAccount(
        displayName: String? = null,
        avatarUrl: String? = null
    ): Result<StoredAccountSession> = withContext(Dispatchers.IO) {
        ensureSecureStorageAvailable().getOrElse { return@withContext Result.failure(it) }
        val access = authService.getAccessTokenAwait()?.trim().orEmpty()
        val refresh = authService.getRefreshToken()?.trim().orEmpty()
        if (access.isBlank() || refresh.isBlank()) {
            return@withContext Result.failure(Exception("No hay sesión activa para guardar"))
        }

        var userId = authService.getCurrentUserId()?.trim()?.lowercase()
        var email = authService.getCurrentUserEmail()?.trim()?.lowercase().orEmpty()
        if (email.isBlank()) {
            return@withContext Result.failure(Exception("No se pudo resolver email de sesión"))
        }

        var entry = StoredAccountSession(
            user_id = userId,
            email = email,
            access_token = access,
            refresh_token = refresh,
            display_name = displayName?.trim()?.ifBlank { null },
            avatar_url = avatarUrl?.trim()?.ifBlank { null },
            last_used_at = System.currentTimeMillis()
        )

        Result.success(storeAccountSnapshot(entry))
    }

    suspend fun switchAccount(accountId: String): Result<StoredAccountSession> = withContext(Dispatchers.IO) {
        ensureSecureStorageAvailable().getOrElse { return@withContext Result.failure(it) }
        var clean = accountId.trim()
        if (clean.isBlank()) return@withContext Result.failure(Exception("Cuenta inválida"))
        val target = _accounts.value.firstOrNull { it.id == clean }
            ?: return@withContext Result.failure(Exception("Cuenta no encontrada"))

        if (TestRuntimeOverrides.isEnabled(context)) {
            var touched = target.copy(last_used_at = System.currentTimeMillis())
            var updated = upsert(touched, _accounts.value).sortedByDescending { it.last_used_at }
            TestRuntimeOverrides.activateStoredAccount(context, touched)
            _accounts.value = updated
            persist(updated)
            return@withContext Result.success(touched)
        }

        return@withContext runCatching {
            if (authService.isLocalFallbackStoredSessionToken(target.access_token, target.refresh_token)) {
                authService.importLocalFallbackStoredSession(
                    accessToken = target.access_token,
                    refreshToken = target.refresh_token,
                    email = target.email,
                    userId = target.user_id,
                    displayName = target.display_name,
                    avatarUrl = target.avatar_url
                ).getOrThrow()

                return@runCatching storeAccountSnapshot(
                    target.copy(last_used_at = System.currentTimeMillis())
                )
            }

            if (authService.isLocalFallbackModeEnabled()) {
                throw IllegalStateException("Esta cuenta guardada pertenece a Supabase. Ingresa con correo y contraseña para activarla en Ubuntu.")
            }

            authService.importSession(target.access_token, target.refresh_token)
            var expectedUserId = target.user_id?.trim()?.lowercase()
                ?: extractJwtClaim(target.access_token, "sub")?.lowercase()
            val expectedEmail = target.email.trim().lowercase().ifBlank {
                extractJwtClaim(target.access_token, "email")?.lowercase().orEmpty()
            }
            val importedIdentity = resolveImportedSessionIdentity(target)
            val activeUserId = importedIdentity.userId
            val activeEmail = importedIdentity.email
            val isExpectedSession =
                (!expectedUserId.isNullOrBlank() && !activeUserId.isNullOrBlank() && expectedUserId == activeUserId) ||
                    (!expectedEmail.isNullOrBlank() && expectedEmail.equals(activeEmail, ignoreCase = true))

            if (!isExpectedSession) {
                throw IllegalStateException("La sesión importada no coincide con la cuenta solicitada")
            }

            var touched = target.copy(
                user_id = activeUserId ?: target.user_id,
                email = activeEmail ?: target.email,
                access_token = importedIdentity.accessToken,
                refresh_token = importedIdentity.refreshToken,
                last_used_at = System.currentTimeMillis()
            )
            storeAccountSnapshot(touched)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it as? Exception ?: Exception("No se pudo cambiar de cuenta")) }
        )
    }

    private suspend fun resolveImportedSessionIdentity(
        fallback: StoredAccountSession
    ): ImportedSessionIdentity {
        repeat(10) {
            var accessToken = authService.getAccessToken()?.trim()?.ifBlank { null } ?: fallback.access_token
            var refreshToken = authService.getRefreshToken()?.trim()?.ifBlank { null } ?: fallback.refresh_token
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
            val currentEmail = authService.getCurrentUserEmail()?.trim()?.lowercase()
            val tokenUserId = extractJwtClaim(accessToken, "sub")?.lowercase()
            val tokenEmail = extractJwtClaim(accessToken, "email")?.lowercase()
            val resolvedUserId = currentUserId ?: tokenUserId ?: fallback.user_id?.trim()?.lowercase()
            val resolvedEmail = currentEmail ?: tokenEmail ?: fallback.email.trim().lowercase()
            if (!resolvedUserId.isNullOrBlank() || !resolvedEmail.isNullOrBlank()) {
                return ImportedSessionIdentity(
                    userId = resolvedUserId,
                    email = resolvedEmail,
                    accessToken = accessToken,
                    refreshToken = refreshToken
                )
            }
            delay(120)
        }

        val fallbackAccessToken = fallback.access_token.trim()
        return ImportedSessionIdentity(
            userId = fallback.user_id?.trim()?.lowercase()
                ?: extractJwtClaim(fallbackAccessToken, "sub")?.lowercase(),
            email = fallback.email.trim().lowercase().ifBlank {
                extractJwtClaim(fallbackAccessToken, "email")?.lowercase().orEmpty()
            },
            accessToken = fallbackAccessToken,
            refreshToken = fallback.refresh_token.trim()
        )
    }

    fun removeAccount(accountId: String) {
        var clean = accountId.trim()
        if (clean.isBlank()) return
        var updated = _accounts.value.filterNot { it.id == clean }
        _accounts.value = updated
        persist(updated)
    }

    fun removeCurrentAccount() {
        val uid = authService.getCurrentUserId()?.trim()?.lowercase()
        var email = authService.getCurrentUserEmail()?.trim()?.lowercase()
        var updated = _accounts.value.filterNot { entry ->
            (!uid.isNullOrBlank() && entry.user_id == uid) ||
                (!email.isNullOrBlank() && entry.email.equals(email, ignoreCase = true))
        }
        _accounts.value = updated
        persist(updated)
    }

    private fun upsert(entry: StoredAccountSession, source: List<StoredAccountSession>): List<StoredAccountSession> {
        var updated = source.toMutableList()
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
        TestRuntimeOverrides.storedAccounts(context)?.let { return it }
        val raw = prefsOrNull?.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        var decoded = runCatching { json.decodeFromString<List<StoredAccountSession>>(raw) }.getOrDefault(emptyList())
        var sanitized = sanitizeAccounts(decoded)
        if (sanitized != decoded) {
            persist(sanitized)
        }
        return sanitized
    }

    @SuppressLint("ApplySharedPref")
    private fun persist(list: List<StoredAccountSession>) {
        var sanitized = sanitizeAccounts(list)
        if (TestRuntimeOverrides.isEnabled(context)) {
            TestRuntimeOverrides.setStoredAccounts(context, sanitized)
            return
        }
        // Session refresh credentials are security-critical. A synchronous
        // commit prevents losing the newest token when Android kills the
        // process immediately after a refresh or the user closes the app.
        prefsOrNull?.edit()?.putString(KEY_ACCOUNTS, json.encodeToString(sanitized))?.commit()
    }

    private suspend fun buildActiveSessionSnapshot(
        session: conectate.com.data.supabase.AuthResponse?
    ): ActiveSessionSnapshot? {
        session ?: return null
        var accessToken = session.accessToken.trim()
        var refreshToken = session.refreshToken.trim()
        var email = session.user.email.trim().lowercase()
        var userId = session.user.id.trim().lowercase().ifBlank {
            extractJwtClaim(accessToken, "sub")?.lowercase()
        }
        if (accessToken.isBlank() || refreshToken.isBlank() || email.isBlank()) return null

        val profileSeed = authService.getCurrentUserProfileSeed()
        return ActiveSessionSnapshot(
            userId = userId,
            email = email,
            accessToken = accessToken,
            refreshToken = refreshToken,
            displayName = profileSeed?.displayName?.trim()?.ifBlank { null }
                ?: profileSeed?.fullName?.trim()?.ifBlank { null },
            avatarUrl = profileSeed?.avatarUrl?.trim()?.ifBlank { null }
        )
    }

    private fun syncActiveSessionSnapshot(snapshot: ActiveSessionSnapshot) {
        if (!TestRuntimeOverrides.isEnabled(context) && prefsOrNull == null) return
        // A queued observer emission can arrive after a synchronous local logout.
        if (!authService.isLoggedIn() ||
            (!snapshot.userId.isNullOrBlank() && !snapshot.userId.equals(authService.getCurrentUserId(), true))) return

        var existing = _accounts.value.firstOrNull {
            (!snapshot.userId.isNullOrBlank() && it.user_id == snapshot.userId) ||
                it.email.equals(snapshot.email, ignoreCase = true)
        }
        val refreshedEntry = StoredAccountSession(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            user_id = snapshot.userId,
            email = snapshot.email,
            access_token = snapshot.accessToken,
            refresh_token = snapshot.refreshToken,
            display_name = snapshot.displayName ?: existing?.display_name,
            avatar_url = snapshot.avatarUrl ?: existing?.avatar_url,
            last_used_at = maxOf(existing?.last_used_at ?: 0L, System.currentTimeMillis())
        )
        val shouldPersist = existing == null ||
            existing.user_id != refreshedEntry.user_id ||
            !existing.email.equals(refreshedEntry.email, ignoreCase = true) ||
            existing.access_token != refreshedEntry.access_token ||
            existing.refresh_token != refreshedEntry.refresh_token ||
            existing.display_name != refreshedEntry.display_name ||
            existing.avatar_url != refreshedEntry.avatar_url

        if (!shouldPersist) return
        storeAccountSnapshot(refreshedEntry)
    }

    private fun storeAccountSnapshot(entry: StoredAccountSession): StoredAccountSession {
        var updated = upsert(entry, _accounts.value)
            .sortedByDescending { it.last_used_at }
            .take(6)
        _accounts.value = updated
        persist(updated)
        return updated.firstOrNull {
            (!entry.user_id.isNullOrBlank() && it.user_id == entry.user_id) ||
                it.email.equals(entry.email, ignoreCase = true)
        } ?: entry
    }

    private fun sanitizeAccounts(source: List<StoredAccountSession>): List<StoredAccountSession> {
        if (source.isEmpty()) return emptyList()

        return source
            .mapNotNull { account ->
                var email = account.email.trim().lowercase()
                var accessToken = account.access_token.trim()
                var refreshToken = account.refresh_token.trim()
                if (email.isBlank() || accessToken.isBlank() || refreshToken.isBlank()) {
                    return@mapNotNull null
                }

                account.copy(
                    user_id = account.user_id?.trim()?.lowercase()?.ifBlank { null },
                    email = email,
                    access_token = accessToken,
                    refresh_token = refreshToken,
                    display_name = account.display_name?.trim()?.ifBlank { null },
                    avatar_url = account.avatar_url?.trim()?.ifBlank { null }
                )
            }
            .sortedByDescending { it.last_used_at }
            .fold(mutableListOf<StoredAccountSession>()) { acc, entry ->
                val existingIndex = acc.indexOfFirst {
                    (!entry.user_id.isNullOrBlank() && it.user_id == entry.user_id) ||
                        it.email.equals(entry.email, ignoreCase = true)
                }
                if (existingIndex >= 0) {
                    var existing = acc[existingIndex]
                    acc[existingIndex] = if (entry.last_used_at >= existing.last_used_at) {
                        entry.copy(
                            id = existing.id,
                            display_name = entry.display_name ?: existing.display_name,
                            avatar_url = entry.avatar_url ?: existing.avatar_url
                        )
                    } else {
                        existing.copy(
                            display_name = existing.display_name ?: entry.display_name,
                            avatar_url = existing.avatar_url ?: entry.avatar_url
                        )
                    }
                } else {
                    acc.add(entry)
                }
                acc
            }
            .sortedByDescending { it.last_used_at }
            .take(6)
    }

    private fun extractJwtClaim(token: String, claim: String): String? {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return null
        val parts = cleanToken.split(".")
        if (parts.size < 2) return null
        return runCatching {
            val normalizedPayload = parts[1]
                .replace('-', '+')
                .replace('_', '/')
                .let { value ->
                    when (value.length % 4) {
                        2 -> "$value=="
                        3 -> "$value="
                        else -> value
                    }
                }
            var decoded = String(Base64.decode(normalizedPayload, Base64.DEFAULT))
            json.parseToJsonElement(decoded)
                .jsonObject[claim]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    @SuppressLint("ApplySharedPref") // Credential deletion must finish before secure-store initialization returns.
    private fun createSecurePrefsState(): SecurePrefsState = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        // Older builds could persist refresh credentials here without
        // encryption when Keystore initialization failed. Never migrate that
        // material; remove it once a secure store is available.
        context.getSharedPreferences("account_switch_fallback", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        SecurePrefsState(prefs = prefs, initializationError = null)
    } catch (error: Throwable) {
        android.util.Log.e("AccountSessionRepository", "Secure account storage is unavailable; quick switching is disabled", error)
        runCatching {
            context.getSharedPreferences("account_switch_fallback", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        SecurePrefsState(prefs = null, initializationError = error)
    }
}






