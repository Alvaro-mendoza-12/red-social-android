package conectate.com.presentation.voicerooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.VoiceRoom
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.NotificationRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import conectate.com.presentation.components.isUserAdmin

sealed class VoiceRoomsUiState {
    object Loading : VoiceRoomsUiState()
    data class Success(val rooms: List<VoiceRoom>) : VoiceRoomsUiState()
    data class SearchResult(val room: VoiceRoom?) : VoiceRoomsUiState()
    data class Error(val message: String) : VoiceRoomsUiState()
}

@HiltViewModel
class VoiceRoomsViewModel @Inject constructor(
    private val voiceRoomRepository: VoiceRoomRepository,
    private val authService: AuthService,
    private val userRepository: UserRepository,
    private val notificationRepository: NotificationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<VoiceRoomsUiState>(VoiceRoomsUiState.Loading)
    val uiState: StateFlow<VoiceRoomsUiState> = _uiState.asStateFlow()

    private val _isCreatingRoom = MutableStateFlow(false)
    val isCreatingRoom: StateFlow<Boolean> = _isCreatingRoom.asStateFlow()

    private val _hasAccess = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private val _accessRequestStatus = MutableStateFlow<String>("none") // none, pending, approved, rejected
    val accessRequestStatus: StateFlow<String> = _accessRequestStatus.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _errorEvents = MutableSharedFlow<String>()
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    private var originalRooms: List<VoiceRoom> = emptyList()

    init {
        checkAccess()
        loadRooms()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _errorEvents.emit("Actualizando...")
            checkAccess()
        }
    }

    private fun checkAccess() {
        viewModelScope.launch {
            val userId = authService.getCurrentUserId() ?: return@launch
            val userResult = userRepository.getUserById(userId)
            
            userResult.onSuccess { user ->
                val isStaticAdmin = isUserAdmin(user?.username, user?.email)
                if (user?.is_admin == true || user?.voice_access_granted == true || isStaticAdmin) {
                    _hasAccess.value = true
                }
                
                if (user?.is_admin == true || isStaticAdmin) {
                    _isAdmin.value = true
                }
                
                if (!_hasAccess.value) {
                    // Check if there is a pending request
                    val statusResult = userRepository.getVoiceAccessRequestStatus(userId)
                    statusResult.onSuccess { status ->
                        _accessRequestStatus.value = status
                         if (status == "approved") {
                            _hasAccess.value = true
                         }
                    }
                }
            }
        }
    }

    fun requestAccess() {
        viewModelScope.launch {
            val userId = authService.getCurrentUserId() ?: return@launch
            _accessRequestStatus.value = "pending" // Optimistic update
            
            val result = userRepository.requestVoiceAccess(userId)
            result.onSuccess {
                _accessRequestStatus.value = "pending"
                notifyAdminsOfRequest(userId)
            }.onFailure { e ->
                _accessRequestStatus.value = "none"
                _errorEvents.emit("Error al solicitar acceso: ${e.message}")
            }
        }
    }

    private fun notifyAdminsOfRequest(requesterId: String) {
        viewModelScope.launch {
            val adminsResult = userRepository.getAdmins()
            val requesterDef = userRepository.getUserById(requesterId).getOrNull()
            val requesterName = requesterDef?.displayName ?: requesterDef?.name ?: "Un usuario"
            
            android.util.Log.d("VoiceRoomsVM", "Notifying admins about request from $requesterName ($requesterId)")

            // 1. Create a formal record in the reports table so it appears in the Admin Panel
            userRepository.createVoiceAccessReport(requesterId, requesterName)
            
            // 2. Send individual notifications to all admins
            adminsResult.onSuccess { admins ->
                if (admins.isEmpty()) {
                    android.util.Log.w("VoiceRoomsVM", "No admins found to notify!")
                }
                admins.forEach { admin ->
                    val extraData = buildJsonObject {
                         put("type", "voice_access_request")
                         put("requester_id", requesterId)
                    }
                    
                    notificationRepository.sendNotification(
                        recipientId = admin.id,
                        type = "voice_access_request",
                        targetId = requesterId,
                        extraData = extraData
                    ).onFailure { e ->
                        android.util.Log.e("VoiceRoomsVM", "Failed to notify admin ${admin.id}", e)
                    }
                }
            }.onFailure { e ->
                android.util.Log.e("VoiceRoomsVM", "Failed to fetch admins", e)
            }
        }
    }

    fun refreshRooms() {
        viewModelScope.launch {
            _uiState.value = VoiceRoomsUiState.Loading
            voiceRoomRepository.refreshRooms()
            // After refreshing, the repository's Flow activeRooms will emit new values.
            // But we must also ensure _uiState goes back to Success if we were in SearchResult or Error.
            // Since loadRooms() collects activeRooms and updates _uiState, we might just need to wait a tick 
            // OR manually set it if loadRooms collection is distinct.
            // To be safe and instant:
            val currentRooms = voiceRoomRepository.activeRooms.value
            _uiState.value = VoiceRoomsUiState.Success(currentRooms)
        }
    }

    private fun loadRooms() {
        viewModelScope.launch {
            try {
                voiceRoomRepository.getActiveRooms().collect { rooms ->
                    originalRooms = rooms
                    _uiState.value = VoiceRoomsUiState.Success(rooms)
                }
            } catch (e: Exception) {
                _uiState.value = VoiceRoomsUiState.Error(
                    e.message ?: "Error al cargar salas"
                )
            }
        }
    }

    fun searchRoomById(id: String) {
        viewModelScope.launch {
            _uiState.value = VoiceRoomsUiState.Loading
            try {
                val result = voiceRoomRepository.getRoomById(id)
                result.fold(
                    onSuccess = { room ->
                        _uiState.value = VoiceRoomsUiState.SearchResult(room)
                    },
                    onFailure = {
                        _uiState.value = VoiceRoomsUiState.SearchResult(null)
                    }
                )
            } catch (e: Exception) {
                _uiState.value = VoiceRoomsUiState.Error(e.message ?: "Error")
            }
        }
    }

    fun resetSearch() {
        _uiState.value = VoiceRoomsUiState.Success(originalRooms)
    }

    fun createRoom(
        name: String,
        description: String,
        onComplete: (Result<VoiceRoom>) -> Unit
    ) {
        viewModelScope.launch {
            _isCreatingRoom.value = true
            try {
                // Use the SAFE repository method that handles timestamps correctly
                val result = voiceRoomRepository.createRoom(
                    name = name,
                    description = description,
                    isPublic = true,  // Default to public for the main screen
                    password = null
                )

                onComplete(result)
            } catch (e: Exception) {
                onComplete(Result.failure(e))
            } finally {
                _isCreatingRoom.value = false
            }
        }
    }

    fun deleteRoom(roomId: String) {
        viewModelScope.launch {
            voiceRoomRepository.deleteRoom(roomId)
        }
    }

    fun getCurrentUserId(): String? {
        return authService.getCurrentUserId()
    }
    
    fun getActiveRoomId(): String? {
        return voiceRoomRepository.getCurrentRoomId()
    }
}
