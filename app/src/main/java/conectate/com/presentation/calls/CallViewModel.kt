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
    private val _activeReactions = MutableStateFlow<List<CallReactionEvent>>(emptyList())
    val activeReactions: StateFlow<List<CallReactionEvent>> = _activeReactions.asStateFlow()
    private val reactionExpiryJobs = mutableMapOf<String, Job>()

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

    fun sendReaction(emoji: String) {
        appendReaction(
            CallReactionEvent(
                userId = authService.getCurrentUserId()?.trim().orEmpty(),
                emoji = emoji
            )
        )
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
        _activeReactions.value = (_activeReactions.value + enriched).takeLast(12)
        scheduleReactionExpiry(enriched)
    }

    private fun scheduleReactionExpiry(reaction: CallReactionEvent) {
        val reactionId = reaction.id.trim()
        if (reactionId.isBlank()) return
        reactionExpiryJobs[reactionId]?.cancel()
        reactionExpiryJobs[reactionId] = viewModelScope.launch {
            delay(2200)
            _activeReactions.value = _activeReactions.value.filterNot { it.id == reactionId }
            reactionExpiryJobs.remove(reactionId)?.cancel()
        }
    }
}






