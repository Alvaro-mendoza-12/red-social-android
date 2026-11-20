package conectate.com.presentation.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.CallConnectionState
import conectate.com.data.model.CallReactionEvent
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.repository.CallRepository
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class CallInviteCandidate(
    val userId: String,
    val displayName: String,
    val handle: String = "",
    val avatarUrl: String? = null,
)

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val authService: AuthService,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
) : ViewModel() {
    private companion object {
        const val REACTION_VISIBLE_MS = 5_400L
        const val LOCAL_ECHO_WINDOW_MS = 3_000L
        const val REACTION_SEND_COOLDOWN_MS = 700L
    }

    val activeCall = callRepository.activeCall
    val endedCallSummary = callRepository.endedCallSummary
    val participants = callRepository.participants
    val isMuted = callRepository.isMuted
    val isSpeaker = callRepository.isSpeaker
    val localVideoTrack: StateFlow<org.webrtc.VideoTrack?> = callRepository.localVideoTrack
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val remoteVideoTracks: StateFlow<Map<String, org.webrtc.VideoTrack>> = callRepository.remoteVideoTracks
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val eglContext get() = callRepository.eglContext
    private val _activeReactions = MutableStateFlow<List<CallReactionEvent>>(emptyList())
    val activeReactions: StateFlow<List<CallReactionEvent>> = _activeReactions.asStateFlow()
    private val reactionExpiryJobs = mutableMapOf<String, Job>()
    private var lastReactionSentAtMs = 0L
    private var videoEnableJob: Job? = null

    private val _inviteQuery = MutableStateFlow("")
    val inviteQuery: StateFlow<String> = _inviteQuery.asStateFlow()
    private val _inviteCandidates = MutableStateFlow<List<CallInviteCandidate>>(emptyList())
    val inviteCandidates: StateFlow<List<CallInviteCandidate>> = _inviteCandidates.asStateFlow()
    private val _isSearchingInvites = MutableStateFlow(false)
    val isSearchingInvites: StateFlow<Boolean> = _isSearchingInvites.asStateFlow()
    private val _invitingUserId = MutableStateFlow<String?>(null)
    val invitingUserId: StateFlow<String?> = _invitingUserId.asStateFlow()
    private val _inviteFeedback = MutableStateFlow<String?>(null)
    val inviteFeedback: StateFlow<String?> = _inviteFeedback.asStateFlow()
    private val recentChats: StateFlow<List<ChatWithLastMessage>> = messageRepository.userChats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())
    private var inviteSearchJob: Job? = null

    init {
        viewModelScope.launch {
            callRepository.incomingReactions.collect { reaction ->
                if (isRecentLocalEcho(reaction)) return@collect
                appendReaction(reaction)
            }
        }
    }

    fun getCurrentUserId(): String? = authService.getCurrentUserId()
    fun hasMicrophonePermission(): Boolean = callRepository.hasMicrophonePermission()

    fun toggleMute(muted: Boolean) {
        callRepository.toggleMute(muted)
    }

    fun toggleSpeaker(on: Boolean) {
        callRepository.toggleSpeaker(on)
    }

    fun toggleVideo(enabled: Boolean) {
        runCatching { callRepository.toggleVideo(enabled) }
    }

    fun ensureVideoEnabledRepeatedly(attempts: Int = 2, delayMs: Long = 1_800L) {
        if (videoEnableJob?.isActive == true) return
        videoEnableJob = viewModelScope.launch {
            repeat(attempts.coerceAtLeast(1)) { index ->
                if (localVideoTrack.value != null) return@launch
                runCatching { callRepository.toggleVideo(true) }
                if (index < attempts - 1) delay(delayMs)
            }
        }
    }

    fun switchCamera() {
        runCatching { callRepository.switchCamera() }
    }

    fun sendReaction(emoji: String) {
        val now = System.currentTimeMillis()
        if (now - lastReactionSentAtMs < REACTION_SEND_COOLDOWN_MS) return
        lastReactionSentAtMs = now
        val reaction = CallReactionEvent(
            id = UUID.randomUUID().toString(),
            userId = authService.getCurrentUserId()?.trim().orEmpty(),
            emoji = emoji,
            timestamp = now
        )
        appendReaction(reaction)
        callRepository.sendReaction(emoji, reaction.id, reaction.timestamp)
    }

    fun canInviteParticipants(): Boolean {
        val current = activeCall.value ?: return false
        val currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        return currentUserId.isNotBlank() &&
            current.initiatorUserId.equals(currentUserId, ignoreCase = true) &&
            current.connectionState != CallConnectionState.RINGING_OUTGOING
    }

    fun prepareInviteCandidates() {
        _inviteFeedback.value = null
        refreshInviteCandidates(_inviteQuery.value)
    }

    fun onInviteQueryChanged(query: String) {
        _inviteQuery.value = query.take(80)
        refreshInviteCandidates(_inviteQuery.value)
    }

    private fun mapChatsToCandidates(
        chats: List<ChatWithLastMessage>,
        activeIds: Set<String>
    ): List<CallInviteCandidate> {
        return chats.mapNotNull { item ->
            if (item.chat.is_group) return@mapNotNull null
            val id = item.otherUserId?.trim().orEmpty()
            if (id.isBlank() || id.lowercase() in activeIds) return@mapNotNull null
            val name = item.nickname?.trim()?.takeIf(String::isNotBlank)
                ?: item.otherUserName?.trim()?.takeIf(String::isNotBlank)
                ?: "Usuario"
            CallInviteCandidate(
                userId = id,
                displayName = name,
                handle = "",
                avatarUrl = item.otherUserImage?.takeIf(String::isNotBlank)
            )
        }.distinctBy { it.userId.lowercase() }
    }

    private fun refreshInviteCandidates(rawQuery: String) {
        inviteSearchJob?.cancel()
        val query = rawQuery.trim()
        val selfId = authService.getCurrentUserId()?.trim()?.lowercase().orEmpty()
        val activeIds = participants.value.map { it.userId.trim().lowercase() }.toSet() + selfId
        val cachedRecent = mapChatsToCandidates(recentChats.value, activeIds)

        if (query.isBlank()) {
            _inviteCandidates.value = cachedRecent
            if (cachedRecent.isNotEmpty()) {
                _isSearchingInvites.value = false
                return
            }
            inviteSearchJob = viewModelScope.launch {
                _isSearchingInvites.value = true
                val loaded = runCatching { messageRepository.userChats.firstOrNull().orEmpty() }.getOrDefault(emptyList())
                _inviteCandidates.value = mapChatsToCandidates(loaded, activeIds)
                _isSearchingInvites.value = false
            }
            return
        }

        val filteredRecent = cachedRecent.filter { candidate ->
            candidate.displayName.contains(query, ignoreCase = true) ||
                candidate.handle.contains(query, ignoreCase = true)
        }
        _inviteCandidates.value = filteredRecent
        if (query.length < 2) {
            _isSearchingInvites.value = false
            return
        }
        inviteSearchJob = viewModelScope.launch {
            _isSearchingInvites.value = true
            delay(200L)
            userRepository.searchUsers(query).onSuccess { users ->
                val remote = users.mapNotNull { user ->
                    val id = user.id.trim()
                    if (id.isBlank() || id.lowercase() in activeIds) return@mapNotNull null
                    val name = user.displayName.takeIf(String::isNotBlank)
                        ?: user.full_name?.takeIf(String::isNotBlank)
                        ?: user.username?.takeIf(String::isNotBlank)
                        ?: "Usuario"
                    val handle = user.username?.takeIf(String::isNotBlank)?.let { "@$it" }.orEmpty()
                    CallInviteCandidate(id, name, handle, user.avatar_url)
                }
                _inviteCandidates.value = (filteredRecent + remote).distinctBy { it.userId.lowercase() }
            }
            _isSearchingInvites.value = false
        }
    }

    fun inviteParticipant(candidate: CallInviteCandidate) {
        if (_invitingUserId.value != null) return
        _invitingUserId.value = candidate.userId
        _inviteFeedback.value = null
        viewModelScope.launch {
            callRepository.inviteParticipantToActiveCall(candidate.userId).fold(
                onSuccess = {
                    _inviteFeedback.value = "Invitación enviada a ${candidate.displayName}."
                },
                onFailure = { error ->
                    _inviteFeedback.value = error.message?.takeIf { it.isNotBlank() }
                        ?: "No se pudo invitar a ${candidate.displayName}."
                },
            )
            _invitingUserId.value = null
        }
    }

    fun clearInviteFeedback() {
        _inviteFeedback.value = null
    }

    fun refreshAudioSession() {
        callRepository.refreshAudioSession()
    }

    fun refreshAudioSessionRepeatedly(attempts: Int = 3, delayMs: Long = 450L) {
        callRepository.refreshAudioSessionRepeatedly(attempts = attempts, delayMs = delayMs)
    }

    fun hangup() {
        viewModelScope.launch(Dispatchers.IO) {
            callRepository.hangup()
        }
    }

    fun clearEndedCallSummary(callId: String? = null) {
        callRepository.clearEndedCallSummary(callId)
    }

    override fun onCleared() {
        inviteSearchJob?.cancel()
        reactionExpiryJobs.values.forEach { it.cancel() }
        reactionExpiryJobs.clear()
        super.onCleared()
    }

    private fun appendReaction(reaction: CallReactionEvent) {
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

    private fun scheduleReactionExpiry(reaction: CallReactionEvent) {
        val reactionId = reaction.id.trim()
        if (reactionId.isBlank()) return
        reactionExpiryJobs[reactionId]?.cancel()
        reactionExpiryJobs[reactionId] = viewModelScope.launch {
            delay(REACTION_VISIBLE_MS)
            _activeReactions.value = _activeReactions.value.filterNot { it.id == reactionId }
            reactionExpiryJobs.remove(reactionId)?.cancel()
        }
    }

    private fun isRecentLocalEcho(reaction: CallReactionEvent): Boolean {
        val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase().orEmpty()
        val reactionUserId = reaction.userId.trim().lowercase()
        if (currentUserId.isBlank() || reactionUserId != currentUserId) return false
        val now = System.currentTimeMillis()
        return _activeReactions.value.any { active ->
            active.userId.trim().lowercase() == currentUserId &&
                active.emoji == reaction.emoji &&
                now - active.timestamp in 0..LOCAL_ECHO_WINDOW_MS
        }
    }
}






