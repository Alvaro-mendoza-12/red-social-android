package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.AdminDashboardStats
import conectate.com.data.localfallback.AdminAccessInfo
import conectate.com.data.localfallback.AdminCatalog
import conectate.com.data.localfallback.AdminUser
import conectate.com.data.localfallback.AdminModerationAppeal
import conectate.com.data.localfallback.AdminRecoveryCase
import conectate.com.data.localfallback.AdminRecoveryAccount
import conectate.com.data.localfallback.SystemHealthResponse
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import conectate.com.core.concurrency.AccountTasks
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val accountLease = authService.captureAccountLease()
    private val adminTasks = AccountTasks(viewModelScope)
    val sessionState = authService.currentSession
    internal fun hasCurrentSession() = accountLease.isCurrent()

    private suspend fun panelAccessToken(): Result<String> = try {
        accountLease.requireCurrent()
        val token = authService.getAccessTokenForAccountAwait(checkNotNull(accountLease.accountId))
            ?: error("Inicia sesión de nuevo para abrir la administración.")
        currentCoroutineContext().ensureActive()
        accountLease.requireCurrent()
        Result.success(token)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

    private fun adminError(error: Throwable?, fallback: String): String {
        if (error is CancellationException) throw error
        val raw = error?.message.orEmpty().trim()
        val normalized = raw.lowercase()
        return when {
            normalized.contains("recovery_contact_not_verified") || normalized.contains("aún no confirmó el contacto") ->
                "El contacto nuevo aún no confirmó su código. Pide al solicitante que lo escriba en la app."
            normalized.contains("recovery_status_conflict") -> "Otro administrador ya cambió la solicitud. Actualiza la lista."
            normalized.contains("recovery_case_account_mismatch") || normalized.contains("asociado a otra cuenta") ->
                "El caso ya está asociado a otra cuenta. Vuelve a revisar la identidad."
            normalized.contains("open_recovery_case_not_found") || normalized.contains("pending_recovery_case_not_found") ->
                "La solicitud ya no está abierta. Actualiza la lista."
            normalized.contains("reviewer_and_verification_reference_required") ->
                "Registra una referencia concreta de la verificación de propiedad."
            normalized.contains("account_must_be_suspended") ->
                "La cuenta debe permanecer suspendida antes de eliminarla."
            normalized.contains("admin_cannot_be_deleted") ||
                normalized.contains("self_delete_forbidden") ->
                "No puedes eliminar tu propia cuenta desde este panel."
            normalized.contains("badge_delete_forbidden") ->
                "Tu cuenta administrativa no tiene permisos de edición para borrar insignias."
            normalized.contains("built_in_badge_cannot_be_deleted") ->
                "Las insignias incluidas con la aplicación no se pueden borrar."
            normalized.contains("badge_not_found") ->
                "La insignia ya no existe. Actualiza el catálogo."
            normalized.contains("hard_delete_confirmation_invalid") ||
                normalized.contains("acknowledgements_required") ->
                "La confirmación de eliminación dejó de ser válida. Actualiza el usuario e inténtalo otra vez."
            normalized.contains("hard_delete_orphans_remain") ->
                "No se pudo completar la eliminación. Actualiza el usuario antes de reintentar."
            normalized.contains("suspension_reason_required") -> "Escribe el motivo de la suspensión."
            normalized.contains("suspension_days_invalid") -> "Selecciona una duración válida."
            normalized.contains("moderation_rule_invalid") -> "Selecciona una regla del catálogo actualizado."
            normalized.contains("admin_cannot_be_suspended") -> "No se puede suspender una cuenta administrativa."
            normalized.contains("user_not_found") -> "La cuenta ya no existe. Actualiza el panel."
            normalized.contains("401") || normalized.contains("invalid_token") ->
                "Tu sesión venció. Inicia sesión otra vez."
            normalized.contains("403") || normalized.contains("admin_required") ->
                "Tu cuenta no tiene permiso para realizar esta acción."
            normalized.contains("unable to resolve") ||
                normalized.contains("unknown host") ||
                normalized.contains("failed to connect") ||
                normalized.contains("timeout") ->
                "No se pudo conectar con el servidor. Verifica tu conexión e inténtalo nuevamente."
            else -> fallback
        }
    }

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _adminAccess = MutableStateFlow(AdminAccessInfo())
    val adminAccess: StateFlow<AdminAccessInfo> = _adminAccess.asStateFlow()

    private val _suspendedProfiles = MutableStateFlow<List<SuspendedProfile>>(emptyList())
    val suspendedProfiles: StateFlow<List<SuspendedProfile>> = _suspendedProfiles.asStateFlow()
    private val _moderationAppeals = MutableStateFlow<List<AdminModerationAppeal>>(emptyList())
    val moderationAppeals: StateFlow<List<AdminModerationAppeal>> = _moderationAppeals.asStateFlow()
    private val _recoveryCases = MutableStateFlow<List<AdminRecoveryCase>>(emptyList())
    val recoveryCases: StateFlow<List<AdminRecoveryCase>> = _recoveryCases.asStateFlow()
    private val _recoverySearch = MutableStateFlow("")
    val recoverySearch = _recoverySearch.asStateFlow()
    private val _recoveryFilter = MutableStateFlow("pending")
    val recoveryFilter = _recoveryFilter.asStateFlow()
    private val _recoveryOffset = MutableStateFlow(0)
    val recoveryOffset = _recoveryOffset.asStateFlow()
    private val _recoveryTotal = MutableStateFlow(0)
    val recoveryTotal = _recoveryTotal.asStateFlow()
    private val _recoveryCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val recoveryCounts = _recoveryCounts.asStateFlow()
    private val _recoveryPageLoading = MutableStateFlow(false)
    val recoveryPageLoading = _recoveryPageLoading.asStateFlow()
    private var recoveryListJob: Job? = null
    private var recoveryListRevision = 0L
    private val _recoveryAccounts = MutableStateFlow<List<AdminRecoveryAccount>>(emptyList())
    val recoveryAccounts: StateFlow<List<AdminRecoveryAccount>> = _recoveryAccounts.asStateFlow()
    private val _recoverySecret = MutableStateFlow<String?>(null)
    val recoverySecret: StateFlow<String?> = _recoverySecret.asStateFlow()
    private val _recoverySecretCaseId = MutableStateFlow<String?>(null)
    val recoverySecretCaseId: StateFlow<String?> = _recoverySecretCaseId.asStateFlow()
    private val _recoverySecretAction = MutableStateFlow<String?>(null)
    val recoverySecretAction: StateFlow<String?> = _recoverySecretAction.asStateFlow()
    private val _recoveryBusy = MutableStateFlow(false)
    val recoveryBusy: StateFlow<Boolean> = _recoveryBusy.asStateFlow()

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

    private val _systemHealth = MutableStateFlow<SystemHealthResponse?>(null)
    val systemHealth: StateFlow<SystemHealthResponse?> = _systemHealth.asStateFlow()

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
    private val decorationMutationLocks = mutableMapOf<String, Mutex>()
    private var userSearchJob: Job? = null
    private var userListRevision = 0L
    private var statsRefreshJob: Job? = null
    private val recoveryAccountLookup by lazy { AdminRecoveryAccountLookup(
        scope = { adminTasks.scope }, isCurrentSession = accountLease::isCurrent,
        read = { query -> localFallbackApiClient.adminFindRecoveryAccounts(panelAccessToken().getOrThrow(), query) },
        publish = { _recoveryAccounts.value = it },
        failure = { _errorMessage.value = adminError(it, "No se pudieron buscar las cuentas. Reintenta.") },
    ) }

    fun checkAdminAccess() {
        adminTasks.scope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _isAdmin.value = false
            _adminAccess.value = AdminAccessInfo()
            try {
                val token = panelAccessToken()
                    .getOrElse {
                        _errorMessage.value = adminError(
                            it,
                            "No pudimos comprobar tu sesión de administrador. Inicia sesión otra vez."
                        )
                        _isLoading.value = false
                        return@launch
                    }

                val result = localFallbackApiClient.adminGetAccess(token)
                if (result.isSuccess) {
                    val access = result.getOrThrow()
                    _adminAccess.value = access
                    _isAdmin.value = access.isAdmin
                    if (!access.isAdmin) {
                        _errorMessage.value = "No tienes permisos para abrir la administración."
                        return@launch
                    }
                    loadStats(token)
                    loadCatalog(token)
                    loadUsers(token, _userSearch.value)
                    loadSuspendedProfiles(token)
                    loadModerationAppeals(token)
                    loadRecoveryCases(token)
                    loadReports(token)
                    loadSystemHealth(token)
                } else {
                    _adminAccess.value = AdminAccessInfo()
                    _isAdmin.value = false
                    _errorMessage.value = adminError(
                        result.exceptionOrNull(),
                        "No tienes permisos de administrador."
                    )
                }
            } catch (e: Exception) {
                _errorMessage.value = adminError(
                    e,
                    "No se pudo abrir el panel de administración. Inténtalo nuevamente."
                )
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

    fun startStatsAutoRefresh() {
        if (statsRefreshJob?.isActive == true) return
        statsRefreshJob = adminTasks.scope.launch {
            var refreshCycle = 0
            while (true) {
                delay(4_000L)
                if (!_isAdmin.value) continue
                panelAccessToken()
                    .onSuccess { token ->
                        loadStats(token)
                        refreshCycle += 1
                        if (refreshCycle % 3 == 0) loadSystemHealth(token)
                    }
                    .onFailure { Log.w(TAG, "No se pudo actualizar el resumen administrativo", it) }
            }
        }
    }

    fun stopStatsAutoRefresh() {
        statsRefreshJob?.cancel()
        statsRefreshJob = null
    }

    private suspend fun loadCatalog(token: String) {
        localFallbackApiClient.adminGetCatalog(token)
            .onSuccess { _catalog.value = it }
            .onFailure {
                Log.w(TAG, "No se pudo cargar el catálogo administrativo", it)
                _errorMessage.value = adminError(
                    it,
                    "No se pudo cargar el catálogo de insignias y marcos."
                )
            }
    }

    private suspend fun loadUsers(token: String, search: String) {
        val revision = ++userListRevision
        val result = localFallbackApiClient.adminGetUsers(token, search)
        currentCoroutineContext().ensureActive()
        accountLease.requireCurrent()
        if (revision != userListRevision || search != _userSearch.value) return
        result
            .onSuccess { page ->
                _users.value = page.items
                val selectedId = _selectedUser.value?.id
                if (selectedId != null) {
                    _selectedUser.value = page.items.firstOrNull { it.id == selectedId }
                }
            }
            .onFailure {
                Log.w(TAG, "No se pudieron cargar usuarios", it)
                _errorMessage.value = adminError(it, "No se pudieron cargar los usuarios. Inténtalo nuevamente.")
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
        userListRevision++
        userSearchJob?.cancel()
        if (!_isAdmin.value) return
        userSearchJob = adminTasks.scope.launch {
            delay(300L)
            runCatching {
                val token = panelAccessToken().getOrThrow()
                loadUsers(token, _userSearch.value)
            }.onFailure { error ->
                _errorMessage.value = adminError(error, "No se pudo completar la búsqueda.")
            }
        }
    }

    fun searchUsers() {
        adminTasks.scope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = panelAccessToken().getOrThrow()
                loadUsers(token, _userSearch.value)
            } catch (e: Exception) {
                _errorMessage.value = adminError(e, "No se pudo completar la búsqueda.")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private var userDetailsJob: Job? = null
    private var userDetailsRevision = 0L

    fun toggleUserDetails(userId: String) {
        userDetailsRevision++
        userDetailsJob?.cancel()
        if (_selectedUser.value?.id == userId) {
            _selectedUser.value = null
            return
        }
        val revision = userDetailsRevision
        _selectedUser.value = _users.value.firstOrNull { it.id == userId }
        userDetailsJob = adminTasks.scope.launch {
            _errorMessage.value = null
            try {
                val token = panelAccessToken().getOrThrow()
                val details = localFallbackApiClient.adminGetUser(token, userId).getOrThrow()
                if (revision == userDetailsRevision) _selectedUser.value = details
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (revision == userDetailsRevision) {
                    _errorMessage.value = adminError(e, "No se pudo cargar el detalle del usuario.")
                }
            }
        }
    }

    fun loadUserDetails(userId: String) {
        adminTasks.scope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = panelAccessToken().getOrThrow()
                localFallbackApiClient.adminGetUser(token, userId)
                    .onSuccess { _selectedUser.value = it }
                    .onFailure {
                        _errorMessage.value = adminError(it, "No se pudo cargar el detalle del usuario.")
                    }
            } catch (e: Exception) {
                _errorMessage.value = adminError(e, "No se pudo cargar el detalle del usuario.")
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
        adminTasks.scope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = panelAccessToken().getOrThrow()
                val target = localFallbackApiClient.adminGetUser(token, userId).getOrThrow()
                when {
                    !target.isTestAccount && !target.isSuspended -> {
                        _errorMessage.value = "La cuenta debe estar suspendida antes de una eliminación permanente."
                    }
                    else -> _deleteCandidate.value = target
                }
            } catch (e: Exception) {
                _errorMessage.value = adminError(
                    e,
                    "No se pudo preparar la eliminación. Actualiza el panel e inténtalo otra vez."
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun consumeDeleteCandidate() {
        _deleteCandidate.value = null
    }

    fun updateUserVerification(userId: String, verified: Boolean) {
        updateUserDecorations(userId, verified = verified)
    }

    private suspend fun loadModerationAppeals(token: String) {
        localFallbackApiClient.adminGetModerationAppeals(token)
            .onSuccess { _moderationAppeals.value = it }
            .onFailure {
                Log.w(TAG, "No se pudieron cargar apelaciones", it)
                _moderationAppeals.value = emptyList()
            }
    }

    suspend fun downloadRecoveryEvidence(caseId: String, evidenceId: String): Result<ByteArray> = try {
        val token = panelAccessToken().getOrThrow()
        val bytes = localFallbackApiClient.adminDownloadRecoveryEvidence(token, caseId, evidenceId).getOrThrow()
        currentCoroutineContext().ensureActive()
        accountLease.requireCurrent()
        Result.success(bytes)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

    fun setRecoverySearch(value: String) {
        _recoverySearch.value = value.take(120)
        _recoveryOffset.value = 0
        clearRecoverySecret()
        refreshRecoveryCases(debounce = true)
    }

    fun setRecoveryFilter(value: String) {
        if (value !in setOf("all", "pending", "in_review", "issued", "completed", "rejected")) return
        _recoveryFilter.value = value
        _recoveryOffset.value = 0
        clearRecoverySecret()
        refreshRecoveryCases()
    }

    fun setRecoveryPage(offset: Int) {
        _recoveryOffset.value = offset.coerceAtLeast(0)
        clearRecoverySecret()
        refreshRecoveryCases()
    }

    fun refreshRecoveryCases(debounce: Boolean = false) {
        val requestedRevision = ++recoveryListRevision
        _recoveryPageLoading.value = true
        recoveryListJob?.cancel()
        recoveryListJob = adminTasks.scope.launch {
            if (debounce) delay(300)
            try {
                loadRecoveryCases(panelAccessToken().getOrThrow())
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (requestedRevision == recoveryListRevision) {
                    _recoveryPageLoading.value = false
                    _errorMessage.value = adminError(error, "No se pudieron cargar las solicitudes.")
                }
            }
        }
    }

    private suspend fun loadRecoveryCases(token: String) {
        val revision = ++recoveryListRevision
        val query = _recoverySearch.value
        val filter = _recoveryFilter.value
        val offset = _recoveryOffset.value
        _recoveryPageLoading.value = true
        try {
            val page = localFallbackApiClient.adminGetRecoveryCases(token, query, filter, offset).getOrThrow()
            if (revision == recoveryListRevision) {
                _recoveryCases.value = page.items
                _recoveryTotal.value = page.total
                _recoveryOffset.value = page.offset
                _recoveryCounts.value = page.statusCounts
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (revision == recoveryListRevision) _errorMessage.value = adminError(error, "No se pudieron cargar las recuperaciones.")
        } finally { if (revision == recoveryListRevision) _recoveryPageLoading.value = false }
    }

    fun findRecoveryAccounts(query: String) = recoveryAccountLookup.search(query)

    fun clearRecoverySecret() {
        _recoverySecret.value = null
        _recoverySecretCaseId.value = null
        _recoverySecretAction.value = null
    }
    fun clearRecoveryAccounts() = recoveryAccountLookup.clear()

    fun recoveryAction(caseId: String, action: String, userId: String = "", evidence: String = "", resetTwoFactor: Boolean = false) {
        if (_recoveryBusy.value) return
        adminTasks.scope.launch {
            _recoveryBusy.value = true
            clearRecoverySecret()
            _statusMessage.value = null
            _errorMessage.value = null
            try {
                val token = panelAccessToken().getOrThrow()
                val body = buildJsonObject {
                    put("userId", userId)
                    put("reviewedBy", authService.getCurrentUserId().orEmpty())
                    put("verificationRef", evidence)
                    put("resetTwoFactor", resetTwoFactor)
                    if (action == "review") put("status", userId)
                }
                localFallbackApiClient.adminRecoveryAction(token, caseId, action, body)
                    .onSuccess { response ->
                        _recoverySecret.value = response["code"]?.jsonPrimitive?.contentOrNull
                            ?: response["resetToken"]?.jsonPrimitive?.contentOrNull
                        _recoverySecretCaseId.value = if (_recoverySecret.value != null) caseId else null
                        _recoverySecretAction.value = if (_recoverySecret.value != null) action else null
                        _statusMessage.value = when (action) {
                            "review" -> "Estado de solicitud actualizado."
                            "contact-code" -> "Código de contacto generado. Envíalo manualmente al contacto indicado."
                            "issue-code" -> "Código temporal emitido. Compártelo solo con el titular verificado."
                            else -> "Solicitud rechazada."
                        }
                        loadRecoveryCases(token)
                    }
                    .onFailure { _errorMessage.value = adminError(it, "No se pudo completar la revisión.") }
            } catch (error: Exception) {
                _errorMessage.value = adminError(error, "No se pudo validar la sesión administrativa.")
            } finally { _recoveryBusy.value = false }
        }
    }

    private suspend fun loadSystemHealth(token: String) {
        localFallbackApiClient.loadSystemHealth(token)
            .onSuccess { _systemHealth.value = it }
            .onFailure {
                Log.w(TAG, "No se pudo cargar el estado de servicios", it)
                _systemHealth.value = null
            }
    }

    fun updateUserBadge(userId: String, badgeId: String, enabled: Boolean) {
        updateUserDecorations(userId, badgeMutation = badgeId to enabled)
    }

    fun createBadge(name: String, description: String, icon: String, color: String) {
        adminTasks.scope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = panelAccessToken().getOrThrow()
                localFallbackApiClient.adminCreateBadge(
                    accessToken = token,
                    name = name,
                    description = description,
                    icon = icon,
                    color = color
                ).onSuccess { badge ->
                    _catalog.value = _catalog.value.copy(
                        badges = (_catalog.value.badges + badge).distinctBy { it.id }
                    )
                    _statusMessage.value = "Insignia creada y disponible para asignar."
                }.onFailure {
                    _errorMessage.value = adminError(it, "No se pudo crear la insignia.")
                }
            } catch (e: Exception) {
                _errorMessage.value = adminError(e, "No se pudo crear la insignia.")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteBadge(badgeId: String) {
        if (badgeId.isBlank()) return
        adminTasks.scope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = panelAccessToken().getOrThrow()
                localFallbackApiClient.adminDeleteBadge(token, badgeId)
                    .onSuccess {
                        _catalog.value = _catalog.value.copy(
                            badges = _catalog.value.badges.filterNot { badge -> badge.id == badgeId }
                        )
                        _users.value = _users.value.map { user ->
                            user.copy(badges = user.badges.filterNot { badge -> badge.id == badgeId })
                        }
                        _selectedUser.value = _selectedUser.value?.let { user ->
                            user.copy(badges = user.badges.filterNot { badge -> badge.id == badgeId })
                        }
                        _statusMessage.value = "Insignia eliminada del catálogo y de los perfiles."
                    }
                    .onFailure {
                        _errorMessage.value = adminError(it, "No se pudo eliminar la insignia.")
                    }
            } catch (error: Exception) {
                _errorMessage.value = adminError(error, "No se pudo eliminar la insignia.")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateUserAdminAccess(userId: String, accessLevel: String) {
        if (!_adminAccess.value.isOwner) {
            _errorMessage.value = "Solo los administradores propietarios pueden cambiar estos permisos."
            return
        }
        val normalized = accessLevel.lowercase().takeIf { it in setOf("none", "view", "write", "full") }
            ?: return
        adminTasks.scope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = panelAccessToken().getOrThrow()
                localFallbackApiClient.adminUpdateUserAccess(token, userId, normalized)
                    .onSuccess { updated ->
                        replaceUser(updated)
                        if (_selectedUser.value?.id == updated.id) _selectedUser.value = updated
                        _statusMessage.value = when (normalized) {
                            "none" -> "Acceso administrativo retirado."
                            "view" -> "Acceso de solo lectura concedido."
                            "write" -> "Acceso de moderación concedido."
                            else -> "Acceso administrativo total concedido; la eliminación de cuentas sigue protegida."
                        }
                    }
                    .onFailure {
                        _errorMessage.value = adminError(it, "No se pudo cambiar el acceso administrativo.")
                    }
            } catch (error: Exception) {
                _errorMessage.value = adminError(error, "No se pudo cambiar el acceso administrativo.")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateUserFrame(userId: String, profileFrame: String) {
        updateUserDecorations(userId, profileFrame = profileFrame)
    }

    private fun updateUserDecorations(
        userId: String,
        verified: Boolean? = null,
        badgeMutation: Pair<String, Boolean>? = null,
        profileFrame: String? = null
    ) {
        adminTasks.scope.launch {
            val lock = decorationMutationLocks.getOrPut(userId) { Mutex() }
            lock.withLock {
                _isLoading.value = true
                _errorMessage.value = null
                try {
                    val token = panelAccessToken().getOrThrow()
                    val badgeIds = badgeMutation?.let { (badgeId, enabled) ->
                        val current = _selectedUser.value?.takeIf { it.id == userId }
                            ?: _users.value.firstOrNull { it.id == userId || it.localId == userId }
                            ?: error("El usuario ya no está disponible.")
                        val ids = current.badges.map { it.id }
                        if (enabled) (ids + badgeId).distinct() else ids - badgeId
                    }
                    localFallbackApiClient.adminUpdateUserDecorations(
                        accessToken = token,
                        userId = userId,
                        verified = verified,
                        badgeIds = badgeIds,
                        profileFrame = profileFrame
                    ).onSuccess { updated ->
                        replaceUser(updated)
                        _selectedUser.value = updated
                        _statusMessage.value = "Cambio guardado en el servidor."
                    }.onFailure {
                        _errorMessage.value = adminError(
                            it,
                            "No se pudo actualizar el usuario. Inténtalo nuevamente."
                        )
                    }
                } catch (e: Exception) {
                    _errorMessage.value = adminError(
                        e,
                        "No se pudo actualizar el usuario. Inténtalo nuevamente."
                    )
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    private fun replaceUser(updated: AdminUser) {
        _users.value = _users.value.map { if (it.id == updated.id || it.localId == updated.localId) updated else it }
    }

    fun suspendUser(
        userId: String,
        reason: String,
        days: Int,
        ruleCode: String,
        severity: String,
        evidence: List<String>
    ) {
        adminTasks.scope.launch {
            try {
                _isLoading.value = true
                val token = panelAccessToken().getOrThrow()
                _errorMessage.value = null
                _statusMessage.value = null
                val result = localFallbackApiClient.adminSuspendUser(
                    token,
                    userId,
                    reason,
                    days,
                    ruleCode,
                    severity,
                    evidence
                )
                if (result.isSuccess) {
                    _statusMessage.value = "Usuario suspendido correctamente."
                    loadStats(token)
                    loadUsers(token, _userSearch.value)
                    loadSuspendedProfiles(token)
                    loadModerationAppeals(token)
                } else {
                    _errorMessage.value = adminError(
                        result.exceptionOrNull(),
                        "No se pudo suspender la cuenta."
                    )
                }
            } catch (e: Exception) {
                _errorMessage.value = adminError(e, "No se pudo suspender la cuenta.")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun unsuspendUser(userId: String) {
        adminTasks.scope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                val token = panelAccessToken().getOrThrow()
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
                    loadModerationAppeals(token)
                } else {
                    _errorMessage.value = adminError(
                        result.exceptionOrNull(),
                        "No se pudo reactivar la cuenta."
                    )
                }
            } catch (e: Exception) {
                _errorMessage.value = adminError(e, "No se pudo reactivar la cuenta.")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateReportStatus(reportId: String, status: String, type: String) {
        adminTasks.scope.launch {
            try {
                _isLoading.value = true
                val token = panelAccessToken().getOrThrow()
                val result = localFallbackApiClient.adminUpdateReportStatus(token, reportId, status, type)
                if (result.isSuccess) {
                    _statusMessage.value = "Estado de reporte actualizado."
                    loadReports(token)
                } else {
                    _errorMessage.value = adminError(
                        result.exceptionOrNull(),
                        "No se pudo actualizar el reporte."
                    )
                }
            } catch (e: Exception) {
                _errorMessage.value = adminError(e, "No se pudo actualizar el reporte.")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun reviewModerationAppeal(
        appealId: String,
        outcome: String,
        note: String
    ) {
        adminTasks.scope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val token = panelAccessToken().getOrThrow()
                localFallbackApiClient.adminReviewModerationAppeal(
                    token,
                    appealId,
                    outcome,
                    note
                ).onSuccess {
                    _statusMessage.value = if (outcome == "overturned") {
                        "Apelación aceptada y cuenta reactivada."
                    } else {
                        "Apelación revisada; la decisión se mantiene."
                    }
                    loadModerationAppeals(token)
                    loadSuspendedProfiles(token)
                    loadStats(token)
                    loadUsers(token, _userSearch.value)
                }.onFailure {
                    _errorMessage.value = adminError(
                        it,
                        "No se pudo guardar la revisión de la apelación."
                    )
                }
            } catch (error: Exception) {
                _errorMessage.value = adminError(
                    error,
                    "No se pudo guardar la revisión de la apelación."
                )
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
    fun deleteUserCompletely(userId: String) {
        if (userId.isBlank()) {
            _errorMessage.value = "ID de usuario inválido."
            return
        }
        adminTasks.scope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                val token = panelAccessToken().getOrThrow()
                // Revalidación obligatoria inmediatamente antes del borrado:
                // permisos, suspensión, correo e ID deben seguir vigentes.
                val target = localFallbackApiClient.adminGetUser(token, userId).getOrThrow()
                if (!target.isTestAccount && !target.isSuspended) {
                    error("La cuenta ya no está suspendida; la eliminación fue cancelada.")
                }
                val confirmationUserId = target.localId.trim().ifBlank { target.id.trim() }
                val expectedConfirmation = if (target.isTestAccount) {
                    "ELIMINAR $confirmationUserId"
                } else {
                    "ELIMINAR $confirmationUserId ${target.email.trim().lowercase()}"
                }
                val result = localFallbackApiClient.adminDeleteUser(
                    token,
                    target.id,
                    "Eliminación administrativa confirmada desde el panel",
                    expectedConfirmation,
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
                    _errorMessage.value = adminError(
                        result.exceptionOrNull(),
                        "No se pudo eliminar la cuenta. Actualiza el panel e inténtalo nuevamente."
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo eliminar la cuenta", e)
                _errorMessage.value = adminError(
                    e,
                    "No se pudo eliminar la cuenta. Actualiza el panel e inténtalo nuevamente."
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    init {
        viewModelScope.launch {
            sessionState.collect {
                if (!accountLease.isCurrent()) {
                    adminTasks.close()
                    _isAdmin.value = false
                    _adminAccess.value = AdminAccessInfo()
                    clearRecoverySecret()
                }
            }
        }
    }

    override fun onCleared() {
        adminTasks.close()
        userSearchJob?.cancel()
        statsRefreshJob?.cancel()
        super.onCleared()
    }
}
