package conectate.com.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Report
import conectate.com.data.model.Badge
import conectate.com.data.model.User
import conectate.com.data.model.VideoReport
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.BadgeRepository
import conectate.com.data.repository.SuspensionDurationUnit
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import javax.inject.Inject

sealed class AdminUiState {
    object Loading : AdminUiState()
    object Unauthorized : AdminUiState()
    data class Success(
        val reports: List<Report>,
        val videoReports: List<VideoReport> = emptyList()
    ) : AdminUiState()
    data class Error(val message: String) : AdminUiState()
}

data class SuspensionRequestConfig(
    val durationValue: Int,
    val durationUnit: SuspensionDurationUnit,
    val reason: String,
    val permanent: Boolean
)


@HiltViewModel
class AdminViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val authService: AuthService,
    private val badgeRepository: BadgeRepository,
    private val userRepository: UserRepository,
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _adminState = MutableStateFlow<AdminUiState>(AdminUiState.Loading)
    val adminState: StateFlow<AdminUiState> = _adminState.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _grantedAssignments = MutableStateFlow<List<conectate.com.data.model.BadgeAssignment>>(emptyList())
    val grantedAssignments: StateFlow<List<conectate.com.data.model.BadgeAssignment>> = _grantedAssignments.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _uiMessage = MutableSharedFlow<String>()
    val uiMessage: SharedFlow<String> = _uiMessage.asSharedFlow()

    init {
        checkAdminStatus()
    }

    private fun checkAdminStatus() {
        viewModelScope.launch {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
            val hasAdminAccess = if (currentUserId.isNullOrBlank()) {
                false
            } else {
                userRepository.getUserById(currentUserId).getOrNull()?.is_admin == true
            }
            _isAdmin.value = hasAdminAccess
            if (hasAdminAccess) {
                loadReports()
            } else {
                _adminState.value = AdminUiState.Unauthorized
            }
        }
    }

    fun loadReports() {
        viewModelScope.launch {
            if (!_isAdmin.value) {
                _adminState.value = AdminUiState.Unauthorized
                return@launch
            }
            _adminState.value = AdminUiState.Loading
            android.util.Log.d("AdminViewModel", "Loading all reports...")
            val userReportsResult = async { messageRepository.getAllReports() }.await()
            val videoReportsResult = async { videoRepository.getAllVideoReports() }.await()

            var reports = userReportsResult.getOrDefault(emptyList())
            var videoReports = videoReportsResult.getOrDefault(emptyList())

            if (userReportsResult.isFailure && videoReportsResult.isFailure) {
                var message = userReportsResult.exceptionOrNull()?.message
                    ?: videoReportsResult.exceptionOrNull()?.message
                    ?: "Error al cargar reportes"
                _adminState.value = AdminUiState.Error(message)
                return@launch
            }

            android.util.Log.d(
                "AdminViewModel",
                "Loaded ${reports.size} user reports and ${videoReports.size} video reports"
            )
            _adminState.value = AdminUiState.Success(reports = reports, videoReports = videoReports)
        }
    }

    fun updateReportStatus(reportId: String, newStatus: String) {
        viewModelScope.launch {
            var result = messageRepository.updateReportStatus(reportId, newStatus)
            
            result.onSuccess {
                loadReports() // Refresh list
            }.onFailure { e ->
                _adminState.value = AdminUiState.Error("Error al actualizar: ${e.message}")
            }
        }
    }

    fun deleteReport(reportId: String) {
        viewModelScope.launch {
            var result = messageRepository.deleteReport(reportId)
            
            result.onSuccess {
                loadReports() // Refresh list
            }.onFailure { e ->
                _adminState.value = AdminUiState.Error("Error al eliminar: ${e.message}")
            }
        }
    }

    fun updateVideoReportStatus(reportId: String, newStatus: String, actionNote: String? = null) {
        viewModelScope.launch {
            var result = videoRepository.updateVideoReportStatus(
                reportId = reportId,
                newStatus = newStatus,
                actionNote = actionNote
            )

            result.onSuccess {
                loadReports()
            }.onFailure { e ->
                _adminState.value = AdminUiState.Error("Error al actualizar reporte de video: ${e.message}")
            }
        }
    }

    fun warnVideoOwner(report: VideoReport) {
        viewModelScope.launch {
            val details = report.details?.takeIf { it.isNotBlank() }?.let { " Detalle: $it" } ?: ""
            val warningMessage = "Advertencia sobre tu video \"${report.videoTitle ?: "Sin título"}\". Motivo: ${report.reason}.$details"
            val notify = videoRepository.sendVideoWarning(report, warningMessage)
            val update = videoRepository.updateVideoReportStatus(
                reportId = report.id,
                newStatus = "advertido",
                actionNote = "Advertencia enviada al creador",
                warnSent = true
            )

            if (notify.isSuccess && update.isSuccess) {
                loadReports()
                _uiMessage.emit("Advertencia enviada")
            } else {
                _adminState.value = AdminUiState.Error("No se pudo enviar advertencia")
            }
        }
    }

    fun deleteVideoFromReport(report: VideoReport) {
        viewModelScope.launch {
            val delete = videoRepository.deleteVideoFromReport(report)
            if (delete.isSuccess) {
                videoRepository.updateVideoReportStatus(
                    reportId = report.id,
                    newStatus = "finalizado",
                    actionNote = "Video eliminado por moderación",
                    strikeApplied = true
                )
                loadReports()
                _uiMessage.emit("Video eliminado")
            } else {
                _adminState.value = AdminUiState.Error("No se pudo eliminar el video reportado")
            }
        }
    }

    fun suspendVideoOwner(
        report: VideoReport,
        config: SuspensionRequestConfig
    ) {
        viewModelScope.launch {
            val suspensionResult = videoRepository.suspendVideoOwnerAccount(
                report = report,
                durationValue = config.durationValue,
                durationUnit = config.durationUnit,
                reason = config.reason,
                permanent = config.permanent
            )
            if (suspensionResult.isSuccess) {
                videoRepository.updateVideoReportStatus(
                    reportId = report.id,
                    newStatus = "en_revision",
                    actionNote = "Cuenta suspendida por moderación",
                    warnSent = true,
                    strikeApplied = true
                )
                loadReports()
                var untilRaw = suspensionResult.getOrNull()?.takeIf { it.isNotBlank() }
                val isPermanent = config.permanent || untilRaw == "PERMANENT"
                val until = untilRaw
                    ?.takeUnless { it == "PERMANENT" }
                    ?.replace("T", " ")
                    ?.take(16)
                _uiMessage.emit(
                    when {
                        isPermanent -> "Cuenta suspendida permanentemente"
                        until.isNullOrBlank() -> "Cuenta suspendida temporalmente"
                        else -> "Cuenta suspendida hasta $until"
                    }
                )
            } else {
                _adminState.value = AdminUiState.Error(
                    suspensionResult.exceptionOrNull()?.message ?: "No se pudo suspender la cuenta"
                )
            }
        }
    }

    fun enableVideoOwner(report: VideoReport) {
        viewModelScope.launch {
            var result = videoRepository.enableUserAccount(report.videoOwnerId)
            if (result.isSuccess) {
                videoRepository.updateVideoReportStatus(
                    reportId = report.id,
                    newStatus = "en_revision",
                    actionNote = "Cuenta habilitada nuevamente"
                )
                loadReports()
                _uiMessage.emit("Cuenta habilitada")
            } else {
                _adminState.value = AdminUiState.Error(
                    result.exceptionOrNull()?.message ?: "No se pudo habilitar la cuenta"
                )
            }
        }
    }

    // ==============================================
    // BADGE MANAGEMENT
    // ==============================================

    private val _searchedUser = MutableStateFlow<User?>(null)
    val searchedUser: StateFlow<User?> = _searchedUser.asStateFlow()

    private val _allBadges = MutableStateFlow<List<Badge>>(emptyList())
    val allBadges: StateFlow<List<Badge>> = _allBadges.asStateFlow()

    private val _verifiedUsers = MutableStateFlow<List<User>>(emptyList())
    val verifiedUsers: StateFlow<List<User>> = _verifiedUsers.asStateFlow()

    private val _userBadges = MutableStateFlow<List<Badge>>(emptyList())
    val userBadges: StateFlow<List<Badge>> = _userBadges.asStateFlow()

    private val _eligibleUsers = MutableStateFlow<List<User>>(emptyList())
    val eligibleUsers: StateFlow<List<User>> = _eligibleUsers.asStateFlow()

    private val _currentThreshold = MutableStateFlow<Int?>(null)
    val currentThreshold: StateFlow<Int?> = _currentThreshold.asStateFlow()

    init {
        loadAllBadges()
        loadGrantedAssignments()
    }

    fun loadGrantedAssignments() {
        viewModelScope.launch {
            _isProcessing.value = true
            
            // 1. Fetch Explicit Badge Assignments
            val badgeCall = async { badgeRepository.getBadgeAssignments() }
            
            // 2. Fetch Verified Users
            val verifiedCall = async { userRepository.getVerifiedUsers() }
            
            val assignmentsResult = badgeCall.await()
            val verifiedResult = verifiedCall.await()
            
            assignmentsResult.onSuccess { assignments ->
                _grantedAssignments.value = assignments
                android.util.Log.d("AdminViewModel", "Loaded ${assignments.size} badge assignments")
            }.onFailure { e ->
                android.util.Log.e("AdminViewModel", "Error loading assignments", e)
            }
            
            verifiedResult.onSuccess { users ->
                _verifiedUsers.value = users
                android.util.Log.d("AdminViewModel", "Loaded ${users.size} verified users")
            }.onFailure { e ->
                android.util.Log.e("AdminViewModel", "Error loading verified users", e)
            }
            
            _isProcessing.value = false
        }
    }

    fun revokeBadge(userId: String, badgeId: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            var currentUserId = authService.getCurrentUserId() ?: return@launch
            badgeRepository.revokeBadge(userId, badgeId, currentUserId).onSuccess {
                loadUserBadges(userId) // Refresh user's badges
                loadGrantedAssignments() // Refresh tracking list
                _uiMessage.emit("Insignia removida")
            }.onFailure { e ->
                _uiMessage.emit("Error al remover insignia: ${e.message}")
            }
            _isProcessing.value = false
        }
    }

    fun revokeAllBadges(userId: String, badges: List<Badge>) {
        viewModelScope.launch {
            _isProcessing.value = true
            var currentUserId = authService.getCurrentUserId() ?: return@launch
            var successCount = 0
            badges.forEach { badge ->
                badgeRepository.revokeBadge(userId, badge.id, currentUserId).onSuccess {
                    successCount++
                }
            }
            loadUserBadges(userId)
            loadGrantedAssignments()
            _uiMessage.emit("Se removieron $successCount insignias")
            _isProcessing.value = false
        }
    }

    fun loadEligibleUsers(threshold: Int) {
        viewModelScope.launch {
            _currentThreshold.value = threshold
            userRepository.getUsersByFollowerThreshold(threshold).onSuccess { users ->
                _eligibleUsers.value = users
            }
        }
    }

    fun clearEligibleUsers() {
        _eligibleUsers.value = emptyList()
        _currentThreshold.value = null
    }

    private fun loadAllBadges() {
        viewModelScope.launch {
            badgeRepository.getAllBadges().onSuccess { badges ->
                _allBadges.value = badges
            }
        }
    }

    fun searchUserByUsername(username: String) {
        viewModelScope.launch {
            userRepository.searchUsers(username).onSuccess { users ->
                _searchedUser.value = users.firstOrNull()
                _searchedUser.value?.let { user ->
                    loadUserBadges(user.id)
                }
            }
        }
    }

    fun clearSearchedUser() {
        _searchedUser.value = null
        _userBadges.value = emptyList()
    }

    private fun loadUserBadges(userId: String) {
        viewModelScope.launch {
            badgeRepository.getUserBadges(userId).onSuccess { badges ->
                _userBadges.value = badges
            }
        }
    }

    fun createCustomBadge(name: String, description: String, icon: String, color: String) {
        viewModelScope.launch {
            var currentUserId = authService.getCurrentUserId() ?: return@launch
            badgeRepository.createCustomBadge(name, description, icon, color, currentUserId)
                .onSuccess {
                    loadAllBadges() // Refresh badge list
                }
        }
    }

    fun seedSystemBadges() {
        viewModelScope.launch {
            var currentUserId = authService.getCurrentUserId() ?: return@launch
            
            val systemBadges = listOf(
                Triple("Influencer 10M", "10 Millones de seguidores", "💎"),
                Triple("Leyenda 50M", "50 Millones de seguidores", "👑")
            )
            
            systemBadges.forEach { (name, desc, icon) ->
                if (allBadges.value.none { it.name == name }) {
                    badgeRepository.createCustomBadge(name, desc, icon, "#FFD700", currentUserId)
                }
            }
            loadAllBadges()
        }
    }

    fun grantBadge(userId: String, badgeId: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            var currentUserId = authService.getCurrentUserId() ?: return@launch
            badgeRepository.grantBadge(userId, badgeId, currentUserId).onSuccess {
                loadUserBadges(userId) // Refresh user's badges
                loadGrantedAssignments() // Refresh tracking list
                _uiMessage.emit("Insignia otorgada correctamente")
            }.onFailure {
                _uiMessage.emit("Error al otorgar insignia: ${it.message}")
            }
            _isProcessing.value = false
        }
    }

    fun setVerified(userId: String, verified: Boolean) {
        viewModelScope.launch {
            _isProcessing.value = true
            var currentUserId = authService.getCurrentUserId() ?: return@launch
            badgeRepository.setVerified(userId, verified, currentUserId).onSuccess {
                loadGrantedAssignments()
                // Refresh searched user
                _searchedUser.value?.let {
                    if (it.id == userId) {
                        _searchedUser.value = it.copy(is_verified = verified)
                    }
                }
                _uiMessage.emit(if (verified) "Usuario verificado" else "Verificación removida")
            }.onFailure { e ->
                _uiMessage.emit("Error: ${e.message}")
            }
            _isProcessing.value = false
        }
    }
}







