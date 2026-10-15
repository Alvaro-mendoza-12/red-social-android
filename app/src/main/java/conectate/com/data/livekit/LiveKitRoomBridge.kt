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
import kotlinx.coroutines.delay
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
        val participantId: String = "",
        val participantIdentity: String = "",
        val bindingVersion: Long = 0L
    )

    data class RemoteParticipant(
        val userId: String,
        val name: String,
        val avatarUrl: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    // Suspend network waits may overlap, but publication-map updates in the SDK
    // must resume serially. Two IO workers can otherwise overwrite that map.
    private val mediaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _remoteVideos = MutableStateFlow<List<RemoteVideo>>(emptyList())
    private val remoteVideoBindings = LiveRemoteVideoBindings()
    val remoteVideos: StateFlow<List<RemoteVideo>> = _remoteVideos.asStateFlow()
    private val _remoteParticipants = MutableStateFlow<List<RemoteParticipant>>(emptyList())
    val remoteParticipants: StateFlow<List<RemoteParticipant>> = _remoteParticipants.asStateFlow()
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
    private data class RendererBinding(
        val track: VideoTrack,
        val layoutListener: android.view.View.OnLayoutChangeListener,
        val sink: livekit.org.webrtc.VideoSink,
        val recovery: LiveRendererRecoveryPolicy,
        var recoveryJob: Job? = null
    )
    private val rendererBindings = java.util.concurrent.ConcurrentHashMap<TextureViewRenderer, RendererBinding>()

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
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val quality = liveCollaborationNetworkProfile(
            AppPerformanceProfiles.detect(context).tier,
            metered = network.isActiveNetworkMetered
        )
        val candidate = LiveKit.create(
            context,
            RoomOptions(
                // Compose owns the visible cells and their lifetimes. Manage
                // subscription dimensions explicitly: SDK visibility observers
                // can retain a disabled stream after renderer replacement.
                adaptiveStream = false,
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
                when (event) {
                    is RoomEvent.TrackSubscribed -> remoteVideoBindings.subscribed(event.publication.sid)
                    is RoomEvent.Reconnected -> remoteVideoBindings.reconnected(
                        candidate.remoteParticipants.values.flatMap { it.trackPublications.keys })
                    else -> Unit
                }
                refreshRemoteParticipants(candidate)
                _state.value = when (candidate.state) {
                    Room.State.CONNECTED -> State.CONNECTED
                    Room.State.RECONNECTING -> State.RECONNECTING
                    Room.State.CONNECTING -> State.CONNECTING
                    Room.State.DISCONNECTED -> State.DISCONNECTED
                }
                if (event is RoomEvent.Reconnected || event is RoomEvent.ParticipantConnected ||
                    event is RoomEvent.TrackPublished) {
                    // Reconcile desired subscriptions after the SFU recreates its
                    // transports/publications. Never keep an old native track as live.
                    candidate.remoteParticipants.values.forEach { participant ->
                        participant.trackPublications.values.filterIsInstance<io.livekit.android.room.track.RemoteTrackPublication>().filter {
                            it.kind == io.livekit.android.room.track.Track.Kind.VIDEO
                        }.forEach { it.setSubscribed(true) }
                    }
                }
                refreshRemoteVideos(candidate)
            }
        }

        try {
            val signalingStarted = android.os.SystemClock.elapsedRealtime()
            candidate.connect(wsUrl, token)
            refreshRemoteParticipants(candidate)
            refreshRemoteVideos(candidate)
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
        val recovery = LiveRendererRecoveryPolicy(android.os.SystemClock.elapsedRealtime())
        val sink = livekit.org.webrtc.VideoSink { frame ->
            recovery.recordFrame(android.os.SystemClock.elapsedRealtime())
            renderer.onFrame(frame)
        }
        video.track.addRenderer(sink)
        // Compose may replace the AndroidView after mute/reconnection. The
        // SDK's global-layout observer waits two seconds; update visibility
        // immediately once this new renderer has its real dimensions.
        val layoutListener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRendererSubscription(video, renderer, enabled = true)
        }
        renderer.addOnLayoutChangeListener(layoutListener)
        val binding = RendererBinding(video.track, layoutListener, sink, recovery)
        rendererBindings.put(renderer, binding)?.let { previous ->
            previous.recoveryJob?.cancel()
            renderer.removeOnLayoutChangeListener(previous.layoutListener)
            runCatching { previous.track.removeRenderer(previous.sink) }
        }
        updateRendererSubscription(video, renderer, enabled = true)
        // During subscription replacement the SDK may clear a sink after it
        // was attached, while RTP continues to decode. Repair only a visible,
        // current publication that has stopped delivering frames. Each binding
        // has two attempts within ten seconds; no second camera or reconnect.
        binding.recoveryJob = scope.launch {
            repeat(20) {
                delay(500)
                if (room !== connectedRoom || rendererBindings[renderer] !== binding) return@launch
                val publication = findRemotePublication(video)
                val active = connectedRoom.state == Room.State.CONNECTED && renderer.isAttachedToWindow &&
                    renderer.width > 0 && renderer.height > 0 && publication?.subscribed == true &&
                    !publication.muted && binding.track.enabled
                if (recovery.claimRepair(android.os.SystemClock.elapsedRealtime(), active)) {
                    rebindRenderer(video, renderer)
                }
            }
        }
        return true
    }

    private fun findRemotePublication(video: RemoteVideo): io.livekit.android.room.track.RemoteTrackPublication? =
        room?.remoteParticipants?.values?.asSequence()
            ?.flatMap { it.trackPublications.values.asSequence() }
            ?.filterIsInstance<io.livekit.android.room.track.RemoteTrackPublication>()
            ?.firstOrNull { it.sid == video.trackId && it.track === video.track }

    private fun updateRendererSubscription(video: RemoteVideo, renderer: TextureViewRenderer, enabled: Boolean) {
        val publication = findRemotePublication(video) ?: return
        publication.setEnabled(enabled)
        if (enabled && renderer.width > 0 && renderer.height > 0) {
            publication.setVideoDimensions(io.livekit.android.room.track.Track.Dimensions(renderer.width, renderer.height))
        }
    }

    /** Reattach the same sink after SDK transport cleanup; keep media and identity. */
    fun rebindRenderer(video: RemoteVideo, renderer: TextureViewRenderer): Boolean {
        val binding = rendererBindings[renderer] ?: return false
        if (binding.track !== video.track || findRemotePublication(video) == null) return false
        return runCatching {
            binding.track.removeRenderer(binding.sink)
            binding.track.addRenderer(binding.sink)
            updateRendererSubscription(video, renderer, enabled = true)
            true
        }.getOrDefault(false)
    }

    fun detachRenderer(video: RemoteVideo, renderer: TextureViewRenderer) {
        val binding = rendererBindings.remove(renderer) ?: return
        binding.recoveryJob?.cancel()
        renderer.removeOnLayoutChangeListener(binding.layoutListener)
        if (rendererBindings.values.none { it.track === binding.track }) {
            updateRendererSubscription(video, renderer, enabled = false)
        }
        runCatching { binding.track.removeRenderer(binding.sink) }
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
            rendererBindings.remove(renderer)?.let { binding ->
                binding.recoveryJob?.cancel()
                renderer.removeOnLayoutChangeListener(binding.layoutListener)
                runCatching { binding.track.removeRenderer(binding.sink) }
            }
            runCatching { renderer.release() }
        }
        runCatching { departedRoom?.release() }.onFailure { error ->
            android.util.Log.e("LiveKitRoomBridge", "Could not release the ended room", error)
        }
        _remoteVideos.value = emptyList()
        remoteVideoBindings.clear()
        _remoteParticipants.value = emptyList()
        _state.value = State.DISCONNECTED
    }

    private fun refreshRemoteVideos(candidate: Room) {
        remoteVideoBindings.retain(candidate.remoteParticipants.values.flatMap {it.trackPublications.keys}.toSet())
        _remoteVideos.value = if (candidate.state != Room.State.CONNECTED) emptyList() else {
            candidate.remoteParticipants.values.flatMap { participant ->
                participant.trackPublications.values.mapNotNull { publication ->
                    val track = publication.track as? VideoTrack ?: return@mapNotNull null
                    if (publication.muted || !publication.subscribed) return@mapNotNull null
                    RemoteVideo(publication.sid, participant.name.orEmpty().ifBlank { "Invitado" },
                        track, participant.sid.value, participant.identity?.value.orEmpty(),
                        remoteVideoBindings.version(publication.sid))
                }
            }.take(9)
        }
    }

    private fun refreshRemoteParticipants(candidate: Room) {
        _remoteParticipants.value = candidate.remoteParticipants.values.mapNotNull { participant ->
            val userId = participant.identity?.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val avatar = participant.metadata?.takeIf { it.length <= 2_048 }?.let { metadata ->
                runCatching { org.json.JSONObject(metadata).optString("avatarUrl").takeIf(String::isNotBlank) }.getOrNull()
            }
            RemoteParticipant(userId, participant.name.orEmpty().ifBlank { "Invitado" }, avatar)
        }.take(9)
    }
}
