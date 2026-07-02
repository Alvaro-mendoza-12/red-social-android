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
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val authService: AuthService
) : ViewModel() {

    val activeCall = callRepository.activeCall
    val endedCallSummary = callRepository.endedCallSummary
    val participants = callRepository.participants
    val isMuted = callRepository.isMuted
    val isSpeaker = callRepository.isSpeaker
    val localVideoTrack = callRepository.localVideoTrack
    val remoteVideoTracks = callRepository.remoteVideoTracks
    val eglContext get() = callRepository.eglContext
    private val _activeReactions = MutableStateFlow<List<CallReactionEvent>>(emptyList())
    val activeReactions: StateFlow<List<CallReactionEvent>> = _activeReactions.asStateFlow()
    private val reactionExpiryJobs = mutableMapOf<String, Job>()
    private var videoEnableJob: Job? = null

    init {
        viewModelScope.launch {
            callRepository.incomingReactions.collect { reaction ->
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
        callRepository.sendReaction(emoji)
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
        _activeReactions.value = (_activeReactions.value + enriched).distinctBy { it.id }.takeLast(12)
        scheduleReactionExpiry(enriched)
    }

    private fun scheduleReactionExpiry(reaction: CallReactionEvent) {
        val reactionId = reaction.id.trim()
        if (reactionId.isBlank()) return
        reactionExpiryJobs[reactionId]?.cancel()
        reactionExpiryJobs[reactionId] = viewModelScope.launch {
            delay(10_000)
            _activeReactions.value = _activeReactions.value.filterNot { it.id == reactionId }
            reactionExpiryJobs.remove(reactionId)?.cancel()
        }
    }
}






