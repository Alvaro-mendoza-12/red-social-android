package conectate.com.data.repository

import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.localfallback.LocalFallbackPasswordResetResponse
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.model.UpdateProfileRequest
import conectate.com.data.model.User
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

import android.content.Context
import android.net.Uri
import conectate.com.core.media.ProfileMediaSync
import dagger.hilt.android.qualifiers.ApplicationContext
import conectate.com.data.supabase.StorageService
import java.util.UUID
import io.ktor.client.statement.bodyAsText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

data class AccountSwitchUiState(
    val isSwitching: Boolean = false,
    val targetAccountId: String? = null,
    val targetDisplayName: String? = null,
    val targetAvatarUrl: String? = null
)

@Singleton
class AuthRepository @Inject constructor(
    private val authService: AuthService,
    private val accountSessionRepositoryProvider: dagger.Lazy<AccountSessionRepository>,
    private val supabaseConfig: SupabaseConfig,
    private val userRepositoryProvider: dagger.Lazy<UserRepository>,
    private val messageRepositoryProvider: dagger.Lazy<MessageRepository>,
    private val groupRepositoryProvider: dagger.Lazy<GroupRepository>,
    private val communityRepositoryProvider: dagger.Lazy<CommunityRepository>,
    private val notificationRepositoryProvider: dagger.Lazy<NotificationRepository>,
    private val twoFactorAuthManagerProvider: dagger.Lazy<conectate.com.auth.twoFactor.TwoFactorAuthManager>,
    private val localFallbackApiClient: LocalFallbackApiClient
) {
    // Restoring the auth StateFlow must not open the saved-account/2FA keystores
    // or construct every messaging graph before Activity.onCreate can draw.
    // Dagger retains the same singleton; its first real operation resolves it.
    private val accountSessionRepository get() = accountSessionRepositoryProvider.get()
    private val userRepository get() = userRepositoryProvider.get()
    private val messageRepository get() = messageRepositoryProvider.get()
    private val groupRepository get() = groupRepositoryProvider.get()
    private val communityRepository get() = communityRepositoryProvider.get()
    private val notificationRepository get() = notificationRepositoryProvider.get()
    private val twoFactorAuthManager get() = twoFactorAuthManagerProvider.get()

    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("AuthRepository", "Uncaught repository coroutine exception", throwable)
        }
    )
    private val accountSwitchMutex = Mutex()
    private val _accountSwitchUiState = MutableStateFlow(AccountSwitchUiState())
    val accountSwitchUiState: StateFlow<AccountSwitchUiState> = _accountSwitchUiState

    suspend fun submitModerationAppeal(
        appealToken: String,
        statement: String
    ): Result<String> = localFallbackApiClient.submitModerationAppeal(appealToken, statement)

    @kotlinx.serialization.Serializable
    private data class SuspensionStateResponse(
        @kotlinx.serialization.SerialName("is_suspended") val isSuspended: Boolean = false,
        @kotlinx.serialization.SerialName("suspended_until") val suspendedUntil: String? = null,
        @kotlinx.serialization.SerialName("suspension_end_date") val suspensionEndDate: String? = null,
        @kotlinx.serialization.SerialName("suspension_reason") val suspensionReason: String? = null
    ) {
        val effectiveEndDate: String?
            get() = suspensionEndDate?.takeIf { it.isNotBlank() } ?: suspendedUntil
    }

    val isUserLoggedIn: Boolean
        get() = authService.isLoggedIn()

    private suspend fun restorePendingWorkAfterLogin(): Exception? {
        val owner = authService.getCurrentUserId()
            ?: return IllegalStateException("No pudimos identificar tu sesión. Inicia sesión de nuevo.")
        return try {
            authService.restorePendingWorkForCurrentAccount()
            if (!authService.commitForCurrentAccount(owner) {})
                throw CancellationException("La cuenta cambió durante la restauración.")
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A failed restore may close its own account, never a newer login.
            if (!authService.commitForCurrentAccount(owner) { authService.forceLocalLogout() })
                throw CancellationException("La cuenta cambió durante la restauración.")
            android.util.Log.e("AuthRepository", "Pending work could not be safely restored", error)
            IllegalStateException("No pudimos abrir los mensajes pendientes de esta cuenta. Inténtalo de nuevo.", error)
        }
    }

    private fun confirmedEntryUser(user: User): User {
        if (!authService.commitForCurrentAccount(user.id) {})
            throw CancellationException("La cuenta cambió antes de terminar el acceso.")
        return user
    }

    val isInitializing: StateFlow<Boolean> = authService.isInitializing

    fun getAuthStateFlow() = authService.currentSession

    val storedAccounts: StateFlow<List<conectate.com.data.model.StoredAccountSession>>
        get() = accountSessionRepository.accounts

    suspend fun signUpWithEmail(
        email: String,
        password: String,
        name: String,
        username: String,
        country: String,
        gender: String,
        birthDate: String,
        imageUri: Uri?,
        acceptedPrivacyPolicy: Boolean
    ): Result<User> {
        if (!acceptedPrivacyPolicy) {
            return Result.failure(Exception("Debes aceptar las políticas de privacidad para crear tu cuenta."))
        }

        val acceptedAt = java.time.OffsetDateTime.now().toString()
        val normalizedName = conectate.com.data.model.DisplayNamePolicy.normalized(name)
        if (!conectate.com.data.model.DisplayNamePolicy.valid(normalizedName)) {
            return Result.failure(Exception("El nombre debe tener entre 2 y 20 caracteres."))
        }
        val normalizedUsername = username.trim().removePrefix("@").lowercase(java.util.Locale.ROOT)
        if (!Regex("[a-z0-9_.-]{3,24}").matches(normalizedUsername)) {
            return Result.failure(Exception("El @usuario debe tener entre 3 y 24 letras, números, puntos, guiones o guiones bajos."))
        }
        val normalizedCountry = country.trim()
        val normalizedGender = gender.trim().lowercase()
            .takeIf { it in setOf("man", "woman", "unspecified") }
            ?: "unspecified"
        val normalizedBirthDate = birthDate.trim()
            .takeIf { value ->
                runCatching {
                    val parsed = java.time.LocalDate.parse(value)
                    conectate.com.data.model.isRegistrationBirthDateAllowed(parsed)
                }.getOrDefault(false)
            }
            ?: return Result.failure(Exception("Debes tener al menos 13 años y seleccionar una fecha de nacimiento válida."))

        val signUpResult = authService.pendingRegistrationFor(email)
            ?.let { Result.success(it) }
            ?: authService.signUp(
                email = email,
                password = password,
                metadata = conectate.com.data.supabase.SignUpMetadata(
                    full_name = name,
                    display_name = name,
                    username = normalizedUsername,
                    country = country,
                    privacy_policy_accepted = acceptedPrivacyPolicy,
                    privacy_policy_accepted_at = acceptedAt
                )
            )

        if (signUpResult.isFailure) {
            return Result.failure(signUpResult.exceptionOrNull() ?: Exception("Unknown signup error"))
        }

        val authResponse = signUpResult.getOrNull()
            ?: return Result.failure(Exception("Sesión nula"))

        val userId = authResponse.user.id
        val usesUbuntuBackend = authService.isLocalFallbackSession()
        var user = User(
            id = userId,
            email = email,
            username = normalizedUsername,
            display_name = normalizedName,
            full_name = normalizedName,
            country = normalizedCountry,
            gender = normalizedGender,
            birth_date = normalizedBirthDate,
            privacy_policy_accepted = true,
            privacy_policy_accepted_at = acceptedAt
        )

        // The Ubuntu registration endpoint already creates its profile shell. Supabase needs
        // the profile row before storage is attached, otherwise a failed first upload used to
        // trigger a second slow attempt and could leave the account without its selected image.
        if (!usesUbuntuBackend) {
            userRepository.createProfile(user = user, privacyPolicyAccepted = true)
                .getOrElse { return Result.failure(it) }
        }

        val uploadedAvatarUrl = imageUri?.let { selectedImage ->
            userRepository.uploadProfileImage(selectedImage).getOrElse { error ->
                android.util.Log.e("AuthRepository", "Profile image upload failed after account creation", error)
                return Result.failure(
                    Exception(
                        "La cuenta fue creada, pero no se pudo subir la foto. Inicia sesión y vuelve a elegirla: ${error.message ?: "error de carga"}",
                        error
                    )
                )
            }
        }

        if (usesUbuntuBackend || uploadedAvatarUrl != null || normalizedBirthDate.isNotBlank()) {
            val profileUpdate = userRepository.updateProfile(
                userId = userId,
                request = UpdateProfileRequest(
                    display_name = normalizedName.takeIf { usesUbuntuBackend && it.isNotBlank() },
                    country = normalizedCountry.takeIf { usesUbuntuBackend && it.isNotBlank() },
                    gender = normalizedGender,
                    birth_date = normalizedBirthDate,
                    avatar_url = uploadedAvatarUrl
                )
            )
            if (profileUpdate.isFailure) {
                val error = profileUpdate.exceptionOrNull()
                return Result.failure(
                    Exception(
                        "La cuenta fue creada, pero no se pudo guardar el perfil: ${error?.message ?: "error de persistencia"}",
                        error
                    )
                )
            }
        }

        user = user.copy(avatar_url = uploadedAvatarUrl)
        val confirmedUser = userRepository.ensureCurrentProfile(user).getOrElse { error ->
            android.util.Log.w("AuthRepository", "Failed to hydrate current profile after sign up", error)
            user
        }
        // El endpoint puede canonicalizar la ruta del objeto (por ejemplo, añadiendo
        // el propietario). La URL confirmada por el servidor debe prevalecer sobre
        // la URL previa a la normalización para que la foto sea visible al entrar.
        val confirmedAvatarUrl = ProfileMediaSync.confirmedReference(
            uploadedUrl = uploadedAvatarUrl,
            persistedUrl = confirmedUser.avatar_url
        )

        val hydratedUser = confirmedUser.copy(
            username = normalizedUsername,
            display_name = normalizedName.takeIf { it.isNotBlank() },
            full_name = normalizedName.takeIf { it.isNotBlank() },
            country = normalizedCountry.takeIf { it.isNotBlank() },
            gender = normalizedGender,
            birth_date = normalizedBirthDate,
            avatar_url = confirmedAvatarUrl
        )

        authService.updateUserMetadata(
            fullName = normalizedName,
            avatarUrl = confirmedAvatarUrl,
            displayName = normalizedName,
            country = normalizedCountry,
            privacyPolicyAccepted = true,
            privacyPolicyAcceptedAt = acceptedAt,
            expectedUserId = hydratedUser.id,
        ).onFailure {
            android.util.Log.w("AuthRepository", "Failed to sync signup metadata", it)
        }

        accountSessionRepository.captureCurrentAccount(
            displayName = hydratedUser.displayName,
            avatarUrl = hydratedUser.avatar_url,
            expectedUserId = hydratedUser.id
        ).onFailure {
            android.util.Log.w("AuthRepository", "Failed to save quick-switch session after sign up", it)
        }

        // Publish the authenticated state only after the selected avatar and
        // every required profile field have been confirmed by the server.
        // Publishing immediately after /register allowed navigation to destroy
        // the registration ViewModel and cancel the pending image upload.
        authService.completePendingRegistration()
        restorePendingWorkAfterLogin()?.let { return Result.failure(it) }
        return Result.success(confirmedEntryUser(hydratedUser))
    }

    suspend fun signInWithEmail(
        email: String,
        password: String,
        secondFactorCode: String? = null
    ): Result<User> {

        var normalizedEmail = email.trim().lowercase()
        var resolvedPrimaryEmail = normalizedEmail
        var login = authService.signIn(normalizedEmail, password, secondFactorCode)

        // Fallback: support additional login emails mapped to a primary profile email.
        if (login.isFailure && !authService.isLocalFallbackModeEnabled()) {
            var resolvedEmail = resolveLoginEmailAlias(normalizedEmail)
            if (!resolvedEmail.isNullOrBlank() && resolvedEmail != normalizedEmail) {
                resolvedPrimaryEmail = resolvedEmail
                login = authService.signIn(resolvedEmail, password, secondFactorCode)
            }
        }

        if (login.isFailure) {
            return Result.failure(login.exceptionOrNull() ?: Exception("Unknown login error"))
        }

        if (authService.isLocalFallbackSession()) {
            restorePendingWorkAfterLogin()?.let { return Result.failure(it) }
            val user = resolveLocalFallbackSessionUser(
                fallbackEmail = resolvedPrimaryEmail
            )
            accountSessionRepository.captureCurrentAccount(
                displayName = user.displayName,
                avatarUrl = user.avatar_url,
                expectedUserId = user.id
            ).onFailure {
                android.util.Log.w("AuthRepository", "Failed to save local fallback account after sign in", it)
            }
            return Result.success(confirmedEntryUser(user))
        }

        if (twoFactorAuthManager.is2FARequired()) {
            authService.signOut()
            return Result.failure(Exception("2FA code required"))
        }

        var tokenGate = authService.ensureValidAccessToken()
        if (tokenGate.isFailure) {
            return Result.failure(
                tokenGate.exceptionOrNull() ?: Exception("No se pudo restaurar la sesión")
            )
        }

        var backendSuspensionGate = enforceBackendSuspensionGate()
        if (backendSuspensionGate.isFailure) {
            forceSuspendedSignOut()
            return Result.failure(
                backendSuspensionGate.exceptionOrNull() ?: Exception("Cuenta suspendida")
            )
        }

        var sessionSeed = authService.getCurrentUserProfileSeed()
        var user = resolveCurrentSessionUser(
            fallbackEmail = resolvedPrimaryEmail,
            fallbackDisplayName = sessionSeed?.displayName ?: sessionSeed?.fullName,
            fallbackAvatarUrl = sessionSeed?.avatarUrl,
            fallbackCountry = sessionSeed?.country
        ).getOrElse { error ->
            return Result.failure(
                error
            )
        }

        var suspensionCheck = enforceUserIsActive(user)
        if (suspensionCheck.isFailure) {
            forceSuspendedSignOut()
            return Result.failure(suspensionCheck.exceptionOrNull() ?: Exception("Cuenta suspendida"))
        }

        restorePendingWorkAfterLogin()?.let { return Result.failure(it) }

        accountSessionRepository.captureCurrentAccount(
            displayName = user.display_name ?: user.full_name ?: user.username,
            avatarUrl = user.avatar_url,
            expectedUserId = user.id
        ).onFailure {
            android.util.Log.w("AuthRepository", "Failed to refresh stored account snapshot after sign in", it)
        }

        return Result.success(confirmedEntryUser(user))
    }

    /**
     * Sign in with email, password, and optional TOTP code for 2FA
     */
    suspend fun signInWithEmail2FA(
        email: String,
        password: String,
        totpCode: String? = null,
        backupCode: String? = null
    ): Result<User> {
        var normalizedEmail = email.trim().lowercase()
        
        // First, authenticate with email and password
        var login = authService.signIn(normalizedEmail, password, totpCode ?: backupCode)
        if (login.isFailure) {
            return Result.failure(login.exceptionOrNull() ?: Exception("Authentication failed"))
        }

        if (authService.isLocalFallbackSession()) {
            restorePendingWorkAfterLogin()?.let { return Result.failure(it) }
            val user = resolveLocalFallbackSessionUser(fallbackEmail = normalizedEmail)
            accountSessionRepository.captureCurrentAccount(
                displayName = user.displayName,
                avatarUrl = user.avatar_url,
                expectedUserId = user.id
            ).onFailure {
                android.util.Log.w("AuthRepository", "Failed to save local fallback 2FA account after sign in", it)
            }
            return Result.success(confirmedEntryUser(user))
        }

        // Check if 2FA is enabled for this user
        if (twoFactorAuthManager.is2FARequired()) {
            // Verify 2FA code
            val twoFaResult = when {
                !totpCode.isNullOrBlank() -> {
                    // Get stored TOTP secret for this user
                    var secret = getStoredTOTPSecret()
                    if (secret == null) {
                        authService.signOut()
                        return Result.failure(Exception("2FA is enabled but no secret found"))
                    }
                    twoFactorAuthManager.verifyTOTPCode(totpCode, secret)
                }
                !backupCode.isNullOrBlank() -> {
                    twoFactorAuthManager.verifyBackupCode(backupCode)
                }
                else -> {
                    authService.signOut()
                    return Result.failure(Exception("2FA code required"))
                }
            }
            
            if (!twoFaResult.success) {
                authService.signOut()
                return Result.failure(Exception(twoFaResult.error ?: "2FA verification failed"))
            }
        }

        // Continue with normal login flow
        var tokenGate = authService.ensureValidAccessToken()
        if (tokenGate.isFailure) {
            return Result.failure(
                tokenGate.exceptionOrNull() ?: Exception("No se pudo restaurar la sesión")
            )
        }

        var backendSuspensionGate = enforceBackendSuspensionGate()
        if (backendSuspensionGate.isFailure) {
            forceSuspendedSignOut()
            return Result.failure(
                backendSuspensionGate.exceptionOrNull() ?: Exception("Cuenta suspendida")
            )
        }

        var sessionSeed = authService.getCurrentUserProfileSeed()
        var user = resolveCurrentSessionUser(
            fallbackEmail = normalizedEmail,
            fallbackDisplayName = sessionSeed?.displayName ?: sessionSeed?.fullName,
            fallbackAvatarUrl = sessionSeed?.avatarUrl,
            fallbackCountry = sessionSeed?.country
        ).getOrElse { error ->
            return Result.failure(error)
        }

        var suspensionCheck = enforceUserIsActive(user)
        if (suspensionCheck.isFailure) {
            forceSuspendedSignOut()
            return Result.failure(suspensionCheck.exceptionOrNull() ?: Exception("Cuenta suspendida"))
        }

        restorePendingWorkAfterLogin()?.let { return Result.failure(it) }

        accountSessionRepository.captureCurrentAccount(
            displayName = user.display_name ?: user.full_name ?: user.username,
            avatarUrl = user.avatar_url,
            expectedUserId = user.id
        ).onFailure {
            android.util.Log.w("AuthRepository", "Failed to refresh stored account snapshot after sign in", it)
        }

        return Result.success(confirmedEntryUser(user))
    }

    /**
     * Setup 2FA for the current user
     */
    suspend fun setupTwoFactorAuth(email: String): Result<conectate.com.auth.twoFactor.TwoFactorAuthManager.TwoFactorAuthResult> {
        return try {
            var result = twoFactorAuthManager.generateTOTPSecret(email)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Enable 2FA for the current user
     */
    suspend fun enableTwoFactorAuth(secret: String): Result<conectate.com.auth.twoFactor.TwoFactorAuthManager.TwoFactorAuthResult> {
        return try {
            var result = twoFactorAuthManager.enable2FA(secret)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Disable 2FA for the current user
     */
    suspend fun disableTwoFactorAuth(): Result<conectate.com.auth.twoFactor.TwoFactorAuthManager.TwoFactorAuthResult> {
        return try {
            var result = twoFactorAuthManager.disable2FA()
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if 2FA is enabled for the current user
     */
    fun isTwoFactorAuthEnabled(): Boolean {
        return twoFactorAuthManager.is2FARequired()
    }

    suspend fun serverTwoFactorStatus() = authService.serverTwoFactorStatus()

    suspend fun passwordRecoveryCodeStatus() = authService.passwordRecoveryCodeStatus()

    suspend fun rotatePasswordRecoveryCodes(password: String, secondFactorCode: String?) =
        authService.rotatePasswordRecoveryCodes(password, secondFactorCode)

    suspend fun setupServerTwoFactor(password: String) = authService.setupServerTwoFactor(password)

    suspend fun enableServerTwoFactor(password: String, code: String) =
        authService.enableServerTwoFactor(password, code)

    suspend fun disableServerTwoFactor(password: String, code: String) =
        authService.disableServerTwoFactor(password, code)

    /**
     * Get 2FA status information
     */
    fun getTwoFactorAuthStatus(): conectate.com.auth.twoFactor.TwoFactorAuthStatus {
        return conectate.com.auth.twoFactor.TwoFactorAuthStatus(
            isEnabled = twoFactorAuthManager.is2FARequired(),
            failedAttempts = twoFactorAuthManager.failedAttempts.value,
            isLockedOut = twoFactorAuthManager.isLockedOut.value,
            remainingLockoutTime = twoFactorAuthManager.getRemainingLockoutTime(),
            unusedBackupCodesCount = twoFactorAuthManager.getUnusedBackupCodesCount()
        )
    }

    /**
     * Get stored TOTP secret for the current user
     * This is a simplified implementation - in production, this should be stored per user
     */
    private suspend fun getStoredTOTPSecret(): String? {
        return try {
            twoFactorAuthManager.getStoredSecret()
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Error getting TOTP secret", e)
            null
        }
    }

    private suspend fun resolveCurrentSessionUser(
        fallbackEmail: String? = null,
        fallbackDisplayName: String? = null,
        fallbackAvatarUrl: String? = null,
        fallbackCountry: String? = null
    ): Result<User> {
        if (authService.isLocalFallbackSession()) {
            return Result.success(
                resolveLocalFallbackSessionUser(
                    fallbackEmail = fallbackEmail,
                    fallbackDisplayName = fallbackDisplayName,
                    fallbackAvatarUrl = fallbackAvatarUrl,
                    fallbackCountry = fallbackCountry
                )
            )
        }

        authService.ensureValidAccessToken()
            .getOrElse { return Result.failure(it) }

        var sessionSeed = authService.getCurrentUserProfileSeed()
        var userId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: sessionSeed?.id?.trim()?.lowercase()
            ?: return Result.failure(Exception("ID de usuario nulo"))

        val directLookup = userRepository.getUserById(userId)
        directLookup.getOrNull()?.let { return Result.success(it) }

        var resolvedEmail = sessionSeed?.email?.trim()?.lowercase()
            ?: fallbackEmail?.trim()?.lowercase()
            ?: authService.getCurrentUserEmail()?.trim()?.lowercase()
            ?: ""
        var userHint = User(
            id = userId,
            email = resolvedEmail,
            display_name = sessionSeed?.displayName ?: fallbackDisplayName,
            full_name = sessionSeed?.fullName ?: sessionSeed?.displayName ?: fallbackDisplayName,
            avatar_url = sessionSeed?.avatarUrl ?: fallbackAvatarUrl,
            country = sessionSeed?.country ?: fallbackCountry,
            privacy_policy_accepted = sessionSeed?.privacyPolicyAccepted == true,
            privacy_policy_accepted_at = sessionSeed?.privacyPolicyAcceptedAt
        )
        val shouldUseHint = userHint.email.isNotBlank() ||
            !userHint.display_name.isNullOrBlank() ||
            !userHint.full_name.isNullOrBlank() ||
            !userHint.avatar_url.isNullOrBlank() ||
            !userHint.country.isNullOrBlank()

        val ensuredUser = userRepository.ensureCurrentProfile(
            userHint = userHint.takeIf { shouldUseHint }
        )
        ensuredUser.getOrNull()?.let { return Result.success(it) }

        val cachedSnapshot = userRepository.getCachedUserSnapshot(userId)
        if (cachedSnapshot != null) {
            return Result.success(cachedSnapshot)
        }

        return Result.failure(
            ensuredUser.exceptionOrNull()
                ?: directLookup.exceptionOrNull()
                ?: Exception("Usuario no encontrado")
        )
    }

    private suspend fun resolveLoginEmailAlias(email: String): String? {
        return try {
            val payload = buildJsonObject {
                put("p_email", JsonPrimitive(email.trim().lowercase()))
            }
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/resolve_login_email") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(payload)
            }

            if (!response.status.isSuccess()) {
                return null
            }

            val raw = response.bodyAsText().trim()
            val decoded = raw.removePrefix("\"").removeSuffix("\"").trim().lowercase()
            if (decoded.isBlank()) null else decoded
        } catch (_: Exception) {
            null
        }
    }

    suspend fun signOut() {
        notificationRepository.clearAccountAlerts(authService.getCurrentUserId())
        // Ensure the user is marked offline as soon as possible.
        runCatching {
            var userId = authService.getCurrentUserId()
            if (!userId.isNullOrBlank() && !authService.isLocalFallbackModeEnabled()) {
                userRepository.setOnlineStatus(userId, false)
            }
        }.onFailure {
            android.util.Log.w("AuthRepository", "Failed to set offline status during signOut", it)
        }

        // Best-effort cleanup: logout must continue even if any single step fails.
        runCatching { notificationRepository.unregisterFcmToken() }
            .onFailure { android.util.Log.w("AuthRepository", "Failed to unregister FCM token during signOut", it) }

        accountSessionRepository.removeCurrentAccount()

        // Synchronous SignOut: wipes local DB and clears Supabase auth state.
        runCatching { authService.signOut() }
            .onFailure { android.util.Log.w("AuthRepository", "AuthService signOut failed", it) }
        
        // In-memory cleanup should always run.
        clearSessionScopedState()
    }

    /**
     * Immediate, non-suspending logout gate for UI actions.
     *
     * Why: If we navigate away first (e.g. to Login), Compose coroutine scopes can be cancelled and the
     * suspend signOut() may never run — leaving the app "stuck" logged-in and bouncing back to Home.
     *
     * This method:
     * - Forces local logout instantly (clears cached tokens + blocks auto session recovery).
     * - Clears in-memory caches.
     * - Starts the heavier Supabase signOut + DB wipe in the background (best-effort).
     */
    fun forceLocalLogout() {
        // Capture what we can before forcing the logout gate (AuthService will stop exposing tokens/userId).
        val userIdSnapshot = runCatching { authService.getCurrentUserId()?.trim()?.lowercase() }.getOrNull()
        val accessTokenSnapshot = runCatching { authService.getAccessToken()?.trim() }.getOrNull()
        val refreshTokenSnapshot = runCatching { authService.getRefreshToken()?.trim() }.getOrNull()
        val wasLocalFallbackSession = authService.isLocalFallbackSession()
        val fcmTokenSnapshot = notificationRepository.capturedFcmToken()
        notificationRepository.clearAccountAlerts(userIdSnapshot)

        accountSessionRepository.removeCurrentAccount()

        runCatching { authService.forceLocalLogout() }
            .onFailure { android.util.Log.w("AuthRepository", "AuthService forceLocalLogout failed", it) }

        clearSessionScopedState()

        // Fire-and-forget full cleanup (DB wipe + Supabase SDK signOut).
        repositoryScope.launch {
            runCatching {
                notificationRepository.unregisterFcmToken(userIdSnapshot, accessTokenSnapshot, fcmTokenSnapshot, wasLocalFallbackSession)
            }.onFailure { android.util.Log.w("AuthRepository", "Captured notification unlink failed", it) }
            runCatching { authService.clearLocalDataForAccountSwitch(userIdSnapshot) }
                .onFailure { android.util.Log.w("AuthRepository", "Failed to clear local DB during forceLocalLogout", it) }
            // Mark offline best-effort before SDK signOut potentially revokes the token.
            if (!userIdSnapshot.isNullOrBlank() && !accessTokenSnapshot.isNullOrBlank() && !wasLocalFallbackSession) {
                runCatching {
                    userRepository.setOnlineStatusWithToken(userIdSnapshot, false, accessTokenSnapshot)
                }.onFailure {
                    android.util.Log.w("AuthRepository", "Failed to set offline status during forceLocalLogout", it)
                }
            }
            runCatching { authService.finishForcedLogout(refreshTokenSnapshot, wasLocalFallbackSession) }
                .onFailure { android.util.Log.w("AuthRepository", "Background session revocation failed", it) }
        }
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        return authService.resetPassword(email)
    }

    suspend fun requestLocalFallbackPasswordReset(email: String): Result<LocalFallbackPasswordResetResponse> {
        return authService.requestLocalFallbackPasswordReset(email)
    }

    suspend fun confirmLocalFallbackPasswordReset(
        resetToken: String,
        newPassword: String
    ): Result<User> {
        val result = authService.confirmLocalFallbackPasswordReset(resetToken, newPassword)
        if (result.isFailure) {
            return Result.failure(result.exceptionOrNull() ?: Exception("No se pudo cambiar la contraseña en Ubuntu."))
        }

        val user = resolveCurrentSessionUser().getOrElse { return Result.failure(it) }
        accountSessionRepository.captureCurrentAccount(
            displayName = user.displayName,
            avatarUrl = user.avatar_url,
            expectedUserId = user.id
        ).onFailure {
            android.util.Log.w("AuthRepository", "Failed to save local fallback account after password reset", it)
        }
        return Result.success(confirmedEntryUser(user))
    }

    suspend fun confirmSavedPasswordRecoveryCode(
        email: String,
        recoveryCode: String,
        newPassword: String,
        secondFactorCode: String?
    ): Result<User> {
        val result = authService.confirmSavedPasswordRecoveryCode(
            email, recoveryCode, newPassword, secondFactorCode
        )
        if (result.isFailure) {
            return Result.failure(result.exceptionOrNull() ?: Exception("No se pudo recuperar la contraseña."))
        }
        val user = resolveCurrentSessionUser().getOrElse { return Result.failure(it) }
        accountSessionRepository.captureCurrentAccount(
            displayName = user.displayName,
            avatarUrl = user.avatar_url,
            expectedUserId = user.id
        ).onFailure {
            android.util.Log.w("AuthRepository", "Failed to save account after recovery", it)
        }
        return Result.success(confirmedEntryUser(user))
    }

    suspend fun completeMigratedLocalPasswordReset(
        migrationToken: String,
        newPassword: String
    ): Result<User> {
        val result = authService.completeMigratedLocalPasswordReset(migrationToken, newPassword)
        if (result.isFailure) {
            return Result.failure(result.exceptionOrNull() ?: Exception("No se pudo activar la contraseña local."))
        }

        val user = resolveCurrentSessionUser().getOrElse { return Result.failure(it) }
        accountSessionRepository.captureCurrentAccount(
            displayName = user.displayName,
            avatarUrl = user.avatar_url,
            expectedUserId = user.id
        ).onFailure {
            android.util.Log.w("AuthRepository", "Failed to save local fallback account after migration reset", it)
        }
        return Result.success(confirmedEntryUser(user))
    }

    suspend fun directPasswordReset(
        email: String,
        newPassword: String
    ): Result<User> {
        val result = authService.directPasswordReset(email, newPassword)
        if (result.isFailure) {
            return Result.failure(result.exceptionOrNull() ?: Exception("No se pudo restablecer la contraseña."))
        }

        val user = resolveCurrentSessionUser().getOrElse { return Result.failure(it) }
        accountSessionRepository.captureCurrentAccount(
            displayName = user.displayName,
            avatarUrl = user.avatar_url,
            expectedUserId = user.id
        ).onFailure {
            android.util.Log.w("AuthRepository", "Failed to save local fallback account after direct reset", it)
        }
        return Result.success(confirmedEntryUser(user))
    }

    suspend fun updatePassword(newPassword: String): Result<Unit> {
        return authService.updateUserPassword(newPassword)
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> {
        return authService.changePassword(currentPassword, newPassword)
    }

    suspend fun importSession(accessToken: String, refreshToken: String) {
        authService.importSession(accessToken, refreshToken)
    }
    fun getCurrentUserId(): String? = authService.getCurrentUserId()
    
    fun getCurrentUserEmail(): String? = authService.getCurrentUserEmail()

    suspend fun getCurrentAccessTokenAwait(): String? = authService.getAccessTokenAwait()

    fun getCurrentRefreshToken(): String? = authService.getRefreshToken()

    suspend fun rememberCurrentAccount(): Result<Unit> {
        // Saving a switchable session only needs the authenticated local tokens.
        // A profile request here delayed opening "Add account" or failed offline.
        val userId = authService.getCurrentUserId()?.trim()?.lowercase()
        val email = authService.getCurrentUserEmail()?.trim()?.lowercase()
        val existing = accountSessionRepository.accounts.value.firstOrNull {
            if (!userId.isNullOrBlank()) it.user_id == userId
            else !email.isNullOrBlank() && it.email.equals(email, ignoreCase = true)
        }
        val seed = authService.getCurrentUserProfileSeed()
        if (seed != null && !seed.id.equals(userId, ignoreCase = true))
            throw CancellationException("La cuenta cambió antes de guardar sus datos.")
        return accountSessionRepository.captureCurrentAccount(
            displayName = seed?.displayName ?: existing?.display_name,
            avatarUrl = seed?.avatarUrl ?: existing?.avatar_url,
            expectedUserId = userId
        ).map { Unit }
    }

    suspend fun switchAccount(accountId: String): Result<User> {
        if (!accountSwitchMutex.tryLock()) {
            return Result.failure(IllegalStateException("Ya hay un cambio de cuenta en curso."))
        }
        val targetAccount = accountSessionRepository.accounts.value.firstOrNull { it.id == accountId }
        _accountSwitchUiState.value = AccountSwitchUiState(
            isSwitching = true,
            targetAccountId = accountId,
            targetDisplayName = targetAccount?.display_name ?: targetAccount?.email,
            targetAvatarUrl = targetAccount?.avatar_url
        )
        val previousAccessToken = authService.getAccessToken()?.trim()
        val previousRefreshToken = authService.getRefreshToken()?.trim()
        var previousUserId = authService.getCurrentUserId()?.trim()?.lowercase()
        var previousUserEmail = authService.getCurrentUserEmail()?.trim()?.lowercase()
        var previousUserSnapshot = previousUserId
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { userRepository.getCachedUserSnapshot(it) }.getOrNull() }
            ?: authService.getCurrentUserProfileSeed()?.let { seed ->
                User(
                    id = seed.id,
                    email = seed.email,
                    display_name = seed.displayName,
                    full_name = seed.fullName ?: seed.displayName,
                    avatar_url = seed.avatarUrl,
                    country = seed.country
                )
            }

        return try {
            // Prevent data leakage/cross-account cache corruption.
            clearSessionScopedState()
            authService.beginAccountSwitch()
            authService.clearLocalDataForAccountSwitch(previousUserId)

            val switched = accountSessionRepository.switchAccount(accountId)
                .getOrThrow()

            authService.ensureValidAccessToken()
                .getOrThrow()
            authService.restorePendingWorkForCurrentAccount()

            var userId = authService.getCurrentUserId() ?: switched.user_id
                ?: throw IllegalStateException("No se pudo activar la sesión")

            var user = userRepository.getUserById(userId).getOrNull()
                ?: authService.getCurrentUserProfileSeed()?.let { seed ->
                    User(
                        id = seed.id,
                        email = seed.email,
                        display_name = seed.displayName ?: switched.display_name,
                        full_name = seed.fullName ?: switched.display_name,
                        avatar_url = seed.avatarUrl ?: switched.avatar_url,
                        country = seed.country
                    )
                }
                ?: User(
                    id = userId,
                    email = switched.email,
                    display_name = switched.display_name,
                    avatar_url = switched.avatar_url
                )

            var suspensionCheck = enforceUserIsActive(user)
            if (suspensionCheck.isFailure) {
                forceSuspendedSignOut()
                return Result.failure(suspensionCheck.exceptionOrNull() ?: Exception("Cuenta suspendida"))
            }

            warmUpSessionAfterAccountSwitch(user)

            var hydratedUser = resolveCurrentSessionUser(
                fallbackEmail = switched.email,
                fallbackDisplayName = user.display_name ?: user.full_name ?: switched.display_name,
                fallbackAvatarUrl = user.avatar_url ?: switched.avatar_url,
                fallbackCountry = user.country
            ).getOrElse {
                android.util.Log.w("AuthRepository", "Using pre-warm user snapshot after account switch", it)
                user
            }

            accountSessionRepository.captureCurrentAccount(
                displayName = hydratedUser.displayName,
                avatarUrl = hydratedUser.avatar_url,
                expectedUserId = hydratedUser.id
            ).onFailure {
                android.util.Log.w("AuthRepository", "Failed to refresh stored account snapshot after switch", it)
            }

            Result.success(confirmedEntryUser(hydratedUser))
        } catch (e: Exception) {
            if (!previousAccessToken.isNullOrBlank() && !previousRefreshToken.isNullOrBlank()) {
                runCatching {
                    authService.importSession(previousAccessToken, previousRefreshToken)
                    authService.restorePendingWorkForCurrentAccount()
                    val restoredUser = restorePreviousSessionAfterSwitchFailure(
                        previousUserId = previousUserId,
                        previousUserEmail = previousUserEmail,
                        previousUserSnapshot = previousUserSnapshot
                    )
                    if (restoredUser != null) {
                        warmUpSessionAfterAccountSwitch(restoredUser)
                    }
                }.onFailure {
                    android.util.Log.w("AuthRepository", "Failed to restore previous session after switch error", it)
                }
            }
            val normalizedFailure = if (looksLikeStoredAccountRefreshFailure(e.message)) {
                IllegalStateException("La sesión guardada expiró. Vuelve a iniciar sesión con esa cuenta.")
            } else {
                e
            }
            Result.failure(normalizedFailure)
        } finally {
            _accountSwitchUiState.value = AccountSwitchUiState()
            accountSwitchMutex.unlock()
        }
    }

    private fun looksLikeStoredAccountRefreshFailure(message: String?): Boolean {
        var normalized = message?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        return normalized.contains("invalid refresh token") ||
            normalized.contains("refresh token not found") ||
            normalized.contains("la sesión guardada expiró") ||
            normalized.contains("la sesion guardada expiro")
    }

    private suspend fun warmUpSessionAfterAccountSwitch(user: User) {
        userRepository.ensureCurrentProfile(user).onFailure {
            android.util.Log.w("AuthRepository", "Profile warm-up failed after account switch", it)
        }

        val warmUpFinished = withTimeoutOrNull(4_000L) {
            coroutineScope {
                listOf(
                    async { messageRepository.getUserChats() },
                    async { groupRepository.syncGroups() },
                    async { communityRepository.syncCommunities() }
                ).awaitAll()
            }
            true
        } ?: false

        if (!warmUpFinished) {
            android.util.Log.w("AuthRepository", "Session warm-up exceeded the soft deadline after account switch")
        }
    }

    private suspend fun restorePreviousSessionAfterSwitchFailure(
        previousUserId: String?,
        previousUserEmail: String?,
        previousUserSnapshot: User?
    ): User? {
        val restoredUserId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: previousUserId?.takeIf { it.isNotBlank() }
        val restoredFromRepository = restoredUserId
            ?.takeIf { it.isNotBlank() }
            ?.let { userRepository.getUserById(it).getOrNull() }
        if (restoredFromRepository != null) {
            return restoredFromRepository
        }

        val restoredSeed = authService.getCurrentUserProfileSeed()
        if (restoredSeed != null) {
            return User(
                id = restoredSeed.id,
                email = restoredSeed.email,
                display_name = restoredSeed.displayName ?: previousUserSnapshot?.display_name,
                full_name = restoredSeed.fullName ?: previousUserSnapshot?.full_name ?: restoredSeed.displayName,
                avatar_url = restoredSeed.avatarUrl ?: previousUserSnapshot?.avatar_url,
                country = restoredSeed.country ?: previousUserSnapshot?.country
            )
        }

        return previousUserSnapshot?.copy(
            id = previousUserSnapshot.id.trim().lowercase(),
            email = previousUserEmail ?: previousUserSnapshot.email
        )
    }

    suspend fun enforceCurrentSessionIsActive(): Result<Unit> {
        if (authService.isLocalFallbackSession()) {
            return if (authService.getCurrentUserId().isNullOrBlank()) {
                Result.failure(Exception("No hay una sesión activa"))
            } else {
                Result.success(Unit)
            }
        }

        var tokenGate = authService.ensureValidAccessToken()
        if (tokenGate.isFailure) {
            return Result.failure(
                tokenGate.exceptionOrNull() ?: Exception("Sesión expirada. Inicia sesión de nuevo.")
            )
        }

        var backendSuspensionGate = enforceBackendSuspensionGate()
        if (backendSuspensionGate.isFailure) {
            forceSuspendedSignOut()
            return Result.failure(
                backendSuspensionGate.exceptionOrNull() ?: Exception("Cuenta suspendida")
            )
        }

        var userId = authService.getCurrentUserId()
            ?: return Result.failure(Exception("No hay una sesión activa"))
        val userResult = userRepository.getUserById(userId)
        if (userResult.isFailure) {
            val errorMessage = userResult.exceptionOrNull()?.message
            return if (looksLikeSessionFailure(errorMessage)) {
                Result.failure(Exception("Sesión expirada. Inicia sesión de nuevo."))
            } else {
                Result.success(Unit)
            }
        }

        var user = userResult.getOrNull()
            ?: return Result.failure(Exception("La cuenta activa ya no está disponible."))
        var suspensionCheck = enforceUserIsActive(user)
        if (suspensionCheck.isSuccess) return Result.success(Unit)
        forceSuspendedSignOut()
        return Result.failure(suspensionCheck.exceptionOrNull() ?: Exception("Cuenta suspendida"))
    }

    private suspend fun resolveLocalFallbackSessionUser(
        fallbackEmail: String? = null,
        fallbackDisplayName: String? = null,
        fallbackAvatarUrl: String? = null,
        fallbackCountry: String? = null
    ): User {
        val seed = authService.getCurrentUserProfileSeed()
        val userId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: seed?.id?.trim()?.lowercase()
            ?: ""
        val email = seed?.email?.trim()?.lowercase()
            ?: fallbackEmail?.trim()?.lowercase()
            ?: authService.getCurrentUserEmail()?.trim()?.lowercase()
            ?: ""
        val displayName = seed?.displayName
            ?: seed?.fullName
            ?: fallbackDisplayName
            ?: "Usuario"
        return User(
            id = userId,
            email = email,
            display_name = displayName,
            full_name = seed?.fullName ?: displayName,
            avatar_url = seed?.avatarUrl ?: fallbackAvatarUrl,
            country = seed?.country ?: fallbackCountry,
            privacy_policy_accepted = true,
            privacy_policy_accepted_at = seed?.privacyPolicyAcceptedAt,
            created_at = seed?.privacyPolicyAcceptedAt ?: ""
        )
    }

    fun removeStoredAccount(accountId: String) {
        val account = accountSessionRepository.accounts.value.firstOrNull { it.id == accountId.trim() }
        val fcmToken = notificationRepository.capturedFcmToken()
        notificationRepository.clearAccountAlerts(account?.user_id)
        if (account != null && authService.isLocalFallbackStoredSessionToken(account.access_token, account.refresh_token)) {
            authService.rememberServerLogout(account.refresh_token)
        }
        accountSessionRepository.removeAccount(accountId).onFailure {
            conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo quitar la cuenta guardada. Inténtalo de nuevo.")
        }
        if (account != null && conectate.com.util.NotificationAccountTarget(account.user_id, account.email)
                .matches(authService.getCurrentUserId(), authService.getCurrentUserEmail())) {
            forceLocalLogout()
            return
        }
        if (account != null) repositoryScope.launch {
            runCatching {
                notificationRepository.unregisterFcmToken(account.user_id, account.access_token, fcmToken,
                    authService.isLocalFallbackStoredSessionToken(account.access_token, account.refresh_token))
            }
            runCatching {
                authService.finishForcedLogout(account.refresh_token,
                    authService.isLocalFallbackStoredSessionToken(account.access_token, account.refresh_token))
            }
        }
    }

    private suspend fun createUserProfile(user: User): Result<Unit> {
        return try {
            var accessToken = authService.getAccessToken()
            if (accessToken == null) {
                return Result.failure(Exception("No hay token de acceso"))
            }

            val userData = buildJsonObject {
                put("id", JsonPrimitive(user.id))
                put("email", JsonPrimitive(user.email))
                user.display_name?.let { put("display_name", JsonPrimitive(it)) }
                user.avatar_url?.let { put("avatar_url", JsonPrimitive(it)) }
                user.bio?.let { put("bio", JsonPrimitive(it)) }
            }

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                // Use upsert behavior in case a trigger already created the profile
                header("Prefer", "resolution=merge-duplicates") 
                setBody(userData)
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val errorBody = response.body<String>()
                Result.failure(Exception("Error creando perfil (${response.status}): $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getUserProfile(userId: String): Result<User> {
        return try {
            var accessToken = authService.getAccessToken()
            if (accessToken == null) {
                return Result.failure(Exception("No hay token de acceso"))
            }

            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("id", "eq.$userId")
            }
            if (response.status.isSuccess()) {
                var users = response.body<List<User>>()
                if (users.isNotEmpty()) {
                    Result.success(users.first())
                } else {
                    Result.failure(Exception("Usuario no encontrado"))
                }
            } else {
                Result.failure(Exception("Error obteniendo perfil: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun enforceUserIsActive(user: User): Result<Unit> {
        if (!user.isSuspendedNow) return Result.success(Unit)
        var reason = user.suspension_reason?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Incumplimiento de normas de la comunidad"
        var untilIso = user.effectiveSuspensionEndDate
        var message = buildSuspensionMessage(user)
        return Result.failure(
            AccountSuspendedException(
                suspensionEndDate = untilIso,
                reason = reason,
                message = message
            )
        )
    }

    private fun buildSuspensionMessage(user: User): String {
        var reason = user.suspension_reason?.trim().takeUnless { it.isNullOrBlank() } ?: "Incumplimiento de normas de la comunidad"
        var untilInstant = parseSuspensionInstant(user.effectiveSuspensionEndDate)
        var untilText = untilInstant
            ?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            ?: "nuevo aviso"
        val remainingDays = untilInstant?.let { instant ->
            val hours = Duration.between(Instant.now(), instant).toHours().coerceAtLeast(0)
            ceil(hours / 24.0).toInt().coerceAtLeast(1)
        }
        val daysText = remainingDays?.let { "$it día(s)" } ?: "tiempo indefinido"
        return buildString {
            append("Tu cuenta fue inhabilitada o suspendida por moderación.\n")
            append("Motivo: $reason.\n")
            if (untilInstant == null) {
                append("La suspensión es permanente.\n")
            } else {
                append("Suspensión vigente por: $daysText.\n")
                append("Vuelve el: $untilText.\n")
            }
            append("No incumplas las normas de la comunidad ni subas contenido inapropiado.")
        }
    }

    private fun parseSuspensionInstant(raw: String?): Instant? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return runCatching {
            var normalized = if (value.contains("T")) value else value.replace(" ", "T")
            Instant.parse(if (normalized.endsWith("Z") || normalized.contains("+")) normalized else "${normalized}Z")
        }.getOrNull()
    }

    private suspend fun enforceBackendSuspensionGate(): Result<Unit> {
        var accessToken = authService.getAccessToken() ?: return Result.success(Unit)
        return try {
            var rpcState = runCatching {
                var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_get_my_suspension_state") {
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Authorization", "Bearer $accessToken")
                    header("Content-Type", "application/json")
                    setBody(buildJsonObject { })
                }
                if (!response.status.isSuccess()) {
                    null
                } else {
                    parseSuspensionState(response.bodyAsText())
                }
            }.getOrNull()

            val fallbackState = if (rpcState == null) {
                fetchSuspensionStateFromProfile(accessToken)
            } else {
                null
            }

            val state = rpcState ?: fallbackState ?: return Result.success(Unit)
            if (!state.isSuspended) return Result.success(Unit)

            var reason = state.suspensionReason?.trim().takeUnless { it.isNullOrBlank() }
                ?: "Incumplimiento de normas de la comunidad"
            var untilIso = state.effectiveEndDate
            var untilInstant = parseSuspensionInstant(untilIso)

            // If fallback data is stale and already expired, allow session.
            if (untilInstant != null && !untilInstant.isAfter(Instant.now())) {
                return Result.success(Unit)
            }

            var untilText = untilInstant
                ?.atZone(ZoneId.systemDefault())
                ?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                ?: "nuevo aviso"
            val suspensionMessage = if (untilInstant == null) {
                "ACCOUNT_SUSPENDED: Tu cuenta está suspendida permanentemente. Motivo: $reason."
            } else {
                "ACCOUNT_SUSPENDED: Tu cuenta está suspendida hasta $untilText. Motivo: $reason."
            }

            Result.failure(
                AccountSuspendedException(
                    suspensionEndDate = untilIso,
                    reason = reason,
                    message = suspensionMessage
                )
            )
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    private suspend fun fetchSuspensionStateFromProfile(
        accessToken: String
    ): SuspensionStateResponse? {
        var userId = authService.getCurrentUserId() ?: return null
        var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
            header("apikey", supabaseConfig.supabaseAnonKey)
            header("Authorization", "Bearer $accessToken")
            parameter("id", "eq.$userId")
            parameter("select", "is_suspended,suspended_until,suspension_end_date,suspension_reason")
            parameter("limit", 1)
        }
        if (!response.status.isSuccess()) return null
        return parseSuspensionState(response.bodyAsText())
    }

    private fun parseSuspensionState(rawBody: String): SuspensionStateResponse? {
        if (rawBody.isBlank()) return null
        return runCatching {
            when (val element = Json.parseToJsonElement(rawBody)) {
                is JsonArray -> {
                    val first = element.firstOrNull() ?: return@runCatching null
                    supabaseConfig.json.decodeFromJsonElement(
                        SuspensionStateResponse.serializer(),
                        first
                    )
                }
                is JsonObject -> supabaseConfig.json.decodeFromJsonElement(
                    SuspensionStateResponse.serializer(),
                    element
                )
                else -> null
            }
        }.getOrNull()
    }

    private suspend fun forceSuspendedSignOut() {
        runCatching { notificationRepository.unregisterFcmToken() }
        runCatching { authService.signOut() }
        clearSessionScopedState()
    }

    private fun looksLikeSessionFailure(message: String?): Boolean {
        var normalized = message?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        return normalized.contains("jwt expired") ||
            normalized.contains("sesión expirada") ||
            normalized.contains("sesion expirada") ||
            normalized.contains("no access token") ||
            normalized.contains("no hay una sesión activa") ||
            normalized.contains("no hay una sesion activa") ||
            normalized.contains("no token") ||
            normalized.contains("invalid jwt") ||
            normalized.contains("pgrst303") ||
            normalized.contains("not authenticated") ||
            normalized.contains("unauthorized")
    }

    private fun clearSessionScopedState() {
        runCatching { messageRepository.clearData() }
        runCatching { userRepository.clearData() }
        runCatching { groupRepository.clearData() }
        runCatching { communityRepository.clearData() }
        runCatching { notificationRepository.clearData() }
    }
}



