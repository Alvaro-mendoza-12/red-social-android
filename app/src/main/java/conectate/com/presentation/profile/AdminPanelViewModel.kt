package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import javax.inject.Inject

@HiltViewModel
class AdminPanelViewModel @Inject constructor(
    private val authService: AuthService,
    private val localFallbackApiClient: LocalFallbackApiClient
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _suspendedProfiles = MutableStateFlow<List<SuspendedProfile>>(emptyList())
    val suspendedProfiles: StateFlow<List<SuspendedProfile>> = _suspendedProfiles.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun checkAdminAccess() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = authService.ensureValidAccessToken()
                    .getOrElse {
                        _errorMessage.value = "Error de autenticación: ${it.message}"
                        _isLoading.value = false
                        return@launch
                    }

                val result = localFallbackApiClient.adminCheckAccess(token)
                if (result.isSuccess) {
                    _isAdmin.value = true
                    loadSuspendedProfiles(token)
                } else {
                    _isAdmin.value = false
                    _errorMessage.value = "No tienes permisos de administrador."
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadSuspendedProfiles(token: String) {
        try {
            val result = localFallbackApiClient.adminGetSuspendedProfiles(token)
            if (result.isSuccess) {
                _suspendedProfiles.value = result.getOrDefault(emptyList())
                _statusMessage.value = "Datos actualizados correctamente."
            } else {
                _errorMessage.value = "Error al cargar perfiles suspendidos."
            }
        } catch (e: Exception) {
            _errorMessage.value = "Error cargando datos: ${e.message}"
        }
    }

    fun refreshData() {
        checkAdminAccess()
    }
}
