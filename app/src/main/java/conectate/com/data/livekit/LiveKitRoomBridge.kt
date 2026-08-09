package conectate.com.data.livekit

import android.content.Context
import conectate.com.data.model.LiveSfuSession
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.events.collect
import io.livekit.android.events.RoomEvent
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional LiveKit transport for the guest/battle collaboration phase.
 *
 * The server issues the short-lived token. This class deliberately accepts only
 * that token and the websocket URL; LiveKit API credentials never cross into
 * the Android process. The existing broadcast transport remains untouched until
 * the collaboration UI explicitly opts into this bridge.
 */
@Singleton
class LiveKitRoomBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    data class RemoteVideo(
        val trackId: String,
        val participantName: String,
        val track: VideoTrack
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _remoteVideos = MutableStateFlow<List<RemoteVideo>>(emptyList())
    val remoteVideos: StateFlow<List<RemoteVideo>> = _remoteVideos.asStateFlow()

    private var room: Room? = null
    private var eventsJob: Job? = null

    suspend fun connect(
        session: LiveSfuSession,
        publishAudio: Boolean,
        publishCamera: Boolean
    ): Result<Unit> = mutex.withLock {
        val wsUrl = session.wsUrl.trim()
        val token = session.token.trim()
        if (wsUrl.isBlank() || token.isBlank()) {
            return@withLock Result.failure(IllegalArgumentException("La sesión LiveKit no es válida."))
        }

        disconnectLocked()
        _state.value = State.CONNECTING
        val candidate = LiveKit.create(context)
        room = candidate
        eventsJob = scope.launch {
            candidate.events.collect { event ->
                _state.value = when (candidate.state) {
                    Room.State.CONNECTED -> State.CONNECTED
                    Room.State.RECONNECTING -> State.RECONNECTING
                    Room.State.CONNECTING -> State.CONNECTING
                    Room.State.DISCONNECTED -> State.DISCONNECTED
                }
                when (event) {
                    is RoomEvent.TrackSubscribed -> {
                        val videoTrack = event.track as? VideoTrack ?: return@collect
                        val item = RemoteVideo(
                            trackId = videoTrack.sid?.takeIf { it.isNotBlank() }
                                ?: videoTrack.name.orEmpty().ifBlank { "video-${videoTrack.hashCode()}" },
                            participantName = event.participant.name.orEmpty().ifBlank { "Invitado" },
                            track = videoTrack
                        )
                        _remoteVideos.value = (_remoteVideos.value.filterNot {
                            it.trackId == item.trackId
                        } + item).takeLast(8)
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        val trackId = event.track.sid?.takeIf { it.isNotBlank() }
                            ?: event.track.name.orEmpty()
                        _remoteVideos.value = _remoteVideos.value.filterNot { it.trackId == trackId }
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        val participantName = event.participant.name
                        _remoteVideos.value = _remoteVideos.value.filterNot {
                            it.participantName == participantName
                        }
                    }
                    else -> Unit
                }
            }
        }

        runCatching {
            candidate.connect(wsUrl, token)
            if (session.role == "host" || session.role == "guest") {
                candidate.localParticipant.setMicrophoneEnabled(publishAudio)
                candidate.localParticipant.setCameraEnabled(publishCamera)
            }
            _state.value = State.CONNECTED
        }.onFailure {
            disconnectLocked()
        }
    }

    fun disconnect() {
        scope.launch { mutex.withLock { disconnectLocked() } }
    }

    suspend fun disconnectAwait() = mutex.withLock {
        disconnectLocked()
    }

    fun attachRenderer(video: RemoteVideo, renderer: TextureViewRenderer): Boolean {
        val connectedRoom = room ?: return false
        connectedRoom.initVideoRenderer(renderer)
        renderer.setMirror(false)
        renderer.setScalingType(livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        video.track.addRenderer(renderer)
        return true
    }

    fun detachRenderer(video: RemoteVideo, renderer: TextureViewRenderer) {
        runCatching { video.track.removeRenderer(renderer) }
        runCatching { renderer.release() }
    }

    private fun disconnectLocked() {
        eventsJob?.cancel()
        eventsJob = null
        room?.disconnect()
        room = null
        _remoteVideos.value = emptyList()
        _state.value = State.DISCONNECTED
    }
}
