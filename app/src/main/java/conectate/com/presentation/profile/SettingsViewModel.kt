package conectate.com.presentation.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.WebLoginManagedSession
import conectate.com.data.repository.WebLoginPairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import conectate.com.data.model.UpdateProfileRequest
import conectate.com.data.local.prefs.AppFontOption
import conectate.com.data.local.prefs.AppThemeOption
import androidx.navigation.NavController
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
    private val localFallbackApiClient: LocalFallbackApiClient
) : ViewModel() {
    private companion object {
        val ALLOWED_ADMIN_EMAILS = setOf(
            "gamergamerff004@gmail.com",
            "alvaromendozamarin004@gmail.com",
            "gamergamerff017@gmail.com"
        )
    }

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
    
    private var currentUser: User? = null

    init {
        loadUserSettings()
        loadAdditionalLoginEmails()
        loadWebSessions()
        resolveAdminIds()
        refreshAdminPanelAccess()
    }

    private val _conectateAdminId = MutableStateFlow<String?>(null)
    private val _alvaroAdminId = MutableStateFlow<String?>(null)

    private fun resolveAdminIds() {
        viewModelScope.launch {
            val conectateUser = resolveSupportAdmin("novvin")
            _conectateAdminId.value = conectateUser?.id
            android.util.Log.d("SettingsViewModel", "Resolved Novvin Admin ID: ${conectateUser?.id}")

            val alvaroUser = resolveSupportAdmin("alvaro")
            _alvaroAdminId.value = alvaroUser?.id
        }
    }

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
            if (!candidate?.id.isNullOrBlank()) return candidate
        }
        for (email in emails) {
            val candidate = userRepository.getUserByEmail(email).getOrNull()
            if (!candidate?.id.isNullOrBlank()) return candidate
        }
        return null
    }

    fun contactSupport(adminName: String, navController: NavController, context: android.content.Context) {
        android.widget.Toast.makeText(context, "Conectando con $adminName...", android.widget.Toast.LENGTH_SHORT).show()
        viewModelScope.launch {
            try {
                val isNovvin = isNovvinSupportAdmin(adminName)
                var adminId = if (isNovvin) _conectateAdminId.value else _alvaroAdminId.value

                if (adminId.isNullOrBlank()) {
                    adminId = resolveSupportAdmin(adminName)?.id
                    if (isNovvin) {
                        _conectateAdminId.value = adminId
                    } else {
                        _alvaroAdminId.value = adminId
                    }
                }

                val targetAdminId = adminId?.trim()?.takeIf { it.isNotBlank() }
                if (targetAdminId != null) {
                    messageRepository.getOrCreateChat(targetAdminId)
                        .onSuccess {
                            val route = Screen.Chat.createRoute(
                                userId = targetAdminId,
                                initialMessage = "Hola $adminName, necesito ayuda con la aplicación."
                            )
                            navController.navigate(route)
                        }
                        .onFailure { error ->
                            android.util.Log.e("SettingsViewModel", "Support chat creation failed", error)
                            android.widget.Toast.makeText(
                                context,
                                error.message ?: "No se pudo abrir el chat de soporte.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                } else {
                    android.widget.Toast.makeText(context, "Admin $adminName no encontrado.", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "Contact support failed", e)
                android.widget.Toast.makeText(context, "Error de red", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadUserSettings() {
        viewModelScope.launch {
            _settingsState.value = SettingsUiState.Loading
            // Assuming AuthRepository has getCurrentUserId or we need to use AuthService
            // If AuthRepository wraps AuthService, it might be named differently
            // For now, let's assume we injected the wrong thing or method is missing.
            // Let's use userRepository if it helps, or fix AuthRepository.
            // Actually, in ProfileViewModel we use AuthService. 
            // In SettingsViewModel we injected AuthRepository.
            // Let's change injection to AuthService for consistency or fix usage
            val userId = authRepository.getCurrentUserId() // If this fails, AuthRepository is missing it.
            if (userId != null) {
                userRepository.getUserById(userId).fold(
                    onSuccess = { user ->
                        if (user != null) {
                            currentUser = user
                            _privacyAccepted.value = user.privacy_policy_accepted
                            _settingsState.value = SettingsUiState.Success(user)
                        } else {
                            _settingsState.value = SettingsUiState.Error("User not found")
                        }
                    },
                    onFailure = {
                        _settingsState.value = SettingsUiState.Error(it.message ?: "Unknown error")
                    }
                )
            }
        }
    }

    fun refreshSettings() {
        loadUserSettings()
        loadAdditionalLoginEmails()
        refreshAdminPanelAccess()
    }

    fun refreshAdminPanelAccess() {
        viewModelScope.launch {
            val email = authRepository.getCurrentUserEmail()?.lowercase()?.trim().orEmpty()
            val isDesignatedAdmin = email in ALLOWED_ADMIN_EMAILS
            // Keep the entry visible while the backend verifies the immutable
            // account id. This also removes the startup race where the profile
            // flag had not loaded yet for one of the three administrator accounts.
            if (isDesignatedAdmin) {
                _adminPanelAccess.value = true
            }
            val token = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
            if (token.isBlank()) {
                _adminPanelAccess.value = isDesignatedAdmin || currentUser?.is_admin == true
                return@launch
            }

            val serverGranted = localFallbackApiClient.adminCheckAccess(token).isSuccess
            val trustedLocalFallback = currentUser?.is_admin == true && isDesignatedAdmin
            _adminPanelAccess.value = serverGranted || trustedLocalFallback
                || isDesignatedAdmin
        }
    }

    fun acceptPrivacyPolicy() {
        viewModelScope.launch {
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

        viewModelScope.launch {
            _appearanceSaving.value = true
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
                val persisted = userRepository.getUserById(user.id, forceNetwork = true).getOrNull()
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
            _appearanceSaving.value = false
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

        viewModelScope.launch {
            _appearanceSaving.value = true
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
                val persisted = userRepository.getUserById(user.id, forceNetwork = true).getOrNull()
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
            _appearanceSaving.value = false
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

        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            val accessToken = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
            if (accessToken.isBlank()) {
                _webSessionMessage.value = "Inicia sesión nuevamente para cerrar sesiones web."
                return@launch
            }
            _webSessionsLoading.value = true
            val result = webLoginPairingRepository.revokeWebSession(sessionId, accessToken)
            if (result.isSuccess) {
                _webSessionMessage.value = "Sesión web cerrada."
                loadWebSessions()
            } else {
                _webSessionsLoading.value = false
                _webSessionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo cerrar la sesión web."
            }
        }
    }

    fun revokeAllWebSessions() {
        viewModelScope.launch {
            val accessToken = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
            if (accessToken.isBlank()) {
                _webSessionMessage.value = "Inicia sesión nuevamente para cerrar sesiones web."
                return@launch
            }
            _webSessionsLoading.value = true
            val result = webLoginPairingRepository.revokeAllWebSessions(accessToken)
            if (result.isSuccess) {
                val count = result.getOrDefault(0)
                _webSessionMessage.value =
                    if (count == 1) "Se cerró 1 sesión web." else "Se cerraron $count sesiones web."
                loadWebSessions()
            } else {
                _webSessionsLoading.value = false
                _webSessionMessage.value = result.exceptionOrNull()?.message ?: "No se pudieron cerrar las sesiones web."
            }
        }
    }

    fun loadAdditionalLoginEmails() {
        viewModelScope.launch {
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

        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            val result = userRepository.updateProfile(user.id, UpdateProfileRequest(is_private = isPrivate))
            if (result.isSuccess) {
                val updatedUser = user.copy(is_private = isPrivate)
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
        viewModelScope.launch {
            val result = userRepository.updateProfile(user.id, UpdateProfileRequest(profile_visibility = normalized))
            if (result.isSuccess) {
                val updatedUser = user.copy(profile_visibility = normalized)
                currentUser = updatedUser
                _settingsState.value = SettingsUiState.Success(updatedUser)
            }
            onResult(result.isSuccess)
        }
    }

    fun updateFavoritesVisibility(isPublic: Boolean, onResult: (Boolean) -> Unit = {}) {
        val user = currentUser ?: return onResult(false)
        viewModelScope.launch {
            val result = userRepository.updateProfile(
                user.id,
                UpdateProfileRequest(favorites_public = isPublic)
            )
            if (result.isSuccess) {
                val updatedUser = user.copy(favorites_public = isPublic)
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
        viewModelScope.launch {
            val result = userRepository.updateProfile(
                user.id,
                UpdateProfileRequest(break_mode = normalized)
            )
            if (result.isSuccess) {
                val updatedUser = user.copy(break_mode = normalized)
                currentUser = updatedUser
                _settingsState.value = SettingsUiState.Success(updatedUser)
            }
            onResult(result.isSuccess)
        }
    }

    fun deleteOwnAccount() {
        if (_isDeletingAccount.value) return
        viewModelScope.launch {
            _isDeletingAccount.value = true
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
            _isDeletingAccount.value = false
        }
    }

    fun consumeAccountDeleted() {
        _accountDeleted.value = false
    }

    val isAdmin: Boolean
        get() = _adminPanelAccess.value
}
