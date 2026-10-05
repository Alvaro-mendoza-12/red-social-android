package conectate.com.data.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import conectate.com.BuildConfig
import conectate.com.core.network.PublicBackendDns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.signaling.SignalingConfig
import conectate.com.data.signaling.SignalingNetwork
import io.socket.client.Socket
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.media.projection.MediaProjection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface SignalingEvents {
    fun onSendOffer(targetSocketId: String, offer: JSONObject)
    fun onSendAnswer(targetSocketId: String, answer: JSONObject)
    fun onSendIceCandidate(targetSocketId: String, candidate: JSONObject)
}

/**
 * WebRTCManager - Safe Mode Version (v4.3 "The Fixer")
 * 
 * Philosophy: 
 * 1. Safe Negotiation: Never recurse during rollback.
 * 2. Explicit Tracks: Use addTrack for predictability.
 * 3. Audio Enforcement: Force audio routing to MODE_IN_COMMUNICATION.
 */
class WebRTCManager(
    private val context: Context,
    signalingEvents: SignalingEvents,
    private val accessTokenProvider: (() -> String?)? = null,
    private val failureReporter: ((metric: String, throwable: Throwable) -> Unit)? = null,
    private val iceTransportPolicy: PeerConnection.IceTransportsType = PeerConnection.IceTransportsType.ALL
) {
    private val cameraSwitchInProgress = AtomicBoolean(false)

    private fun reportFailure(metric: String, message: String, throwable: Throwable) {
        Log.e("WebRTCManager", message, throwable)
        runCatching { failureReporter?.invoke(metric, throwable) }
            .onFailure { reportingError ->
                Log.w("WebRTCManager", "Failure telemetry could not be recorded for $metric", reportingError)
            }
    }
    companion object {
        // Keep remote audio at WebRTC's reference level. Extra gain on the
        // handset speaker makes echo cancellation less effective on some devices.
        private const val REMOTE_AUDIO_GAIN = 1.0
        private val turnHttpClient by lazy {
            SignalingNetwork.client.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
        private val liveManagers = Collections.synchronizedMap(
            WeakHashMap<WebRTCManager, Unit>()
        )

        /**
         * Releases native allocations only for managers without active peers
         * or capture. The engine is recreated automatically on the next use.
         */
        fun releaseIdleResources() {
            val snapshot = synchronized(liveManagers) { liveManagers.keys.toList() }
            snapshot.forEach { manager -> manager.releaseIdleResourcesAsync() }
        }
    }

    private val isEmulatorAudioEnvironment = isProbablyRunningOnEmulator()
    private val preferredAudioInputSource =
        if (isEmulatorAudioEnvironment) {
            MediaRecorder.AudioSource.MIC
        } else {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
    private val preferredAudioSampleRate =
        if (isEmulatorAudioEnvironment) {
            16_000
        } else {
            48_000
        }
    private val preferredLowLatencyAudio = !isEmulatorAudioEnvironment

    private fun routeSpeakerphone(audioManager: AudioManager, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preferredDeviceTypes = if (enabled) {
                listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            } else {
                // Keep wired/Bluetooth headsets when present. Otherwise route a
                // normal audio call through the earpiece instead of leaving the
                // platform free to select the loudspeaker again.
                listOf(
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                )
            }

            val targetDevice = preferredDeviceTypes.firstNotNullOfOrNull { type ->
                audioManager.availableCommunicationDevices.firstOrNull { it.type == type }
            }
            if (targetDevice != null) {
                audioManager.setCommunicationDevice(targetDevice)
            } else if (!enabled) {
                audioManager.clearCommunicationDevice()
            }
            return
        }
        @Suppress("DEPRECATION")
        run {
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    private fun isProbablyRunningOnEmulator(): Boolean {
        val markers = listOf(
            Build.FINGERPRINT,
            Build.MODEL,
            Build.BRAND,
            Build.DEVICE,
            Build.PRODUCT,
            Build.HARDWARE,
            Build.MANUFACTURER
        ).joinToString(separator = " ").lowercase()

        return listOf(
            "generic",
            "emulator",
            "ranchu",
            "goldfish",
            "sdk_gphone",
            "sdk_phone",
            "aosp"
        ).any { marker -> markers.contains(marker) }
    }

    private var currentUserId: String = ""
    private val deviceTier by lazy { conectate.com.util.AppPerformanceProfiles.detect(context).tier }
    private var localSocketId: String = ""
    
    private var factory: PeerConnectionFactory? = null
    private var rootEglBase: EglBase? = runCatching { EglBase.create() }
        .onFailure { throwable ->
            Log.e("WebRTCManager", "EGL init failed video rendering will stay disabled", throwable)
        }
        .getOrNull()
    val eglContext: EglBase.Context? get() = rootEglBase?.eglBaseContext
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    @Volatile
    private var outgoingAudioBufferCallback: JavaAudioDeviceModule.AudioBufferCallback? = null
    
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    @Volatile
    private var selectedCameraFilter: LiveCameraFilter = LiveCameraFilter.NONE
    @Volatile
    private var selectedCameraAdjustments: LiveCameraAdjustments = LiveCameraAdjustments()
    private var cameraFilterProcessor: LiveCameraFilterProcessor? = null
    @Volatile
    private var cameraFilterPerformanceListener: ((LiveFilterPerformanceState) -> Unit)? = null
    private var outgoingVideoEnabled: Boolean = true
    
    private val _localVideoTrackFlow = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack = _localVideoTrackFlow.asStateFlow()
    
    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks = _remoteVideoTracks.asStateFlow()

    private val _peerConnectionStates = MutableStateFlow<Map<String, PeerConnection.IceConnectionState>>(emptyMap())
    val peerConnectionStates = _peerConnectionStates.asStateFlow()

    private val _signalingStates = MutableStateFlow<Map<String, PeerConnection.SignalingState>>(emptyMap())
    val signalingStates = _signalingStates.asStateFlow()
    
    // Debug helper
    private val _debugInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    val debugInfo = _debugInfo.asStateFlow()

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val mediaCallbackGeneration = java.util.concurrent.atomic.AtomicLong()
    private val deferredPeerDisposals = DeferredNativeDisposals<PeerConnection>()
    @Volatile private var outgoingAudioEnabled = true
    private val remoteUserIds = ConcurrentHashMap<String, String>()
    private val remoteAudioTracks = ConcurrentHashMap<String, AudioTrack>()
    private var remoteAudioPlaybackEnabled: Boolean = true
    
    private val makingOffer = ConcurrentHashMap<String, Boolean>()
    private val iceCandidateQueue = ConcurrentHashMap<String, MutableList<IceCandidate>>()
    private val iceRetryCount = ConcurrentHashMap<String, Int>()
    private val disconnectRecoveryJobs = ConcurrentHashMap<String, Job>()
    private val offerRetryJobs = ConcurrentHashMap<String, Job>()
    private val localOfferTimeoutJobs = ConcurrentHashMap<String, Job>()
    private val glareRecoveryJobs = ConcurrentHashMap<String, Job>()
    private val ignoreOffer = mutableMapOf<String, Boolean>()
    /**
     * True while a remote OFFER is being rolled back/applied. WebRTC invokes
     * onRenegotiationNeeded during a polite rollback on some vendor builds;
     * without this gate that callback can create a second local offer before
     * setRemoteDescription(OFFER) completes and strand the peer in glare.
     */
    private val isSettingRemoteOfferPending = mutableMapOf<String, Boolean>()
    private val isSettingRemoteAnswerPending = mutableMapOf<String, Boolean>()
    private val MAX_ICE_RETRIES = 3
    private val managedIceWaitTimeoutMs = 10_000L
    private val glareRecoveryTimeoutMs = 2_000L
    private val lowLatencyJitterPackets = 20
    private val rendererDetachGraceMs = 96L
    private class DeferredVideoCleanup(
        val track: VideoTrack?,
        val capturer: VideoCapturer?,
        val helper: SurfaceTextureHelper?,
        val source: VideoSource?
    )
    private val deferredVideoCleanupLock = Any()
    private val deferredVideoCleanups = mutableListOf<DeferredVideoCleanup>()
    
    private val selfHostedTurnUrl: String get() = SignalingConfig.TURN_CREDENTIALS_URL.trim()
    private val hasManagedTurnConfigured: Boolean get() = selfHostedTurnUrl.isNotBlank()
    private val dynamicIceServers = mutableListOf<PeerConnection.IceServer>()
    @Volatile private var signalingEvents: SignalingEvents = signalingEvents
    private val managedIceSessionGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val managedIceRefreshLock = Any()
    @Volatile
    private var managedIceRefreshLatch = java.util.concurrent.CountDownLatch(0)
    @Volatile
    private var managedIceExpiresAtEpochSeconds: Long = 0L
    private var managedIceRefreshJob: Job? = null
    private val managedIceRefreshSkewSeconds = 5 * 60L
    private val managedIceRefreshRetryMs = 30_000L
    
    private val executor = MediaTaskExecutor(onFailure = { error ->
        reportFailure("webrtc_native_task", "Native media task failed", error)
    })
    private val destroyRequested = AtomicBoolean(false)
    private val destructionCallbacks = mutableListOf<() -> Unit>()
    private var destroyed = false
    
    // Public STUN fallbacks used while managed TURN is loading or if it fails.
    private var iceServers = buildList {
        add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
        add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer())
    }

    private val statsScope = newSafeScope("WebRTCManagerStats", Dispatchers.Default)
    private val asyncScope = newSafeScope("WebRTCManagerAsync", Dispatchers.Default)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkStateLock = Any()
    private var activeNetworkHandle: Long? = connectivityManager.activeNetwork?.networkHandle
    private var networkCallbackRegistered = false
    private var networkRecoveryJob: Job? = null
    private val networkRecoveryGeneration = java.util.concurrent.atomic.AtomicLong()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val currentHandle = network.networkHandle
            val previousHandle = synchronized(networkStateLock) {
                activeNetworkHandle.also { activeNetworkHandle = currentHandle }
            }
            if (
                NetworkHandoffPolicy.shouldRecover(
                    previousNetworkHandle = previousHandle,
                    currentNetworkHandle = currentHandle,
                    hasActivePeers = peerConnections.isNotEmpty()
                )
            ) {
                recoverAfterNetworkChange(
                    reason = if (previousHandle == null) "network-restored" else "network-handoff"
                )
            }
        }

        override fun onLost(network: Network) {
            synchronized(networkStateLock) {
                if (activeNetworkHandle == network.networkHandle) {
                    activeNetworkHandle = null
                }
            }
        }
    }
    
    init {
        synchronized(liveManagers) { liveManagers[this] = Unit }
        try {
            Log.w("WebRTCManager", "🚀 WebRTC Initializing (Standard Mode) 🚀")
            if (isEmulatorAudioEnvironment) {
                Log.w(
                    "WebRTC",
                    "Emulator audio detected (${Build.HARDWARE}/${Build.PRODUCT}). " +
                        "Using MIC + ${preferredAudioSampleRate}Hz + lowLatency=$preferredLowLatencyAudio."
                )
            }
            
            // v24.1: Configure AudioManager FIRST, before creating any tracks
            var am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            routeSpeakerphone(am, enabled = true)
            if (am.isMicrophoneMute) {
                Log.w("WebRTC", "Mic was muted - unmuting")
                am.isMicrophoneMute = false
            }
            Log.i("WebRTC", "Audio pre-configured: Mode=COMMUNICATION, Speaker=ON")
            if (!hasManagedTurnConfigured) {
                Log.w(
                    "WebRTC",
                    "No TURN credentials endpoint is configured. Only public STUN fallbacks are available, so calls on mobile/NAT-heavy networks may stay in CONNECTING."
                )
            }
            
            initializeFactory()
            createLocalAudioTrack()
            
            // Managed TURN fetch via authenticated self-hosted coturn endpoint.
            if (hasManagedTurnConfigured) {
                refreshManagedIceServers(force = true)
            } else {
                Log.i("WebRTC", "Managed TURN not configured. Using fallback ICE servers only.")
            }
            registerNetworkHandoffMonitor()
            
            // v22.0: Background Stats Monitor for Audio Levels
            statsScope.launch {
                var sample = 0
                while (true) {
                    delay(1000)
                    updateLocalAudioLevelFromStats()
                    if (BuildConfig.DEBUG && ++sample % 5 == 0) logMediaStats()
                }
            }
        } catch (e: Exception) {
            reportFailure("webrtc_init", "Critical error during WebRTC initialization", e)
        }
    }

    fun setCurrentUser(userId: String, socketId: String) {
        if (shouldDiscardManagedIceForAccountChange(currentUserId, userId)) {
            synchronized(managedIceRefreshLock) {
                synchronized(dynamicIceServers) {
                    managedIceSessionGeneration.incrementAndGet()
                    dynamicIceServers.clear()
                    managedIceExpiresAtEpochSeconds = 0L
                }
                managedIceRefreshLatch.countDown()
                managedIceRefreshLatch = java.util.concurrent.CountDownLatch(0)
            }
            managedIceRefreshJob?.cancel()
            Log.i("WebRTC", "Discarded managed TURN credentials after media account changed")
        }
        this.currentUserId = userId
        this.localSocketId = socketId
    }

    fun setSignalingEvents(events: SignalingEvents) { signalingEvents = events }

    /**
     * Hard Reset - Purges all hardware and software WebRTC state.
     * Prevents ANRs and "Zombie" media tracks.
     */
    fun hardReset() {
        executor.execute {
            runCatching { hardResetInternal() }
                .onFailure { error -> Log.e("WebRTCManager", "Hard reset failed", error) }
        }
    }

    suspend fun hardResetAwait() {
        suspendCancellableCoroutine { continuation ->
            executor.execute(onUnavailable = { if (continuation.isActive) continuation.resume(Unit) }) {
                val failure = runCatching { hardResetInternal() }.exceptionOrNull()
                if (continuation.isActive) {
                    if (failure == null) continuation.resume(Unit)
                    else continuation.resumeWithException(failure)
                }
            }
        }
    }

    private fun hardResetInternal() {
        mediaCallbackGeneration.incrementAndGet()
        networkRecoveryGeneration.incrementAndGet()
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        Log.w("WebRTCManager", "🔥 Performing STABILIZED RESET of media engine...")
        // Keep the factory and ADM alive; quick re-entry can otherwise leave
        // device camera/audio drivers locked. Every native release is best-effort
        // so one vendor failure cannot strand the remaining resources.
        val peers = peerConnections.values.toList()
        peerConnections.clear()
        _remoteVideoTracks.value = emptyMap()
        peers.forEach(::disposePeerAfterRendererDetach)
            
            // 2. Clear state maps
            remoteAudioTracks.clear()
            makingOffer.clear()
            iceCandidateQueue.clear()
            iceRetryCount.clear()
            disconnectRecoveryJobs.values.forEach { it.cancel() }
            disconnectRecoveryJobs.clear()
            offerRetryJobs.values.forEach { it.cancel() }
            offerRetryJobs.clear()
            localOfferTimeoutJobs.values.forEach { it.cancel() }
            localOfferTimeoutJobs.clear()
            glareRecoveryJobs.values.forEach { it.cancel() }
            glareRecoveryJobs.clear()
            remoteUserIds.clear() // v7.2.4: Clear User ID mapping
            _remoteVideoTracks.value = emptyMap()
            _peerConnectionStates.value = emptyMap()
            _signalingStates.value = emptyMap()
            _debugInfo.value = emptyMap() // v7.2.4: Clear Debug Info mapping
            ignoreOffer.clear()
            isSettingRemoteOfferPending.clear()
            isSettingRemoteAnswerPending.clear()
            outgoingVideoEnabled = true
            localSocketId = ""
            
        val capturer = videoCapturer.also { videoCapturer = null }
        val helper = surfaceTextureHelper.also { surfaceTextureHelper = null }
        val source = localVideoSource.also { localVideoSource = null }
        val track = _localVideoTrackFlow.value
        _localVideoTrackFlow.value = null
        releaseReplacedVideoPipelineAfterRendererDetach(track, capturer, helper, source)

        Log.i("WebRTCManager", "✅ Media state cleared and ready for fresh re-entry")
    }

    /**
     * Checks if the media engine is in a valid state for re-entry.
     * Returns true only if factory exists and we have active peer connections or are ready to accept them.
     */
    fun isHealthy(): Boolean {
        if (factory == null || localAudioTrack == null) return false
        return _peerConnectionStates.value.values.none {
            it == PeerConnection.IceConnectionState.FAILED || it == PeerConnection.IceConnectionState.CLOSED
        }
    }

    /**
     * Ensures local tracks are valid, recreating them if they were accidentally disposed.
     */
    /**
     * Ensures local tracks are valid, recreating them if they were accidentally disposed.
     */
    fun ensureLocalTracks() {
        executor.execute {
            ensureEngineReady()
            var track = localAudioTrack
            if (track == null || track.state() == MediaStreamTrack.State.ENDED) {
                Log.w("WebRTC", "Audio track invalid - recreating")
                createLocalAudioTrack()
            }
        }
    }
    


    private fun initializeFactory() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())

        // Hardware AEC/NS is enabled where the platform exposes it; WebRTC
        // falls back to its software processors on devices without support.
        Log.i(
            "WebRTC",
            "Creating ADM with source=$preferredAudioInputSource, sampleRate=$preferredAudioSampleRate, lowLatency=$preferredLowLatencyAudio"
        )
        val hardwareAecAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
        val hardwareNoiseSuppressorAvailable = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)
        Log.i(
            "WebRTC",
            "Voice processing: hardwareAEC=$hardwareAecAvailable hardwareNS=$hardwareNoiseSuppressorAvailable"
        )
        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            // Availability is a capability check, not an Android-version check. AEC/NS exist
            // on many Android 7-9 phones too; unsupported devices keep WebRTC's software path.
            .setUseHardwareAcousticEchoCanceler(hardwareAecAvailable)
            .setUseHardwareNoiseSuppressor(hardwareNoiseSuppressorAvailable)
            .setUseLowLatency(preferredLowLatencyAudio)
            .setInputSampleRate(preferredAudioSampleRate)
            .setOutputSampleRate(preferredAudioSampleRate)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioSource(preferredAudioInputSource)
            // The callback delegates through a volatile reference so LIVE can
            // attach/detach its authorized music mixer without rebuilding the
            // microphone or the peer connections.
            .setAudioBufferCallback { buffer: ByteBuffer,
                                      audioFormat: Int,
                                      channelCount: Int,
                                      sampleRate: Int,
                                      bytesRead: Int,
                                      captureTimeNs: Long ->
                outgoingAudioBufferCallback?.onBuffer(
                    buffer,
                    audioFormat,
                    channelCount,
                    sampleRate,
                    bytesRead,
                    captureTimeNs
                ) ?: captureTimeNs
            }
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(e: String?) { Log.e("WebRTC", "AudioRecord Init Error: $e") }
                override fun onWebRtcAudioRecordStartError(c: JavaAudioDeviceModule.AudioRecordStartErrorCode?, e: String?) { Log.e("WebRTC", "AudioRecord Start Error: $c - $e") }
                override fun onWebRtcAudioRecordError(e: String?) { Log.e("WebRTC", "AudioRecord Generic Error: $e") }
            })
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            // PREFER VP8: High stability on Android devices for screen sharing
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEglBase?.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext))
            .setOptions(PeerConnectionFactory.Options().apply {
                networkIgnoreMask = 0
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()
    }

    private fun ensureEngineReady() {
        if (rootEglBase == null) {
            rootEglBase = runCatching { EglBase.create() }
                .onFailure { error ->
                    Log.e("WebRTCManager", "Could not restore EGL after memory trim", error)
                }
                .getOrNull()
        }
        if (factory == null) {
            initializeFactory()
        }
        if (localAudioTrack == null || localAudioTrack?.state() == MediaStreamTrack.State.ENDED) {
            createLocalAudioTrack()
        }
        registerNetworkHandoffMonitor()
    }

    private fun releaseIdleResourcesAsync() {
        runCatching {
            executor.execute {
                if (
                    peerConnections.isNotEmpty() ||
                    videoCapturer != null ||
                    localSocketId.isNotBlank() || !deferredPeerDisposals.isEmpty() ||
                    synchronized(deferredVideoCleanupLock) { deferredVideoCleanups.isNotEmpty() }
                ) {
                    return@execute
                }

                Log.i("WebRTCManager", "Releasing idle native media engine after memory trim")
                unregisterNetworkHandoffMonitor()
                networkRecoveryJob?.cancel()
                networkRecoveryJob = null
                managedIceRefreshJob?.cancel()
                managedIceRefreshJob = null

                drainDeferredPeerDisposals()
                drainDeferredVideoCleanups()
                runCatching { localAudioTrack?.dispose() }
                localAudioTrack = null
                runCatching { localAudioSource?.dispose() }
                localAudioSource = null

                runCatching { factory?.dispose() }
                factory = null
                runCatching { audioDeviceModule?.release() }
                audioDeviceModule = null
            }
        }.onFailure { error ->
            Log.w("WebRTCManager", "Idle WebRTC trim could not be scheduled", error)
        }
    }
    
    private fun destroyInternal() {
        mediaCallbackGeneration.incrementAndGet()
        networkRecoveryGeneration.incrementAndGet()
        Log.d("WebRTCManager", "Internal cleanup starting...")
        unregisterNetworkHandoffMonitor()
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        
        // Stop all captures first
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
        
        val localVideoTrack = _localVideoTrackFlow.value
        _localVideoTrackFlow.value = null
        runCatching { localVideoTrack?.setEnabled(false) }
        runCatching { localVideoTrack?.dispose() }
        runCatching { localVideoSource?.dispose() }
        localVideoSource = null
        
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        
        peerConnections.values.forEach { runCatching { it.dispose() } }
        peerConnections.clear()
        drainDeferredPeerDisposals()
        disconnectRecoveryJobs.values.forEach { it.cancel() }
        disconnectRecoveryJobs.clear()
        offerRetryJobs.values.forEach { it.cancel() }
        offerRetryJobs.clear()
        localOfferTimeoutJobs.values.forEach { it.cancel() }
        localOfferTimeoutJobs.clear()
        glareRecoveryJobs.values.forEach { it.cancel() }
        glareRecoveryJobs.clear()
        makingOffer.clear()
        ignoreOffer.clear()
        isSettingRemoteOfferPending.clear()
        isSettingRemoteAnswerPending.clear()
        
        remoteAudioTracks.clear()
        _remoteVideoTracks.value = emptyMap()
        _peerConnectionStates.value = emptyMap()
        _signalingStates.value = emptyMap()
        _debugInfo.value = emptyMap()
        outgoingVideoEnabled = true

        // A source swap can leave the replaced native pipeline in the short
        // renderer-detach grace window. Drain those objects before the EGL
        // context and factory disappear, even when the user exits immediately.
        drainDeferredVideoCleanups()

        // Audio sources are proxies owned by the factory's signaling thread.
        // Release them while that thread still exists, before disposing the factory.
        runCatching { localAudioSource?.dispose() }
        localAudioSource = null

        runCatching { factory?.dispose() }
        factory = null

        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null

        runCatching { rootEglBase?.release() }
        rootEglBase = null

        statsScope.cancel()
        managedIceRefreshJob?.cancel()
        renegotiateJob?.cancel()
        renegotiateScope.cancel()
        asyncScope.cancel()
        synchronized(liveManagers) { liveManagers.remove(this) }
        executor.shutdown()
    }

    private fun registerNetworkHandoffMonitor() {
        if (networkCallbackRegistered) return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
            Log.i("WebRTCManager", "Default network handoff monitor registered")
        }.onFailure { error ->
            Log.w("WebRTCManager", "Could not register network handoff monitor", error)
        }
    }

    private fun unregisterNetworkHandoffMonitor() {
        if (!networkCallbackRegistered) return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }.onFailure { error ->
            Log.w("WebRTCManager", "Could not unregister network handoff monitor", error)
        }
        networkCallbackRegistered = false
    }

    /**
     * Refreshes TURN credentials and sends an ICE-restart offer for every
     * current peer. It keeps the same local audio/video tracks, mute state and
     * camera/screen capture instead of rebuilding the whole media engine.
     */
    fun recoverAfterNetworkChange(reason: String = "network-handoff") {
        if (peerConnections.isEmpty()) return
        val recoveryGeneration = networkRecoveryGeneration.incrementAndGet()
        networkRecoveryJob?.cancel()
        networkRecoveryJob = asyncScope.launch {
            // Coalesce the short burst of callbacks produced by Wi-Fi/mobile
            // handoffs, then use credentials valid on the new path.
            delay(350L)
            if (peerConnections.isEmpty()) return@launch

            ensureLocalTracks()
            refreshManagedIceServers(force = true)
            awaitManagedIceIfNeeded()

            executor.execute {
                // Cancellation cannot retract work already queued on the native executor.
                // A departed session or a newer handoff must invalidate that work.
                if (networkRecoveryGeneration.get() != recoveryGeneration) return@execute
                val servers = currentIceServersSnapshot()
                peerConnections.forEach { (peerKey, pc) ->
                    if (pc.signalingState() == PeerConnection.SignalingState.CLOSED) {
                        return@forEach
                    }
                    runCatching {
                        pc.setConfiguration(buildRtcConfiguration(servers))

                        getOrAddAudioTransceiver(pc).apply {
                            sender.setTrack(localAudioTrack, false)
                            direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                        }
                        getOrAddVideoTransceiver(pc).apply {
                            sender.setTrack(activeOutgoingVideoTrack(), false)
                            direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                        }

                        iceRetryCount[peerKey] = 0
                        disconnectRecoveryJobs.remove(peerKey)?.cancel()
                        requestOffer(
                            socketId = peerKey,
                            pc = pc,
                            iceRestart = true,
                            reason = reason,
                            retryDelayMs = 600L
                        )
                        updateDebugInfo(peerKey, "Recuperando conexión tras cambio de red")
                    }.onFailure { error ->
                        Log.e("WebRTCManager", "Network recovery failed for $peerKey", error)
                    }
                }
            }
        }
    }

    private var localAudioSource: AudioSource? = null

    private fun createLocalAudioTrack() {
        var f = factory ?: return
        localAudioSource?.dispose()
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"))
        }
        localAudioSource = f.createAudioSource(audioConstraints)
        localAudioTrack = f.createAudioTrack("ARDAMSa0", localAudioSource)
        localAudioTrack?.setEnabled(true)
        Log.i("WebRTC", "Audio track created: ${localAudioTrack?.id()}, enabled=${localAudioTrack?.enabled()}")
    }

    private fun handleIncomingTrack(socketId: String, track: MediaStreamTrack) {
        Log.i("WebRTCManager", "Add Remote Track: ${track.kind()} ($socketId)")
        if (track.kind() == "video" && track is VideoTrack) {
            track.setEnabled(true)
            var map = _remoteVideoTracks.value.toMutableMap()
            map[socketId] = track
            _remoteVideoTracks.value = map
        }
        if (track.kind() == "audio" && track is AudioTrack) {
            Log.i("WebRTCManager", "🔊 Remote Audio Track Received for $socketId")
            runCatching {
                var am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                }
            }
            peerConnections[socketId]?.setAudioPlayout(remoteAudioPlaybackEnabled)
            track.setEnabled(remoteAudioPlaybackEnabled)
            track.setVolume(if (remoteAudioPlaybackEnabled) REMOTE_AUDIO_GAIN else 0.0)
            // Rolled-back negotiations can leave an older receiver alive.
            // Play this participant once, even when a new track replaces it.
            remoteAudioTracks.put(socketId, track)?.takeIf { it.id() != track.id() }?.let { oldTrack ->
                runCatching { oldTrack.setEnabled(false) }
            }
            Log.i("WebRTCManager", "Remote Audio Track configured and enabled for $socketId")
        }
    }

    private fun updateLocalVideoSenders(track: VideoTrack?) {
        peerConnections.forEach { (peerKey, pc) ->
            pc.transceivers
                .filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                .forEach { transceiver ->
                    transceiver.sender.setTrack(track, false)
                    transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                }
            setMaxBitrate(pc, peerKey)
        }
    }

    private fun activeOutgoingVideoTrack(): VideoTrack? {
        return if (outgoingVideoEnabled) _localVideoTrackFlow.value else null
    }

    private fun getOrAddAudioTransceiver(pc: PeerConnection): RtpTransceiver {
        return pc.transceivers.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            .let { transceivers -> transceivers.firstOrNull { it.mid != null } ?: transceivers.firstOrNull() }
            ?: pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                    listOf("stream")
                )
            )
    }

    private fun getOrAddVideoTransceiver(pc: PeerConnection): RtpTransceiver {
        return pc.transceivers.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            .let { transceivers -> transceivers.firstOrNull { it.mid != null } ?: transceivers.firstOrNull() }
            ?: pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                    listOf("stream")
                )
            )
    }

    private fun releaseVideoPipeline(
        capturer: VideoCapturer?,
        helper: SurfaceTextureHelper?,
        source: VideoSource?
    ) {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { helper?.dispose() }
        runCatching { source?.dispose() }
    }

    /**
     * Installs a post-capture audio callback. Only the LIVE repository uses
     * this hook; calls and voice rooms leave it null and retain the untouched
     * microphone path.
     */
    fun setOutgoingAudioBufferCallback(callback: JavaAudioDeviceModule.AudioBufferCallback?) {
        outgoingAudioBufferCallback = callback
    }

    private fun releaseReplacedVideoPipelineAfterRendererDetach(
        track: VideoTrack?,
        capturer: VideoCapturer?,
        helper: SurfaceTextureHelper?,
        source: VideoSource?
    ) {
        // Stop hardware use immediately, but keep the native track/source alive
        // briefly because a renderer may still be detaching its sink.
        runCatching { track?.setEnabled(false) }
        runCatching { capturer?.stopCapture() }
        val cleanup = DeferredVideoCleanup(track, capturer, helper, source)
        synchronized(deferredVideoCleanupLock) { deferredVideoCleanups += cleanup }
        asyncScope.launch {
            // StateFlow publishes the replacement immediately; retain the old
            // native objects long enough for Compose to remove its renderer sink.
            delay(rendererDetachGraceMs)
            runCatching {
                executor.execute {
                    val ownsCleanup = synchronized(deferredVideoCleanupLock) {
                        deferredVideoCleanups.remove(cleanup)
                    }
                    if (ownsCleanup) {
                        runCatching { cleanup.track?.dispose() }
                        releaseVideoPipeline(cleanup.capturer, cleanup.helper, cleanup.source)
                    }
                }
            }.onFailure { error ->
                Log.w("WebRTCManager", "Could not schedule replaced video cleanup", error)
                val ownsCleanup = synchronized(deferredVideoCleanupLock) {
                    deferredVideoCleanups.remove(cleanup)
                }
                if (ownsCleanup) {
                    runCatching { cleanup.track?.dispose() }
                    releaseVideoPipeline(cleanup.capturer, cleanup.helper, cleanup.source)
                }
            }
        }
    }

    private fun <T> rekeyStateMap(
        stateFlow: MutableStateFlow<Map<String, T>>,
        oldKey: String,
        newKey: String
    ) {
        if (oldKey == newKey) return
        val current = stateFlow.value
        var value = current[oldKey] ?: return
        val updated = current.toMutableMap()
        updated.remove(oldKey)
        updated[newKey] = value
        stateFlow.value = updated
    }

    private fun resolvePeerKey(rawId: String?, remoteUserId: String? = null): String? {
        val cleanRawId = rawId?.trim().orEmpty()
        val cleanRemoteUserId = remoteUserId?.trim().orEmpty()

        if (cleanRawId.isNotBlank() && peerConnections.containsKey(cleanRawId)) {
            return cleanRawId
        }

        if (cleanRemoteUserId.isNotBlank()) {
            peerConnections.keys.firstOrNull { key ->
                remoteUserIds[key].equals(cleanRemoteUserId, ignoreCase = true)
            }?.let { return it }
        }

        if (cleanRawId.isNotBlank()) {
            peerConnections.keys.firstOrNull { key ->
                remoteUserIds[key].equals(cleanRawId, ignoreCase = true)
            }?.let { return it }
        }

        return null
    }

    private fun migratePeerKey(oldKey: String, newKey: String, remoteUserId: String) {
        var cleanOldKey = oldKey.trim()
        val cleanNewKey = newKey.trim()
        if (cleanOldKey.isBlank() || cleanNewKey.isBlank() || cleanOldKey == cleanNewKey) {
            return
        }

        var connection = peerConnections.remove(cleanOldKey) ?: return
        peerConnections.remove(cleanNewKey)?.takeIf { it !== connection }?.dispose()
        peerConnections[cleanNewKey] = connection

        val previousUserId = remoteUserIds.remove(cleanOldKey).orEmpty()
        val resolvedUserId = remoteUserId.ifBlank { previousUserId }
        if (resolvedUserId.isNotBlank()) {
            remoteUserIds[cleanNewKey] = resolvedUserId
        }

        remoteAudioTracks.remove(cleanOldKey)?.let { remoteAudioTracks[cleanNewKey] = it }
        makingOffer.remove(cleanOldKey)?.let { makingOffer[cleanNewKey] = it }
        iceRetryCount.remove(cleanOldKey)?.let { iceRetryCount[cleanNewKey] = it }
        iceCandidateQueue.remove(cleanOldKey)?.let { pending ->
            iceCandidateQueue.getOrPut(cleanNewKey) { mutableListOf() }.addAll(pending)
        }
        disconnectRecoveryJobs.remove(cleanOldKey)?.let { disconnectRecoveryJobs[cleanNewKey] = it }
        offerRetryJobs.remove(cleanOldKey)?.let { offerRetryJobs[cleanNewKey] = it }
        localOfferTimeoutJobs.remove(cleanOldKey)?.let { localOfferTimeoutJobs[cleanNewKey] = it }
        glareRecoveryJobs.remove(cleanOldKey)?.let { glareRecoveryJobs[cleanNewKey] = it }
        ignoreOffer.remove(cleanOldKey)?.let { ignoreOffer[cleanNewKey] = it }
        isSettingRemoteOfferPending.remove(cleanOldKey)?.let { isSettingRemoteOfferPending[cleanNewKey] = it }
        isSettingRemoteAnswerPending.remove(cleanOldKey)?.let { isSettingRemoteAnswerPending[cleanNewKey] = it }

        rekeyStateMap(_remoteVideoTracks, cleanOldKey, cleanNewKey)
        rekeyStateMap(_peerConnectionStates, cleanOldKey, cleanNewKey)
        rekeyStateMap(_signalingStates, cleanOldKey, cleanNewKey)
        rekeyStateMap(_debugInfo, cleanOldKey, cleanNewKey)
    }

    private fun scheduleOfferRetry(
        socketId: String,
        remoteUserId: String = "",
        iceRestart: Boolean = false,
        delayMs: Long = 350L,
        reason: String = "retry"
    ) {
        var requestedKey = socketId.trim().ifBlank { remoteUserId.trim() }
        if (requestedKey.isBlank()) return

        var peerKey = resolvePeerKey(requestedKey, remoteUserId) ?: requestedKey
        offerRetryJobs.remove(peerKey)?.cancel()
        offerRetryJobs[peerKey] = asyncScope.launch {
            delay(delayMs)
            executor.execute {
                offerRetryJobs.remove(peerKey)
                var resolvedKey = resolvePeerKey(peerKey, remoteUserId)
                    ?: resolvePeerKey(requestedKey, remoteUserId)
                    ?: peerKey
                var pc = peerConnections[resolvedKey] ?: return@execute
                requestOffer(
                    socketId = resolvedKey,
                    pc = pc,
                    iceRestart = iceRestart,
                    reason = reason,
                    retryDelayMs = delayMs,
                    allowRetry = false
                )
            }
        }
    }

    private fun requestOffer(
        socketId: String,
        pc: PeerConnection,
        iceRestart: Boolean = false,
        reason: String,
        retryDelayMs: Long = 350L,
        allowRetry: Boolean = true
    ): Boolean {
        var remoteUserId = remoteUserIds[socketId].orEmpty()
        var peerKey = resolvePeerKey(socketId, remoteUserId) ?: socketId.trim().ifBlank { remoteUserId }
        if (peerKey.isBlank()) return false

        if (makingOffer[peerKey] == true) {
            Log.w("WebRTC", "Skipping offer for $peerKey ($reason): already making an offer")
            if (allowRetry) {
                scheduleOfferRetry(peerKey, remoteUserId, iceRestart, retryDelayMs, "$reason-retry")
            }
            return false
        }

        if (isSettingRemoteAnswerPending[peerKey] == true) {
            Log.w("WebRTC", "Skipping offer for $peerKey ($reason): remote answer still pending")
            if (allowRetry) {
                scheduleOfferRetry(peerKey, remoteUserId, iceRestart, retryDelayMs, "$reason-retry")
            }
            return false
        }

        if (isSettingRemoteOfferPending[peerKey] == true) {
            Log.w("WebRTC", "Skipping offer for $peerKey ($reason): remote offer still pending")
            if (allowRetry) {
                scheduleOfferRetry(peerKey, remoteUserId, iceRestart, retryDelayMs, "$reason-retry")
            }
            return false
        }

        var signalingState = pc.signalingState()
        if (signalingState != PeerConnection.SignalingState.STABLE) {
            Log.w("WebRTC", "Skipping offer for $peerKey ($reason): signaling=$signalingState")
            if (allowRetry) {
                scheduleOfferRetry(peerKey, remoteUserId, iceRestart, retryDelayMs, "$reason-retry")
            }
            return false
        }

        offerRetryJobs.remove(peerKey)?.cancel()
        makingOffer[peerKey] = true
        Log.i("WebRTC", "Creating offer for $peerKey ($reason, iceRestart=$iceRestart)")
        createOffer(peerKey, pc, iceRestart = iceRestart)
        return true
    }

    private fun cancelGlareRecovery(peerKey: String) {
        glareRecoveryJobs.remove(peerKey)?.cancel()
    }

    private fun scheduleGlareRecovery(socketId: String, remoteUserId: String = "") {
        var requestedKey = socketId.trim().ifBlank { remoteUserId.trim() }
        if (requestedKey.isBlank()) return

        var peerKey = resolvePeerKey(requestedKey, remoteUserId) ?: requestedKey
        cancelGlareRecovery(peerKey)
        glareRecoveryJobs[peerKey] = asyncScope.launch {
            delay(glareRecoveryTimeoutMs)
            executor.execute {
                glareRecoveryJobs.remove(peerKey)
                var resolvedKey = resolvePeerKey(peerKey, remoteUserId) ?: peerKey
                if (ignoreOffer[resolvedKey] != true) return@execute

                ignoreOffer[resolvedKey] = false
                var pc = peerConnections[resolvedKey] ?: return@execute
                var iceState = pc.iceConnectionState()
                var signalingState = pc.signalingState()

                Log.w(
                    "WebRTC",
                    "Cleared stale GLARE ignore flag for $resolvedKey after ${glareRecoveryTimeoutMs}ms " +
                        "(sig=$signalingState, ice=$iceState)"
                )

                if (
                    signalingState == PeerConnection.SignalingState.STABLE &&
                    makingOffer[resolvedKey] != true &&
                    isSettingRemoteOfferPending[resolvedKey] != true &&
                    isSettingRemoteAnswerPending[resolvedKey] != true
                ) {
                    requestOffer(
                        socketId = resolvedKey,
                        pc = pc,
                        iceRestart = iceState == PeerConnection.IceConnectionState.FAILED ||
                            iceState == PeerConnection.IceConnectionState.DISCONNECTED,
                        reason = "glare-timeout-recovery",
                        retryDelayMs = 600L,
                        allowRetry = false
                    )
                }
            }
        }
    }

    private fun shouldInitiateIceRecovery(socketId: String): Boolean {
        var remoteUserId = remoteUserIds[socketId].orEmpty()
        if (remoteUserId.isBlank()) return true
        return !isPolite(remoteUserId, socketId)
    }


    fun onUserJoined(remoteSocketId: String, remoteUserId: String, restartExistingPeer: Boolean = true) {
        var rsid = remoteSocketId.trim()
        var ruid = remoteUserId.trim()
        if (rsid.isBlank() || ruid.isBlank()) return
        if (
            (currentUserId.isNotBlank() && currentUserId.equals(ruid, ignoreCase = true)) ||
            (localSocketId.isNotBlank() && localSocketId == rsid)
        ) {
            // Never create a peer connection to our own socket/account. A delayed
            // participant snapshot during reconnect could otherwise feed our mic
            // back as a remote track and sound like a strong echo.
            Log.w("WebRTCManager", "Ignoring self participant $ruid ($rsid)")
            return
        }
        executor.execute {
            Log.d("WebRTCManager", "onUserJoined: $ruid ($rsid)")
            val existingPeerKey = resolvePeerKey(rsid, ruid)
            val socketWasReplaced = existingPeerKey != null && existingPeerKey != rsid
            if (socketWasReplaced) {
                // A PeerConnection observer captures the target socket id at
                // creation time. Migrating that connection would send fresh ICE
                // candidates to the stale socket after a Socket.IO reconnect.
                Log.i(
                    "WebRTCManager",
                    "Recreating peer for reconnected user $ruid ($existingPeerKey -> $rsid)"
                )
                detachPeerInternal(checkNotNull(existingPeerKey))?.let(::disposePeerAfterRendererDetach)
            }
            val reusedExistingPeer = existingPeerKey != null && !socketWasReplaced
            var pc = getOrCreatePC(rsid, ruid) ?: return@execute
            var peerKey = resolvePeerKey(rsid, ruid) ?: rsid.ifBlank { ruid }
            
            if (shouldSkipExistingPeerOffer(reusedExistingPeer, restartExistingPeer, pc.iceConnectionState())) {
                Log.d("WebRTCManager", "Preserving existing peer negotiation and media")
                return@execute
            }
            val isPolite = isPolite(ruid, rsid)
            updateDebugInfo(peerKey, "Role: ${if(isPolite) "POLITE" else "IMPOLITE"}\nUserID: $ruid")
            
            if (!isPolite) {
                 Log.i("WebRTC", "Impolite peer initiating connection to $peerKey")
                 requestOffer(
                     socketId = peerKey,
                     pc = pc,
                     iceRestart = reusedExistingPeer,
                     reason = if (reusedExistingPeer) "user-rejoined" else "user-joined"
                 )
            } else {
                 Log.i("WebRTC", "Polite peer waiting for offer from $peerKey")
            }
        }
    }

    private fun getOrCreatePC(socketId: String, userId: String): PeerConnection? {
        var rsid = socketId.trim()
        var ruid = userId.trim()
        var requestedKey = rsid.ifBlank { ruid }
        if (requestedKey.isBlank()) return null

        val existingKey = resolvePeerKey(requestedKey, ruid)
        if (existingKey != null) {
            if (requestedKey != ruid && existingKey != requestedKey) {
                migratePeerKey(existingKey, requestedKey, ruid)
            } else if (ruid.isNotBlank()) {
                remoteUserIds[existingKey] = ruid
            }
            return peerConnections[resolvePeerKey(requestedKey, ruid) ?: requestedKey]
        }
        
        ensureEngineReady()
        remoteUserIds[requestedKey] = ruid
        peerConnections[requestedKey]?.let { return it }
        
        var pc = createPeerConnection(requestedKey, ruid) ?: return null
        peerConnections[requestedKey] = pc
        
        // INITIALIZE STATE TO NEW
        var map = _peerConnectionStates.value.toMutableMap()
        map[requestedKey] = PeerConnection.IceConnectionState.NEW
        _peerConnectionStates.value = map

        // The answerer adopts the remote offer's m-lines before attaching local
        // tracks. Pre-creating transceivers on both peers can strand its camera
        // and microphone on extra, unassociated m-lines: ICE connects but the
        // initial answer transmits no image or voice.
        if (isPolite(ruid, rsid)) return pc
        
        // v24.2: Standard Transceiver Setup
        // 1. Audio: Always SEND_RECV to ensure we can talk and hear
        var audioTransceiver = pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("stream"))
        )
        localAudioTrack?.let { track ->
            audioTransceiver.sender.setTrack(track, false)
            Log.i("WebRTC", "🎤 Audio track attached to $requestedKey")
        } ?: Log.w("WebRTC", "⚠️ No local audio track available for $requestedKey")

        // 2. Negotiate video in both directions from the beginning. A camera or
        // screen track can then be attached later without a new SDP exchange.
        var videoTransceiver = pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("stream"))
        )
        activeOutgoingVideoTrack()?.let { track ->
            videoTransceiver.sender.setTrack(track, false)
            Log.i("WebRTC", "📹 Video track attached to $requestedKey")
        } ?: run {
            Log.d("WebRTC", "📹 No local video track; keeping SEND_RECV m-line for $requestedKey")
        }

        pc.setAudioRecording(localAudioTrack?.enabled() ?: true)
        pc.setAudioPlayout(remoteAudioPlaybackEnabled)
        
        return pc
    }

    private fun beginManagedIceRefresh(force: Boolean): java.util.concurrent.CountDownLatch? {
        val shouldRefresh = synchronized(dynamicIceServers) {
            shouldRefreshManagedIce(force, dynamicIceServers.isNotEmpty(), managedIceExpiresAtEpochSeconds,
                System.currentTimeMillis() / 1_000L)
        }
        if (!shouldRefresh) {
            Log.d("WebRTC", "Reusing managed TURN credentials while they remain valid.")
            return null
        }

        synchronized(managedIceRefreshLock) {
            if (managedIceRefreshLatch.count > 0) {
                Log.d("WebRTC", "Skipping managed TURN refresh because another refresh is already in progress.")
                return null
            }
            return java.util.concurrent.CountDownLatch(1).also { managedIceRefreshLatch = it }
        }
    }

    private fun finishManagedIceRefresh(latch: java.util.concurrent.CountDownLatch) {
        synchronized(managedIceRefreshLock) {
            if (managedIceRefreshLatch === latch && latch.count > 0) {
                latch.countDown()
            }
        }
    }

    private fun activeManagedIceRefreshLatch(): java.util.concurrent.CountDownLatch? {
        return synchronized(managedIceRefreshLock) {
            managedIceRefreshLatch.takeIf { it.count > 0 }
        }
    }

    private fun managedIceNeedsRefreshBeforeConnecting(): Boolean = synchronized(dynamicIceServers) {
        shouldAwaitManagedIceRefresh(
            hasServers = dynamicIceServers.isNotEmpty(),
            expiresAtEpochSeconds = managedIceExpiresAtEpochSeconds,
            nowEpochSeconds = System.currentTimeMillis() / 1_000L
        )
    }

    private fun awaitManagedIceIfNeeded() {
        if (hasManagedTurnConfigured && managedIceNeedsRefreshBeforeConnecting()) {
            activeManagedIceRefreshLatch()?.await(managedIceWaitTimeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun fetchManagedIceServers(generation: Long) {
        var loaded = false
        try {
            loaded = fetchSelfHostedIceServers(generation)
        } catch (e: Exception) {
            reportFailure("webrtc_turn_refresh", "Managed TURN refresh failed", e)
        } finally {
            Log.d(
                "WebRTC",
                "Managed TURN setup finished. Using ${if (dynamicIceServers.isNotEmpty()) "managed TURN + fallback" else "fallback ICE only"}"
            )
            if (!loaded && hasManagedTurnConfigured && generation == managedIceSessionGeneration.get()) {
                scheduleManagedIceRefresh(managedIceRefreshRetryMs)
            }
        }
    }

    private fun scheduleManagedIceRefresh(delayMs: Long? = null) {
        if (!hasManagedTurnConfigured) return
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val calculatedDelayMs = delayMs ?: (
            (managedIceExpiresAtEpochSeconds - nowSeconds - managedIceRefreshSkewSeconds)
                .coerceAtLeast(30L) * 1_000L
        )
        managedIceRefreshJob?.cancel()
        managedIceRefreshJob = asyncScope.launch {
            delay(calculatedDelayMs)
            Log.i("WebRTC", "Refreshing managed TURN credentials before expiration")
            refreshManagedIceServers(force = true)
        }
    }

    fun refreshManagedIceServers(force: Boolean = false) {
        if (!hasManagedTurnConfigured) {
            Log.d("WebRTC", "Skipping managed TURN refresh because no provider is configured.")
            return
        }

        var refreshLatch = beginManagedIceRefresh(force) ?: return
        val generation = managedIceSessionGeneration.get()
        Log.i("WebRTC", "Refreshing managed TURN configuration (force=$force)")
        asyncScope.launch {
            try {
                fetchManagedIceServers(generation)
            } finally {
                finishManagedIceRefresh(refreshLatch)
            }
        }
    }

    private fun fetchSelfHostedIceServers(generation: Long): Boolean {
        try {
            if (selfHostedTurnUrl.isBlank()) {
                Log.i("WebRTC", "Skipping self-hosted TURN fetch because endpoint is not configured.")
                return false
            }

            val token = accessTokenProvider?.invoke()?.trim().orEmpty()
            if (token.isBlank()) {
                Log.w("WebRTC", "Skipping self-hosted TURN fetch because there is no auth token available.")
                return false
            }

            Log.i("WebRTC", "Fetching managed TURN configuration from self-hosted coturn endpoint")
            val request = Request.Builder().url(selfHostedTurnUrl)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .build()
            turnHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(response.body?.string().orEmpty())
                    val loaded = loadIceServersFromEnvelope(jsonResponse, generation, token)
                    if (!loaded) {
                        Log.e("WebRTC", "Self-hosted TURN response did not include any ICE servers.")
                    }
                    return loaded
                }
                Log.e("WebRTC", "Self-hosted TURN endpoint returned HTTP ${response.code}")
            }
        } catch (e: Exception) {
            reportFailure("webrtc_turn_fetch", "Self-hosted TURN request failed", e)
        }
        return false
    }

    private fun loadIceServersFromEnvelope(jsonResponse: JSONObject, generation: Long, requestToken: String): Boolean {
        var envelope = when {
            jsonResponse.has("iceServers") -> jsonResponse
            jsonResponse.optJSONObject("v")?.has("iceServers") == true -> jsonResponse.optJSONObject("v")
            jsonResponse.optJSONObject("d")?.has("iceServers") == true -> jsonResponse.optJSONObject("d")
            else -> null
        }

        if (envelope == null) {
            Log.e("WebRTC", "Managed TURN response missing expected iceServers envelope")
            return false
        }

        synchronized(dynamicIceServers) {
            if (!acceptsManagedIceResponse(generation, managedIceSessionGeneration.get(), requestToken,
                    accessTokenProvider?.invoke()?.trim().orEmpty())) {
                Log.i("WebRTC", "Discarded TURN response from a previous media session")
                return false
            }
            managedIceExpiresAtEpochSeconds = envelope.optLong("expiresAtEpochSeconds", 0L)
            dynamicIceServers.clear()
            val iceServersObj = envelope.get("iceServers")
            if (iceServersObj is org.json.JSONArray) {
                for (i in 0 until iceServersObj.length()) {
                    addIceServerFromJSON(iceServersObj.getJSONObject(i))
                }
            } else if (iceServersObj is JSONObject) {
                addIceServerFromJSON(iceServersObj)
            }
        }

        Log.i("WebRTC", "Managed TURN configuration loaded: ${dynamicIceServers.size} servers")
        if (managedIceExpiresAtEpochSeconds > 0L) {
            scheduleManagedIceRefresh()
        }
        executor.execute {
            applyUpdatedIceConfigurationToActivePeers()
        }
        return dynamicIceServers.isNotEmpty()
    }

    private fun currentIceServersSnapshot(): List<PeerConnection.IceServer> {
        return synchronized(dynamicIceServers) {
            if (dynamicIceServers.isEmpty()) {
                Log.w("WebRTC", "No managed TURN servers found, using public STUN fallbacks.")
                iceServers
            } else {
                dynamicIceServers + iceServers
            }
        }
    }

    private fun buildRtcConfiguration(servers: List<PeerConnection.IceServer>): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = iceTransportPolicy

            // v17.2: Force Modern Policies for better NAT traversal
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            keyType = PeerConnection.KeyType.ECDSA
            audioJitterBufferFastAccelerate = true
            audioJitterBufferMaxPackets = lowLatencyJitterPackets
            iceCandidatePoolSize = 1
        }
    }

    private fun applyUpdatedIceConfigurationToActivePeers() {
        if (peerConnections.isEmpty()) return

        var combinedIceServers = currentIceServersSnapshot()
        peerConnections.forEach { (peerKey, pc) ->
            runCatching {
                pc.setConfiguration(buildRtcConfiguration(combinedIceServers))
                Log.i("WebRTC", "Applied updated ICE configuration to active peer $peerKey")
            }.onFailure { error ->
                Log.w("WebRTC", "Failed to apply refreshed ICE configuration to $peerKey: ${error.message}")
            }
        }
    }

    private fun createPeerConnection(socketId: String, remoteUserId: String): PeerConnection? {
        val callbackGeneration = mediaCallbackGeneration.get()
        // Wait for managed TURN credentials before falling back.  This matters a lot
        // for users outside the local network: signaling can work through Cloudflare,
        // but audio/video/screen-share needs TURN candidates ready before the first offer.
        try {
            var refreshLatch = activeManagedIceRefreshLatch()
            if (
                refreshLatch == null &&
                hasManagedTurnConfigured &&
                managedIceNeedsRefreshBeforeConnecting()
            ) {
                Log.w("WebRTC", "⌛ createPeerConnection: managed TURN is absent or expired; refreshing before creating peer...")
                refreshManagedIceServers(force = true)
                refreshLatch = activeManagedIceRefreshLatch()
            }
            if (refreshLatch != null && managedIceNeedsRefreshBeforeConnecting()) {
                Log.w("WebRTC", "⌛ createPeerConnection: Waiting for managed TURN refresh...")
                refreshLatch.await(managedIceWaitTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            Log.e("WebRTC", "⌛ Latency Error: Timed out waiting for managed TURN")
        }

        var combinedIceServers = currentIceServersSnapshot()
        val config = buildRtcConfiguration(combinedIceServers)

        return factory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (callbackGeneration != mediaCallbackGeneration.get()) return
                val candidateType = if (candidate.sdp.contains("typ relay")) "RELAY (TURN)" 
                                    else if (candidate.sdp.contains("typ srflx")) "SRFLX (STUN)" 
                                    else "HOST (LOCAL)"
                Log.i("WebRTC", "📡 Generated Candidate: $candidateType")
                
                val json = JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                }
                signalingEvents.onSendIceCandidate(socketId, json)
            }

            override fun onRenegotiationNeeded() {
                executor.execute {
                    if (callbackGeneration != mediaCallbackGeneration.get()) return@execute
                    var pc = peerConnections[socketId] ?: return@execute
                    if (localSocketId.isBlank()) {
                        Log.w("WebRTC", "Skipping offer: localSocketId not yet set")
                        return@execute
                    }
                    var sigState = pc.signalingState()
                    var iceState = pc.iceConnectionState()
                    
                    Log.d("WebRTC", "RenegotiationNeeded for $socketId (Sig: $sigState, Ice: $iceState)")
                    
                    if (sigState == PeerConnection.SignalingState.STABLE) {
                        // PERFECT NEGOTIATION: Only initiate if connection is established 
                        // OR if we are the Impolite peer.
                        // If connection is NEW and we are POLITE, we MUST let the other peer initiate.
                        if (iceState == PeerConnection.IceConnectionState.NEW && isPolite(remoteUserId, socketId)) {
                            Log.i("WebRTC", "☕ Polite peer waiting for initial offer from $remoteUserId ($socketId)")
                            return@execute
                        }

                        if (WebRtcNegotiationPolicy.shouldDeferLocalOffer(
                                makingOffer = makingOffer[socketId] == true,
                                settingRemoteOffer = isSettingRemoteOfferPending[socketId] == true,
                                settingRemoteAnswer = isSettingRemoteAnswerPending[socketId] == true,
                                signalingState = sigState
                            )
                        ) {
                            Log.d("WebRTC", "Ignoring RenegotiationNeeded for $socketId because a negotiation is already in flight")
                            return@execute
                        }
                        
                        Log.i("WebRTC", "🚀 Initiating negotiation for $socketId")
                        requestOffer(
                            socketId = socketId,
                            pc = pc,
                            iceRestart = false,
                            reason = "onRenegotiationNeeded"
                        )
                    } else {
                        Log.w("WebRTC", "Ignoring RenegotiationNeeded: current state is $sigState")
                    }
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                if (callbackGeneration != mediaCallbackGeneration.get()) return
                val track = receiver?.track() ?: return
                executor.execute {
                    if (callbackGeneration != mediaCallbackGeneration.get()) return@execute
                    handleIncomingTrack(socketId, track)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                if (callbackGeneration != mediaCallbackGeneration.get()) return
                val track = transceiver?.receiver?.track() ?: return
                executor.execute {
                    if (callbackGeneration != mediaCallbackGeneration.get()) return@execute
                    handleIncomingTrack(socketId, track)
                }
            }

            override fun onRemoveTrack(receiver: RtpReceiver?) {
                if (callbackGeneration != mediaCallbackGeneration.get()) return
                val track = receiver?.track() ?: return
                executor.execute {
                    if (callbackGeneration != mediaCallbackGeneration.get()) return@execute
                    Log.w("WebRTCManager", "Remove Remote Track: ${track.kind()} ($socketId)")
                    if (track.kind() == "video") {
                        val map = _remoteVideoTracks.value.toMutableMap()
                        if (map[socketId]?.id() == track.id()) {
                            map.remove(socketId)
                        }
                        _remoteVideoTracks.value = map
                    }
                    if (track.kind() == "audio") {
                        remoteAudioTracks.remove(socketId)
                    }
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                // Native callbacks may arrive after close/dispose. Serialize state
                // updates with reset and reject callbacks from the previous session.
                executor.execute iceState@{
                    if (callbackGeneration != mediaCallbackGeneration.get()) return@iceState
                    Log.d("WebRTC", "ICE $state ($socketId)")
                    var map = _peerConnectionStates.value.toMutableMap()
                    map[socketId] = state
                    _peerConnectionStates.value = map
                
                    if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                        disconnectRecoveryJobs.remove(socketId)?.cancel()
                        // Reinforce and check audio session on connection
                        executor.execute {
                            if (callbackGeneration != mediaCallbackGeneration.get()) return@execute
                            var am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                                am.mode = AudioManager.MODE_IN_COMMUNICATION
                                Log.w("WebRTC", "Audio mode fixed to COMMUNICATION after ICE connected")
                            }
                        
                            // v7.2.6: Reinforce transceivers to ensure local track is still active
                            var pc = peerConnections[socketId]
                            // getTransceivers() disposes its previous Java wrappers.
                            // Snapshot the sender's native identity before reading it again.
                            val primaryAudioSenderId = pc?.let(::getOrAddAudioTransceiver)?.sender?.id()
                            pc?.transceivers?.forEach { t ->
                                if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                                    // Native getters create new Java wrappers. Reference
                                    // equality would clear every microphone sender here.
                                    val primaryAudio = isSameMediaSender(t.sender.id(), primaryAudioSenderId)
                                    if (primaryAudio && t.direction == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY) {
                                        t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                                    }
                                    t.sender.setTrack(if (primaryAudio) localAudioTrack else null, false)
                                } else if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                                    var activeVideoTrack = activeOutgoingVideoTrack()
                                    t.sender.setTrack(activeVideoTrack, false)
                                    t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                                }
                            }
                            pc?.setAudioPlayout(remoteAudioPlaybackEnabled)
                            remoteAudioTracks[socketId]?.let { remoteTrack ->
                                remoteTrack.setEnabled(remoteAudioPlaybackEnabled)
                                remoteTrack.setVolume(if (remoteAudioPlaybackEnabled) REMOTE_AUDIO_GAIN else 0.0)
                            }
                        }
                    }
                    if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        disconnectRecoveryJobs.remove(socketId)?.cancel()
                        disconnectRecoveryJobs[socketId] = asyncScope.launch {
                            delay(1_500)
                            executor.execute {
                                if (callbackGeneration != mediaCallbackGeneration.get()) return@execute
                                var pc = peerConnections[socketId] ?: return@execute
                                val currentState = pc.iceConnectionState()
                                if (
                                    currentState != PeerConnection.IceConnectionState.DISCONNECTED &&
                                    currentState != PeerConnection.IceConnectionState.FAILED
                                ) {
                                    return@execute
                                }

                                Log.w("WebRTC", "ICE still disconnected for $socketId, forcing recovery offer")
                                if (!shouldInitiateIceRecovery(socketId)) {
                                    Log.i("WebRTC", "Polite peer waiting for remote ICE recovery offer ($socketId)")
                                    return@execute
                                }

                                var audioTransceiver = getOrAddAudioTransceiver(pc)
                                audioTransceiver.sender.setTrack(localAudioTrack, false)
                                audioTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV

                                var videoTransceiver = getOrAddVideoTransceiver(pc)
                                var activeVideoTrack = activeOutgoingVideoTrack()
                                videoTransceiver.sender.setTrack(activeVideoTrack, false)
                                videoTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV

                                requestOffer(
                                    socketId = socketId,
                                    pc = pc,
                                    iceRestart = true,
                                    reason = "ice-disconnected-recovery",
                                    retryDelayMs = 500L
                                )
                            }
                        }
                    }
                    if (state == PeerConnection.IceConnectionState.FAILED) {
                        disconnectRecoveryJobs.remove(socketId)?.cancel()
                        Log.e("WebRTC", "❌ ICE FAILED! Possible firewall/networking issue ($socketId)")
                        // v17.0: Dynamic Ice Restart on Failure with limit and delay
                        executor.execute {
                            if (callbackGeneration != mediaCallbackGeneration.get()) return@execute
                            var pc = peerConnections[socketId]
                            val retries = iceRetryCount[socketId] ?: 0
                            if (pc != null && pc.iceConnectionState() == PeerConnection.IceConnectionState.FAILED && retries < MAX_ICE_RETRIES) {
                                if (!shouldInitiateIceRecovery(socketId)) {
                                    Log.i("WebRTC", "Polite peer waiting for remote ICE restart offer ($socketId)")
                                    return@execute
                                }
                                Log.w("WebRTC", "🔄 Attempting ICE RESTART for $socketId (Attempt ${retries + 1}/$MAX_ICE_RETRIES)...")
                                iceRetryCount[socketId] = retries + 1
                            
                                asyncScope.launch {
                                    // A network hand-off or a long session may outlive the
                                    // credential used by the original allocation. Refresh it
                                    // before ICE restart instead of reusing stale ICE servers.
                                    refreshManagedIceServers(force = true)
                                    awaitManagedIceIfNeeded()
                                    delay(500)
                                    executor.execute retryPeerConnection@{
                                        if (callbackGeneration != mediaCallbackGeneration.get()) return@retryPeerConnection
                                        val activePc = peerConnections[socketId] ?: return@retryPeerConnection
                                        activePc.setConfiguration(buildRtcConfiguration(currentIceServersSnapshot()))
                                        requestOffer(
                                            socketId = socketId,
                                            pc = activePc,
                                            iceRestart = true,
                                            reason = "ice-failed-recovery",
                                            retryDelayMs = 600L
                                        )
                                    }
                                }
                            } else if (retries >= MAX_ICE_RETRIES) {
                                Log.e("WebRTC", "❌ ICE Restart limit reached for $socketId. Please check your network/firewall.")
                            }
                        }
                    }
                
                    if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                        iceRetryCount[socketId] = 0 // Reset on success
                    }
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) { 
                executor.execute signalingState@{
                    if (callbackGeneration != mediaCallbackGeneration.get()) return@signalingState
                    Log.d("WebRTC", "SIG $state ($socketId)")
                    var map = _signalingStates.value.toMutableMap()
                    map[socketId] = state
                    _signalingStates.value = map
                }
            }
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) { Log.d("WebRTC", "ICE Receiving: $b ($socketId)") }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d("WebRTC", "📡 ICE Gathering State: $state ($socketId)")
            }
            override fun onAddStream(stream: MediaStream) {
                if (callbackGeneration != mediaCallbackGeneration.get()) return
                // Compatibility with Play Store builds that still publish a
                // legacy MediaStream instead of Unified Plan transceivers.
                val tracks = stream.audioTracks.toList() + stream.videoTracks.toList()
                executor.execute {
                    if (callbackGeneration != mediaCallbackGeneration.get()) return@execute
                    tracks.forEach { handleIncomingTrack(socketId, it) }
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onRemoveStream(stream: MediaStream) {}
        })
    }

    // --- SIGNALING HANDLERS ---

    fun onOfferReceived(senderSocketId: String, remoteUserId: String, offerSdp: String) {
        executor.execute {
            var requestedKey = senderSocketId.trim().ifBlank { remoteUserId.trim() }
            var pc = getOrCreatePC(requestedKey, remoteUserId) ?: return@execute
            var peerKey = resolvePeerKey(requestedKey, remoteUserId) ?: requestedKey
            
            val collision = (makingOffer[peerKey] == true) ||
                (isSettingRemoteOfferPending[peerKey] == true) ||
                (pc.signalingState() != PeerConnection.SignalingState.STABLE)
            ignoreOffer[peerKey] = !isPolite(remoteUserId, senderSocketId) && collision
            
            if (ignoreOffer[peerKey] == true) {
                scheduleGlareRecovery(peerKey, remoteUserId)
                Log.w("WebRTC", "⚔️ GLARE: Collision! IMPOLITE peer IGNORING offer from $peerKey")
                return@execute
            }

            // Set the gate before rollback. A successful rollback may invoke
            // onRenegotiationNeeded synchronously on several WebRTC/OEM builds.
            isSettingRemoteOfferPending[peerKey] = true
            
            if (collision) {
                // POLITE PEER: Rollback then process remote offer
                Log.i("WebRTC", "🤝 GLARE: Collision! POLITE peer rolling back for $peerKey")
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d("WebRTC", "Rollback SUCCESS for $peerKey. Processing remote offer...")
                        executor.execute { processOffer(peerKey, pc, offerSdp) }
                    }
                    override fun onSetFailure(s: String?) {
                        Log.e("WebRTC", "Rollback FAILED for $peerKey: $s. Trying to proceed anyway...")
                        executor.execute { processOffer(peerKey, pc, offerSdp) }
                    }
                }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                return@execute
            }
            
            processOffer(peerKey, pc, offerSdp)
        }
    }

    private fun processOffer(socketId: String, pc: PeerConnection, sdp: String) {
        isSettingRemoteOfferPending[socketId] = true
        Log.d("WebRTC", "Setting Remote Description (OFFER) for $socketId")
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.d("WebRTC", "Remote Description (OFFER) SET successfully for $socketId")
                cancelGlareRecovery(socketId)
                ignoreOffer[socketId] = false
                makingOffer[socketId] = false
                getOrAddAudioTransceiver(pc).apply {
                    sender.setTrack(localAudioTrack, false)
                    direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                }
                getOrAddVideoTransceiver(pc).apply {
                    sender.setTrack(activeOutgoingVideoTrack(), false)
                    direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                }
                drainIceQueue(socketId, pc)
                createAnswer(socketId, pc)
            }
            override fun onSetFailure(s: String?) {
                cancelGlareRecovery(socketId)
                ignoreOffer[socketId] = false
                isSettingRemoteOfferPending[socketId] = false
                makingOffer[socketId] = false
                Log.e("WebRTC", "Remote Description (OFFER) FAILED: $s ($socketId)")
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun onAnswerReceived(senderSocketId: String, answerSdp: String, remoteUserId: String = "") {
        executor.execute {
            var requestedKey = senderSocketId.trim().ifBlank { remoteUserId.trim() }
            var peerKey = resolvePeerKey(requestedKey, remoteUserId) ?: requestedKey
            if (
                remoteUserId.isNotBlank() &&
                requestedKey.isNotBlank() &&
                requestedKey != remoteUserId &&
                peerKey != requestedKey &&
                peerConnections.containsKey(peerKey)
            ) {
                migratePeerKey(peerKey, requestedKey, remoteUserId)
                peerKey = resolvePeerKey(requestedKey, remoteUserId) ?: requestedKey
            }

            var pc = peerConnections[peerKey]
            if (pc == null) {
                Log.w("WebRTC", "Remote answer ignored, no peer connection found for sender=$senderSocketId userId=$remoteUserId. Known peers=${peerConnections.keys}")
                return@execute
            }
            if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                Log.d("WebRTC", "Ignoring remote answer without a pending local offer")
                return@execute
            }
            Log.d("WebRTC", "Setting Remote Description (ANSWER) for $peerKey")
            isSettingRemoteAnswerPending[peerKey] = true
            offerRetryJobs.remove(peerKey)?.cancel()
            pc.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    localOfferTimeoutJobs.remove(peerKey)?.cancel()
                    Log.d("WebRTC", "Remote Description (ANSWER) SET successfully for $peerKey")
                    isSettingRemoteAnswerPending[peerKey] = false
                    cancelGlareRecovery(peerKey)
                    ignoreOffer[peerKey] = false
                    drainIceQueue(peerKey, pc)
                    makingOffer[peerKey] = false 
                }
                override fun onSetFailure(s: String?) { 
                    Log.e("WebRTC", "Remote Description (ANSWER) FAILED: $s ($peerKey)")
                    isSettingRemoteAnswerPending[peerKey] = false
                    cancelGlareRecovery(peerKey)
                    ignoreOffer[peerKey] = false
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        }
    }

    fun onIceCandidateReceived(socketId: String, json: JSONObject, remoteUserId: String = "") {
        executor.execute {
            var requestedKey = socketId.trim().ifBlank { remoteUserId.trim() }
            var peerKey = resolvePeerKey(requestedKey, remoteUserId) ?: requestedKey
            if (
                remoteUserId.isNotBlank() &&
                requestedKey.isNotBlank() &&
                requestedKey != remoteUserId &&
                peerKey != requestedKey &&
                peerConnections.containsKey(peerKey)
            ) {
                migratePeerKey(peerKey, requestedKey, remoteUserId)
                peerKey = resolvePeerKey(requestedKey, remoteUserId) ?: requestedKey
            }

            var pc = peerConnections[peerKey]
            val candidate = IceCandidate(json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate"))
            
            if (ignoreOffer[peerKey] == true) {
                Log.d("WebRTC", "Ignoring candidate because of active GLARE/ignoreOffer ($peerKey)")
                return@execute
            }

            if (pc != null) {
                // BUG FIX: Add if we have remote description, regardless of stability
                if (pc.remoteDescription != null && isSettingRemoteAnswerPending[peerKey] != true) {
                    val candidateStr = json.getString("candidate")
                    var parts = candidateStr.split(' ')
                    var type = if (parts.size > 7) {
                        when (parts[7]) {
                            "host" -> "HOST"
                            "srflx" -> "SRFLX"
                            "relay" -> "RELAY"
                            else -> "UNK"
                        }
                    } else "UNK"
                    
                    val logPart = if (parts.size > 7) parts[7] else "unknown"
                    Log.d("WebRTC", "Adding ICE Candidate [$type]: $logPart ($peerKey)")
                    pc.addIceCandidate(candidate)
                } else {
                    iceCandidateQueue.getOrPut(peerKey) { mutableListOf() }.add(candidate)
                    Log.d("WebRTC", "Queueing ICE Candidate (no remote desc) ($peerKey)")
                }
            } else {
                iceCandidateQueue.getOrPut(peerKey) { mutableListOf() }.add(candidate)
                Log.d("WebRTC", "Queueing ICE Candidate (PC null) ($peerKey)")
            }
        }
    }

    private fun drainIceQueue(socketId: String, pc: PeerConnection) {
        iceCandidateQueue[socketId]?.forEach { pc.addIceCandidate(it) }
        iceCandidateQueue.remove(socketId)
    }

    private fun preferCompatibleVideoCodec(desc: SessionDescription): SessionDescription {
        val vp8Preferred = preferCodec(desc.description, media = "video", codec = "VP8")
        var finalSdp = if (vp8Preferred != desc.description) {
            vp8Preferred
        } else {
            preferCodec(desc.description, media = "video", codec = "H264")
        }
        return if (finalSdp == desc.description) {
            desc
        } else {
            SessionDescription(desc.type, finalSdp)
        }
    }

    private fun preferCodec(
        sdpDescription: String,
        media: String,
        codec: String
    ): String {
        val lines = sdpDescription.split("\r\n").toMutableList()
        var mediaLineIndex = lines.indexOfFirst { it.startsWith("m=$media ") }
        if (mediaLineIndex == -1) return sdpDescription

        val codecPayloads = lines
            .filter { it.startsWith("a=rtpmap:", ignoreCase = true) && it.contains(codec, ignoreCase = true) }
            .mapNotNull { line ->
                line.substringAfter("a=rtpmap:", "")
                    .substringBefore(' ')
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
        if (codecPayloads.isEmpty()) return sdpDescription

        val mediaLineParts = lines[mediaLineIndex].split(' ').toMutableList()
        if (mediaLineParts.size <= 3) return sdpDescription

        val header = mediaLineParts.take(3)
        var payloads = mediaLineParts.drop(3)
        val reorderedPayloads = codecPayloads.filter { it in payloads } + payloads.filterNot { it in codecPayloads }
        if (payloads == reorderedPayloads) return sdpDescription

        lines[mediaLineIndex] = (header + reorderedPayloads).joinToString(" ")
        return lines.joinToString("\r\n")
    }

    private fun createOffer(socketId: String, pc: PeerConnection, iceRestart: Boolean = false) {
        var constraints = MediaConstraints()
        if (iceRestart) {
            constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(d: SessionDescription?) {
                var desc = d
                if (desc == null) {
                    makingOffer[socketId] = false
                    Log.e("WebRTC", "Local Offer CREATE returned null desc ($socketId)")
                    return
                }
                var preferredDesc = preferCompatibleVideoCodec(desc)
                Log.d("WebRTC", "Local Offer CREATED for $socketId")
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d("WebRTC", "Local Offer SET for $socketId")
                        // v24.3: Apply Bitrate control after setting local description
                        setMaxBitrate(pc, socketId)
                        signalingEvents.onSendOffer(socketId, JSONObject().apply { 
                            put("type", "offer")
                            put("sdp", preferredDesc.description)
                            put("userId", currentUserId)
                        })
                        makingOffer[socketId] = false
                        localOfferTimeoutJobs.remove(socketId)?.cancel()
                        localOfferTimeoutJobs[socketId] = asyncScope.launch {
                            // TURN discovery may take up to ten seconds on a
                            // new network. Do not roll back while the peer is
                            // still fetching credentials and preparing its answer.
                            delay(20_000L)
                            executor.execute {
                                localOfferTimeoutJobs.remove(socketId)
                                val activePc = peerConnections[socketId] ?: return@execute
                                if (activePc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                                    return@execute
                                }
                                Log.w("WebRTC", "Offer answer timeout for $socketId; rolling back")
                                activePc.setLocalDescription(object : SdpObserverAdapter() {
                                    override fun onSetSuccess() {
                                        makingOffer[socketId] = false
                                    }

                                    override fun onSetFailure(s: String?) {
                                        Log.e("WebRTC", "Offer timeout rollback failed for $socketId: $s")
                                    }
                                }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                            }
                        }
                    }
                    override fun onSetFailure(s: String?) {
                        makingOffer[socketId] = false
                        Log.e("WebRTC", "Local Offer SET FAILED: $s ($socketId)")
                    }
                }, preferredDesc)
            }
            override fun onCreateFailure(s: String?) {
                makingOffer[socketId] = false
                Log.e("WebRTC", "Local Offer CREATE FAILED: $s ($socketId)")
            }
        }, constraints)
    }

    private fun createAnswer(socketId: String, pc: PeerConnection) {
        var constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(d: SessionDescription?) {
                var desc = d
                if (desc == null) {
                    isSettingRemoteOfferPending[socketId] = false
                    Log.e("WebRTC", "Local Answer CREATE returned null desc ($socketId)")
                    return
                }
                var preferredDesc = preferCompatibleVideoCodec(desc)
                Log.d("WebRTC", "Local Answer CREATED for $socketId")
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        isSettingRemoteOfferPending[socketId] = false
                        Log.d("WebRTC", "Local Answer SET for $socketId")
                        // v24.3: Apply Bitrate control after setting local description
                        setMaxBitrate(pc, socketId)
                        signalingEvents.onSendAnswer(socketId, JSONObject().apply { 
                            put("type", "answer")
                            put("sdp", preferredDesc.description)
                            put("userId", currentUserId)
                        })
                    }
                    override fun onSetFailure(s: String?) {
                        isSettingRemoteOfferPending[socketId] = false
                        Log.e("WebRTC", "Local Answer SET FAILED: $s ($socketId)")
                    }
                }, preferredDesc)
            }
            override fun onCreateFailure(s: String?) {
                isSettingRemoteOfferPending[socketId] = false
                Log.e("WebRTC", "Local Answer CREATE FAILED: $s ($socketId)")
            }
        }, constraints)
    }

    // --- MEDIA CONTROL ---

    fun setVideoEnabled(enabled: Boolean) {
        executor.execute {
            if (outgoingVideoEnabled == enabled && _localVideoTrackFlow.value != null == enabled) return@execute
            outgoingVideoEnabled = enabled
            if (enabled) {
                if (_localVideoTrackFlow.value == null) {
                    startVideo(isScreen = false)
                } else {
                    _localVideoTrackFlow.value?.setEnabled(true)
                }
            } else {
                _localVideoTrackFlow.value?.setEnabled(false)
            }
            updateLocalVideoSenders(if (enabled) _localVideoTrackFlow.value else null)
        }
    }

    fun switchCamera(onComplete: (Boolean) -> Unit = {}): Boolean {
        val capturer = videoCapturer as? CameraVideoCapturer ?: return false
        if (!cameraSwitchInProgress.compareAndSet(false, true)) return false
        executor.execute {
            runCatching {
                capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        cameraSwitchInProgress.set(false)
                        onComplete(true)
                    }

                    override fun onCameraSwitchError(errorDescription: String?) {
                        cameraSwitchInProgress.set(false)
                        Log.e("WebRTCManager", "Camera switch failed: ${errorDescription.orEmpty()}")
                        onComplete(false)
                    }
                })
            }.onFailure { error ->
                cameraSwitchInProgress.set(false)
                Log.e("WebRTCManager", "Camera switch threw", error)
                onComplete(false)
            }
        }
        return true
    }

    fun setCameraFilter(filter: LiveCameraFilter) {
        selectedCameraFilter = filter
        executor.execute {
            cameraFilterProcessor?.select(filter)
        }
        if (cameraFilterProcessor == null) {
            cameraFilterPerformanceListener?.invoke(
                if (filter == LiveCameraFilter.NONE) {
                    LiveFilterPerformanceState.NATURAL
                } else {
                    LiveFilterPerformanceState.FULL
                }
            )
        }
    }

    fun setCameraAdjustments(adjustments: LiveCameraAdjustments) {
        selectedCameraAdjustments = adjustments.normalized()
        executor.execute {
            cameraFilterProcessor?.selectAdjustments(selectedCameraAdjustments)
        }
        if (cameraFilterProcessor == null && selectedCameraFilter == LiveCameraFilter.NONE) {
            cameraFilterPerformanceListener?.invoke(
                if (selectedCameraAdjustments.hasVisibleEffect) {
                    LiveFilterPerformanceState.FULL
                } else {
                    LiveFilterPerformanceState.NATURAL
                }
            )
        }
    }

    fun setCameraFilterPerformanceListener(
        listener: ((LiveFilterPerformanceState) -> Unit)?
    ) {
        cameraFilterPerformanceListener = listener
        cameraFilterProcessor?.let { processor ->
            listener?.invoke(processor.currentPerformanceState)
        }
    }


    fun startVideo(
        isScreen: Boolean,
        intent: android.content.Intent? = null,
        isFront: Boolean = true,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        targetFps: Int? = null,
        onProjectionStopped: () -> Unit = {},
        onComplete: (Boolean) -> Unit = {}
    ) {
        executor.execute(onUnavailable = { onComplete(false) }) {
            var completionSent = false
            val previousCapturer = videoCapturer
            val previousHelper = surfaceTextureHelper
            val previousSource = localVideoSource
            val previousTrack = _localVideoTrackFlow.value
            val previousCameraFilterProcessor = cameraFilterProcessor
            var candidateCapturer: VideoCapturer? = null
            var candidateHelper: SurfaceTextureHelper? = null
            var candidateSource: VideoSource? = null
            var candidateTrack: VideoTrack? = null
            var candidateCameraFilterProcessor: LiveCameraFilterProcessor? = null
            var committed = false
            fun complete(success: Boolean) {
                if (!completionSent) {
                    completionSent = true
                    onComplete(success)
                }
            }
            try {
                Log.d("WebRTCManager", "Starting video: screen=$isScreen")

                ensureEngineReady()
                fun makeEven(value: Int): Int = (value / 2) * 2

                val metrics = context.resources.displayMetrics
                val requestedCameraProfile = nativeCameraProfile(deviceTier, targetWidth ?: 960,
                    targetHeight ?: 540, targetFps ?: 60)
                val cameraProfile = if (isScreen) requestedCameraProfile else
                    supportedNativeCameraProfile(requestedCameraProfile, supportedCameraFormats(isFront))
                val fps = if (isScreen) (targetFps ?: 15).coerceIn(10, 30) else cameraProfile.fps
                val (finalWidth, finalHeight) = if (isScreen) {
                    // Preserve aspect ratio and fit within the requested bounds.
                    val srcW = metrics.widthPixels.coerceAtLeast(1)
                    val srcH = metrics.heightPixels.coerceAtLeast(1)
                    val boundW = (targetWidth ?: 1280).coerceAtLeast(320)
                    val boundH = (targetHeight ?: 720).coerceAtLeast(320)
                    val scale = minOf(boundW.toFloat() / srcW.toFloat(), boundH.toFloat() / srcH.toFloat())
                        .coerceIn(0.1f, 1f)
                    makeEven((srcW * scale).toInt().coerceAtLeast(320)) to
                        makeEven((srcH * scale).toInt().coerceAtLeast(320))
                } else {
                    cameraProfile.width to cameraProfile.height
                }

                // Reconfiguring an existing camera in place avoids opening two
                // camera devices concurrently, which fails on many OEM drivers.
                val existingCamera = previousCapturer as? CameraVideoCapturer
                if (!isScreen && existingCamera != null && previousTrack != null) {
                    val changed = runCatching {
                        existingCamera.changeCaptureFormat(finalWidth, finalHeight, fps)
                        previousTrack.setEnabled(true)
                        updateLocalVideoSenders(activeOutgoingVideoTrack())
                    }.isSuccess
                    complete(changed)
                    return@execute
                }

                // Build the replacement pipeline first so source switches do not blank the UI.
                var screenCapturerRef: VideoCapturer? = null
                val capturer = if (isScreen) {
                    val projectionIntent = intent ?: run {
                        Log.e("WebRTCManager", "Screen share requested without MediaProjection intent")
                        complete(false)
                        return@execute
                    }
                    ScreenCapturerAndroid(
                        projectionIntent,
                        object : MediaProjection.Callback() {
                            override fun onStop() {
                                executor.execute {
                                    if (videoCapturer === screenCapturerRef) {
                                        onProjectionStopped()
                                    }
                                }
                            }
                        }
                    ).also { screenCapturerRef = it }
                } else {
                    createCameraCapturer(isFront)
                } ?: run {
                    complete(false)
                    return@execute
                }
                candidateCapturer = capturer

                var f = factory ?: run {
                    complete(false)
                    return@execute
                }
                val source = f.createVideoSource(isScreen)
                candidateSource = source
                if (!isScreen) {
                    candidateCameraFilterProcessor = LiveCameraFilterProcessor(
                        initialFilter = selectedCameraFilter,
                        initialAdjustments = selectedCameraAdjustments,
                        onPerformanceStateChanged = { state ->
                            cameraFilterPerformanceListener?.invoke(state)
                        }
                    )
                        .also(source::setVideoProcessor)
                }
                val helper = SurfaceTextureHelper.create("CaptureThread", rootEglBase?.eglBaseContext)
                candidateHelper = helper

                capturer.initialize(helper, context, source.capturerObserver)

                Log.i("WebRTCManager", "Starting capturer: ${finalWidth}x$finalHeight @${fps}fps (Screen: $isScreen)")
                val captureProfiles = if (isScreen) {
                    listOf(
                        Triple(finalWidth, finalHeight, fps.coerceAtMost(15)),
                        Triple(makeEven(1280), makeEven(720), 15),
                        Triple(makeEven(960), makeEven(540), 15),
                        Triple(makeEven(640), makeEven(360), 12)
                    )
                } else {
                    nativeCameraFallbacks(cameraProfile).map { Triple(it.width, it.height, it.fps) }
                }.distinct()

                var startedCapture = false
                captureProfiles.forEach { (width, height, profileFps) ->
                    if (startedCapture) return@forEach
                    try {
                        capturer.startCapture(width, height, profileFps)
                        startedCapture = true
                        Log.i("WebRTCManager", "Capture started with ${width}x$height @${profileFps}fps")
                    } catch (e: Exception) {
                        runCatching { capturer.stopCapture() }
                        Log.w("WebRTCManager", "Capture profile ${width}x$height @${profileFps}fps failed: ${e.message}")
                    }
                }

                if (!startedCapture) {
                    releaseVideoPipeline(capturer, helper, source)
                    candidateCapturer = null
                    candidateHelper = null
                    candidateSource = null
                    Log.e("WebRTCManager", "No compatible capture profile could be started")
                    complete(false)
                    return@execute
                }

                val trackId = if (isScreen) "SCREEN_01" else "CAMERA_01"
                val track = f.createVideoTrack(trackId, source).apply { setEnabled(true) }
                candidateTrack = track

                // 2. Swap atomically once the new track is actually ready.
                videoCapturer = capturer
                surfaceTextureHelper = helper
                localVideoSource = source
                cameraFilterProcessor = candidateCameraFilterProcessor
                _localVideoTrackFlow.value = track
                updateLocalVideoSenders(activeOutgoingVideoTrack())
                committed = true

                runCatching {
                    releaseReplacedVideoPipelineAfterRendererDetach(
                        track = previousTrack?.takeIf { it !== track },
                        capturer = previousCapturer,
                        helper = previousHelper,
                        source = previousSource
                    )
                }
                
                // replaceTrack publishes through the video m-line negotiated at
                // connection setup and avoids an offer/answer glare race.
                complete(true)
            } catch (e: Exception) {
                Log.e("WebRTCManager", "Error starting video: ${e.message}")
                complete(false)
            } finally {
                if (!committed && candidateCapturer != null) {
                    if (videoCapturer === candidateCapturer) {
                        videoCapturer = previousCapturer
                        surfaceTextureHelper = previousHelper
                        localVideoSource = previousSource
                        cameraFilterProcessor = previousCameraFilterProcessor
                        _localVideoTrackFlow.value = previousTrack
                        runCatching { updateLocalVideoSenders(activeOutgoingVideoTrack()) }
                    }
                    releaseReplacedVideoPipelineAfterRendererDetach(
                        track = candidateTrack,
                        capturer = candidateCapturer,
                        helper = candidateHelper,
                        source = candidateSource
                    )
                }
            }
        }
    }

    suspend fun startVideoAwait(
        isScreen: Boolean,
        intent: android.content.Intent? = null,
        isFront: Boolean = true,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        targetFps: Int? = null,
        onProjectionStopped: () -> Unit = {}
    ): Boolean = suspendCancellableCoroutine { continuation ->
        startVideo(
            isScreen = isScreen,
            intent = intent,
            isFront = isFront,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            targetFps = targetFps,
            onProjectionStopped = onProjectionStopped,
            onComplete = { started ->
                if (continuation.isActive) continuation.resume(started)
            }
        )
    }

    fun stopVideo() {
        executor.execute { stopVideoInternal() }
    }

    suspend fun stopVideoAwait() {
        suspendCancellableCoroutine { continuation ->
            executor.execute(onUnavailable = { if (continuation.isActive) continuation.resume(Unit) }) {
                val failure = runCatching { stopVideoInternal() }.exceptionOrNull()
                if (continuation.isActive) {
                    if (failure == null) continuation.resume(Unit)
                    else continuation.resumeWithException(failure)
                }
            }
        }
    }

    private fun stopVideoInternal() {
        val previousCapturer = videoCapturer
        val previousHelper = surfaceTextureHelper
        val previousSource = localVideoSource
        val previousTrack = _localVideoTrackFlow.value

        videoCapturer = null
        surfaceTextureHelper = null
        localVideoSource = null
        cameraFilterProcessor = null
        _localVideoTrackFlow.value = null
        updateLocalVideoSenders(null)
        releaseReplacedVideoPipelineAfterRendererDetach(
            track = previousTrack,
            capturer = previousCapturer,
            helper = previousHelper,
            source = previousSource
        )
    }

    private val renegotiateScope = newSafeScope("WebRTCManagerRenegotiate", Dispatchers.Default)
    private var renegotiateJob: Job? = null

    private fun renegotiateAll(iceRestart: Boolean = false, force: Boolean = false) {
        renegotiateJob?.cancel()
        renegotiateJob = renegotiateScope.launch {
            delay(100) // Debounce reduced for faster sync
            executor.execute {
                val vt = activeOutgoingVideoTrack()
                Log.d("WebRTCManager", "Renegotiating all connections with videoTrack=${vt != null}")
                
                peerConnections.forEach { (id, pc) ->
                    var videoTransceiver = getOrAddVideoTransceiver(pc)
                    val previousTrackId = videoTransceiver.sender.track()?.id()
                    videoTransceiver.sender.setTrack(vt, false)
                    val oldDir = videoTransceiver.direction
                    val newDirection = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    videoTransceiver.direction = newDirection
                    
                    Log.d("WebRTCManager", "Connection $id: Video direction set to ${videoTransceiver.direction}")
                    
                    // Aggressive re-negotiation: ensure the other side sees the new track state immediately.
                    // Rollback logic handles any glare/collision.
                    var audioTransceiver = getOrAddAudioTransceiver(pc)
                    audioTransceiver.sender.setTrack(localAudioTrack, false)
                    if (audioTransceiver.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                        audioTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    }

                    val trackChanged = previousTrackId != vt?.id()
                    val directionChanged = oldDir != newDirection
                    if (!force && !directionChanged && !trackChanged && !iceRestart) {
                        Log.d("WebRTC", "Skipping renegotiation for $id: no effective media change")
                        return@forEach
                    }

                    requestOffer(
                        socketId = id,
                        pc = pc,
                        iceRestart = iceRestart,
                        reason = "renegotiate-all",
                        retryDelayMs = 350L
                    )
                }
            }
        }
    }

    private fun createCameraCapturer(isFront: Boolean): VideoCapturer? {
        val enumerators = buildList<CameraEnumerator> {
            if (Camera2Enumerator.isSupported(context)) {
                add(Camera2Enumerator(context))
            }
            add(Camera1Enumerator(true))
        }

        enumerators.forEach { enumerator ->
            val preferredDevices = enumerator.deviceNames.filter {
                if (isFront) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
            } + enumerator.deviceNames

            preferredDevices.distinct().forEach { deviceName ->
                val capturer = runCatching {
                    if (enumerator is Camera2Enumerator) NovvinCamera2Capturer(context, deviceName, null)
                    else enumerator.createCapturer(deviceName, null)
                }.getOrNull()
                if (capturer != null) {
                    Log.i("WebRTCManager", "Using camera capturer: $deviceName (${enumerator.javaClass.simpleName})")
                    return capturer
                }
            }
        }

        Log.e("WebRTCManager", "No compatible camera capturer found")
        return null
    }

    private fun supportedCameraFormats(isFront: Boolean): List<NativeCameraFormat> = runCatching {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context))
            Camera2Enumerator(context) else Camera1Enumerator(true)
        val camera = enumerator.deviceNames.firstOrNull {
            if (isFront) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: enumerator.deviceNames.firstOrNull() ?: return@runCatching emptyList()
        // Camera2's minimum frame duration alone can overstate the AE limit.
        val aeMaximum = if (enumerator is Camera2Enumerator) runCatching {
            val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            manager.getCameraCharacteristics(camera)
                .get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.maxOfOrNull { it.upper }?.let { if (it >= 1000) it / 1000 else it }
        }.getOrNull() else null
        enumerator.getSupportedFormats(camera).orEmpty().map { format ->
            NativeCameraFormat(format.width, format.height,
                minOf(format.framerate.max / 1000, aeMaximum ?: 60))
        }
    }.getOrElse { error ->
        Log.w("WebRTCManager", "Cannot read camera cadence; using conservative capture", error)
        emptyList()
    }

    fun destroy(onComplete: () -> Unit = {}) {
        synchronized(destructionCallbacks) {
            if (destroyed) {
                runCatching { onComplete() }
                return
            }
            destructionCallbacks += onComplete
        }
        if (!destroyRequested.compareAndSet(false, true)) return
        executor.execute {
            hardResetInternal()
            // Publish null/empty tracks before releasing the factory and EGL.
            // The renderer needs a frame to remove its sinks on the main thread.
            asyncScope.launch {
                delay(rendererDetachGraceMs)
                executor.execute {
                    try { destroyInternal() } finally {
                        val callbacks = synchronized(destructionCallbacks) {
                            destroyed = true
                            destructionCallbacks.toList().also { destructionCallbacks.clear() }
                        }
                        callbacks.forEach { callback -> runCatching { callback() } }
                    }
                }
            }
        }
    }


    fun onUserLeft(sid: String) {
        executor.execute {
            var peerKey = resolvePeerKey(sid, sid) ?: sid.trim()
            Log.d("WebRTCManager", "Cleaning up state for disconnected user: $sid (resolved=$peerKey)")
            detachPeerInternal(peerKey)?.let(::disposePeerAfterRendererDetach)
        }
    }

    private fun detachPeerInternal(peerKey: String): PeerConnection? {
        val peerToDispose = peerConnections.remove(peerKey)
        _remoteVideoTracks.value = _remoteVideoTracks.value.toMutableMap().apply { remove(peerKey) }
        _peerConnectionStates.value = _peerConnectionStates.value.toMutableMap().apply { remove(peerKey) }
        _signalingStates.value = _signalingStates.value.toMutableMap().apply { remove(peerKey) }
        _debugInfo.value = _debugInfo.value.toMutableMap().apply { remove(peerKey) }
        remoteUserIds.remove(peerKey)
        remoteAudioTracks.remove(peerKey)?.let { track -> runCatching { track.setEnabled(false) } }
        makingOffer.remove(peerKey)
        iceRetryCount.remove(peerKey)
        iceCandidateQueue.remove(peerKey)
        disconnectRecoveryJobs.remove(peerKey)?.cancel()
        offerRetryJobs.remove(peerKey)?.cancel()
        localOfferTimeoutJobs.remove(peerKey)?.cancel()
        glareRecoveryJobs.remove(peerKey)?.cancel()
        ignoreOffer.remove(peerKey)
        isSettingRemoteOfferPending.remove(peerKey)
        isSettingRemoteAnswerPending.remove(peerKey)
        return peerToDispose
    }

    private fun disposePeerAfterRendererDetach(peer: PeerConnection) {
        deferredPeerDisposals.add(peer)
        asyncScope.launch {
            delay(rendererDetachGraceMs)
            runCatching {
                executor.execute {
                    if (deferredPeerDisposals.claim(peer)) runCatching { peer.dispose() }
                }
            }
        }
    }

    private fun drainDeferredPeerDisposals() {
        deferredPeerDisposals.drain().forEach { peer -> runCatching { peer.dispose() } }
    }

    private fun drainDeferredVideoCleanups() {
        val pending = synchronized(deferredVideoCleanupLock) {
            deferredVideoCleanups.toList().also { deferredVideoCleanups.clear() }
        }
        pending.forEach { cleanup ->
            runCatching { cleanup.track?.dispose() }
            releaseVideoPipeline(cleanup.capturer, cleanup.helper, cleanup.source)
        }
    }
    
    fun getEglBaseContext() = rootEglBase?.eglBaseContext
    fun setAudioEnabled(e: Boolean) { 
        executor.execute { 
            try {
                outgoingAudioEnabled = e
                localAudioTrack?.setEnabled(e)
                peerConnections.values.forEach { pc ->
                    pc.setAudioRecording(e)
                    pc.transceivers
                        .filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
                        .forEach { transceiver ->
                            transceiver.sender.setTrack(localAudioTrack, false)
                            if (transceiver.direction == RtpTransceiver.RtpTransceiverDirection.INACTIVE) {
                                transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                            }
                        }
                }
                Log.d("WebRTC", "Audio track ${if(e) "ENABLED" else "DISABLED"}")
            } catch (ex: Exception) {
                Log.e("WebRTC", "Error in setAudioEnabled: ${ex.message}")
            }
        } 
    }

    fun setVideoSendEnabled(enabled: Boolean, renegotiate: Boolean = true) {
        executor.execute {
            outgoingVideoEnabled = enabled
            updateLocalVideoSenders(activeOutgoingVideoTrack())
            if (renegotiate) {
                renegotiateAll(iceRestart = false, force = false)
            }
        }
    }

    fun hasLocalVideoTrack(): Boolean {
        return _localVideoTrackFlow.value != null
    }

    fun setRemoteAudioPlaybackEnabled(enabled: Boolean) {
        executor.execute {
            remoteAudioPlaybackEnabled = enabled
            peerConnections.values.forEach { it.setAudioPlayout(enabled) }
            remoteAudioTracks.values.forEach { track ->
                runCatching {
                    track.setEnabled(enabled)
                    track.setVolume(if (enabled) REMOTE_AUDIO_GAIN else 0.0)
                }
            }
            Log.d("WebRTC", "Remote audio playback ${if (enabled) "ENABLED" else "DISABLED"}")
        }
    }
    fun setAudioVolume(s: String, v: Double) {
        executor.execute {
            remoteAudioTracks[s]?.setVolume(v.coerceIn(0.0, 1.0) * REMOTE_AUDIO_GAIN)
        }
    }
    
    /**
     * Gets the local audio level. 
     * v22.0: Now attempts to fetch real stats from active peer connections.
     */
    fun getLocalAudioLevel(): Double {
        // UI polling must not dereference a native track while the media
        // executor is disposing it. Only read cached Java/Kotlin state here.
        if (factory == null || localAudioTrack == null || !outgoingAudioEnabled) return 0.0
        return if (peerConnections.isEmpty()) 0.1 else _lastLocalLevel
    }

    @Volatile private var _lastLocalLevel: Double = 0.0

    private fun updateLocalAudioLevelFromStats() {
        if (executor.isShutdown) return
        runCatching {
            executor.execute {
                val entry = peerConnections.entries.firstOrNull {
                    it.value.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED
                } ?: peerConnections.entries.firstOrNull() ?: return@execute
                val peerId = entry.key
                val pc = entry.value
                runCatching {
                    pc.getStats { reports ->
                        if (peerConnections[peerId] !== pc) return@getStats
                        _lastLocalLevel = reports.statsMap.values
                            .filter { it.type == "media-source" && it.members["kind"] == "audio" }
                            .maxOfOrNull { (it.members["audioLevel"] as? Number)?.toDouble() ?: 0.0 } ?: 0.0
                    }
                }
            }
        }
    }

    fun setSpeakerphoneOn(on: Boolean) { 
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            routeSpeakerphone(audioManager, enabled = on)
        } catch (e: Exception) {
            Log.w("WebRTCManager", "Failed to route speakerphone to on=$on", e)
        }
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) {}
        override fun onSetFailure(s: String?) {}
    }
    
    private fun updateDebugInfo(id: String, info: String) {
        var map = _debugInfo.value.toMutableMap()
        map[id] = info
        _debugInfo.value = map
    }

    // v19.0: Remote Audio Level Detection via RTCStats
    fun getRemoteAudioLevels(callback: (Map<String, Double>) -> Unit) {
        if (executor.isShutdown) {
            callback(emptyMap())
            return
        }
        executor.execute {
            val peers = peerConnections.toMap()
            val batch = RtcAudioLevelBatch(peers.keys, callback)
            if (peers.isEmpty()) {
                batch.finish()
                return@execute
            }
            statsScope.launch {
                delay(200)
                batch.finish()
            }
            peers.forEach { (socketId, pc) ->
                runCatching { pc.getStats { report ->
                    var maxLevel = 0.0
                    // Iterate and find audio levels
                    // Standard WebRTC Stats: "inbound-rtp" or "track"
                    // We look for type "inbound-rtp" and mediaType "audio"
                    // Property: audioLevel (0.0 to 1.0)
                    // Or "track" with kind "audio" and audioLevel
                    
                    val statsMap = report.statsMap
                    for (stats in statsMap.values) {
                        if (stats.type == "inbound-rtp" && 
                           (stats.members["kind"] as? String == "audio" || stats.members["mediaType"] as? String == "audio")) {
                                
                            var level = stats.members["audioLevel"]
                            if (level is Double) {
                                if (level > maxLevel) maxLevel = level
                            }
                        }
                        // Fallback/Legacy
                        if (stats.type == "track" && stats.members["kind"] as? String == "audio") {
                             var level = stats.members["audioLevel"]
                             if (level is Double) {
                                 if (level > maxLevel) maxLevel = level
                             }
                        }
                    }
                    
                    batch.accept(socketId, maxLevel)
                } }.onFailure { batch.accept(socketId, 0.0) }
            }
        }
    }

    // Debug builds only. Never include SDP, candidates, tokens, addresses or account IDs.
    private fun logMediaStats() {
        if (executor.isShutdown) return
        executor.execute {
            peerConnections.values.toList().forEachIndexed { index, pc ->
                runCatching { pc.getStats { report ->
                    report.statsMap.values.forEach { stats ->
                        if (stats.type == "inbound-rtp" || stats.type == "outbound-rtp") {
                            val m = stats.members
                            val fields = listOf("bytesReceived", "bytesSent", "packetsReceived",
                                "packetsLost", "framesDecoded", "framesEncoded", "framesPerSecond",
                                "jitter", "totalDecodeTime", "jitterBufferDelay", "jitterBufferEmittedCount")
                                .mapNotNull { key -> (m[key] as? Number)?.let { "$key=$it" } }
                                .joinToString(" ")
                            Log.d("RtcMediaStats", "peer=$index ${stats.type} kind=${m["kind"] ?: m["mediaType"]} $fields")
                        }
                        if (stats.type == "candidate-pair" && stats.members["state"] == "succeeded") {
                            val rtt = stats.members["currentRoundTripTime"] as? Number
                            if (rtt != null) Log.d("RtcMediaStats", "peer=$index rttSeconds=$rtt")
                        }
                    }
                } }
            }
        }
    }

    private fun isPolite(remoteUserId: String, remoteSocketId: String): Boolean {
        // Tie-breaker using IDs (Standard Perfect Negotiation)
        // If our ID is smaller lexicographically, we are "polite" (yield to the other side)
        return (currentUserId < remoteUserId) || (currentUserId == remoteUserId && localSocketId < remoteSocketId)
    }

    private fun addIceServerFromJSON(server: JSONObject) {
        try {
            val urlsObj = server.get("urls")
            val urlsList = mutableListOf<String>()
            
            if (urlsObj is JSONArray) {
                for (j in 0 until urlsObj.length()) urlsList.add(urlsObj.getString(j))
            } else {
                urlsList.add(urlsObj.toString())
            }

            val username = if (server.has("username")) server.getString("username") else null
            val credential = if (server.has("credential")) server.getString("credential") else null

            urlsList
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { url ->
                    val builder = PeerConnection.IceServer.builder(url)
                    username?.let { builder.setUsername(it) }
                    credential?.let { builder.setPassword(it) }

                    val iceServer = builder.createIceServer()
                    synchronized(dynamicIceServers) {
                        dynamicIceServers.add(iceServer)
                    }
                    Log.d("WebRTC", "   + Added ICE Node: $url")
                }

            if (urlsList.isEmpty()) {
                Log.w("WebRTC", "Managed TURN response contained an ICE server without urls")
            }
        } catch (e: Exception) {
            Log.e("WebRTC", "❌ Failed to parse individual ICE server: ${e.message}")
        }
    }

    private fun setMaxBitrate(pc: PeerConnection, socketId: String) {
        pc.senders.forEach { sender ->
            var track = sender.track() ?: return@forEach
            if (track.kind() == "video") {
                val params = sender.parameters
                if (params.encodings.isNotEmpty()) {
                    val isScreen = track.id().contains("SCREEN")
                    val maxBitrate = if (isScreen) 2_500_000 else when (deviceTier) {
                        conectate.com.util.AppDeviceTier.LOW -> 600_000
                        conectate.com.util.AppDeviceTier.BALANCED -> 1_200_000
                        conectate.com.util.AppDeviceTier.HIGH -> 1_800_000
                    }
                    // Moving cameras favor cadence; shared documents favor legibility.
                    params.degradationPreference = if (isScreen)
                        RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION else
                        RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                    params.encodings.forEach { it.maxBitrateBps = maxBitrate }
                    if (!sender.setParameters(params)) {
                        Log.w("WebRTC", "Encoder parameters were rejected for $socketId")
                    }
                    Log.d("WebRTC", "Bitrate restricted to ${maxBitrate/1000}kbps for $socketId")
                }
            }
        }
    }
}



