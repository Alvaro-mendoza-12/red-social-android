package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.AdminDashboardStats
import conectate.com.data.localfallback.AdminCatalog
import conectate.com.data.localfallback.AdminUser
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

    private val _isLoading = MutableStateFlow(true)
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

    private val _stats = MutableStateFlow(AdminDashboardStats())
    val stats: StateFlow<AdminDashboardStats> = _stats.asStateFlow()

    private val _users = MutableStateFlow<List<AdminUser>>(emptyList())
    val users: StateFlow<List<AdminUser>> = _users.asStateFlow()

    private val _catalog = MutableStateFlow(AdminCatalog())
    val catalog: StateFlow<AdminCatalog> = _catalog.asStateFlow()

    private val _selectedUser = MutableStateFlow<AdminUser?>(null)
    val selectedUser: StateFlow<AdminUser?> = _selectedUser.asStateFlow()

    private val _userSearch = MutableStateFlow("")
    val userSearch: StateFlow<String> = _userSearch.asStateFlow()

    private val _lastDeletedUserId = MutableStateFlow<String?>(null)
    val lastDeletedUserId: StateFlow<String?> = _lastDeletedUserId.asStateFlow()

    private val _deleteCandidate = MutableStateFlow<AdminUser?>(null)
    val deleteCandidate: StateFlow<AdminUser?> = _deleteCandidate.asStateFlow()

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
                    loadStats(token)
                    loadCatalog(token)
                    loadUsers(token, _userSearch.value)
                    loadSuspendedProfiles(token)
                    loadReports(token)
                } else {
                    _isAdmin.value = false
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "No tienes permisos de administrador."
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadStats(token: String) {
        localFallbackApiClient.adminGetStats(token)
            .onSuccess { _stats.value = it }
            .onFailure { Log.w(TAG, "No se pudieron cargar estadísticas administrativas", it) }
    }

    private suspend fun loadCatalog(token: String) {
        localFallbackApiClient.adminGetCatalog(token)
            .onSuccess { _catalog.value = it }
            .onFailure {
                Log.w(TAG, "No se pudo cargar el catálogo administrativo", it)
                _errorMessage.value = "No se pudo cargar el catálogo de insignias y marcos: ${it.message}"
            }
    }

    private suspend fun loadUsers(token: String, search: String) {
        localFallbackApiClient.adminGetUsers(token, search)
            .onSuccess { page ->
                _users.value = page.items
                val selectedId = _selectedUser.value?.id
                if (selectedId != null) {
                    _selectedUser.value = page.items.firstOrNull { it.id == selectedId }
                }
            }
            .onFailure {
                Log.w(TAG, "No se pudieron cargar usuarios", it)
                _errorMessage.value = if (it.message.orEmpty().contains("404")) {
                    "El servidor público está desactualizado y aún no publica admin_users_v1. Actualiza la API; no es un fallo de tu cuenta ni del buscador."
                } else {
                    "No se pudieron cargar los usuarios: ${it.message}"
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

    fun setUserSearch(value: String) {
        _userSearch.value = value.take(120)
    }

    fun searchUsers() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = authService.ensureValidAccessToken().getOrThrow()
                loadUsers(token, _userSearch.value)
            } catch (e: Exception) {
                _errorMessage.value = "Error al buscar usuarios: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadUserDetails(userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = authService.ensureValidAccessToken().getOrThrow()
                localFallbackApiClient.adminGetUser(token, userId)
                    .onSuccess { _selectedUser.value = it }
                    .onFailure { _errorMessage.value = "No se pudo cargar el detalle: ${it.message}" }
            } catch (e: Exception) {
                _errorMessage.value = "No se pudo cargar el detalle: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Obtiene el estado más reciente desde el servidor antes de abrir la
     * confirmación destructiva. Así el botón funciona también desde la pestaña
     * Suspendidos y no depende de una fila de búsqueda desactualizada.
     */
    fun prepareUserDeletion(userId: String) {
        if (userId.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = authService.ensureValidAccessToken().getOrThrow()
                val target = localFallbackApiClient.adminGetUser(token, userId).getOrThrow()
                when {
                    target.isAdmin -> _errorMessage.value = "No se puede eliminar una cuenta administradora."
                    !target.isTestAccount && !target.isSuspended -> {
                        _errorMessage.value = "La cuenta debe estar suspendida antes de una eliminación permanente."
                    }
                    else -> _deleteCandidate.value = target
                }
            } catch (e: Exception) {
                _errorMessage.value = "No se pudo preparar la eliminación: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun consumeDeleteCandidate() {
        _deleteCandidate.value = null
    }

    fun updateUserDecorations(
        userId: String,
        verified: Boolean,
        badgeIds: List<String>,
        profileFrame: String
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = authService.ensureValidAccessToken().getOrThrow()
                localFallbackApiClient.adminUpdateUserDecorations(
                    token, userId, verified, badgeIds, profileFrame
                ).onSuccess { updated ->
                    replaceUser(updated)
                    _selectedUser.value = updated
                    _statusMessage.value = "Perfil administrativo actualizado."
                }.onFailure {
                    _errorMessage.value = "No se pudo actualizar el usuario: ${it.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "No se pudo actualizar el usuario: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun replaceUser(updated: AdminUser) {
        _users.value = _users.value.map { if (it.id == updated.id || it.localId == updated.localId) updated else it }
    }

    fun suspendUser(userId: String, reason: String, days: Int) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val token = authService.ensureValidAccessToken().getOrThrow()
                val result = localFallbackApiClient.adminSuspendUser(token, userId, reason, days)
                if (result.isSuccess) {
                    _statusMessage.value = "Usuario suspendido correctamente."
                    loadStats(token)
                    loadUsers(token, _userSearch.value)
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

    fun unsuspendUser(userId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                val token = authService.ensureValidAccessToken().getOrThrow()
                val result = localFallbackApiClient.adminUnsuspendUser(
                    token,
                    userId,
                    "Reactivación desde el panel administrativo"
                )
                if (result.isSuccess) {
                    _statusMessage.value = "Cuenta reactivada correctamente."
                    loadStats(token)
                    loadUsers(token, _userSearch.value)
                    loadSuspendedProfiles(token)
                } else {
                    _errorMessage.value = "Error al reactivar: ${result.exceptionOrNull()?.message}"
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

    /**
     * Elimina COMPLETAMENTE un usuario y todos sus datos:
     * cuenta, perfil, publicaciones, videos, imágenes, comentarios,
     * chats, grupos, comunidades, seguidores, seguidos, archivos,
     * tokens, notificaciones y registros en almacenamiento.
     *
     * Esta acción es IRREVERSIBLE.
     */
    fun deleteUserCompletely(userId: String, reason: String, confirmation: String) {
        if (userId.isBlank()) {
            _errorMessage.value = "ID de usuario inválido."
            return
        }
        if (reason.isBlank()) {
            _errorMessage.value = "Debes escribir el motivo de eliminación."
            return
        }
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                val token = authService.ensureValidAccessToken().getOrThrow()
                // Revalidación obligatoria inmediatamente antes del borrado:
                // permisos, suspensión, correo e ID deben seguir vigentes.
                val target = localFallbackApiClient.adminGetUser(token, userId).getOrThrow()
                if (target.isAdmin) error("No se puede eliminar una cuenta administradora.")
                if (!target.isTestAccount && !target.isSuspended) {
                    error("La cuenta ya no está suspendida; la eliminación fue cancelada.")
                }
                val expectedConfirmation = if (target.isTestAccount) {
                    "ELIMINAR ${target.id}"
                } else {
                    "ELIMINAR ${target.id} ${target.email.trim().lowercase()}"
                }
                if (confirmation != expectedConfirmation) error("La confirmación escrita no coincide.")
                val result = localFallbackApiClient.adminDeleteUser(
                    token,
                    target.id,
                    reason,
                    confirmation,
                    target.email,
                    deleteSuspendedAccount = !target.isTestAccount
                )
                if (result.isSuccess) {
                    _statusMessage.value = "Cuenta eliminada completamente."
                    _lastDeletedUserId.value = target.id
                    _selectedUser.value = null
                    _deleteCandidate.value = null
                    loadStats(token)
                    loadUsers(token, _userSearch.value)
                    loadSuspendedProfiles(token)
                    loadReports(token)
                } else {
                    _errorMessage.value = "Error al eliminar usuario: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
