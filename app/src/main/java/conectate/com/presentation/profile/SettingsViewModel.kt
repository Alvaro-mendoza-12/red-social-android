package conectate.com.presentation.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.ServerTwoFactorSetup
import conectate.com.data.localfallback.ServerTwoFactorStatus
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.WebLoginManagedSession
import conectate.com.data.repository.WebLoginPairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import java.time.Instant
import conectate.com.data.model.UpdateProfileRequest
import conectate.com.data.local.prefs.AppFontOption
import conectate.com.data.local.prefs.AppThemeOption
import androidx.navigation.NavController
import conectate.com.presentation.common.toUserFacingMessage
import conectate.com.presentation.navigation.Screen

sealed class SettingsUiState {
    object Loading : SettingsUiState()
    data class Success(val user: User) : SettingsUiState()
    data class Error(val message: String) : SettingsUiState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val messageRepository: conectate.com.data.repository.MessageRepository,
    private val themePreferences: conectate.com.data.local.prefs.ThemePreferences,
    private val webLoginPairingRepository: WebLoginPairingRepository,
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val settingsBackupManager: conectate.com.data.local.prefs.SettingsBackupManager,
) : ViewModel() {

    val isDarkMode: StateFlow<Boolean> = themePreferences.isDarkMode
    val appTheme: StateFlow<AppThemeOption> = themePreferences.appTheme
    val fontOption: StateFlow<AppFontOption> = themePreferences.fontOption
    val textScale: StateFlow<Float> = themePreferences.textScale
    val profileAvatarVideosMuted: StateFlow<Boolean> = themePreferences.profileAvatarVideosMuted

    fun setDarkMode(enabled: Boolean) {
        themePreferences.setDarkMode(enabled)
    }

    fun setAppTheme(theme: AppThemeOption) {
        themePreferences.setAppTheme(theme)
    }

    fun setFontOption(option: AppFontOption) {
        themePreferences.setFontOption(option)
    }

    fun setTextScale(scale: Float) {
        themePreferences.setTextScale(scale)
    }

    fun setProfileAvatarVideosMuted(enabled: Boolean) {
        themePreferences.setProfileAvatarVideosMuted(enabled)
    }

    fun exportSettingsBackup(uri: Uri, accountEmail: String? = null): Result<Unit> =
        settingsBackupManager.exportTo(uri, accountEmail)

    fun importSettingsBackup(uri: Uri): Result<Unit> = settingsBackupManager.importFrom(uri)

    fun scheduleAndroidBackup(accountEmail: String? = null): Result<Instant> =
        settingsBackupManager.scheduleAndroidBackup(accountEmail)

    fun lastAndroidBackupRequest(): Instant? = settingsBackupManager.lastAndroidBackupRequest()

    private val _settingsState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val settingsState: StateFlow<SettingsUiState> = _settingsState.asStateFlow()
    private val _additionalLoginEmails = MutableStateFlow<List<String>>(emptyList())
    val additionalLoginEmails: StateFlow<List<String>> = _additionalLoginEmails.asStateFlow()
    private val _emailSettingsMessage = MutableStateFlow<String?>(null)
    val emailSettingsMessage: StateFlow<String?> = _emailSettingsMessage.asStateFlow()
    private val _privacyAccepted = MutableStateFlow(false)
    val privacyAccepted: StateFlow<Boolean> = _privacyAccepted.asStateFlow()
    private val _privacyMessage = MutableStateFlow<String?>(null)
    val privacyMessage: StateFlow<String?> = _privacyMessage.asStateFlow()
    private val _webSessions = MutableStateFlow<List<WebLoginManagedSession>>(emptyList())
    val webSessions: StateFlow<List<WebLoginManagedSession>> = _webSessions.asStateFlow()
    private val _webSessionsLoading = MutableStateFlow(false)
    val webSessionsLoading: StateFlow<Boolean> = _webSessionsLoading.asStateFlow()
    private val _webSessionMessage = MutableStateFlow<String?>(null)
    val webSessionMessage: StateFlow<String?> = _webSessionMessage.asStateFlow()
    private val _adminPanelAccess = MutableStateFlow(false)
    val adminPanelAccess: StateFlow<Boolean> = _adminPanelAccess.asStateFlow()
    private val _appearanceSaving = MutableStateFlow(false)
    val appearanceSaving: StateFlow<Boolean> = _appearanceSaving.asStateFlow()
    private val _appearanceMessage = MutableStateFlow<String?>(null)
    val appearanceMessage: StateFlow<String?> = _appearanceMessage.asStateFlow()
    private val _profileMusicUploading = MutableStateFlow(false)
    val profileMusicUploading: StateFlow<Boolean> = _profileMusicUploading.asStateFlow()
    private val _profileMusicUploadProgress = MutableStateFlow(0f)
    val profileMusicUploadProgress: StateFlow<Float> = _profileMusicUploadProgress.asStateFlow()
    private val _profileMusicSelectedName = MutableStateFlow<String?>(null)
    val profileMusicSelectedName: StateFlow<String?> = _profileMusicSelectedName.asStateFlow()
    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount: StateFlow<Boolean> = _isDeletingAccount.asStateFlow()
    private val _deleteAccountMessage = MutableStateFlow<String?>(null)
    val deleteAccountMessage: StateFlow<String?> = _deleteAccountMessage.asStateFlow()
    private val _accountDeleted = MutableStateFlow(false)
    val accountDeleted: StateFlow<Boolean> = _accountDeleted.asStateFlow()
    private val _serverTwoFactorStatus = MutableStateFlow<ServerTwoFactorStatus?>(null)
    val serverTwoFactorStatus: StateFlow<ServerTwoFactorStatus?> = _serverTwoFactorStatus.asStateFlow()
    private val _serverTwoFactorSetup = MutableStateFlow<ServerTwoFactorSetup?>(null)
    val serverTwoFactorSetup: StateFlow<ServerTwoFactorSetup?> = _serverTwoFactorSetup.asStateFlow()
    private val _serverRecoveryCodes = MutableStateFlow<List<String>>(emptyList())
    val serverRecoveryCodes: StateFlow<List<String>> = _serverRecoveryCodes.asStateFlow()
    private val _twoFactorBusy = MutableStateFlow(false)
    val twoFactorBusy: StateFlow<Boolean> = _twoFactorBusy.asStateFlow()
    private val _twoFactorMessage = MutableStateFlow<String?>(null)
    val twoFactorMessage: StateFlow<String?> = _twoFactorMessage.asStateFlow()
    private val _twoFactorDisabled = MutableStateFlow(false)
    val twoFactorDisabled: StateFlow<Boolean> = _twoFactorDisabled.asStateFlow()
    private val _passwordRecoveryRemaining = MutableStateFlow<Int?>(null)
    val passwordRecoveryRemaining: StateFlow<Int?> = _passwordRecoveryRemaining.asStateFlow()
    private val _passwordRecoveryCodes = MutableStateFlow<List<String>>(emptyList())
    val passwordRecoveryCodes: StateFlow<List<String>> = _passwordRecoveryCodes.asStateFlow()
    private val _passwordRecoveryBusy = MutableStateFlow(false)
    val passwordRecoveryBusy: StateFlow<Boolean> = _passwordRecoveryBusy.asStateFlow()
    private val _passwordRecoveryMessage = MutableStateFlow<String?>(null)
    val passwordRecoveryMessage: StateFlow<String?> = _passwordRecoveryMessage.asStateFlow()
    
    private var currentUser: User? = null
    private val accountTasks = conectate.com.core.concurrency.AccountTasks(viewModelScope)
    private var settingsAccount = authRepository.getCurrentUserId()

    private fun isNovvinSupportAdmin(adminName: String): Boolean =
        adminName.trim().lowercase() in setOf("novvin", "noobin", "conectate")

    private suspend fun resolveSupportAdmin(adminName: String): User? {
        val isNovvin = isNovvinSupportAdmin(adminName)
        val usernames = if (isNovvin) {
            listOf("novvin", "conectate", "gamergamerff004", "gamergamerff017")
        } else {
            listOf("alvaro")
        }
        val emails = if (isNovvin) {
            listOf(
                "gamergamerff004@gmail.com",
                "gamergamerff017@gmail.com",
                "conectate@admin.com"
            )
        } else {
            listOf("alvaromendozamarin004@gmail.com")
        }

        for (username in usernames) {
            val candidate = userRepository.getUserByUsername(username).getOrNull()
            if (candidate?.is_admin == true && candidate.id.isNotBlank()) return candidate
        }
        for (email in emails) {
            val candidate = userRepository.getUserByEmail(email).getOrNull()
            if (candidate?.is_admin == true && candidate.id.isNotBlank()) return candidate
        }
        return null
    }

    private suspend fun resolveSupportAdminId(adminName: String): String? {
        if (localFallbackApiClient.isConfigured) {
            val owner = authRepository.getCurrentUserId()
            val token = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
            if (owner == null || owner != authRepository.getCurrentUserId())
                throw kotlinx.coroutines.CancellationException("La cuenta cambió al abrir soporte.")
            check(token.isNotBlank()) { "Inicia sesión para contactar con soporte." }
            // An unavailable authoritative directory must not fall back to a mutable handle.
            return localFallbackApiClient.resolveSupportAdminId(token, adminName).getOrThrow()
                .trim().takeIf(String::isNotBlank)
        }
        return resolveSupportAdmin(adminName)?.id?.trim()?.takeIf { it.isNotBlank() }
    }

    fun contactSupport(adminName: String, navController: NavController, context: android.content.Context,
        initialMessage: String = "Hola $adminName, necesito ayuda con la aplicación.") {
        val owner = authRepository.getCurrentUserId() ?: return
        android.widget.Toast.makeText(context, "Conectando con $adminName...", android.widget.Toast.LENGTH_SHORT).show()
        accountTasks.scope.launch {
            try {
                if (owner != authRepository.getCurrentUserId()) throw kotlinx.coroutines.CancellationException("La cuenta cambió.")
                val targetAdminId = resolveSupportAdminId(adminName)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (owner != authRepository.getCurrentUserId()) throw kotlinx.coroutines.CancellationException("La cuenta cambió.")
                if (targetAdminId != null) {
                    messageRepository.getOrCreateChat(targetAdminId)
                        .onSuccess {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (owner != authRepository.getCurrentUserId()) throw kotlinx.coroutines.CancellationException("La cuenta cambió.")
                            val route = Screen.Chat.createRoute(
                                userId = targetAdminId,
                                initialMessage = initialMessage
                            )
                            navController.navigate(route)
                        }
                        .onFailure { error ->
                            android.util.Log.e("SettingsViewModel", "Support chat creation failed", error)
                            conectate.com.presentation.common.AppFeedbackBus.showError(
                                error.toUserFacingMessage("No se pudo abrir el chat de soporte.")
                            )
                        }
                } else {
                    conectate.com.presentation.common.AppFeedbackBus.showError(
                        "No encontramos la cuenta de $adminName. Inténtalo nuevamente."
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "Contact support failed", e)
                conectate.com.presentation.common.AppFeedbackBus.showError(
                    e.toUserFacingMessage("No se pudo abrir el chat de soporte.")
                )
            }
        }
    }

    private fun loadUserSettings() {
        val userId = authRepository.getCurrentUserId() ?: run {
            _settingsState.value = SettingsUiState.Error("Inicia sesión para abrir los ajustes."); return
        }
        accountTasks.scope.launch {
            _settingsState.value = SettingsUiState.Loading
            val result = conectate.com.core.network.runReadWithDeadline(10_000) { userRepository.getUserById(userId).getOrThrow() }
            if (authRepository.getCurrentUserId() != userId) return@launch
            result.fold(onSuccess = { user ->
                if (user == null) _settingsState.value = SettingsUiState.Error("No se pudo encontrar tu perfil.")
                else { currentUser = user; _privacyAccepted.value = user.privacy_policy_accepted; _settingsState.value = SettingsUiState.Success(user) }
            }, onFailure = { _settingsState.value = SettingsUiState.Error("No se pudieron cargar los ajustes. Reintenta.") })
        }
    }

    fun refreshSettings() {
        loadUserSettings()
        loadAdditionalLoginEmails()
        refreshAdminPanelAccess()
    }

    private val adminAccessCheck by lazy { AdminAccessCheck(
        scope = { accountTasks.scope }, account = authRepository::getCurrentUserId,
        request = { owner ->
            val token = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
            if (owner != authRepository.getCurrentUserId()) throw kotlinx.coroutines.CancellationException("La cuenta cambió.")
            if (token.isBlank()) Result.failure(IllegalStateException("Inicia sesión para comprobar tus permisos."))
            else localFallbackApiClient.adminCheckAccess(token)
        },
        publish = { decision ->
            _adminPanelAccess.value = decision.granted
            if (decision.unavailable && currentUser?.is_admin == true) {
                conectate.com.presentation.common.AppFeedbackBus.showError("No pudimos comprobar tus permisos de administración. Reintenta.")
            }
        },
    ) }

    fun refreshAdminPanelAccess() = adminAccessCheck.refresh()

    fun acceptPrivacyPolicy() {
        accountTasks.scope.launch {
            val result = userRepository.acceptPrivacyPolicy()
            if (result.isSuccess) {
                _privacyAccepted.value = true
                _privacyMessage.value = "Políticas de privacidad aceptadas."
                loadUserSettings()
            } else {
                _privacyMessage.value = result.exceptionOrNull()?.message ?: "No se pudo registrar aceptación."
            }
        }
    }

    fun clearPrivacyMessage() {
        _privacyMessage.value = null
    }

    fun saveProfileAppearance(
        theme: AppThemeOption,
        effect: String,
        welcome: String
    ) {
        val user = currentUser
        if (user == null) {
            _appearanceMessage.value = "No se pudo cargar tu perfil."
            return
        }
        val normalizedEffect = effect.trim().lowercase()
            .takeIf {
                it in setOf(
                    "none", "sparkles", "constellation", "confetti",
                    "waves", "orbit", "rain", "equalizer"
                )
            }
            ?: "none"
        val normalizedWelcome = welcome.trim().take(120)

        if (_appearanceSaving.value) return
        _appearanceSaving.value = true
        accountTasks.scope.launch {
            try {
            val result = userRepository.updateProfile(
                user.id,
                UpdateProfileRequest(
                    profile_theme = theme.storageValue,
                    profile_effect = normalizedEffect,
                    profile_welcome = normalizedWelcome
                )
            )
            if (result.isSuccess) {
                // El servidor responde 2xx solo tras persistir el perfil; la verificación estricta
                // por igualdad exacta daba falsos "no confirmó" por el round-trip JSON. Se confía en
                // el éxito del servidor y se refresca la UI con el perfil persistido.
                val persisted = conectate.com.core.network.runReadWithDeadline(10_000) { userRepository.getUserById(user.id, forceNetwork = true).getOrThrow() }.getOrNull()
                    ?: userRepository.getCachedUserSnapshot(user.id)
                if (persisted == null) {
                    _appearanceMessage.value = "El servidor guardó el perfil, pero no fue posible recargarlo."
                } else {
                    currentUser = persisted
                    _settingsState.value = SettingsUiState.Success(persisted)
                    _appearanceMessage.value = "Perfil actualizado."
                }
            } else {
                _appearanceMessage.value =
                    result.exceptionOrNull()?.message ?: "No se pudo actualizar el perfil."
            }
            } finally { _appearanceSaving.value = false }
        }
    }

    fun saveProfileCustomization(
        theme: AppThemeOption,
        effect: String,
        welcome: String,
        interests: List<String>,
        hobbies: List<String>,
        linkedGames: List<String>,
        gameLogoUrls: Map<String, String>,
        favoriteMusicUrl: String,
        favoriteBook: String,
        favoriteSport: String,
        career: String,
        availabilitySchedule: String
    ) {
        val user = currentUser
        if (user == null) {
            _appearanceMessage.value = "No se pudo cargar tu perfil."
            return
        }
        val normalizedEffect = effect.trim().lowercase()
            .takeIf {
                it in setOf(
                    "none", "sparkles", "constellation", "confetti",
                    "waves", "orbit", "rain", "equalizer"
                )
            }
            ?: "none"
        val normalizedWelcome = welcome.trim().take(120)
        fun normalizedList(values: List<String>, limit: Int): List<String> = values
            .map { it.trim().take(80) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(limit)
        val normalizedInterests = normalizedList(interests, 40)
        val normalizedHobbies = normalizedList(hobbies, 40)
        val normalizedGames = normalizedList(linkedGames, 20)
        val gameNames = normalizedGames.associateBy { it.lowercase() }
        val normalizedLogos = gameLogoUrls.mapNotNull { (game, url) ->
            val normalizedGame = gameNames[game.trim().lowercase()] ?: return@mapNotNull null
            val normalizedUrl = url.trim().take(700)
            if (!normalizedUrl.startsWith("https://") && !normalizedUrl.startsWith("http://")) {
                return@mapNotNull null
            }
            normalizedGame to normalizedUrl
        }.toMap()

        if (_appearanceSaving.value) return
        _appearanceSaving.value = true
        accountTasks.scope.launch {
            try {
            val result = userRepository.updateProfile(
                user.id,
                UpdateProfileRequest(
                    profile_theme = theme.storageValue,
                    profile_effect = normalizedEffect,
                    profile_welcome = normalizedWelcome,
                    interests = normalizedInterests,
                    hobbies = normalizedHobbies,
                    linked_games = normalizedGames,
                    game_logo_urls = normalizedLogos,
                    favorite_music_url = favoriteMusicUrl.trim().take(700),
                    favorite_book = favoriteBook.trim().take(160),
                    favorite_sport = favoriteSport.trim().take(160),
                    career = career.trim().take(180),
                    availability_schedule = availabilitySchedule.trim().take(280)
                )
            )
            if (result.isSuccess) {
                // El servidor responde 2xx solo DESPUÉS de escribir el perfil (si el INSERT falla,
                // devuelve error y result.isFailure). La verificación estricta anterior comparaba con
                // igualdad exacta el perfil re-descargado (listas/mapas incluidas), lo que producía
                // falsos "no confirmó" por diferencias de formato/orden del round-trip JSON aunque el
                // guardado SÍ funcionara. Se confía en el éxito del servidor y se refresca la UI con
                // el perfil persistido (fetch de red) cuando está disponible.
                val persisted = conectate.com.core.network.runReadWithDeadline(10_000) { userRepository.getUserById(user.id, forceNetwork = true).getOrThrow() }.getOrNull()
                    ?: userRepository.getCachedUserSnapshot(user.id)
                if (persisted == null) {
                    _appearanceMessage.value = "El servidor guardó el perfil, pero no fue posible recargarlo."
                } else {
                    currentUser = persisted
                    _settingsState.value = SettingsUiState.Success(persisted)
                    _appearanceMessage.value = "Perfil y recomendaciones actualizados."
                }
            } else {
                _appearanceMessage.value =
                    result.exceptionOrNull()?.message ?: "No se pudo actualizar el perfil."
            }
            } finally { _appearanceSaving.value = false }
        }
    }

    fun uploadProfileMusic(
        uri: Uri,
        displayName: String?,
        contentTypeHint: String? = null,
        deleteTemporaryFile: Boolean = false,
        onUploaded: (String) -> Unit
    ) {
        val user = currentUser
        if (user == null) {
            _appearanceMessage.value = "No se pudo cargar tu perfil."
            return
        }
        if (_profileMusicUploading.value) return

        accountTasks.scope.launch {
            _profileMusicUploading.value = true
            _profileMusicUploadProgress.value = 0f
            _profileMusicSelectedName.value = displayName
                ?.trim()
                ?.take(160)
                ?.takeIf(String::isNotBlank)

            val previousMusicUrl = user.favorite_music_url.orEmpty()
            fun deleteTemporaryClip() {
                if (deleteTemporaryFile && uri.scheme.equals("file", ignoreCase = true)) {
                    uri.path?.let { path -> runCatching { java.io.File(path).delete() } }
                }
            }

            val upload = userRepository.uploadProfileMusic(uri, contentTypeHint) { progress ->
                _profileMusicUploadProgress.value = progress.coerceIn(0f, 1f)
            }
            val uploadedUrl = upload.getOrElse { error ->
                _appearanceMessage.value =
                    error.message ?: "No se pudo subir la canción seleccionada."
                _profileMusicUploading.value = false
                deleteTemporaryClip()
                return@launch
            }

            val update = userRepository.updateProfile(
                user.id,
                UpdateProfileRequest(favorite_music_url = uploadedUrl)
            )
            if (update.isFailure) {
                userRepository.deleteUploadedProfileMusic(uploadedUrl)
                _appearanceMessage.value =
                    update.exceptionOrNull()?.message ?: "No se pudo guardar la canción en el perfil."
                _profileMusicUploading.value = false
                deleteTemporaryClip()
                return@launch
            }

            val persisted = userRepository.getUserById(user.id, forceNetwork = true).getOrNull()
                ?: user.copy(favorite_music_url = uploadedUrl)
            currentUser = persisted
            _settingsState.value = SettingsUiState.Success(persisted)
            _profileMusicUploadProgress.value = 1f
            onUploaded(uploadedUrl)
            _appearanceMessage.value = "Canción guardada en tu perfil."

            if (
                previousMusicUrl.isNotBlank() &&
                previousMusicUrl != uploadedUrl
            ) {
                userRepository.deleteUploadedProfileMusic(previousMusicUrl)
            }
            _profileMusicUploading.value = false
            deleteTemporaryClip()
        }
    }

    fun clearAppearanceMessage() {
        _appearanceMessage.value = null
    }

    fun loadWebSessions() {
        if (_webSessionsLoading.value) return
        accountTasks.scope.launch {
            _webSessionsLoading.value = true
            _webSessionMessage.value = null
            try {
                val accessToken = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
                if (accessToken.isBlank()) {
                    _webSessions.value = emptyList()
                    _webSessionMessage.value = "Inicia sesión nuevamente para revisar tus sesiones web."
                    return@launch
                }
                val result = webLoginPairingRepository.listActiveWebSessions(accessToken)
                if (result.isSuccess) {
                    _webSessions.value = result.getOrDefault(emptyList())
                } else {
                    _webSessionMessage.value =
                        result.exceptionOrNull()?.message ?: "No se pudieron cargar las sesiones web."
                }
            } finally {
                _webSessionsLoading.value = false
            }
        }
    }

    fun revokeWebSession(sessionId: String) {
        if (_webSessionsLoading.value) return
        _webSessionsLoading.value = true
        accountTasks.scope.launch {
            val accessToken = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
            if (accessToken.isBlank()) {
                _webSessionMessage.value = "Inicia sesión nuevamente para cerrar sesiones web."
                _webSessionsLoading.value = false
                return@launch
            }
            _webSessionsLoading.value = true
            val result = webLoginPairingRepository.revokeWebSession(sessionId, accessToken)
            if (result.isSuccess) {
                _webSessionMessage.value = "Sesión web cerrada."
                _webSessions.value = _webSessions.value.filterNot { it.sessionId == sessionId }
                _webSessionsLoading.value = false
            } else {
                _webSessionsLoading.value = false
                _webSessionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo cerrar la sesión web."
            }
        }
    }

    fun revokeAllWebSessions() {
        if (_webSessionsLoading.value) return
        _webSessionsLoading.value = true
        accountTasks.scope.launch {
            val accessToken = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
            if (accessToken.isBlank()) {
                _webSessionMessage.value = "Inicia sesión nuevamente para cerrar sesiones web."
                _webSessionsLoading.value = false
                return@launch
            }
            _webSessionsLoading.value = true
            val result = webLoginPairingRepository.revokeAllWebSessions(accessToken)
            if (result.isSuccess) {
                val count = result.getOrDefault(0)
                _webSessionMessage.value =
                    if (count == 1) "Se cerró 1 sesión web." else "Se cerraron $count sesiones web."
                _webSessions.value = emptyList()
                _webSessionsLoading.value = false
            } else {
                _webSessionsLoading.value = false
                _webSessionMessage.value = result.exceptionOrNull()?.message ?: "No se pudieron cerrar las sesiones web."
            }
        }
    }

    fun loadAdditionalLoginEmails() {
        accountTasks.scope.launch {
            val result = userRepository.getAdditionalLoginEmails()
            if (result.isSuccess) {
                _additionalLoginEmails.value = result.getOrDefault(emptyList())
            }
        }
    }

    fun addAdditionalLoginEmail(email: String) {
        val normalized = email.trim().lowercase()
        if (normalized.isBlank()) {
            _emailSettingsMessage.value = "Ingresa un correo válido."
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalized).matches()) {
            _emailSettingsMessage.value = "Formato de correo inválido."
            return
        }
        if (_additionalLoginEmails.value.contains(normalized)) {
            _emailSettingsMessage.value = "Ese correo ya está agregado."
            return
        }
        if (_additionalLoginEmails.value.size >= 3) {
            _emailSettingsMessage.value = "Solo puedes agregar hasta 3 correos adicionales."
            return
        }

        accountTasks.scope.launch {
            val result = userRepository.addAdditionalLoginEmail(normalized)
            if (result.isSuccess) {
                _emailSettingsMessage.value = "Correo adicional agregado."
                loadAdditionalLoginEmails()
            } else {
                _emailSettingsMessage.value = result.exceptionOrNull()?.message ?: "No se pudo agregar el correo."
            }
        }
    }

    fun removeAdditionalLoginEmail(email: String) {
        accountTasks.scope.launch {
            val result = userRepository.removeAdditionalLoginEmail(email)
            if (result.isSuccess) {
                _emailSettingsMessage.value = "Correo eliminado."
                loadAdditionalLoginEmails()
            } else {
                _emailSettingsMessage.value = result.exceptionOrNull()?.message ?: "No se pudo eliminar el correo."
            }
        }
    }

    fun clearEmailMessage() {
        _emailSettingsMessage.value = null
    }



    fun updatePrivacy(isPrivate: Boolean, onResult: (Boolean) -> Unit = {}) {
        val user = currentUser ?: return onResult(false)
        accountTasks.scope.launch {
            val result = userRepository.updateProfile(user.id, UpdateProfileRequest(is_private = isPrivate))
            if (result.isSuccess) {
                val updatedUser = (currentUser?.takeIf { it.id == user.id } ?: user).copy(is_private = isPrivate)
                currentUser = updatedUser
                _settingsState.value = SettingsUiState.Success(updatedUser)
            }
            onResult(result.isSuccess)
        }
    }

    fun updateVisibility(visibility: String, onResult: (Boolean) -> Unit = {}) {
        val user = currentUser ?: return onResult(false)
        val normalized = visibility.takeIf { it in setOf("everyone", "connections") }
            ?: return onResult(false)
        accountTasks.scope.launch {
            val result = userRepository.updateProfile(user.id, UpdateProfileRequest(profile_visibility = normalized))
            if (result.isSuccess) {
                val updatedUser = (currentUser?.takeIf { it.id == user.id } ?: user).copy(profile_visibility = normalized)
                currentUser = updatedUser
                _settingsState.value = SettingsUiState.Success(updatedUser)
            }
            onResult(result.isSuccess)
        }
    }

    fun updateFavoritesVisibility(isPublic: Boolean, onResult: (Boolean) -> Unit = {}) {
        val user = currentUser ?: return onResult(false)
        accountTasks.scope.launch {
            val result = userRepository.updateProfile(
                user.id,
                UpdateProfileRequest(favorites_public = isPublic)
            )
            if (result.isSuccess) {
                val updatedUser = (currentUser?.takeIf { it.id == user.id } ?: user).copy(favorites_public = isPublic)
                currentUser = updatedUser
                _settingsState.value = SettingsUiState.Success(updatedUser)
            }
            onResult(result.isSuccess)
        }
    }

    fun loadServerTwoFactorStatus() {
        if (!localFallbackApiClient.isConfigured) return
        accountTasks.scope.launch {
            authRepository.serverTwoFactorStatus().fold(
                onSuccess = {
                    _serverTwoFactorStatus.value = it
                    if (!it.enabled && authRepository.isTwoFactorAuthEnabled()) {
                        _twoFactorMessage.value =
                            "La verificación antigua guardada solo en este teléfono no protege los accesos nuevos. Activa aquí la verificación del servidor."
                    }
                },
                onFailure = { _twoFactorMessage.value = it.toUserFacingMessage() }
            )
        }
    }

    fun setupServerTwoFactor(password: String) {
        if (_twoFactorBusy.value) return
        _twoFactorBusy.value = true
        accountTasks.scope.launch {
            try {
            _twoFactorMessage.value = null
            authRepository.setupServerTwoFactor(password).fold(
                onSuccess = { _serverTwoFactorSetup.value = it },
                onFailure = { _twoFactorMessage.value = it.toUserFacingMessage() }
            )
            } finally { _twoFactorBusy.value = false }
        }
    }

    fun enableServerTwoFactor(password: String, code: String) {
        if (_twoFactorBusy.value) return
        _twoFactorBusy.value = true
        accountTasks.scope.launch {
            try {
            _twoFactorMessage.value = null
            authRepository.enableServerTwoFactor(password, code).fold(
                onSuccess = { codes ->
                    _serverRecoveryCodes.value = codes
                    _serverTwoFactorSetup.value = null
                    _serverTwoFactorStatus.value = ServerTwoFactorStatus(true, codes.size)
                    _twoFactorMessage.value = "Verificación activada. Guarda los códigos de respaldo ahora; se muestran una sola vez."
                },
                onFailure = { _twoFactorMessage.value = it.toUserFacingMessage() }
            )
            } finally { _twoFactorBusy.value = false }
        }
    }

    fun disableServerTwoFactor(password: String, code: String) {
        if (_twoFactorBusy.value) return
        _twoFactorBusy.value = true
        accountTasks.scope.launch {
            try {
            _twoFactorMessage.value = null
            authRepository.disableServerTwoFactor(password, code).fold(
                onSuccess = {
                    _serverTwoFactorStatus.value = ServerTwoFactorStatus(false)
                    _serverRecoveryCodes.value = emptyList()
                    _twoFactorDisabled.value = true
                },
                onFailure = { _twoFactorMessage.value = it.toUserFacingMessage() }
            )
            } finally { _twoFactorBusy.value = false }
        }
    }

    fun clearDisplayedRecoveryCodes() {
        _serverRecoveryCodes.value = emptyList()
    }

    fun loadPasswordRecoveryCodeStatus() {
        if (!localFallbackApiClient.isConfigured) return
        accountTasks.scope.launch {
            authRepository.passwordRecoveryCodeStatus().fold(
                onSuccess = { _passwordRecoveryRemaining.value = it.recoveryCodesRemaining },
                onFailure = { _passwordRecoveryMessage.value = it.toUserFacingMessage() }
            )
        }
    }

    fun rotatePasswordRecoveryCodes(password: String, secondFactorCode: String?) {
        if (_passwordRecoveryBusy.value) return
        _passwordRecoveryBusy.value = true
        accountTasks.scope.launch {
            try {
            _passwordRecoveryMessage.value = null
            authRepository.rotatePasswordRecoveryCodes(password, secondFactorCode).fold(
                onSuccess = { codes ->
                    _passwordRecoveryCodes.value = codes
                    _passwordRecoveryRemaining.value = codes.size
                    _passwordRecoveryMessage.value =
                        "Guarda los códigos fuera de este teléfono. Los anteriores ya no funcionan."
                },
                onFailure = { _passwordRecoveryMessage.value = it.toUserFacingMessage() }
            )
            } finally { _passwordRecoveryBusy.value = false }
        }
    }

    fun clearDisplayedPasswordRecoveryCodes() {
        _passwordRecoveryCodes.value = emptyList()
    }

    fun updateProfileViewHistory(enabled: Boolean, onResult: (Boolean) -> Unit = {}) {
        val user = currentUser ?: return onResult(false)
        accountTasks.scope.launch {
            val result = userRepository.updateProfile(
                user.id,
                UpdateProfileRequest(profile_view_history_enabled = enabled)
            )
            if (result.isSuccess) {
                if (!enabled) userRepository.clearProfileViewHistory()
                val updatedUser = (currentUser?.takeIf { it.id == user.id } ?: user).copy(profile_view_history_enabled = enabled)
                currentUser = updatedUser
                _settingsState.value = SettingsUiState.Success(updatedUser)
            }
            onResult(result.isSuccess)
        }
    }

    fun updateBreakMode(mode: String, onResult: (Boolean) -> Unit = {}) {
        val user = currentUser ?: return onResult(false)
        val normalized = mode.takeIf { it in setOf("off", "mute", "hidden") }
            ?: return onResult(false)
        accountTasks.scope.launch {
            val result = userRepository.updateProfile(
                user.id,
                UpdateProfileRequest(break_mode = normalized)
            )
            if (result.isSuccess) {
                val updatedUser = (currentUser?.takeIf { it.id == user.id } ?: user).copy(break_mode = normalized)
                currentUser = updatedUser
                _settingsState.value = SettingsUiState.Success(updatedUser)
            }
            onResult(result.isSuccess)
        }
    }

    fun deleteOwnAccount() {
        if (_isDeletingAccount.value) return
        _isDeletingAccount.value = true
        accountTasks.scope.launch {
            try {
            _deleteAccountMessage.value = null
            val result = userRepository.deleteOwnAccount()
            if (result.isSuccess) {
                authRepository.signOut()
                currentUser = null
                _accountDeleted.value = true
            } else {
                _deleteAccountMessage.value = result.exceptionOrNull()?.message
                    ?: "No se pudo eliminar la cuenta. Intenta nuevamente."
            }
            } finally { _isDeletingAccount.value = false }
        }
    }

    fun consumeAccountDeleted() {
        _accountDeleted.value = false
    }

    init {
        viewModelScope.launch {
            authRepository.getAuthStateFlow().map { it?.user?.id }.distinctUntilChanged().collect { account ->
                if (account != settingsAccount) {
                    settingsAccount = account
                    accountTasks.reset()
                    currentUser = null
                    _serverRecoveryCodes.value = emptyList(); _passwordRecoveryCodes.value = emptyList()
                    _serverTwoFactorSetup.value = null; _serverTwoFactorStatus.value = null
                    _webSessions.value = emptyList(); _additionalLoginEmails.value = emptyList()
                    _webSessionsLoading.value = false; _twoFactorBusy.value = false; _passwordRecoveryBusy.value = false
                    _appearanceSaving.value = false; _profileMusicUploading.value = false; _isDeletingAccount.value = false
                    _privacyAccepted.value = false; _adminPanelAccess.value = false
                    _settingsState.value = SettingsUiState.Error("La sesión cambió. Vuelve a abrir los ajustes.")
                }
            }
        }
        loadUserSettings()
        loadServerTwoFactorStatus()
        loadPasswordRecoveryCodeStatus()
        loadAdditionalLoginEmails()
        loadWebSessions()
        refreshAdminPanelAccess()
    }

    val isAdmin: Boolean
        get() = _adminPanelAccess.value
}
