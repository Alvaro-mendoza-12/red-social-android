package conectate.com.presentation.voicerooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.VoiceRoom
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import javax.inject.Inject

@HiltViewModel
class VoiceRoomActiveViewModel @Inject constructor(
    private val voiceRoomRepository: VoiceRoomRepository,
    private val authService: AuthService,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _room = MutableStateFlow<VoiceRoom?>(null)
    val room: StateFlow<VoiceRoom?> = _room.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeaker = MutableStateFlow(true)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker.asStateFlow()

    // Video State - OBSERVE Repository (single source of truth)
    val videoMode = voiceRoomRepository.currentVideoMode

    private val _fullScreenParticipantId = MutableStateFlow<String?>(null)
    val fullScreenParticipantId = _fullScreenParticipantId.asStateFlow()

    private val _isPlayerFullscreen = MutableStateFlow(false)
    val isPlayerFullscreen = _isPlayerFullscreen.asStateFlow()

    fun setPlayerFullscreen(isFullscreen: Boolean) {
        _isPlayerFullscreen.value = isFullscreen
    }

    private val _isJoined = MutableStateFlow(false)
    val isJoined = _isJoined.asStateFlow()

    private val _showPasswordPrompt = MutableStateFlow(false)
    val showPasswordPrompt = _showPasswordPrompt.asStateFlow()

    private val _passwordError = MutableStateFlow<String?>(null)
    val passwordError = _passwordError.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _activeReactions = MutableStateFlow<List<ReactionEvent>>(emptyList())
    val activeReactions = _activeReactions.asStateFlow()

    // Invitation states
    private val _inviteQuery = MutableStateFlow("")
    val inviteQuery = _inviteQuery.asStateFlow()

    private val _inviteResults = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val inviteResults = _inviteResults.asStateFlow()

    private val _isSearchingInvites = MutableStateFlow(false)
    val isSearchingInvites = _isSearchingInvites.asStateFlow()

    private val _currentUserId = MutableStateFlow<String>("")
    val currentUserId = _currentUserId.asStateFlow()

    // Cinema State
    val cinemaState = voiceRoomRepository.cinemaState
    val cinemaSync = voiceRoomRepository.cinemaSync
    
    init {
        _currentUserId.value = authService.getCurrentUserId() ?: ""
    }
    
    fun verifyPassword(password: String) {
        // Validation logic here
        _showPasswordPrompt.value = false
        // Re-attempt join if needed or verify against room details used in join
    }
    
    fun toggleFullScreen(participantId: String?) {
        _fullScreenParticipantId.value = if (_fullScreenParticipantId.value == participantId) null else participantId
    }

    val remoteVideoTracks = voiceRoomRepository.remoteVideoTracks
    val localVideoTrack = voiceRoomRepository.localVideoTrack
    val peerConnectionStates = voiceRoomRepository.peerConnectionStates
    val signalingStates = voiceRoomRepository.signalingStates
    val debugInfo = voiceRoomRepository.debugInfo
    val sharingStates = voiceRoomRepository.sharingStates
    
    fun getEglBaseContext() = voiceRoomRepository.getEglBaseContext()

    fun getCurrentUserId(): String? = authService.getCurrentUserId()
    
    // ... (participants flow setup remains same)

    fun toggleCamera() {
        val currentMode = videoMode.value
        if (currentMode == VoiceRoomRepository.VideoMode.CAMERA) {
            voiceRoomRepository.stopVideo()
        } else {
            // Stop any other active mode first
            if (currentMode != VoiceRoomRepository.VideoMode.NONE) {
                 voiceRoomRepository.stopVideo()
                 voiceRoomRepository.toggleCameraSharing(false)
                 voiceRoomRepository.toggleScreenSharing(false)
            }
            voiceRoomRepository.startCameraCapture(true)
            voiceRoomRepository.toggleCameraSharing(true)
        }
        updateServiceType(videoMode.value)
    }
    
    fun toggleScreenShare(intent: android.content.Intent) {
        val currentMode = videoMode.value
        if (currentMode == VoiceRoomRepository.VideoMode.SCREEN) {
            updateServiceType(VoiceRoomRepository.VideoMode.NONE)
            voiceRoomRepository.stopVideo()
            voiceRoomRepository.toggleScreenSharing(false)
        } else {
             voiceRoomRepository.stopVideo()
             voiceRoomRepository.toggleCameraSharing(false)
             // CRITICAL: Update service type FIRST
             updateServiceType(VoiceRoomRepository.VideoMode.SCREEN)
             viewModelScope.launch {
                 // Delay to ensure Android processes service type change
                 delay(500)
                 voiceRoomRepository.startScreenCapture(intent)
                 voiceRoomRepository.toggleScreenSharing(true)
             }
        }
    }

    fun stopVideo() {
        voiceRoomRepository.stopVideo()
        voiceRoomRepository.toggleCameraSharing(false)
        voiceRoomRepository.toggleScreenSharing(false)
        updateServiceType(VoiceRoomRepository.VideoMode.NONE)
    }

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    private var currentRoomId: String? = null

    private val _isJoining = MutableStateFlow(false)
    val isJoining = _isJoining.asStateFlow()

    // v15.0: Password validation state
    private var isPasswordValidated = false

    fun validatePassword(password: String) {
        val targetRoom = _room.value
        if (targetRoom?.password == password) {
            isPasswordValidated = true
            _passwordError.value = null
            _showPasswordPrompt.value = false
            
            // CRITICAL: Set joined state and unify IDs
            _isJoined.value = true
            currentRoomId = targetRoom.id
            initVoiceRoom(targetRoom.id, targetRoom.readableId ?: targetRoom.id)
        } else {
             _passwordError.value = "Contraseña incorrecta. Inténtalo de nuevo."
        }
    }

    fun joinRoom(roomId: String) {
        if ((currentRoomId == roomId && _room.value != null) || _isJoining.value) return
        _isJoining.value = true

        viewModelScope.launch {
            val localUserId = getCurrentUserId() ?: ""
            voiceRoomRepository.currentParticipants.collect { repoParticipants ->
                _participants.value = repoParticipants.map { 
                    val isSpeaking = if (it.id == localUserId && !_isMuted.value) it.isSpeaking else it.isSpeaking
                    Participant(it.id, it.name, it.socketId, it.avatarUrl, isSpeaking, it.isMuted)
                }
            }
        }

        viewModelScope.launch {
            try {
                val userId = authService.getCurrentUserId() ?: run {
                    _isJoining.value = false
                    return@launch
                }
                
                startService()

                val userDeferred = async { userRepository.getUserById(userId) }
                val roomDeferred = async { voiceRoomRepository.getRoomById(roomId) }
                
                val userResult = userDeferred.await()
                val roomResult = roomDeferred.await()
                
                val initialRoomObj = roomResult.getOrNull()
                if (initialRoomObj == null) {
                     Log.e("VoiceRoomActiveVM", "Room data not found for $roomId")
                     _isJoining.value = false
                     return@launch
                }
                
                // Secure Join Check
                if (!initialRoomObj.isPublic && !isPasswordValidated && initialRoomObj.creatorId != userId) {
                    _room.value = initialRoomObj
                    _showPasswordPrompt.value = true
                    _isJoining.value = false
                    return@launch
                }

                val targetUuid = initialRoomObj.id
                _room.value = initialRoomObj
                currentRoomId = targetUuid
                _isJoined.value = true 
                
                initVoiceRoom(targetUuid, roomId)
                
            } catch (e: Exception) {
                Log.e("VoiceRoomActiveVM", "CRITICAL Error joining room", e)
                _isJoining.value = false
            }
        }
    }

    private fun initVoiceRoom(targetUuid: String, signalingId: String) {
        viewModelScope.launch {
             try {
                val userId = authService.getCurrentUserId() ?: return@launch
                val profile = userRepository.getUserById(userId).getOrNull()
                val displayName = profile?.displayName ?: "Usuario"
                val avatarUrl = profile?.avatar_url
                
                voiceRoomRepository.joinRoom(targetUuid, userId, displayName, avatarUrl, signalingRoomId = signalingId)
                
                withContext(Dispatchers.Main) {
                    startVoiceDetection()
                }
            } catch (e: Exception) {
                Log.e("VoiceRoomActiveVM", "Background init error: ${e.message}")
            } finally {
                _isJoining.value = false 
            }
        }
        setupCollectors()
    }
    
    private fun setupCollectors() {
        viewModelScope.launch {
            voiceRoomRepository.incomingMessages.collect { msg ->
                _messages.value = (_messages.value + msg).takeLast(50)
                // Increment unread count if it's not our own message
                if (msg.userId != getCurrentUserId()) {
                    _unreadChatCount.value += 1
                }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.incomingReactions.collect { reaction ->
                val id = java.util.UUID.randomUUID().toString()
                _activeReactions.value = _activeReactions.value + reaction.copy(id = id)
                viewModelScope.launch {
                    delay(3000)
                    _activeReactions.value = _activeReactions.value.filterNot { it.id == id }
                }
            }
        }
    }

    private fun startService() {
        try {
             val intent = android.content.Intent(voiceRoomRepository.context, conectate.com.services.VoiceForegroundService::class.java)
             intent.action = "START"
             // Pass room name if available early, otherwise default
             intent.putExtra("EXTRA_ROOM_NAME", _room.value?.name ?: "Sala de Voz")
             voiceRoomRepository.context.startForegroundService(intent)
        } catch (e: Exception) {
             android.util.Log.e("VoiceRoomActiveVM", "Failed to start foreground service", e)
        }
    }
    
    private fun updateServiceType(targetMode: VoiceRoomRepository.VideoMode) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val type = resolveForegroundServiceType(targetMode)
             
            android.util.Log.d("VoiceRoomActiveVM", "Updating Service to type: $type (Mode=$targetMode)")
            val intent = android.content.Intent(voiceRoomRepository.context, conectate.com.services.VoiceForegroundService::class.java).apply {
                action = "UPDATE_TYPE" // Distinct action for clarity
                putExtra(conectate.com.services.VoiceForegroundService.EXTRA_TYPE, type)
                putExtra(conectate.com.services.VoiceForegroundService.EXTRA_ROOM_NAME, _room.value?.name ?: "Sala de Voz")
                putExtra(conectate.com.services.VoiceForegroundService.EXTRA_IS_MUTED, _isMuted.value)
            }
            
            // For Android 14+, we MUST use startForegroundService if the service is not already running
            // or if we are upgrading its capabilities while in background.
            // However, startService is usually enough if it's already running.
            voiceRoomRepository.context.startService(intent)
        }
    }

    private fun resolveForegroundServiceType(targetMode: VoiceRoomRepository.VideoMode): Int {
        return when {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R -> {
                when (targetMode) {
                    VoiceRoomRepository.VideoMode.SCREEN -> {
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                    VoiceRoomRepository.VideoMode.CAMERA -> {
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    }
                    else -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                targetMode == VoiceRoomRepository.VideoMode.SCREEN -> {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            else -> 0
        }
    }

    private fun stopService() {
        try {
             val intent = android.content.Intent(voiceRoomRepository.context, conectate.com.services.VoiceForegroundService::class.java)
             intent.action = "STOP"
             voiceRoomRepository.context.stopService(intent)
        } catch (e: Exception) {
             android.util.Log.e("VoiceRoomActiveVM", "Failed to stop foreground service", e)
        }
    }

    fun leaveRoom() {
        stopService()
        
        viewModelScope.launch {
            try {
                currentRoomId?.let { roomId ->
                    voiceRoomRepository.leaveRoom(roomId)
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomActiveVM", "Error leaving room", e)
            }
            currentRoomId = null
            _messages.value = emptyList() // Clear messages on leave
        }
    }

    fun sendMessage(text: String) {
        val userId = authService.getCurrentUserId() ?: return
        val displayName = _participants.value.find { it.id == userId }?.name ?: "Usuario"
        val message = ChatMessage(userId = userId, userName = displayName, text = text)
        
        // Optimistic display for self
        _messages.value = (_messages.value + message).takeLast(50)
        
        viewModelScope.launch {
            voiceRoomRepository.sendMessage(message)
        }
    }

    fun sendReaction(emoji: String) {
        val userId = authService.getCurrentUserId() ?: return
        val reaction = ReactionEvent(userId = userId, emoji = emoji)
        
        // Optimistic display for self
        val id = java.util.UUID.randomUUID().toString()
        _activeReactions.value = _activeReactions.value + reaction.copy(id = id)
        viewModelScope.launch {
            delay(3000)
            _activeReactions.value = _activeReactions.value.filterNot { it.id == id }
        }

        viewModelScope.launch {
            voiceRoomRepository.sendReaction(reaction)
        }
    }

    fun updateRoom(name: String, isPublic: Boolean, password: String?) {
        viewModelScope.launch {
            try {
                currentRoomId?.let { roomId ->
                    voiceRoomRepository.updateRoom(roomId, name, isPublic, password)
                    // Optionally refresh locally if needed, but Realtime should handle it
                    // However, Realtime updates _activeRooms list, not the _room single flow directly unless we chain them.
                    // _room is updated in joinRoom initially. 
                    // Let's manually refetch to be safe.
                    val updated = voiceRoomRepository.getRoomById(roomId).getOrNull()
                    if (updated != null) {
                        _room.value = updated
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomActiveVM", "Error updating room", e)
            }
        }
    }

    fun deleteRoom() {
        viewModelScope.launch {
            try {
                currentRoomId?.let { roomId ->
                    voiceRoomRepository.deleteRoom(roomId)
                    leaveRoom() // Also leave locally
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomActiveVM", "Error deleting room", e)
            }
        }
    }

    fun onInviteQueryChanged(query: String) {
        _inviteQuery.value = query
        if (query.length >= 2) {
            searchUsersToInvite(query)
        } else if (query.isEmpty()) {
            loadFriendsToInvite()
        }
    }

    fun loadFriendsToInvite() {
        val currentUserId = authService.getCurrentUserId() ?: return
        viewModelScope.launch {
            _isSearchingInvites.value = true
            val followingResult = userRepository.getFollowing(currentUserId)
            if (followingResult.isSuccess) {
                _inviteResults.value = followingResult.getOrDefault(emptyList())
            }
            _isSearchingInvites.value = false
        }
    }

    private fun searchUsersToInvite(query: String) {
        viewModelScope.launch {
            _isSearchingInvites.value = true
            val result = userRepository.searchUsers(query)
            if (result.isSuccess) {
                _inviteResults.value = result.getOrDefault(emptyList())
            }
            _isSearchingInvites.value = false
        }
    }

    fun inviteUser(user: conectate.com.data.model.User) {
        val roomId = currentRoomId ?: return
        val roomName = _room.value?.name ?: "Sala de Voz"
        val userId = authService.getCurrentUserId() ?: return
        
        viewModelScope.launch {
            val senderProfile = userRepository.getUserById(userId).getOrNull()
            val inviterName = senderProfile?.displayName ?: "Alguien"
            
            voiceRoomRepository.sendInvitation(
                targetUserId = user.id,
                roomId = roomId,
                roomName = roomName,
                inviterName = inviterName
            )
        }
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        val userId = authService.getCurrentUserId() ?: return
        val roomId = currentRoomId ?: return
        
        // v7.1: Force Mute at the WebRTC AudioTransceiver Level
        voiceRoomRepository.toggleMute(_isMuted.value)
        
        // Update Service Notification
        try {
            val intent = android.content.Intent(voiceRoomRepository.context, conectate.com.services.VoiceForegroundService::class.java).apply {
                action = "MUTE"
                putExtra("isMuted", _isMuted.value)
            }
            voiceRoomRepository.context.startService(intent)
        } catch (e: Exception) {}
        
        // Update speaking status immediately
        voiceRoomRepository.setSpeakingStatus(roomId, userId, false)
        
        // Update local participants UI
        _participants.value = _participants.value.map { participant ->
            if (participant.id == userId) {
                participant.copy(isMuted = _isMuted.value, isSpeaking = false)
            } else {
                participant
            }
        }
    }

    fun toggleSpeaker() {
        _isSpeaker.value = !_isSpeaker.value
        voiceRoomRepository.toggleSpeaker(_isSpeaker.value)
    }

    fun setParticipantVolume(socketId: String, volume: Float) {
        // volume slider usually 0f to 1f. WebRTC usually 0.0 to 10.0. 
        // Let's map 0..1 to 0..10? Or just 0..1 if strictly standard?
        // WebRTC standard says 1.0 is normal. 0 is silence. >1 is gain.
        voiceRoomRepository.setParticipantVolume(socketId, volume.toDouble())
    }

    // v19.0: Chat Unread Count
    private val _unreadChatCount = MutableStateFlow(0)
    val unreadChatCount = _unreadChatCount.asStateFlow()

    fun markChatRead() {
        _unreadChatCount.value = 0
    }

    // Simplified talking detection using WebRTC state
    // (This replaces the hardware-conflicting VoiceDetector)
    private fun startVoiceDetection() {
        viewModelScope.launch {
            while (isActive) {
                val userId = authService.getCurrentUserId() ?: break
                val roomId = currentRoomId ?: break
                
                // 1. Local
                val isLocalSpeaking = if (!_isMuted.value) {
                     voiceRoomRepository.getLocalAudioLevel() > 0.05 // v19.0: Lowered threshold for sensitivity
                } else false
                
                // 2. Fetch Remote Levels
                voiceRoomRepository.getRemoteAudioLevels { remoteLevels ->
                     _participants.value = _participants.value.map { p ->
                         if (p.id == userId) {
                             // Update local speaking state
                             if (p.isSpeaking != isLocalSpeaking) {
                                 voiceRoomRepository.setSpeakingStatus(roomId, userId, isLocalSpeaking)
                                 p.copy(isSpeaking = isLocalSpeaking)
                             } else p
                         } else {
                             // Update remote speaking state based on Real-time Audio Level
                             val level = remoteLevels[p.socketId] ?: 0.0
                             val isRemoteSpeaking = level > 0.05 // Threshold
                             
                             if (p.isSpeaking != isRemoteSpeaking) {
                                  p.copy(isSpeaking = isRemoteSpeaking)
                             } else p
                         }
                     }
                }
                
                delay(300) // Poll every 300ms
            }
        }
    }

    fun toggleCinemaMode() {
        val currentRoomId = currentRoomId ?: return
        val isCreator = _room.value?.creatorId == getCurrentUserId()
        if (!isCreator) return

        val currentState = cinemaState.value?.isCinemaMode ?: false
        val newState = !currentState
        
        voiceRoomRepository.emitCinemaState(
            currentRoomId,
            conectate.com.data.repository.CinemaState(
                isCinemaMode = newState,
                videoUrl = if (newState) cinemaState.value?.videoUrl else null,
                videoTitle = if (newState) cinemaState.value?.videoTitle else null,
                isPlaying = if (newState) (cinemaState.value?.isPlaying ?: false) else false,
                currentPosition = if (newState) (cinemaState.value?.currentPosition ?: 0L) else 0L
            )
        )
    }

    fun exitCinemaMode() {
        val currentRoomId = currentRoomId ?: return
        if (_room.value?.creatorId == getCurrentUserId()) {
            // v7.1: Explicitly preserve audio mode before clearing cinema
            voiceRoomRepository.emitCinemaState(
                currentRoomId,
                conectate.com.data.repository.CinemaState(
                    isCinemaMode = false,
                    isPlaying = false,
                    currentPosition = 0L
                )
            )
        }
    }

    fun selectVideo(url: String, title: String) {
        val currentRoomId = currentRoomId ?: return
        voiceRoomRepository.emitCinemaState(
            currentRoomId,
            conectate.com.data.repository.CinemaState(
                isCinemaMode = true,
                videoUrl = url,
                videoTitle = title,
                isPlaying = true,
                currentPosition = 0L
            )
        )
    }

    fun syncCinema(isPlaying: Boolean, position: Long) {
        val currentId = currentRoomId ?: return
        if (_room.value?.creatorId == getCurrentUserId()) {
            voiceRoomRepository.emitCinemaSync(currentId, isPlaying, position)
        }
    }

    fun controlCinema(action: String, position: Long) {
        val currentId = currentRoomId ?: return
        if (_room.value?.creatorId == getCurrentUserId()) {
            voiceRoomRepository.emitCinemaControl(currentId, action, position)
        }
    }

    suspend fun resolveUniversalLink(url: String) = voiceRoomRepository.resolveUniversalLink(url)

    override fun onCleared() {
        super.onCleared()
        // v18.0: Background Audio Support
        // Do NOT stop service or leave room when ViewModel is cleared (Screen destroyed).
        // Service must persist. 
        // Cleanup happens only via explicit leaveRoom() call.
        // stopService() 
        // stopVideo()
        // leaveRoom()
        android.util.Log.d("VoiceRoomActiveVM", "ViewModel cleared, but session persists for background audio.")
    }

    init {
        // v15.0: Preload Catalog for Instant Open (<2s target)
        viewModelScope.launch(Dispatchers.IO) {
             try {
                 // Using a temporary instance to warm up the Static Cache
                 val preloader = CinemaCatalog("cafce0be2d371c964acd937d58621e6a")
                 preloader.getTrending()
                 android.util.Log.d("VoiceRoomActiveVM", "🚀 Catalog pre-warmed successfully")
             } catch (e: Exception) {
                 android.util.Log.e("VoiceRoomActiveVM", "Catalog preload failed", e)
             }
        }
    }


}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val userId: String,
    val userName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ReactionEvent(
    val id: String = "",
    val userId: String,
    val emoji: String
)
