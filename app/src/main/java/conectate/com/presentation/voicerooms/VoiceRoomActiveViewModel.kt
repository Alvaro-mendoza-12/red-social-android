package conectate.com.presentation.voicerooms

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.VoiceParticipant
import conectate.com.data.model.VoiceRoom
import conectate.com.data.model.VoiceStageRoles
import conectate.com.data.model.canPublishOnStage
import conectate.com.data.model.isStageMode
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.data.repository.VoiceCaption
import conectate.com.data.repository.VoiceRoomSummary
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.webrtc.VideoTrack
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class VoiceRoomActiveViewModel @Inject constructor(
    private val voiceRoomRepository: VoiceRoomRepository,
    private val authService: AuthService,
    private val userRepository: conectate.com.data.repository.UserRepository,
    private val messageRepository: conectate.com.data.repository.MessageRepository,
    private val groupRepository: conectate.com.data.repository.GroupRepository,
    private val communityRepository: conectate.com.data.repository.CommunityRepository,
) : ViewModel() {
    private companion object {
        const val REACTION_VISIBLE_MS = 6_000L
        const val LOCAL_ECHO_WINDOW_MS = 3_000L
        const val REACTION_SEND_COOLDOWN_MS = 700L
    }

    private val _room = MutableStateFlow<VoiceRoom?>(null)
    val room: StateFlow<VoiceRoom?> = _room.asStateFlow()
    val cueSettings = voiceRoomRepository.cueSettings
    fun updateCueSettings(value: conectate.com.data.voice.VoiceCueSettings) = voiceRoomRepository.updateCueSettings(value)

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeaker = MutableStateFlow(true)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker.asStateFlow()

    private val _isJoined = MutableStateFlow(false)
    val isJoined: StateFlow<Boolean> = _isJoined.asStateFlow()

    private val _joinError = MutableStateFlow<String?>(null)
    val joinError: StateFlow<String?> = _joinError.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _participants = MutableStateFlow<List<VoiceParticipant>>(emptyList())
    val participants: StateFlow<List<VoiceParticipant>> = _participants.asStateFlow()

    private val _videoMode = MutableStateFlow(VoiceRoomRepository.VideoMode.NONE)
    val videoMode: StateFlow<VoiceRoomRepository.VideoMode> = _videoMode.asStateFlow()
    val videoError: StateFlow<String?> = voiceRoomRepository.videoError

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()
    val sharingStates: StateFlow<Map<String, Pair<Boolean, Boolean>>> =
        voiceRoomRepository.sharingStates

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _peerConnectionStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerConnectionStates: StateFlow<Map<String, String>> = _peerConnectionStates.asStateFlow()

    private val _signalingStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val signalingStates: StateFlow<Map<String, String>> = _signalingStates.asStateFlow()

    private val _debugInfo = MutableStateFlow<String?>(null)
    val debugInfo: StateFlow<String?> = _debugInfo.asStateFlow()

    private val _fullScreenParticipantId = MutableStateFlow<String?>(null)
    val fullScreenParticipantId: StateFlow<String?> = _fullScreenParticipantId.asStateFlow()

    private val _cinemaState = MutableStateFlow<CinemaState?>(null)
    val cinemaState: StateFlow<CinemaState?> = _cinemaState.asStateFlow()

    val cinemaSync = _cinemaState

    private val _isPlayerFullscreen = MutableStateFlow(false)
    val isPlayerFullscreen: StateFlow<Boolean> = _isPlayerFullscreen.asStateFlow()

    fun setPlayerFullscreen(fullscreen: Boolean) {
        _isPlayerFullscreen.value = fullscreen
    }

    private val _unreadChatCount = MutableStateFlow(0)
    val unreadChatCount: StateFlow<Int> = _unreadChatCount.asStateFlow()

    private val _showPasswordPrompt = MutableStateFlow(false)
    val showPasswordPrompt: StateFlow<Boolean> = _showPasswordPrompt.asStateFlow()

    private val _passwordError = MutableStateFlow<String?>(null)
    val passwordError: StateFlow<String?> = _passwordError.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private var chatVisible = false
    private val deletedChatIds=linkedSetOf<String>()
    private val confirmedDeletedChatIds=linkedSetOf<String>()
    private val unreadChatIds=linkedSetOf<String>()
    private val _chatError=MutableStateFlow<String?>(null)
    val chatError=_chatError.asStateFlow()
    fun setChatVisible(visible: Boolean) { chatVisible = visible; if (visible) markChatRead() }

    private val _activeReactions = MutableStateFlow<List<ReactionEvent>>(emptyList())
    val activeReactions: StateFlow<List<ReactionEvent>> = _activeReactions.asStateFlow()
    val captionsEnabled = voiceRoomRepository.captionsEnabled
    val captionConsentUserIds = voiceRoomRepository.captionConsentUserIds
    val liveCaptions: StateFlow<List<VoiceCaption>> = voiceRoomRepository.liveCaptions
    val captionError = voiceRoomRepository.captionError
    val voiceSummary: StateFlow<VoiceRoomSummary?> = voiceRoomRepository.voiceSummary
    val voiceSummaryError = voiceRoomRepository.voiceSummaryError
    val isVoiceSummaryLoading = voiceRoomRepository.isVoiceSummaryLoading
    private val reactionExpiryJobs = mutableMapOf<String, Job>()
    private var lastReactionSentAtMs = 0L
    private var videoActionJob: Job? = null

    private val _shareDestinations = MutableStateFlow<List<VoiceShareDestination>>(emptyList())
    val shareDestinations: StateFlow<List<VoiceShareDestination>> = _shareDestinations.asStateFlow()
    private val _selectedShareKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedShareKeys: StateFlow<Set<String>> = _selectedShareKeys.asStateFlow()
    private val _isLoadingShareDestinations = MutableStateFlow(false)
    val isLoadingShareDestinations: StateFlow<Boolean> = _isLoadingShareDestinations.asStateFlow()
    private val _isSendingShareInvites = MutableStateFlow(false)
    val isSendingShareInvites: StateFlow<Boolean> = _isSendingShareInvites.asStateFlow()
    private val _shareNotice = MutableStateFlow<String?>(null)
    val shareNotice: StateFlow<String?> = _shareNotice.asStateFlow()
    private var shareLoadJob: Job? = null

    private val _inviteQuery = MutableStateFlow("")
    val inviteQuery: StateFlow<String> = _inviteQuery.asStateFlow()

    private val inviteSearch = conectate.com.presentation.search.UnifiedSearchController<UserInvite, Unit>(viewModelScope,
        findUsers = { term ->
            val account = authService.getCurrentUserId()
            userRepository.searchUsers(term).map { users ->
                check(account == authService.getCurrentUserId())
                users.filterNot { it.id == account }.distinctBy { it.id }.map {
                    UserInvite(it.id, it.displayName?.takeIf(String::isNotBlank) ?: it.username?.takeIf(String::isNotBlank) ?: it.full_name?.takeIf(String::isNotBlank) ?: "Usuario", it.avatar_url)
                }
            }
        }, findContent = { Result.success(emptyList()) })
    val inviteResults = inviteSearch.users
    val isSearchingInvites = inviteSearch.loading
    val inviteSearchError = inviteSearch.usersError
    private val _invitingId = MutableStateFlow<String?>(null)
    val invitingId = _invitingId.asStateFlow()
    private val _inviteNotice = MutableStateFlow<String?>(null)
    val inviteNotice = _inviteNotice.asStateFlow()

    private val roomTransition = Mutex()
    private var entryJob: Job? = null
    private var exitJob: Job? = null
    private var joinedRoomId: String? = null
    private var pendingPasswordRoomId: String? = null
    private var stageSyncJob: Job? = null

    init {
        viewModelScope.launch {
            voiceRoomRepository.currentRoom.collectLatest { room ->
                if(room?.id!=_room.value?.id) {
                    _messages.value=emptyList();deletedChatIds.clear();confirmedDeletedChatIds.clear();unreadChatIds.clear();_unreadChatCount.value=0
                }
                _room.value = room
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.currentParticipants.collectLatest { participants ->
                _participants.value = participants
                val selfId = _currentUserId.value?.trim()?.lowercase().orEmpty()
                if (selfId.isNotBlank()) {
                    _isMuted.value = participants.firstOrNull {
                        it.id.trim().lowercase() == selfId
                    }?.isMuted ?: _isMuted.value
                }
                if (voiceRoomRepository.captionsEnabled.value && !canCurrentUserPublish()) {
                    voiceRoomRepository.disableCaptions()
                    _joinError.value = "Los subtítulos se detuvieron porque ya no tienes permiso para hablar."
                }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.currentVideoMode.collectLatest { mode ->
                _videoMode.value = mode
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.remoteVideoTracks.collectLatest { tracks ->
                _remoteVideoTracks.value = tracks
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.localVideoTrack.collectLatest { track ->
                _localVideoTrack.value = track
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.peerConnectionStates.collectLatest { states ->
                _peerConnectionStates.value = states.mapValues { it.value.name }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.signalingStates.collectLatest { states ->
                _signalingStates.value = states.mapValues { it.value.name }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.debugInfo.collectLatest { info ->
                _debugInfo.value = info.entries
                    .take(6)
                    .joinToString("\n") { "${it.key}: ${it.value}" }
                    .ifBlank { null }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.sessionState.collectLatest { session ->
                _isJoined.value = session.isConnected
                _videoMode.value = session.videoMode
                _isMuted.value = session.isMuted
                if (session.connectionState == conectate.com.data.repository.VoiceConnectionState.ERROR) {
                    val error = session.connectionError ?: "No se pudo conectar con la sala de voz."
                    _joinError.value = error
                    if (_room.value?.passwordRequired == true && error.contains("VOICE_ROOM_ACCESS_DENIED")) {
                        pendingPasswordRoomId = _room.value?.id
                        _passwordError.value = "La contraseña no es correcta."
                        _showPasswordPrompt.value = true
                    }
                } else if (session.isConnected) {
                    _joinError.value = null
                    _passwordError.value = null
                    _showPasswordPrompt.value = false
                    pendingPasswordRoomId = null
                }
                session.room?.let { _room.value = it }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.cinemaState.collectLatest { state ->
                _cinemaState.value = state?.let {
                    CinemaState(
                        isCinemaMode = it.isCinemaMode,
                        videoUrl = it.videoUrl.orEmpty(),
                        videoTitle = it.videoTitle.orEmpty(),
                        isPlaying = it.isPlaying,
                        currentPosition = it.currentPosition
                    )
                }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.incomingMessages.collectLatest { message ->
                if(message.roomId!=null&&message.roomId!=_room.value?.id)return@collectLatest
                val key="${message.userId}:${message.id}"
                if(key in deletedChatIds)return@collectLatest
                val known = _messages.value.any { it.id == message.id && it.userId==message.userId }
                _messages.value = boundedRoomChat(_messages.value + message)
                if (!known && !chatVisible && !message.userId.equals(getCurrentUserId(),true)) unreadChatIds.add(key)
                reconcileChatUnread()
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.deletedRoomMessages.collectLatest { (roomId,author,id) ->
                if(roomId!=_room.value?.id)return@collectLatest
                val key="$author:$id";deletedChatIds.add(key);confirmedDeletedChatIds.add(key)
                while(deletedChatIds.size>1000)deletedChatIds.remove(deletedChatIds.first())
                while(confirmedDeletedChatIds.size>1000)confirmedDeletedChatIds.remove(confirmedDeletedChatIds.first())
                _messages.value=_messages.value.filterNot { it.id==id&&it.userId==author };reconcileChatUnread()
            }
        }
        viewModelScope.launch {
            while(isActive) { delay(1000);if(_messages.value.isNotEmpty()){_messages.value=boundedRoomChat(_messages.value);reconcileChatUnread()} }
        }
        viewModelScope.launch {
            voiceRoomRepository.incomingReactions.collectLatest { reaction ->
                if (isRecentLocalEcho(reaction)) return@collectLatest
                appendReaction(reaction)
            }
        }
    }

    fun getEglBaseContext(): org.webrtc.EglBase.Context? {
        return voiceRoomRepository.getEglBaseContext()
    }

    fun joinRoom(roomId: String, password: String? = null) {
        val initiatingSession = authService.captureAccountLease()
        if (!initiatingSession.isCurrent()) return
        entryJob?.cancel()
        entryJob = viewModelScope.launch {
            initiatingSession.requireCurrent()
            _joinError.value = null
            val seed = authService.getCurrentUserProfileSeed()
            currentCoroutineContext().ensureActive()
            initiatingSession.requireCurrent()
            val userId = voiceRoomRepository.getCurrentUserId()
                ?: seed?.id
                ?: run {
                    _joinError.value = "No se pudo identificar tu sesión. Vuelve a iniciar sesión."
                    return@launch
                }
            _currentUserId.value = userId
            try {
                val roomResult = voiceRoomRepository.getRoomById(roomId)
                currentCoroutineContext().ensureActive()
                initiatingSession.requireCurrent()
                val resolvedRoom = roomResult.getOrThrow()
                roomTransition.withLock {
                    currentCoroutineContext().ensureActive()
                    initiatingSession.requireCurrent()
                    _room.value = resolvedRoom
                    val isCreator = resolvedRoom.creatorId.trim().equals(userId.trim(), ignoreCase = true)
                    if (resolvedRoom.passwordRequired && !isCreator && password == null) {
                        pendingPasswordRoomId = resolvedRoom.id
                        _passwordError.value = null
                        _showPasswordPrompt.value = true
                        return@withLock
                    }
                    if (resolvedRoom.passwordRequired && !isCreator && password.isNullOrEmpty()) {
                        _passwordError.value = "Escribe la contraseña de la sala."
                        _showPasswordPrompt.value = true
                        return@withLock
                    }
                    _showPasswordPrompt.value = false
                    _passwordError.value = null
                    if (joinedRoomId == resolvedRoom.id && _isJoined.value) return@withLock
                    joinedRoomId = resolvedRoom.id

                    val displayName = seed?.displayName
                        ?: seed?.fullName
                        ?: "Usuario"
                    val startMuted = !resolvedRoom.groupId.isNullOrBlank() || (resolvedRoom.isStageMode()
                        && resolvedRoom.creatorId.trim().lowercase() != userId.trim().lowercase())
                    if (startMuted) {
                        _isMuted.value = true
                    }
                    voiceRoomRepository.toggleSpeaker(_isSpeaker.value)
                    initiatingSession.requireCurrent()
                    voiceRoomRepository.joinRoom(
                        roomId = resolvedRoom.id,
                        userId = userId,
                        displayName = displayName,
                        avatarUrl = seed?.avatarUrl,
                        signalingRoomId = roomId,
                        startMuted = startMuted,
                        roomPassword = password,
                        resolvedRoom = resolvedRoom
                    )
                    currentCoroutineContext().ensureActive()
                    initiatingSession.requireCurrent()
                    if (resolvedRoom.isStageMode()) {
                        startStageSync(resolvedRoom.id)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                initiatingSession.requireCurrent()
                android.util.Log.e("VoiceRoomActiveVM", "Error joining room", e)
                _isJoined.value = false
                _joinError.value = e.message ?: "No se pudo entrar a la sala de voz."
            }
        }
    }

    fun toggleMute() {
        if (!canCurrentUserPublish()) {
            _isMuted.value = true
            _joinError.value = "Solicita la palabra para activar el micrófono en este escenario."
            return
        }
        val next = !_isMuted.value
        _isMuted.value = next
        voiceRoomRepository.toggleMute(next)
    }

    fun toggleSpeaker() {
        val next = !_isSpeaker.value
        _isSpeaker.value = next
        voiceRoomRepository.toggleSpeaker(next)
    }

    fun enableCaptions() {
        voiceRoomRepository.enableCaptions().fold(
            onSuccess = { _joinError.value = null },
            onFailure = {
                _joinError.value = it.message ?: "No se pudieron activar los subtítulos."
            }
        )
    }

    fun disableCaptions() {
        voiceRoomRepository.disableCaptions()
    }

    fun clearCaptionError() {
        voiceRoomRepository.clearCaptionError()
    }

    fun requestVoiceSummary() {
        voiceRoomRepository.requestVoiceSummary(accepted = true).onFailure {
            _joinError.value = it.message ?: "No se pudo solicitar el resumen."
        }
    }

    fun clearVoiceSummary() {
        voiceRoomRepository.clearVoiceSummary()
    }

    fun clearVoiceSummaryError() {
        voiceRoomRepository.clearVoiceSummaryError()
    }

    fun toggleCamera() {
        if (videoActionJob?.isActive == true) return
        videoActionJob = viewModelScope.launch {
            if (!_isJoined.value) {
                _joinError.value = "Espera a que la sala termine de conectar."
                return@launch
            }
            if (!canCurrentUserPublish()) {
                _joinError.value = "El moderador debe darte la palabra antes de activar la cámara."
                return@launch
            }
            runCatching {
                if (_videoMode.value == VoiceRoomRepository.VideoMode.CAMERA) {
                    voiceRoomRepository.stopVideo()
                } else {
                    voiceRoomRepository.startCameraCapture()
                }
            }.onFailure {
                _joinError.value = "No se pudo cambiar la cámara. Intenta otra vez."
            }
            delay(2_200)
        }
    }

    fun toggleScreenShare(data: Intent) {
        if (videoActionJob?.isActive == true) return
        videoActionJob = viewModelScope.launch {
            if (!_isJoined.value) {
                _joinError.value = "Espera a que la sala termine de conectar."
                return@launch
            }
            if (!canCurrentUserPublish()) {
                _joinError.value = "El moderador debe darte la palabra antes de compartir pantalla."
                return@launch
            }
            runCatching {
                if (_videoMode.value == VoiceRoomRepository.VideoMode.SCREEN) {
                    voiceRoomRepository.stopVideo()
                } else {
                    voiceRoomRepository.startScreenCapture(data)
                }
            }.onFailure {
                _joinError.value = "No se pudo compartir la pantalla. Vuelve a intentarlo."
            }
            delay(2_200)
        }
    }

    fun stopVideo() {
        runCatching { voiceRoomRepository.stopVideo() }
    }

    fun clearVideoError() {
        voiceRoomRepository.clearVideoError()
    }

    fun leaveRoom() {
        entryJob?.cancel()
        entryJob = null
        if (exitJob?.isActive == true) return
        stageSyncJob?.cancel()
        stageSyncJob = null
        pendingPasswordRoomId = null
        _showPasswordPrompt.value = false
        _passwordError.value = null
        // The screen can be popped immediately after tapping Leave. Finish the
        // accepted exit after any in-flight admission has released the media engine.
        exitJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                roomTransition.withLock {
                    voiceRoomRepository.leaveRoom()
                    _isJoined.value = false
                    joinedRoomId = null
                }
            }
        }
    }

    fun canCurrentUserPublish(): Boolean {
        if (_room.value?.isStageMode() != true) return true
        if (
            _room.value?.creatorId?.trim()
                ?.equals(getCurrentUserId().trim(), ignoreCase = true) == true
        ) return true
        return _participants.value.firstOrNull {
            it.id.trim().equals(getCurrentUserId().trim(), ignoreCase = true)
        }?.canPublishOnStage() == true
    }

    fun isCurrentUserStageModerator(): Boolean {
        val currentId = getCurrentUserId().trim()
        if (_room.value?.creatorId?.trim()?.equals(currentId, ignoreCase = true) == true) return true
        return _participants.value.firstOrNull {
            it.id.trim().equals(currentId, ignoreCase = true)
        }?.role?.equals(VoiceStageRoles.MODERATOR, ignoreCase = true) == true
    }

    fun setHandRaised(handRaised: Boolean) {
        val roomId = _room.value?.id ?: return
        viewModelScope.launch {
            voiceRoomRepository.setStageHandRaised(roomId, handRaised).fold(
                onSuccess = { _joinError.value = null },
                onFailure = { _joinError.value = it.message ?: "No se pudo actualizar tu solicitud." }
            )
        }
    }

    fun moveStageParticipant(userId: String, role: String) {
        val roomId = _room.value?.id ?: return
        viewModelScope.launch {
            voiceRoomRepository.moderateStageParticipant(roomId, userId, role = role).fold(
                onSuccess = { _joinError.value = null },
                onFailure = { _joinError.value = it.message ?: "No se pudo actualizar a esta persona." }
            )
        }
    }

    fun setStageParticipantMuted(userId: String, isMuted: Boolean) {
        val roomId = _room.value?.id ?: return
        viewModelScope.launch {
            voiceRoomRepository.moderateStageParticipant(roomId, userId, isMuted = isMuted).fold(
                onSuccess = { _joinError.value = null },
                onFailure = { _joinError.value = it.message ?: "No se pudo cambiar el micrófono." }
            )
        }
    }

    private fun startStageSync(roomId: String) {
        stageSyncJob?.cancel()
        stageSyncJob = viewModelScope.launch {
            while (joinedRoomId == roomId) {
                voiceRoomRepository.refreshVoiceRoomState(roomId).onFailure { error ->
                    android.util.Log.w("VoiceRoomActiveVM", "No se pudo sincronizar el escenario", error)
                }
                delay(2_500)
            }
        }
    }

    fun getCurrentUserId(): String = _currentUserId.value ?: ""

    fun toggleFullScreen(participantId: String?) {
        _fullScreenParticipantId.value = participantId
    }

    fun setParticipantVolume(participantId: String, volume: Float) {
        voiceRoomRepository.setParticipantVolume(participantId, volume.toDouble())
    }

    fun selectVideo(url: String, title: String) {
        if (_room.value?.creatorId?.trim()?.equals(getCurrentUserId().trim(), ignoreCase = true) != true) return
        val state = CinemaState(
            isCinemaMode = true,
            videoUrl = url,
            videoTitle = title,
            isPlaying = true,
            currentPosition = 0L
        )
        _cinemaState.value = state
        _room.value?.id?.let { roomId ->
            voiceRoomRepository.emitCinemaState(
                roomId,
                conectate.com.data.repository.CinemaState(
                    isCinemaMode = true,
                    videoUrl = url,
                    videoTitle = title,
                    isPlaying = true,
                    currentPosition = 0L
                )
            )
        }
    }

    suspend fun resolveUniversalLink(url: String): VoiceRoomRepository.CinemaResolvedItem =
        voiceRoomRepository.resolveUniversalLink(url)

    fun sendReaction(emoji: String) {
        val now = System.currentTimeMillis()
        if (now - lastReactionSentAtMs < REACTION_SEND_COOLDOWN_MS) return
        lastReactionSentAtMs = now
        val reaction = ReactionEvent(
            id = UUID.randomUUID().toString(),
            userId = getCurrentUserId(),
            emoji = emoji,
            timestamp = now
        )
        appendReaction(reaction)
        _room.value?.id?.let { roomId ->
            voiceRoomRepository.sendReaction(roomId, emoji, reaction.id, reaction.timestamp)
        }
    }

    private fun appendReaction(reaction: ReactionEvent) {
        val enriched = reaction.copy(id = reaction.id.ifBlank { UUID.randomUUID().toString() })
        if (_activeReactions.value.any { it.id == enriched.id }) return
        val normalizedUser = enriched.userId.trim().lowercase()
        val withoutPreviousFromUser = if (normalizedUser.isNotBlank()) {
            _activeReactions.value.filterNot { existing ->
                val sameUser = existing.userId.trim().lowercase() == normalizedUser
                if (sameUser) reactionExpiryJobs.remove(existing.id)?.cancel()
                sameUser
            }
        } else {
            _activeReactions.value
        }
        _activeReactions.value = (withoutPreviousFromUser + enriched).distinctBy { it.id }.takeLast(12)
        scheduleReactionExpiry(enriched)
    }

    private fun scheduleReactionExpiry(reaction: ReactionEvent) {
        val reactionId = reaction.id.trim()
        if (reactionId.isBlank()) return
        reactionExpiryJobs[reactionId]?.cancel()
        reactionExpiryJobs[reactionId] = viewModelScope.launch {
            delay(REACTION_VISIBLE_MS)
            _activeReactions.value = _activeReactions.value.filterNot { it.id == reactionId }
            reactionExpiryJobs.remove(reactionId)?.cancel()
        }
    }

    private fun isRecentLocalEcho(reaction: ReactionEvent): Boolean {
        val currentUserId = getCurrentUserId().trim().lowercase()
        val reactionUserId = reaction.userId.trim().lowercase()
        if (currentUserId.isBlank() || reactionUserId != currentUserId) return false
        val now = System.currentTimeMillis()
        return _activeReactions.value.any { active ->
            active.userId.trim().lowercase() == currentUserId &&
                active.emoji == reaction.emoji &&
                now - active.timestamp in 0..LOCAL_ECHO_WINDOW_MS
        }
    }

    fun exitCinemaMode() {
        _cinemaState.value = null
        _room.value?.id?.let { roomId ->
            voiceRoomRepository.emitCinemaState(
                roomId,
                conectate.com.data.repository.CinemaState(isCinemaMode = false)
            )
        }
    }

    fun syncCinema(playing: Boolean, position: Long) {
        _cinemaState.value = _cinemaState.value?.copy(isPlaying = playing, currentPosition = position)
        _room.value?.id?.let { roomId -> voiceRoomRepository.emitCinemaSync(roomId, playing, position) }
    }

    fun controlCinema(action: String, position: Long) {
        when (action) {
            "play" -> _cinemaState.value = _cinemaState.value?.copy(isPlaying = true)
            "pause" -> _cinemaState.value = _cinemaState.value?.copy(isPlaying = false)
            "seek" -> _cinemaState.value = _cinemaState.value?.copy(currentPosition = position)
        }
        _room.value?.id?.let { roomId -> voiceRoomRepository.emitCinemaControl(roomId, action, position) }
    }

    private val settingsActions by lazy {
        conectate.com.presentation.messages.CollectionActionRunner(viewModelScope, authService::getCurrentUserId, { _joinError.value = it }, authService.currentSession.map { authService.getCurrentUserId() })
    }
    fun updateRoom(name: String, isPublic: Boolean, password: String?) {
        val room = _room.value ?: return
        if (name.isBlank()) { _joinError.value = "El nombre de la sala es obligatorio."; return }
        settingsActions.run(room.id, "No se pudo guardar la sala. Revisa tus permisos y la conexión y reintenta.",
            { _room.value?.id == room.id }, { voiceRoomRepository.updateRoom(room.id, name.trim(), isPublic, password) }, {
                _room.value = _room.value?.copy(name = name.trim(), isPublic = isPublic)
                _joinError.value = null
            })
    }

    fun validatePassword(password: String) {
        val roomId = pendingPasswordRoomId ?: _room.value?.id ?: return
        if (password.isEmpty()) {
            _passwordError.value = "Escribe la contraseña de la sala."
            return
        }
        _showPasswordPrompt.value = false
        joinRoom(roomId, password)
    }

    fun markChatRead() {
        unreadChatIds.clear()
        _unreadChatCount.value = 0
    }

    private fun reconcileChatUnread() {
        unreadChatIds.retainAll(_messages.value.map { "${it.userId}:${it.id}" }.toSet())
        _unreadChatCount.value=unreadChatIds.size
    }

    fun retryMessage(message: ChatMessage) {
        if(message.userId!=getCurrentUserId()||message.expiresAt<=System.currentTimeMillis())return
        val owner=getCurrentUserId();val roomId=_room.value?.id
        _messages.value=boundedRoomChat(_messages.value+message.copy(delivery=RoomChatDelivery.SENDING));_chatError.value=null
        viewModelScope.launch {
            val result=voiceRoomRepository.sendMessage(message)
            if(owner!=getCurrentUserId()||roomId!=_room.value?.id||"${message.userId}:${message.id}" in deletedChatIds)return@launch
            result.onSuccess { accepted -> _messages.value=boundedRoomChat(_messages.value+accepted) }.onFailure { error ->
                _messages.value=_messages.value.map { if(it.id==message.id&&it.userId==message.userId&&it.delivery!=RoomChatDelivery.SENT)it.copy(delivery=RoomChatDelivery.FAILED)else it }
                _chatError.value=error.message
            }
        }
    }

    fun deleteMessage(message: ChatMessage) {
        if(message.userId!=getCurrentUserId())return
        val key="${message.userId}:${message.id}";val owner=getCurrentUserId();val roomId=_room.value?.id
        deletedChatIds.add(key);_messages.value=_messages.value.filterNot { it.id==message.id&&it.userId==message.userId };reconcileChatUnread()
        viewModelScope.launch {
            val result=voiceRoomRepository.deleteRoomMessage(message)
            if(owner!=getCurrentUserId()||roomId!=_room.value?.id)return@launch
            result.onFailure { error -> if(key !in confirmedDeletedChatIds){deletedChatIds.remove(key);_messages.value=boundedRoomChat(_messages.value+message).sortedBy { it.timestamp };_chatError.value=error.message} }
        }
    }

    fun sendMessage(text: String, imageData: String? = null) {
        if (text.isBlank() && imageData == null) return
        if (imageData != null && RoomChatPhoto.bytes(imageData) == null) return
        viewModelScope.launch {
            val msg = ChatMessage(
                id = UUID.randomUUID().toString(),
                userId = getCurrentUserId(),
                text = text.trim().take(2000).ifBlank { "📷 Foto" },
                timestamp = System.currentTimeMillis(),
                imageData = imageData,
                roomId = _room.value?.id
            )
            retryMessage(msg)
        }
    }

    fun onInviteQueryChanged(query: String) {
        _inviteQuery.value = query.take(120)
        inviteSearch.search(_inviteQuery.value)
    }

    fun prepareShareDestinations() {
        _shareNotice.value = null
        shareLoadJob?.cancel()
        shareLoadJob = viewModelScope.launch {
            _isLoadingShareDestinations.value = true
            val account = authService.getCurrentUserId()
            val chats = runCatching { messageRepository.userChats.firstOrNull().orEmpty() }.getOrDefault(emptyList())
            val groups = runCatching { groupRepository.getGroupsFlow().firstOrNull().orEmpty() }.getOrDefault(emptyList())
            val communities = runCatching { communityRepository.getCommunitiesFlow().firstOrNull().orEmpty() }.getOrDefault(emptyList())
            if (account != authService.getCurrentUserId()) {
                _isLoadingShareDestinations.value = false
                return@launch
            }
            val items = buildList {
                chats.forEach { item ->
                    val chatId = item.chat.id.trim()
                    if (chatId.isBlank() || item.chat.is_group) return@forEach
                    val title = item.nickname?.trim()?.takeIf(String::isNotBlank)
                        ?: item.otherUserName?.trim()?.takeIf(String::isNotBlank)
                        ?: "Chat"
                    add(
                        VoiceShareDestination(
                            key = "chat:$chatId",
                            id = chatId,
                            kind = VoiceShareKind.CHAT,
                            title = title,
                            subtitle = "Chat directo",
                            avatarUrl = item.otherUserImage?.takeIf(String::isNotBlank),
                            sortKey = item.lastMessage?.created_at ?: item.chat.last_message_at ?: item.chat.updated_at,
                        )
                    )
                }
                groups.forEach { item ->
                    if (item.group.is_deleted || !item.group.is_member) return@forEach
                    val groupId = item.group.id.trim()
                    if (groupId.isBlank()) return@forEach
                    add(
                        VoiceShareDestination(
                            key = "group:$groupId",
                            id = groupId,
                            kind = VoiceShareKind.GROUP,
                            title = item.group.name.ifBlank { "Grupo" },
                            subtitle = "Grupo",
                            avatarUrl = item.group.image?.takeIf(String::isNotBlank),
                            sortKey = item.lastMessage?.created_at ?: item.group.last_message_at ?: item.group.created_at,
                        )
                    )
                }
                communities.forEach { item ->
                    if (item.community.is_deleted) return@forEach
                    val communityId = item.community.id.trim()
                    if (communityId.isBlank()) return@forEach
                    add(
                        VoiceShareDestination(
                            key = "community:$communityId",
                            id = communityId,
                            kind = VoiceShareKind.COMMUNITY,
                            title = item.community.name.ifBlank { "Comunidad" },
                            subtitle = "Comunidad",
                            avatarUrl = item.community.image?.takeIf(String::isNotBlank),
                            sortKey = item.lastMessage?.created_at ?: item.community.last_message_at ?: item.community.created_at,
                        )
                    )
                }
            }.sortedByDescending { it.sortKey }
            _shareDestinations.value = items
            _isLoadingShareDestinations.value = false
        }
    }

    fun toggleShareSelection(key: String) {
        val current = _selectedShareKeys.value
        _selectedShareKeys.value = if (key in current) current - key else current + key
    }

    fun clearShareState() {
        _selectedShareKeys.value = emptySet()
        _shareNotice.value = null
        onInviteQueryChanged("")
    }

    fun sendShareInvites(shareLink: String) {
        val room = _room.value ?: return
        val account = authService.getCurrentUserId() ?: return
        if (_isSendingShareInvites.value) return
        val selectedKeys = _selectedShareKeys.value
        if (selectedKeys.isEmpty()) return

        val knownDestinations = _shareDestinations.value.associateBy { it.key }
        val knownUsers = inviteResults.value.associateBy { "user:${it.id}" }
        val targets = selectedKeys.mapNotNull { key ->
            knownDestinations[key] ?: knownUsers[key]?.let { user ->
                VoiceShareDestination(
                    key = key,
                    id = user.id,
                    kind = VoiceShareKind.USER,
                    title = user.displayName,
                    subtitle = "Usuario",
                    avatarUrl = user.avatarUrl,
                )
            }
        }
        if (targets.isEmpty()) return

        val inviteText = buildString {
            append("Te invito a la sala de voz ${room.name}.\n")
            append("ID de sala: ${room.readableId?.takeIf(String::isNotBlank) ?: room.id}")
            if (shareLink.isNotBlank()) {
                append("\nEnlace: $shareLink")
            }
        }

        _isSendingShareInvites.value = true
        _shareNotice.value = null
        viewModelScope.launch {
            var sentCount = 0
            var failedCount = 0
            for (target in targets) {
                if (account != authService.getCurrentUserId()) break
                val delivered = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                        when (target.kind) {
                            VoiceShareKind.CHAT -> {
                                messageRepository.sendMessage(target.id, inviteText).getOrThrow()
                            }
                            VoiceShareKind.GROUP -> {
                                groupRepository.sendGroupMessage(target.id, inviteText).getOrThrow()
                            }
                            VoiceShareKind.COMMUNITY -> {
                                communityRepository.sendCommunityMessage(target.id, inviteText).getOrThrow()
                            }
                            VoiceShareKind.USER -> {
                                val chat = messageRepository.getOrCreateChat(target.id).getOrThrow()
                                check(account == authService.getCurrentUserId())
                                messageRepository.sendMessage(chat.id, inviteText).getOrThrow()
                            }
                        }
                        true
                    } == true
                }.getOrDefault(false)
                if (delivered) sentCount += 1 else failedCount += 1
            }
            if (account == authService.getCurrentUserId()) {
                _selectedShareKeys.value = emptySet()
                _shareNotice.value = when {
                    sentCount > 0 && failedCount == 0 -> if (sentCount == 1) {
                        "Invitación enviada a 1 conversación."
                    } else {
                        "Invitaciones enviadas a $sentCount conversaciones."
                    }
                    sentCount > 0 -> "Se enviaron $sentCount invitaciones ($failedCount pendientes de reintentar)."
                    else -> "No se pudo enviar la invitación. Revisa tu conexión e intenta de nuevo."
                }
            }
            _isSendingShareInvites.value = false
        }
    }

    fun inviteUser(user: UserInvite) {
        val room = _room.value ?: return
        val account = authService.getCurrentUserId() ?: return
        if (_invitingId.value != null) return
        _invitingId.value = user.id
        _inviteNotice.value = null
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    val chat = messageRepository.getOrCreateChat(user.id).getOrThrow()
                    check(account == authService.getCurrentUserId())
                    messageRepository.sendMessage(chat.id,
                        "Te invito a la sala de voz ${room.name}.\nID de sala: ${room.readableId ?: room.id}\nPuedes encontrarla en Social · Salas de voz con este ID.").getOrThrow()
                } ?: error("La invitación tardó demasiado.")
                if (account == authService.getCurrentUserId()) _inviteNotice.value = "Invitación añadida al chat con ${user.displayName}."
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (account == authService.getCurrentUserId()) _inviteNotice.value = "No se confirmó el envío. Revisa la conversación antes de reintentar."
            } finally { _invitingId.value = null }
        }
    }

    override fun onCleared() {
        shareLoadJob?.cancel()
        reactionExpiryJobs.values.forEach { it.cancel() }
        reactionExpiryJobs.clear()
        super.onCleared()
    }
}

data class CinemaState(
    val isCinemaMode: Boolean,
    val videoUrl: String = "",
    val videoTitle: String = "",
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L
)

const val ROOM_CHAT_TTL_MS=60L*60*1000
enum class RoomChatDelivery { SENDING, SENT, FAILED }
data class ChatMessage(
    val id: String,
    val userId: String,
    val text: String,
    val timestamp: Long,
    val userName: String = "",
    val imageData: String? = null,
    val expiresAt: Long = timestamp+ROOM_CHAT_TTL_MS,
    val delivery: RoomChatDelivery = RoomChatDelivery.SENT,
    val roomId: String? = null
)

internal fun boundedRoomChat(messages: List<ChatMessage>, now: Long=System.currentTimeMillis()): List<ChatMessage> {
    val unique = messages.filter { minOf(it.expiresAt, it.timestamp + ROOM_CHAT_TTL_MS)>now }.associateBy { "${it.userId}:${it.id}" }.values.toList().takeLast(100)
    val photoIds = unique.filter { it.imageData != null }.takeLast(10).map { it.id }.toSet()
    return unique.map { if (it.imageData != null && it.id !in photoIds) it.copy(imageData = null, text = "Foto anterior de esta sesión") else it }
}

data class ReactionEvent(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val timestamp: Long,
    val userId: String
)

data class UserInvite(
    val id: String,
    val displayName: String,
    val avatarUrl: String? = null
)

enum class VoiceShareKind {
    CHAT,
    GROUP,
    COMMUNITY,
    USER,
}

data class VoiceShareDestination(
    val key: String,
    val id: String,
    val kind: VoiceShareKind,
    val title: String,
    val subtitle: String,
    val avatarUrl: String? = null,
    val sortKey: String = "",
)
