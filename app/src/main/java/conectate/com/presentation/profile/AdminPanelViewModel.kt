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
import android.util.Log

@HiltViewModel
class AdminPanelViewModel @Inject constructor(
    private val authService: AuthService,
    private val localFallbackApiClient: LocalFallbackApiClient
) : ViewModel() {
    private companion object {
        const val TAG = "AdminPanelViewModel"
    }

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

                val email = authService.getCurrentUserEmail()?.lowercase()?.trim() ?: ""
                val isDesignatedAdmin = email == "gamergamerff004@gmail.com" ||
                                       email == "alvaromendozamarin004@gmail.com" ||
                                       email == "gamergamerff017@gmail.com"

                if (isDesignatedAdmin) {
                    _isAdmin.value = true
                    loadSuspendedProfiles(token)
                    loadReports(token)
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
            } else {
                Log.w(TAG, "No se pudieron cargar reportes de usuarios", userRes.exceptionOrNull())
            }
            val videoRes = localFallbackApiClient.adminGetVideoReports(token)
            if (videoRes.isSuccess) {
                _videoReports.value = videoRes.getOrDefault(emptyList())
            } else {
                Log.w(TAG, "No se pudieron cargar reportes de videos", videoRes.exceptionOrNull())
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron cargar reportes", e)
        }
    }

    private suspend fun loadSuspendedProfiles(token: String) {
        try {
            val result = localFallbackApiClient.adminGetSuspendedProfiles(token)
            if (result.isSuccess) {
                _suspendedProfiles.value = result.getOrDefault(emptyList())
                _statusMessage.value = "Datos actualizados correctamente."
            } else {
                _suspendedProfiles.value = emptyList()
                Log.w(TAG, "No se pudieron cargar perfiles suspendidos", result.exceptionOrNull())
            }
        } catch (e: Exception) {
            _suspendedProfiles.value = emptyList()
            Log.w(TAG, "No se pudieron cargar perfiles suspendidos", e)
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
