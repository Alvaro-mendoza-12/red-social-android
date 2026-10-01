package conectate.com.data.livekit

import android.content.Context
import conectate.com.data.model.LiveSfuSession
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.collect
import io.livekit.android.events.RoomEvent
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import conectate.com.util.AppPerformanceProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    @param:ApplicationContext private val context: Context
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    data class RemoteVideo(
        val trackId: String,
        val participantName: String,
        val track: VideoTrack,
        val participantId: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    // Suspend network waits may overlap, but publication-map updates in the SDK
    // must resume serially. Two IO workers can otherwise overwrite that map.
    private val mediaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _remoteVideos = MutableStateFlow<List<RemoteVideo>>(emptyList())
    val remoteVideos: StateFlow<List<RemoteVideo>> = _remoteVideos.asStateFlow()
    private val _errors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors = _errors.asSharedFlow()

    data class StartupTimings(
        val roomCreationMs: Long,
        val signalingMs: Long,
        val audioMs: Long,
        val videoMs: Long,
        val totalMs: Long
    )

    @Volatile var lastStartupTimings: StartupTimings? = null
        private set

    private var room: Room? = null
    private var eventsJob: Job? = null
    private var publishingRole: Boolean = false
    private var connectionIdentity: Triple<String, String, String>? = null
    private var sharedVideoSource: StateFlow<org.webrtc.VideoTrack?>? = null
    private val desiredMedia = java.util.concurrent.atomic.AtomicReference(false to false)
    private val rendererBindings = java.util.concurrent.ConcurrentHashMap<TextureViewRenderer, VideoTrack>()

    suspend fun connect(
        session: LiveSfuSession,
        publishAudio: Boolean,
        publishCamera: Boolean,
        existingVideo: StateFlow<org.webrtc.VideoTrack?>? = null
    ): Result<Unit> = mutex.withLock {
        val wsUrl = session.wsUrl.trim()
        val token = session.token.trim()
        if (wsUrl.isBlank() || token.isBlank()) {
            return@withLock Result.failure(IllegalArgumentException("La sesión LiveKit no es válida."))
        }

        val identity = Triple(wsUrl, session.roomName.trim(), session.role)
        val current = room
        if (current != null && current.state == Room.State.CONNECTED && identity.second.isNotBlank() &&
            connectionIdentity == identity && sharedVideoSource === existingVideo
        ) {
            desiredMedia.set(publishAudio to publishCamera)
            return@withLock try {
                if (publishingRole) applyMediaLocked(current)
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { disconnectLocked() }
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }

        disconnectLocked()
        desiredMedia.set(publishAudio to publishCamera)
        publishingRole = session.role == "host" || session.role == "guest"
        _state.value = State.CONNECTING
        lastStartupTimings = null
        val startupStarted = android.os.SystemClock.elapsedRealtime()
        val quality = liveCollaborationQualityProfile(AppPerformanceProfiles.detect(context).tier)
        val candidate = LiveKit.create(
            context,
            RoomOptions(
                adaptiveStream = true,
                dynacast = true,
                videoTrackCaptureDefaults = LocalVideoTrackOptions(
                    captureParams = VideoCaptureParameter(quality.width, quality.height, quality.maxFps)
                ),
                videoTrackPublishDefaults = VideoTrackPublishDefaults(
                    videoEncoding = VideoEncoding(quality.maxBitrate, quality.maxFps),
                    simulcast = quality.simulcast,
                    degradationPreference = livekit.org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                ),
                audioTrackCaptureDefaults = LocalAudioTrackOptions(
                    noiseSuppression = true,
                    echoCancellation = true,
                    autoGainControl = true,
                    highPassFilter = true,
                    typingNoiseDetection = true
                )
            )
        )
        val roomCreationMs = android.os.SystemClock.elapsedRealtime() - startupStarted
        room = candidate
        eventsJob = scope.launch {
            candidate.events.collect { event ->
                if (room !== candidate) return@collect
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
                            track = videoTrack,
                            participantId = event.participant.sid.value
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
                        val participantId = event.participant.sid.value
                        _remoteVideos.value = _remoteVideos.value.filterNot {
                            it.participantId == participantId
                        }
                    }
                    else -> Unit
                }
            }
        }

        try {
            val signalingStarted = android.os.SystemClock.elapsedRealtime()
            candidate.connect(wsUrl, token)
            val signalingMs = android.os.SystemClock.elapsedRealtime() - signalingStarted
            var audioMs = 0L
            var videoMs = 0L
            if (publishingRole) {
                val (audio, video) = desiredMedia.get()
                applySerializedMedia(
                    audio = {
                        val started = android.os.SystemClock.elapsedRealtime()
                        enableMicrophone(candidate, audio)
                        audioMs = android.os.SystemClock.elapsedRealtime() - started
                    },
                    video = {
                        val started = android.os.SystemClock.elapsedRealtime()
                        if (existingVideo != null) {
                            val sharedTrack = candidate.localParticipant.createVideoTrack(
                                name = "live-selected-source",
                                capturer = ExistingLiveVideoCapturer(existingVideo)
                            )
                            try {
                                sharedTrack.start()
                                // A paused host must not publish a live frame before
                                // the publication's muted state has been applied.
                                sharedTrack.enabled = video
                                sharedTrack.startCapture()
                                check(candidate.localParticipant.publishVideoTrack(sharedTrack)) {
                                    "No se pudo compartir la imagen del directo con la batalla."
                                }
                            } catch (error: Throwable) {
                                runCatching { sharedTrack.stop(); sharedTrack.dispose() }
                                throw error
                            }
                        }
                        // The custom camera publication must exist before this call;
                        // otherwise the SDK would open a second physical camera.
                        enableCamera(candidate, video)
                        videoMs = android.os.SystemClock.elapsedRealtime() - started
                    }
                )
                if (desiredMedia.get() != (audio to video)) applyMediaLocked(candidate)
            }
            _state.value = State.CONNECTED
            connectionIdentity = identity
            sharedVideoSource = existingVideo
            lastStartupTimings = StartupTimings(roomCreationMs, signalingMs, audioMs, videoMs,
                android.os.SystemClock.elapsedRealtime() - startupStarted)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            // Structured publishing has joined both children before the room's
            // native tracks are released. Cancellation must not become a UI error.
            withContext(NonCancellable) { disconnectLocked() }
            throw cancelled
        } catch (error: Throwable) {
            disconnectLocked()
            Result.failure(error)
        }
    }

    fun disconnect() {
        scope.launch { mutex.withLock { disconnectLocked() } }
    }

    suspend fun disconnectAwait() = mutex.withLock {
        disconnectLocked()
    }

    /** Latest controls win even when the user changes them while connecting. */
    fun updateMediaEnabled(audio: Boolean, video: Boolean) {
        desiredMedia.set(audio to video)
        scope.launch {
            mutex.withLock {
                val current = room ?: return@withLock
                if (!publishingRole) return@withLock
                runCatching { applyMediaLocked(current) }.onFailure { error ->
                    android.util.Log.e("LiveKitRoomBridge", "Could not update collaboration controls", error)
                    _errors.tryEmit("No se pudo actualizar la cámara o el micrófono de la batalla. Inténtalo de nuevo.")
                }
            }
        }
    }

    private suspend fun applySerializedMedia(audio: suspend () -> Unit, video: suspend () -> Unit) =
        withContext(mediaDispatcher) { applyCollaborationMedia(audio, video) }

    private suspend fun applyMediaLocked(candidate: Room) {
        val (audio, video) = desiredMedia.get()
        applySerializedMedia(
            audio = { enableMicrophone(candidate, audio) },
            video = { enableCamera(candidate, video) }
        )
    }

    private suspend fun enableMicrophone(candidate: Room, enabled: Boolean) {
        val changed = candidate.localParticipant.setMicrophoneEnabled(enabled)
        check(!enabled || changed) { "No se pudo activar el micrófono del directo. Revisa su permiso e inténtalo de nuevo." }
    }

    private suspend fun enableCamera(candidate: Room, enabled: Boolean) {
        if (enabled && candidate.localParticipant.getTrackPublication(io.livekit.android.room.track.Track.Source.CAMERA) == null) {
            val track = candidate.localParticipant.createVideoTrack(
                name = "live-camera", capturer = createSteadyCollaborationCamera(context)
            )
            try {
                track.start()
                track.startCapture()
                check(candidate.localParticipant.publishVideoTrack(track)) {
                    "No se pudo compartir la cámara del directo."
                }
            } catch (error: Throwable) {
                runCatching { track.stop(); track.dispose() }
                throw error
            }
        }
        val changed = candidate.localParticipant.setCameraEnabled(enabled)
        check(!enabled || changed) { "No se pudo activar la cámara del directo. Revisa su permiso e inténtalo de nuevo." }
    }

    fun attachRenderer(video: RemoteVideo, renderer: TextureViewRenderer): Boolean {
        val connectedRoom = room ?: return false
        connectedRoom.initVideoRenderer(renderer)
        renderer.setMirror(false)
        renderer.setScalingType(livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        video.track.addRenderer(renderer)
        rendererBindings.put(renderer, video.track)?.takeIf { it !== video.track }?.let { previous ->
            runCatching { previous.removeRenderer(renderer) }
        }
        return true
    }

    fun detachRenderer(video: RemoteVideo, renderer: TextureViewRenderer) {
        val boundTrack = rendererBindings.remove(renderer) ?: return
        runCatching { boundTrack.removeRenderer(renderer) }
        runCatching { renderer.release() }
    }

    private fun disconnectLocked() {
        eventsJob?.cancel()
        eventsJob = null
        val departedRoom = room
        room = null
        publishingRole = false
        connectionIdentity = null
        sharedVideoSource = null
        // Renderers must relinquish the old room's EGL context before it is released.
        rendererBindings.keys.toList().forEach { renderer ->
            rendererBindings.remove(renderer)?.let { track -> runCatching { track.removeRenderer(renderer) } }
            runCatching { renderer.release() }
        }
        runCatching { departedRoom?.release() }.onFailure { error ->
            android.util.Log.e("LiveKitRoomBridge", "Could not release the ended room", error)
        }
        _remoteVideos.value = emptyList()
        _state.value = State.DISCONNECTED
    }
}
