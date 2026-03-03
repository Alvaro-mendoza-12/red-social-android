package conectate.com.data.repository

import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.model.User
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import conectate.com.data.supabase.StorageService
import java.util.UUID
import io.ktor.client.statement.bodyAsText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

@Singleton
class AuthRepository @Inject constructor(
    private val authService: AuthService,
    private val accountSessionRepository: AccountSessionRepository,
    private val supabaseConfig: SupabaseConfig,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val notificationRepository: NotificationRepository
) {
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

    val isInitializing: StateFlow<Boolean> = authService.isInitializing

    fun getAuthStateFlow() = authService.currentSession

    val storedAccounts: StateFlow<List<conectate.com.data.model.StoredAccountSession>> = accountSessionRepository.accounts

    suspend fun signUpWithEmail(
        email: String,
        password: String,
        name: String,
        country: String,
        imageUri: Uri?,
        acceptedPrivacyPolicy: Boolean
    ): Result<User> {
        if (!acceptedPrivacyPolicy) {
            return Result.failure(Exception("Debes aceptar las políticas de privacidad para crear tu cuenta."))
        }

        // 1️⃣ SIGN UP
        val signUpResult = authService.signUp(
            email = email,
            password = password,
            metadata = conectate.com.data.supabase.SignUpMetadata(
                full_name = name,
                country = country
            )
        )

        if (signUpResult.isFailure) {
            return Result.failure(signUpResult.exceptionOrNull() ?: Exception("Unknown signup error"))  // ✅ FIXED: No more !!
        }

        val authResponse = signUpResult.getOrNull()
            ?: return Result.failure(Exception("Sesión nula"))

        val userId = authResponse.user.id

        // 2️⃣ CREAR PERFIL
        val user = User(
            id = userId,
            email = email,
            full_name = name,
            country = country
        )

        val profileResult = userRepository.createProfile(
            user = user,
            privacyPolicyAccepted = acceptedPrivacyPolicy
        )
        if (profileResult.isFailure) {
            return Result.failure(profileResult.exceptionOrNull() ?: Exception("Unknown profile creation error"))  // ✅ FIXED: No more !!
        }

        // 3️⃣ (OPCIONAL) SUBIR FOTO
        if (imageUri != null) {
            userRepository.uploadProfileImage(imageUri)
        }

        accountSessionRepository.captureCurrentAccount(
            displayName = user.display_name ?: user.full_name ?: user.username,
            avatarUrl = user.avatar_url
        )

        return Result.success(user)
    }

    suspend fun signInWithEmail(
        email: String,
        password: String
    ): Result<User> {

        val normalizedEmail = email.trim().lowercase()
        var login = authService.signIn(normalizedEmail, password)

        // Fallback: support additional login emails mapped to a primary profile email.
        if (login.isFailure) {
            val resolvedEmail = resolveLoginEmailAlias(normalizedEmail)
            if (!resolvedEmail.isNullOrBlank() && resolvedEmail != normalizedEmail) {
                login = authService.signIn(resolvedEmail, password)
            }
        }

        if (login.isFailure) {
            return Result.failure(login.exceptionOrNull() ?: Exception("Unknown login error"))  // ✅ FIXED: No more !!
        }

        val backendSuspensionGate = enforceBackendSuspensionGate()
        if (backendSuspensionGate.isFailure) {
            forceSuspendedSignOut()
            return Result.failure(
                backendSuspensionGate.exceptionOrNull() ?: Exception("Cuenta suspendida")
            )
        }

        val userId = authService.getCurrentUserId()
            ?: return Result.failure(Exception("ID de usuario nulo"))

        val user = userRepository.getUserById(userId).getOrNull()
            ?: return Result.failure(Exception("Usuario no encontrado"))

        val suspensionCheck = enforceUserIsActive(user)
        if (suspensionCheck.isFailure) {
            forceSuspendedSignOut()
            return Result.failure(suspensionCheck.exceptionOrNull() ?: Exception("Cuenta suspendida"))
        }

        accountSessionRepository.captureCurrentAccount(
            displayName = user.display_name ?: user.full_name ?: user.username,
            avatarUrl = user.avatar_url
        )

        return Result.success(user)
    }

    private suspend fun resolveLoginEmailAlias(email: String): String? {
        return try {
            val payload = buildJsonObject {
                put("p_email", JsonPrimitive(email.trim().lowercase()))
            }
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/resolve_login_email") {
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
        // Best-effort cleanup: logout must continue even if any single step fails.
        runCatching { notificationRepository.unregisterFcmToken() }
            .onFailure { android.util.Log.w("AuthRepository", "Failed to unregister FCM token during signOut", it) }

        runCatching { accountSessionRepository.removeCurrentAccount() }
            .onFailure { android.util.Log.w("AuthRepository", "Failed to remove current account session during signOut", it) }
        
        // Synchronous SignOut: wipes local DB and clears Supabase auth state.
        runCatching { authService.signOut() }
            .onFailure { android.util.Log.w("AuthRepository", "AuthService signOut failed", it) }
        
        // In-memory cleanup should always run.
        runCatching { messageRepository.clearData() }
        runCatching { userRepository.clearData() }
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        return authService.resetPassword(email)
    }

    suspend fun updatePassword(newPassword: String): Result<Unit> {
        return authService.updateUserPassword(newPassword)
    }

    suspend fun importSession(accessToken: String, refreshToken: String) {
        authService.importSession(accessToken, refreshToken)
    }


    suspend fun startPhoneSignIn(phone: String): Result<Unit> {
        return Result.success(Unit)
    }

    suspend fun confirmPhoneOtp(phone: String, otp: String, name: String, avatarUrl: String?): Result<User> {
        return Result.failure(Exception("OTP no implementado"))
    }

    fun getCurrentUserId(): String? = authService.getCurrentUserId()
    
    fun getCurrentUserEmail(): String? = authService.getCurrentUserEmail()

    suspend fun rememberCurrentAccount(): Result<Unit> {
        val userId = authService.getCurrentUserId()
        val profile = if (!userId.isNullOrBlank()) userRepository.getUserById(userId).getOrNull() else null
        val result = accountSessionRepository.captureCurrentAccount(
            displayName = profile?.display_name ?: profile?.full_name ?: profile?.username,
            avatarUrl = profile?.avatar_url
        )
        return if (result.isSuccess) Result.success(Unit) else Result.failure(result.exceptionOrNull() ?: Exception("No se pudo guardar cuenta"))
    }

    suspend fun switchAccount(accountId: String): Result<User> {
        return try {
            // Prevent data leakage/cross-account cache corruption.
            messageRepository.clearData()
            userRepository.clearData()
            authService.clearLocalDataForAccountSwitch()

            val switched = accountSessionRepository.switchAccount(accountId)
                .getOrElse { return Result.failure(it) }

            val userId = authService.getCurrentUserId() ?: switched.user_id
                ?: return Result.failure(Exception("No se pudo activar la sesión"))

            val user = userRepository.getUserById(userId).getOrNull()
                ?: User(
                    id = userId,
                    email = switched.email,
                    display_name = switched.display_name,
                    avatar_url = switched.avatar_url
                )

            val suspensionCheck = enforceUserIsActive(user)
            if (suspensionCheck.isFailure) {
                forceSuspendedSignOut()
                return Result.failure(suspensionCheck.exceptionOrNull() ?: Exception("Cuenta suspendida"))
            }

            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun enforceCurrentSessionIsActive(): Result<Unit> {
        val backendSuspensionGate = enforceBackendSuspensionGate()
        if (backendSuspensionGate.isFailure) {
            forceSuspendedSignOut()
            return Result.failure(
                backendSuspensionGate.exceptionOrNull() ?: Exception("Cuenta suspendida")
            )
        }

        val userId = authService.getCurrentUserId() ?: return Result.success(Unit)
        val user = userRepository.getUserById(userId).getOrNull() ?: return Result.success(Unit)
        val suspensionCheck = enforceUserIsActive(user)
        if (suspensionCheck.isSuccess) return Result.success(Unit)
        forceSuspendedSignOut()
        return Result.failure(suspensionCheck.exceptionOrNull() ?: Exception("Cuenta suspendida"))
    }

    fun removeStoredAccount(accountId: String) {
        accountSessionRepository.removeAccount(accountId)
    }

    private suspend fun createUserProfile(user: User): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken()
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

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
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
            val accessToken = authService.getAccessToken()
            if (accessToken == null) {
                return Result.failure(Exception("No hay token de acceso"))
            }

            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("id", "eq.$userId")
            }
            if (response.status.isSuccess()) {
                val users = response.body<List<User>>()
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

    private suspend fun getNextNumericId(accessToken: String): Long {
        return try {
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("select", "numeric_id")
                parameter("order", "numeric_id.desc")
                parameter("limit", "1")
            }
            
            if (response.status.isSuccess()) {
                val users = response.body<List<User>>() // This might fail if JSON structure is partial? User model expects all fields?
                // Actually User model has default values for almost everything, so it should be fine.
                // But safer to parse as generic JsonArray or specific simple-DTO to avoid validation errors if User strict.
                // Given User model defaults, it should work.
                
                val maxId = users.firstOrNull()?.numeric_id ?: 999L
                maxId + 1
            } else {
                1000L // Default start
            }
        } catch (e: Exception) {
            e.printStackTrace()
            1000L
        }
    }

    private fun enforceUserIsActive(user: User): Result<Unit> {
        if (!user.isSuspendedNow) return Result.success(Unit)
        val reason = user.suspension_reason?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Incumplimiento de normas de la comunidad"
        val untilIso = user.effectiveSuspensionEndDate
        val message = buildSuspensionMessage(user)
        return Result.failure(
            AccountSuspendedException(
                suspensionEndDate = untilIso,
                reason = reason,
                message = message
            )
        )
    }

    private fun buildSuspensionMessage(user: User): String {
        val reason = user.suspension_reason?.trim().takeUnless { it.isNullOrBlank() } ?: "Incumplimiento de normas de la comunidad"
        val untilInstant = parseSuspensionInstant(user.effectiveSuspensionEndDate)
        val untilText = untilInstant
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
            val normalized = if (value.contains("T")) value else value.replace(" ", "T")
            Instant.parse(if (normalized.endsWith("Z") || normalized.contains("+")) normalized else "${normalized}Z")
        }.getOrNull()
    }

    private suspend fun enforceBackendSuspensionGate(): Result<Unit> {
        val accessToken = authService.getAccessToken() ?: return Result.success(Unit)
        return try {
            val rpcState = runCatching {
                val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_get_my_suspension_state") {
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

            val reason = state.suspensionReason?.trim().takeUnless { it.isNullOrBlank() }
                ?: "Incumplimiento de normas de la comunidad"
            val untilIso = state.effectiveEndDate
            val untilInstant = parseSuspensionInstant(untilIso)

            // If fallback data is stale and already expired, allow session.
            if (untilInstant != null && !untilInstant.isAfter(Instant.now())) {
                return Result.success(Unit)
            }

            val untilText = untilInstant
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
        val userId = authService.getCurrentUserId() ?: return null
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
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
        runCatching { messageRepository.clearData() }
        runCatching { userRepository.clearData() }
    }
}
