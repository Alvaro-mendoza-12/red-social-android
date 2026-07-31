package conectate.com.presentation.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.CallReactionEvent
import conectate.com.data.repository.CallRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val authService: AuthService
) : ViewModel() {
    private companion object {
        const val REACTION_VISIBLE_MS = 13_000L
        const val LOCAL_ECHO_WINDOW_MS = 3_000L
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
    private var videoEnableJob: Job? = null

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
        val reaction = CallReactionEvent(
            id = UUID.randomUUID().toString(),
            userId = authService.getCurrentUserId()?.trim().orEmpty(),
            emoji = emoji,
            timestamp = System.currentTimeMillis()
        )
        appendReaction(reaction)
        callRepository.sendReaction(emoji, reaction.id, reaction.timestamp)
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
        reactionExpiryJobs.values.forEach { it.cancel() }
        reactionExpiryJobs.clear()
        super.onCleared()
    }

    private fun appendReaction(reaction: CallReactionEvent) {
        val enriched = reaction.copy(id = reaction.id.ifBlank { UUID.randomUUID().toString() })
        if (_activeReactions.value.any { it.id == enriched.id }) return
        _activeReactions.value = (_activeReactions.value + enriched).distinctBy { it.id }.takeLast(12)
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






