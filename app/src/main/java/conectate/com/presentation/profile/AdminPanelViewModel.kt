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

    private val _userReports = MutableStateFlow<List<conectate.com.data.model.Report>>(emptyList())
    val userReports: StateFlow<List<conectate.com.data.model.Report>> = _userReports.asStateFlow()

    private val _videoReports = MutableStateFlow<List<conectate.com.data.model.VideoReport>>(emptyList())
    val videoReports: StateFlow<List<conectate.com.data.model.VideoReport>> = _videoReports.asStateFlow()

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
                    loadReports(token)
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

    private suspend fun loadReports(token: String) {
        try {
            val userRes = localFallbackApiClient.adminGetReports(token)
            if (userRes.isSuccess) {
                _userReports.value = userRes.getOrDefault(emptyList())
            }
            val videoRes = localFallbackApiClient.adminGetVideoReports(token)
            if (videoRes.isSuccess) {
                _videoReports.value = videoRes.getOrDefault(emptyList())
            }
        } catch (e: Exception) {
            _errorMessage.value = "Error cargando reportes: ${e.message}"
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

    fun suspendUser(userId: String, reason: String, days: Int) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val token = authService.ensureValidAccessToken().getOrThrow()
                val result = localFallbackApiClient.adminSuspendUser(token, userId, reason, days)
                if (result.isSuccess) {
                    _statusMessage.value = "Usuario suspendido correctamente."
                    loadSuspendedProfiles(token)
                } else {
                    _errorMessage.value = "Error al suspender: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateReportStatus(reportId: String, status: String, type: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val token = authService.ensureValidAccessToken().getOrThrow()
                val result = localFallbackApiClient.adminUpdateReportStatus(token, reportId, status, type)
                if (result.isSuccess) {
                    _statusMessage.value = "Estado de reporte actualizado."
                    loadReports(token)
                } else {
                    _errorMessage.value = "Error al actualizar reporte: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
