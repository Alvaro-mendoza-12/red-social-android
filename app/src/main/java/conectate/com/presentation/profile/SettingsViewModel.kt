package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
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
    private val webLoginPairingRepository: WebLoginPairingRepository
) : ViewModel() {

    val isDarkMode: StateFlow<Boolean> = themePreferences.isDarkMode
    val profileAvatarVideosMuted: StateFlow<Boolean> = themePreferences.profileAvatarVideosMuted

    fun setDarkMode(enabled: Boolean) {
        themePreferences.setDarkMode(enabled)
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
    
    private var currentUser: User? = null

    init {
        loadUserSettings()
        loadAdditionalLoginEmails()
        loadWebSessions()
        resolveAdminIds()
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
        android.widget.Toast.makeText(context, "Conectando con $adminName...", android.widget.Toast.LENGTH_SHORT).show()
        viewModelScope.launch {
            try {
                var adminId = if (adminName.equals("conectate", ignoreCase = true)) _conectateAdminId.value else _alvaroAdminId.value

                if (adminId.isNullOrBlank()) {
                    if (adminName.equals("conectate", ignoreCase = true)) {
                        val conectateUser = userRepository.getUserByUsername("conectate").getOrNull()
                            ?: userRepository.getUserByUsername("Conectate").getOrNull()
                            ?: userRepository.getUserByUsername("gamergamerff017").getOrNull()
                            ?: userRepository.getUserByEmail("conectate@admin.com").getOrNull()
                            ?: userRepository.getUserByEmail("gamergamerff017@gmail.com").getOrNull()
                        adminId = conectateUser?.id
                    } else {
                        val alvaroUser = userRepository.getUserByUsername("alvaro").getOrNull()
                            ?: userRepository.getUserByUsername("Alvaro").getOrNull()
                            ?: userRepository.getUserByEmail("alvaromendozamarin004@gmail.com").getOrNull()
                        adminId = alvaroUser?.id
                    }
                }

                if (!adminId.isNullOrBlank()) {
                    val route = Screen.Chat.createRoute(
                        userId = adminId,
                        initialMessage = "Hola $adminName, necesito ayuda con la aplicación."
                    )
                    navController.navigate(route)
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



    fun updatePrivacy(isPrivate: Boolean) {
        // Feature disabled: Column 'is_private' missing in DB
        /*
        val user = currentUser ?: return
        viewModelScope.launch {
            // Using UpdateProfileRequest with other fields null (omitted via encodeDefaults=false)
            val result = userRepository.updateProfile(user.id, UpdateProfileRequest(is_private = isPrivate))
            if (result.isSuccess) {
                 val updatedUser = user.copy(is_private = isPrivate)
                 currentUser = updatedUser
                 _settingsState.value = SettingsUiState.Success(updatedUser)
            }
        }
        */
    }

    fun updateVisibility(visibility: String) {
        // Feature disabled: Column 'profile_visibility' missing in DB
        /*
        val user = currentUser ?: return
        viewModelScope.launch {
            val result = userRepository.updateProfile(user.id, UpdateProfileRequest(profile_visibility = visibility))
            if (result.isSuccess) {
                val updatedUser = user.copy(profile_visibility = visibility)  // ✅ FIXED: Intermediate variable
                currentUser = updatedUser
                _settingsState.value = SettingsUiState.Success(updatedUser)  // ✅ FIXED: No more !!
            }
        }
        */
    }

    val isAdmin: Boolean
        get() {
            // 1. Check email first (failsafe)
            val email = authRepository.getCurrentUserEmail()?.lowercase()?.trim() ?: ""
            val isDesignatedAdmin = email == "gamergamerff004@gmail.com" ||
                                   email == "alvaromendozamarin004@gmail.com" ||
                                   email == "gamergamerff017@gmail.com"
            
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
