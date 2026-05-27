package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.auth.twoFactor.TwoFactorAuthManager
import conectate.com.auth.twoFactor.TwoFactorAuthStatus
import conectate.com.data.local.prefs.AppFontOption
import conectate.com.data.local.prefs.AppSettingsPreferences
import conectate.com.data.local.prefs.MessagesPreferences
import conectate.com.data.model.User
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import conectate.com.data.model.UpdateProfileRequest
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen

sealed class SettingsUiState {
    object Loading : SettingsUiState()
    data class Success(val user: User) : SettingsUiState()
    data class Error(val message: String) : SettingsUiState()
}

data class TwoFactorSetupUiState(
    val isLoading: Boolean = false,
    val secret: String? = null,
    val qrCodeUrl: String? = null,
    val otpauthUrl: String? = null,
    val backupCodes: List<String> = emptyList(),
    val message: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val messageRepository: conectate.com.data.repository.MessageRepository,
    private val themePreferences: conectate.com.data.local.prefs.ThemePreferences,
    private val messagesPreferences: MessagesPreferences,
    private val appSettingsPreferences: AppSettingsPreferences,
    private val twoFactorAuthManager: TwoFactorAuthManager
) : ViewModel() {

    val isDarkMode: StateFlow<Boolean> = themePreferences.isDarkMode
    val fontOption: StateFlow<AppFontOption> = themePreferences.fontOption
    val textScale: StateFlow<Float> = themePreferences.textScale
    val compactMode: StateFlow<Boolean> = messagesPreferences.compactMode
    val chatSoundsEnabled: StateFlow<Boolean> = messagesPreferences.chatSoundsEnabled
    val typingIndicatorsEnabled: StateFlow<Boolean> = messagesPreferences.typingIndicatorsEnabled
    val connectivityHintsEnabled: StateFlow<Boolean> = messagesPreferences.connectivityHintsEnabled
    val crossAccountSuggestionsEnabled: StateFlow<Boolean> = messagesPreferences.crossAccountSuggestionsEnabled
    val enterToSendEnabled: StateFlow<Boolean> = messagesPreferences.enterToSendEnabled
    val mediaAutoDownloadEnabled: StateFlow<Boolean> = messagesPreferences.mediaAutoDownloadEnabled
    val readReceiptsEnabled: StateFlow<Boolean> = messagesPreferences.readReceiptsEnabled
    val messageNotificationsEnabled: StateFlow<Boolean> = appSettingsPreferences.messageNotificationsEnabled
    val callNotificationsEnabled: StateFlow<Boolean> = appSettingsPreferences.callNotificationsEnabled
    val socialNotificationsEnabled: StateFlow<Boolean> = appSettingsPreferences.socialNotificationsEnabled
    val liveNotificationsEnabled: StateFlow<Boolean> = appSettingsPreferences.liveNotificationsEnabled
    val dataSaverEnabled: StateFlow<Boolean> = appSettingsPreferences.dataSaverEnabled
    val autoDownloadOnWifi: StateFlow<Boolean> = appSettingsPreferences.autoDownloadOnWifi
    val autoDownloadOnMobile: StateFlow<Boolean> = appSettingsPreferences.autoDownloadOnMobile
    val language: StateFlow<String> = appSettingsPreferences.language
    val reduceMotionEnabled: StateFlow<Boolean> = appSettingsPreferences.reduceMotionEnabled
    val bottomNavigationLayout: StateFlow<Int> = appSettingsPreferences.bottomNavigationLayout
    val bottomNavigationOrder: StateFlow<List<String>> = appSettingsPreferences.bottomNavigationOrder

    fun setDarkMode(enabled: Boolean) {
        themePreferences.setDarkMode(enabled)
    }

    fun setFontOption(option: AppFontOption) {
        themePreferences.setFontOption(option)
    }

    fun setTextScale(scale: Float) {
        themePreferences.setTextScale(scale)
    }

    fun setCompactMode(enabled: Boolean) {
        messagesPreferences.setCompactMode(enabled)
    }

    fun setChatSoundsEnabled(enabled: Boolean) {
        messagesPreferences.setChatSoundsEnabled(enabled)
    }

    fun setTypingIndicatorsEnabled(enabled: Boolean) {
        messagesPreferences.setTypingIndicatorsEnabled(enabled)
    }

    fun setConnectivityHintsEnabled(enabled: Boolean) {
        messagesPreferences.setConnectivityHintsEnabled(enabled)
    }

    fun setCrossAccountSuggestionsEnabled(enabled: Boolean) {
        messagesPreferences.setCrossAccountSuggestionsEnabled(enabled)
    }

    fun setEnterToSendEnabled(enabled: Boolean) {
        messagesPreferences.setEnterToSendEnabled(enabled)
    }

    fun setMediaAutoDownloadEnabled(enabled: Boolean) {
        messagesPreferences.setMediaAutoDownloadEnabled(enabled)
    }

    fun setReadReceiptsEnabled(enabled: Boolean) {
        messagesPreferences.setReadReceiptsEnabled(enabled)
    }

    fun setMessageNotificationsEnabled(enabled: Boolean) {
        appSettingsPreferences.setMessageNotificationsEnabled(enabled)
    }

    fun setCallNotificationsEnabled(enabled: Boolean) {
        appSettingsPreferences.setCallNotificationsEnabled(enabled)
    }

    fun setSocialNotificationsEnabled(enabled: Boolean) {
        appSettingsPreferences.setSocialNotificationsEnabled(enabled)
    }

    fun setLiveNotificationsEnabled(enabled: Boolean) {
        appSettingsPreferences.setLiveNotificationsEnabled(enabled)
    }

    fun setDataSaverEnabled(enabled: Boolean) {
        appSettingsPreferences.setDataSaverEnabled(enabled)
    }

    fun setAutoDownloadOnWifi(enabled: Boolean) {
        appSettingsPreferences.setAutoDownloadOnWifi(enabled)
    }

    fun setAutoDownloadOnMobile(enabled: Boolean) {
        appSettingsPreferences.setAutoDownloadOnMobile(enabled)
    }

    fun setLanguage(language: String) {
        appSettingsPreferences.setLanguage(language)
    }

    fun setReduceMotionEnabled(enabled: Boolean) {
        appSettingsPreferences.setReduceMotionEnabled(enabled)
    }

    fun setBottomNavigation(layout: Int, order: List<String>) {
        appSettingsPreferences.setBottomNavigation(layout, order)
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
    private val _accountActionMessage = MutableStateFlow<String?>(null)
    val accountActionMessage: StateFlow<String?> = _accountActionMessage.asStateFlow()
    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount: StateFlow<Boolean> = _isDeletingAccount.asStateFlow()
    private val _twoFactorStatus = MutableStateFlow(authRepository.getTwoFactorAuthStatus())
    val twoFactorStatus: StateFlow<TwoFactorAuthStatus> = _twoFactorStatus.asStateFlow()
    private val _twoFactorSetup = MutableStateFlow(TwoFactorSetupUiState())
    val twoFactorSetup: StateFlow<TwoFactorSetupUiState> = _twoFactorSetup.asStateFlow()
    
    private var currentUser: User? = null

    init {
        loadUserSettings()
        loadAdditionalLoginEmails()
        resolveAdminIds()
        refreshTwoFactorStatus()
    }

    private val _conectateAdminId = MutableStateFlow<String?>(null)
    private val _alvaroAdminId = MutableStateFlow<String?>(null)

    private fun resolveAdminIds() {
        viewModelScope.launch {
            // Try to find by username first, fallback to email if needed
            // "Conectate"
            val conectateUser = userRepository.getUserByUsername("conectate").getOrNull() 
                ?: userRepository.getUserByUsername("Conectate").getOrNull()
                ?: userRepository.getUserByUsername("gamergamerff017").getOrNull() // From screenshot
                ?: userRepository.getUserByEmail("conectate@admin.com").getOrNull() 
                ?: userRepository.getUserByEmail("gamergamerff017@gmail.com").getOrNull()
            
            _conectateAdminId.value = conectateUser?.id
            android.util.Log.d("SettingsViewModel", "Resolved Conectate Admin ID: ${conectateUser?.id}")

            // "Alvaro"
            val alvaroUser = userRepository.getUserByUsername("alvaro").getOrNull()
                ?: userRepository.getUserByUsername("Alvaro").getOrNull()
                ?: userRepository.getUserByEmail("alvaromendozamarin004@gmail.com").getOrNull()
            
            _alvaroAdminId.value = alvaroUser?.id
        }
    }

    fun contactSupport(adminName: String, navController: NavController, context: android.content.Context) {
        val adminId = if (adminName.equals("conectate", ignoreCase = true)) {
            _conectateAdminId.value
        } else {
             _alvaroAdminId.value
        }

        android.util.Log.d("SettingsViewModel", "Contacting support: $adminName, Resolved ID: $adminId")

        if (!adminId.isNullOrBlank()) {
            android.widget.Toast.makeText(context, "Abriendo chat con $adminName...", android.widget.Toast.LENGTH_SHORT).show()
            try {
                val route = Screen.Chat.createRoute(
                    userId = adminId, 
                    initialMessage = "Hola $adminName, necesito ayuda con la aplicación."
                )
                android.util.Log.d("SettingsViewModel", "Navigating to: $route")
                navController.navigate(route)
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "Navigation failed", e)
                android.widget.Toast.makeText(context, "Error de navegación: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            android.widget.Toast.makeText(context, "Admin $adminName no encontrado. Intente más tarde.", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.e("SettingsViewModel", "Admin ID not found for $adminName")
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

    fun deleteOwnAccount(onDeleted: () -> Unit = {}) {
        if (_isDeletingAccount.value) return

        viewModelScope.launch {
            _isDeletingAccount.value = true
            _accountActionMessage.value = null

            val result = userRepository.deleteOwnAccount()
            if (result.isSuccess) {
                _accountActionMessage.value = "Cuenta eliminada correctamente."
                runCatching { authRepository.signOut() }
                onDeleted()
            } else {
                _accountActionMessage.value = result.exceptionOrNull()?.message
                    ?: "No se pudo eliminar la cuenta."
            }

            _isDeletingAccount.value = false
        }
    }

    fun clearAccountActionMessage() {
        _accountActionMessage.value = null
    }

    fun updatePrivacy(isPrivate: Boolean) {
        val user = currentUser ?: return
        viewModelScope.launch {
            val result = userRepository.updateProfile(user.id, UpdateProfileRequest(is_private = isPrivate))
            if (result.isSuccess) {
                 val updatedUser = user.copy(is_private = isPrivate)
                 currentUser = updatedUser
                 _settingsState.value = SettingsUiState.Success(updatedUser)
            } else {
                _privacyMessage.value = result.exceptionOrNull()?.message ?: "No se pudo actualizar la privacidad."
            }
        }
    }

    fun updateVisibility(visibility: String) {
        val user = currentUser ?: return
        viewModelScope.launch {
            val result = userRepository.updateProfile(user.id, UpdateProfileRequest(profile_visibility = visibility))
            if (result.isSuccess) {
                val updatedUser = user.copy(profile_visibility = visibility)
                currentUser = updatedUser
                _settingsState.value = SettingsUiState.Success(updatedUser)
            } else {
                _privacyMessage.value = result.exceptionOrNull()?.message ?: "No se pudo actualizar la visibilidad del perfil."
            }
        }
    }

    fun updatePresenceVisibility(
        lastSeenVisibility: String? = null,
        onlineStatusVisibility: String? = null
    ) {
        val user = currentUser ?: return
        viewModelScope.launch {
            val result = userRepository.updatePresenceVisibility(
                userId = user.id,
                lastSeenVisibility = lastSeenVisibility,
                onlineStatusVisibility = onlineStatusVisibility
            )
            if (result.isSuccess) {
                val updatedUser = user.copy(
                    last_seen_visibility = lastSeenVisibility ?: user.last_seen_visibility,
                    online_status_visibility = onlineStatusVisibility ?: user.online_status_visibility
                )
                currentUser = updatedUser
                _settingsState.value = SettingsUiState.Success(updatedUser)
            } else {
                _privacyMessage.value = result.exceptionOrNull()?.message ?: "No se pudo guardar la visibilidad de presencia."
            }
        }
    }

    fun refreshTwoFactorStatus() {
        _twoFactorStatus.value = authRepository.getTwoFactorAuthStatus()
    }

    fun startTwoFactorSetup() {
        val accountEmail = currentUser?.email?.ifBlank { null }
            ?: authRepository.getCurrentUserEmail()
            ?: return

        viewModelScope.launch {
            _twoFactorSetup.value = _twoFactorSetup.value.copy(isLoading = true, message = null)
            val result = authRepository.setupTwoFactorAuth(accountEmail)
            _twoFactorSetup.value = result.fold(
                onSuccess = { setup ->
                    TwoFactorSetupUiState(
                        isLoading = false,
                        secret = setup.secret ?: twoFactorAuthManager.getPendingSecret(),
                        qrCodeUrl = setup.qrCodeUrl ?: twoFactorAuthManager.getPendingQrCodeUrl(),
                        otpauthUrl = setup.otpauthUrl ?: twoFactorAuthManager.getPendingOtpAuthUrl(),
                        backupCodes = if (setup.backupCodes.isNotEmpty()) {
                            setup.backupCodes
                        } else {
                            twoFactorAuthManager.getPendingBackupCodes()
                        }
                    )
                },
                onFailure = { error ->
                    TwoFactorSetupUiState(
                        isLoading = false,
                        message = error.message ?: "No se pudo preparar 2FA."
                    )
                }
            )
            refreshTwoFactorStatus()
        }
    }

    fun confirmTwoFactorSetup(code: String) {
        val secret = _twoFactorSetup.value.secret ?: twoFactorAuthManager.getPendingSecret()
        if (secret.isNullOrBlank()) {
            _twoFactorSetup.value = _twoFactorSetup.value.copy(
                message = "Primero genera la configuración 2FA."
            )
            return
        }

        viewModelScope.launch {
            _twoFactorSetup.value = _twoFactorSetup.value.copy(isLoading = true, message = null)
            val verification = twoFactorAuthManager.verifyTOTPCode(code, secret)
            if (!verification.success) {
                _twoFactorSetup.value = _twoFactorSetup.value.copy(
                    isLoading = false,
                    message = verification.error ?: "El código 2FA no es válido."
                )
                refreshTwoFactorStatus()
                return@launch
            }

            val enableResult = authRepository.enableTwoFactorAuth(secret)
            _twoFactorSetup.value = enableResult.fold(
                onSuccess = {
                    TwoFactorSetupUiState(message = "Autenticación en dos pasos activada correctamente.")
                },
                onFailure = { error ->
                    _twoFactorSetup.value.copy(
                        isLoading = false,
                        message = error.message ?: "No se pudo activar 2FA."
                    )
                }
            )
            refreshTwoFactorStatus()
        }
    }

    fun disableTwoFactor() {
        viewModelScope.launch {
            _twoFactorSetup.value = _twoFactorSetup.value.copy(isLoading = true, message = null)
            val result = authRepository.disableTwoFactorAuth()
            _twoFactorSetup.value = result.fold(
                onSuccess = {
                    TwoFactorSetupUiState(message = "Autenticación en dos pasos desactivada.")
                },
                onFailure = { error ->
                    _twoFactorSetup.value.copy(
                        isLoading = false,
                        message = error.message ?: "No se pudo desactivar 2FA."
                    )
                }
            )
            refreshTwoFactorStatus()
        }
    }

    fun clearTwoFactorMessage() {
        _twoFactorSetup.value = _twoFactorSetup.value.copy(message = null, isLoading = false)
    }

    val isAdmin: Boolean
        get() {
            // 1. Check email first (failsafe)
            val email = authRepository.getCurrentUserEmail()?.lowercase()?.trim() ?: ""
            val isDesignatedAdmin = email == "gamergamerff017@gmail.com" || 
                                   email == "alvaromendozamarin004@gmail.com" ||
                                   email == "gamergamerff004@gmail.com"
            
            if (isDesignatedAdmin) return true

            // 2. Check database flag
            val state = _settingsState.value
            if (state is SettingsUiState.Success) {
                // If we have a user loaded, check their admin flag
                return state.user.is_admin
            }
            
            return false
        }
}
