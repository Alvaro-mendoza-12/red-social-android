package conectate.com.presentation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class ChatMessageAudioState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val progress: Float = 0f,
    val duration: String = "",
    val totalDurationMs: Long? = null,
    val speed: Float = 1f,
)

/** Only the active audio row observes the playback clock; text/photo rows observe none. */
@Composable
internal fun rememberChatMessageAudioState(
    messageId: String,
    contentType: String,
    fallbackDuration: String,
    currentAudioId: StateFlow<String?>,
    isPlaying: StateFlow<Boolean>,
    progress: StateFlow<Float>,
    duration: StateFlow<String>,
    totalDurations: StateFlow<Map<String, Long>>,
    speed: StateFlow<Float>,
): ChatMessageAudioState {
    if (contentType != "audio") return ChatMessageAudioState(duration = fallbackDuration)
    val activeFlow = remember(messageId, currentAudioId) {
        currentAudioId.map { it == messageId }.distinctUntilChanged()
    }
    val initialActive = remember(messageId, currentAudioId) { currentAudioId.value == messageId }
    val active by activeFlow.collectAsStateWithLifecycle(initialValue = initialActive)
    val totalFlow = remember(messageId, totalDurations) {
        totalDurations.map { it[messageId] }.distinctUntilChanged()
    }
    val initialTotal = remember(messageId, totalDurations) { totalDurations.value[messageId] }
    val total by totalFlow.collectAsStateWithLifecycle(initialValue = initialTotal)
    val playbackSpeed by speed.collectAsStateWithLifecycle()
    if (!active) return ChatMessageAudioState(duration = fallbackDuration, totalDurationMs = total, speed = playbackSpeed)
    val playing by isPlaying.collectAsStateWithLifecycle()
    val position by progress.collectAsStateWithLifecycle()
    val label by duration.collectAsStateWithLifecycle()
    return ChatMessageAudioState(true, playing, position, label, total, playbackSpeed)
}
