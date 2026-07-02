package conectate.com.data.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.signaling.SignalingConfig
import io.socket.client.Socket
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.media.projection.MediaProjection

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
    private val signalingEvents: SignalingEvents,
    private val accessTokenProvider: (() -> String?)? = null
) {
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
            if (!enabled) {
                audioManager.clearCommunicationDevice()
                return }
            var speaker = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) {
                audioManager.setCommunicationDevice(speaker)
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
    private var localSocketId: String = ""
    
    private var factory: PeerConnectionFactory? = null
    private var rootEglBase: EglBase? = runCatching { EglBase.create() }
        .onFailure { throwable ->
            Log.e("WebRTCManager", "EGL init failed video rendering will stay disabled", throwable)
        }
        .getOrNull()
    val eglContext: EglBase.Context? get() = rootEglBase?.eglBaseContext
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
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

    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private val remoteUserIds = mutableMapOf<String, String>()
    private val remoteAudioTracks = mutableMapOf<String, AudioTrack>()
    private var remoteAudioPlaybackEnabled: Boolean = true
    
    private val makingOffer = mutableMapOf<String, Boolean>()
    private val iceCandidateQueue = mutableMapOf<String, MutableList<IceCandidate>>()
    private val iceRetryCount = mutableMapOf<String, Int>()
    private val disconnectRecoveryJobs = ConcurrentHashMap<String, Job>()
    private val offerRetryJobs = ConcurrentHashMap<String, Job>()
    private val glareRecoveryJobs = ConcurrentHashMap<String, Job>()
    private val ignoreOffer = mutableMapOf<String, Boolean>()
    private val isSettingRemoteAnswerPending = mutableMapOf<String, Boolean>()
    private val MAX_ICE_RETRIES = 3
    private val managedIceWaitTimeoutMs = 3_500L
    private val glareRecoveryTimeoutMs = 2_000L
    private val lowLatencyJitterPackets = 20
    
    private val selfHostedTurnUrl = SignalingConfig.TURN_CREDENTIALS_URL.trim()
    private val hasManagedTurnConfigured = selfHostedTurnUrl.isNotBlank()
    private val dynamicIceServers = mutableListOf<PeerConnection.IceServer>()
    private val managedIceRefreshLock = Any()
    @Volatile
    private var managedIceRefreshLatch = java.util.concurrent.CountDownLatch(0)
    
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // Public STUN fallbacks used while managed TURN is loading or if it fails.
    private var iceServers = buildList {
        add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
        add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer())
    }

    private val statsScope = newSafeScope("WebRTCManagerStats", Dispatchers.Default)
    private val asyncScope = newSafeScope("WebRTCManagerAsync", Dispatchers.Default)
    
    init {
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
            
            // v22.0: Background Stats Monitor for Audio Levels
            statsScope.launch {
                while (true) {
                    delay(1000)
                    updateLocalAudioLevelFromStats()
                }
            }
        } catch (e: Exception) {
            Log.e("WebRTCManager", "❌ CRITICAL ERROR during init: ${e.message}")
            e.printStackTrace()
        }
    }

    fun setCurrentUser(userId: String, socketId: String) {
        this.currentUserId = userId
        this.localSocketId = socketId
    }

    /**
     * Hard Reset - Purges all hardware and software WebRTC state.
     * Prevents ANRs and "Zombie" media tracks.
     */
    fun hardReset() {
        executor.execute {
            Log.w("WebRTCManager", "🔥 Performing STABILIZED RESET of media engine...")
            // v6.0: We do NOT destroy the factory or ADM here.
            // Full destruction locks hardware drivers on quick re-entry.
            
            // 1. Close all PeerConnections
            peerConnections.values.forEach { it.dispose() }
            peerConnections.clear()
            
            // 2. Clear state maps
            remoteAudioTracks.clear()
            makingOffer.clear()
            iceCandidateQueue.clear()
            iceRetryCount.clear()
            disconnectRecoveryJobs.values.forEach { it.cancel() }
            disconnectRecoveryJobs.clear()
            offerRetryJobs.values.forEach { it.cancel() }
            offerRetryJobs.clear()
            glareRecoveryJobs.values.forEach { it.cancel() }
            glareRecoveryJobs.clear()
            remoteUserIds.clear() // v7.2.4: Clear User ID mapping
            _remoteVideoTracks.value = emptyMap()
            _peerConnectionStates.value = emptyMap()
            _signalingStates.value = emptyMap()
            _debugInfo.value = emptyMap() // v7.2.4: Clear Debug Info mapping
            ignoreOffer.clear()
            isSettingRemoteAnswerPending.clear()
            outgoingVideoEnabled = true
            localSocketId = ""
            
            // 3. Stop local captures but keep factory alive
            videoCapturer?.stopCapture()
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            videoCapturer?.dispose()
            videoCapturer = null
            localVideoSource?.dispose()
            localVideoSource = null
            _localVideoTrackFlow.value?.setEnabled(false)
            _localVideoTrackFlow.value = null
            
            Log.i("WebRTCManager", "✅ Media state cleared and ready for fresh re-entry")
        }
    }

    /**
     * Checks if the media engine is in a valid state for re-entry.
     * Returns true only if factory exists and we have active peer connections or are ready to accept them.
     */
    fun isHealthy(): Boolean {
        // If factory is dead, we are dead.
        if (factory == null) return false
        
        // If we have peer connections, check if any are failed.
        if (peerConnections.isNotEmpty()) {
             val hasFailures = peerConnections.values.any { 
                 it.connectionState() == PeerConnection.PeerConnectionState.FAILED ||
                 it.connectionState() == PeerConnection.PeerConnectionState.CLOSED
             }
             if (hasFailures) return false
        }
        
        // v7.2.5: Check if track is disposed
        var track = localAudioTrack
        if (track == null || track.state() == MediaStreamTrack.State.ENDED) return false
        
        return true
    }

    /**
     * Ensures local tracks are valid, recreating them if they were accidentally disposed.
     */
    /**
     * Ensures local tracks are valid, recreating them if they were accidentally disposed.
     */
    fun ensureLocalTracks() {
        executor.execute {
            var track = localAudioTrack
            if (track == null || track.state() == MediaStreamTrack.State.ENDED) {
                Log.w("WebRTC", "Audio track invalid - recreating")
                createLocalAudioTrack()
            }
        }
    }
    


    private fun initializeFactory() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())

        // PREFER SOFTWARE AEC/NS for better compatibility with budget devices (Infinix/MediaTek)
        // Hardware AEC often locks the mic on budget Android 12/13 versions.
        Log.i(
            "WebRTC",
            "Creating ADM with source=$preferredAudioInputSource, sampleRate=$preferredAudioSampleRate, lowLatency=$preferredLowLatencyAudio"
        )
        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false) 
            .setUseHardwareNoiseSuppressor(false)
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
    
    private fun destroyInternal() {
        Log.d("WebRTCManager", "Internal cleanup starting...")
        
        // Stop all captures first
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        
        localVideoSource?.dispose()
        localVideoSource = null
        _localVideoTrackFlow.value?.setEnabled(false)
        _localVideoTrackFlow.value = null
        
        localAudioTrack?.dispose()
        localAudioTrack = null
        
        peerConnections.values.forEach { it.dispose() }
        peerConnections.clear()
        disconnectRecoveryJobs.values.forEach { it.cancel() }
        disconnectRecoveryJobs.clear()
        offerRetryJobs.values.forEach { it.cancel() }
        offerRetryJobs.clear()
        glareRecoveryJobs.values.forEach { it.cancel() }
        glareRecoveryJobs.clear()
        
        remoteAudioTracks.clear()
        _remoteVideoTracks.value = emptyMap()
        _peerConnectionStates.value = emptyMap()
        _signalingStates.value = emptyMap()
        _debugInfo.value = emptyMap()
        outgoingVideoEnabled = true
        
        factory?.dispose()
        factory = null
        
        audioDeviceModule?.release()
        audioDeviceModule = null
    }

    private fun createLocalAudioTrack() {
        var f = factory ?: return
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"))
        }
        val audioSource = f.createAudioSource(audioConstraints)
        localAudioTrack = f.createAudioTrack("ARDAMSa0", audioSource)
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
            track.setVolume(if (remoteAudioPlaybackEnabled) 1.0 else 0.0)
            remoteAudioTracks[socketId] = track
            Log.i("WebRTCManager", "Remote Audio Track configured and enabled for $socketId")
        }
    }

    private fun updateLocalVideoSenders(track: VideoTrack?) {
        peerConnections.forEach { (_, pc) ->
            pc.transceivers
                .filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                .forEach { transceiver ->
                    transceiver.sender.setTrack(track, false)
                    transceiver.direction =
                        if (track != null) {
                            RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                        } else {
                            RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                        }
                }
        }
    }

    private fun activeOutgoingVideoTrack(): VideoTrack? {
        return if (outgoingVideoEnabled) _localVideoTrackFlow.value else null
    }

    private fun getOrAddAudioTransceiver(pc: PeerConnection): RtpTransceiver {
        return pc.transceivers.find { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            ?: pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                    listOf("stream")
                )
            )
    }

    private fun getOrAddVideoTransceiver(pc: PeerConnection): RtpTransceiver {
        return pc.transceivers.find { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            ?: pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
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
        glareRecoveryJobs.remove(cleanOldKey)?.let { glareRecoveryJobs[cleanNewKey] = it }
        ignoreOffer.remove(cleanOldKey)?.let { ignoreOffer[cleanNewKey] = it }
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


    fun onUserJoined(remoteSocketId: String, remoteUserId: String) {
        var rsid = remoteSocketId.trim()
        var ruid = remoteUserId.trim()
        executor.execute {
            Log.d("WebRTCManager", "onUserJoined: $ruid ($rsid)")
            var pc = getOrCreatePC(rsid, ruid) ?: return@execute
            var peerKey = resolvePeerKey(rsid, ruid) ?: rsid.ifBlank { ruid }
            
            val isPolite = isPolite(ruid, rsid)
            updateDebugInfo(peerKey, "Role: ${if(isPolite) "POLITE" else "IMPOLITE"}\nUserID: $ruid")
            
            if (!isPolite) {
                 Log.i("WebRTC", "Impolite peer initiating connection to $peerKey")
                 requestOffer(
                     socketId = peerKey,
                     pc = pc,
                     iceRestart = false,
                     reason = "user-joined"
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
        
        remoteUserIds[requestedKey] = ruid
        peerConnections[requestedKey]?.let { return it }
        
        var pc = createPeerConnection(requestedKey, ruid) ?: return null
        peerConnections[requestedKey] = pc
        
        // INITIALIZE STATE TO NEW
        var map = _peerConnectionStates.value.toMutableMap()
        map[requestedKey] = PeerConnection.IceConnectionState.NEW
        _peerConnectionStates.value = map
        
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

        // 2. Video: Start as SEND_RECV but only attach track if active
        var videoTransceiver = pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("stream"))
        )
        activeOutgoingVideoTrack()?.let { track ->
            videoTransceiver.sender.setTrack(track, false)
            videoTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            Log.i("WebRTC", "📹 Video track attached to $requestedKey")
        } ?: run {
            videoTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            Log.d("WebRTC", "📹 No local video track, setting to RECV_ONLY for $requestedKey")
        }

        pc.setAudioRecording(localAudioTrack?.enabled() ?: true)
        pc.setAudioPlayout(remoteAudioPlaybackEnabled)
        
        return pc
    }

    private fun beginManagedIceRefresh(force: Boolean): java.util.concurrent.CountDownLatch? {
        if (!force && synchronized(dynamicIceServers) { dynamicIceServers.isNotEmpty() }) {
            Log.d("WebRTC", "Skipping managed TURN refresh because ICE servers are already loaded.")
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

    private fun fetchManagedIceServers() {
        try {
            fetchSelfHostedIceServers()
        } catch (e: Exception) {
            Log.e("WebRTC", "Managed TURN fetch exception: ${e.message}")
            e.printStackTrace()
        } finally {
            Log.d(
                "WebRTC",
                "Managed TURN setup finished. Using ${if (dynamicIceServers.isNotEmpty()) "managed TURN + fallback" else "fallback ICE only"}"
            )
        }
    }

    fun refreshManagedIceServers(force: Boolean = false) {
        if (!hasManagedTurnConfigured) {
            Log.d("WebRTC", "Skipping managed TURN refresh because no provider is configured.")
            return
        }

        var refreshLatch = beginManagedIceRefresh(force) ?: return
        Log.i("WebRTC", "Refreshing managed TURN configuration (force=$force)")
        asyncScope.launch {
            try {
                fetchManagedIceServers()
            } finally {
                finishManagedIceRefresh(refreshLatch)
            }
        }
    }

    private fun fetchSelfHostedIceServers(): Boolean {
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
            var connection = java.net.URL(selfHostedTurnUrl).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                var response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                val loaded = loadIceServersFromEnvelope(jsonResponse)
                if (!loaded) {
                    Log.e("WebRTC", "Self-hosted TURN response did not include any ICE servers.")
                }
                return loaded
            }

            val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() }
            Log.e("WebRTC", "Self-hosted TURN endpoint error $responseCode: $errorMsg")
        } catch (e: Exception) {
            Log.e("WebRTC", "Self-hosted TURN fetch exception: ${e.message}")
            e.printStackTrace()
        }
        return false
    }

    private fun loadIceServersFromEnvelope(jsonResponse: JSONObject): Boolean {
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
            iceTransportsType = PeerConnection.IceTransportsType.ALL

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
        // Wait briefly for managed TURN credentials before falling back.
        try {
            var refreshLatch = activeManagedIceRefreshLatch()
            if (refreshLatch != null) {
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
                    var pc = peerConnections[socketId] ?: return@execute
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

                        if (makingOffer[socketId] == true || isSettingRemoteAnswerPending[socketId] == true) {
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
                var track = receiver?.track() ?: return
                handleIncomingTrack(socketId, track)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                var track = transceiver?.receiver?.track() ?: return
                handleIncomingTrack(socketId, track)
            }

            override fun onRemoveTrack(receiver: RtpReceiver?) {
                var track = receiver?.track() ?: return
                Log.w("WebRTCManager", "Remove Remote Track: ${track.kind()} ($socketId)")
                if (track.kind() == "video") {
                    var map = _remoteVideoTracks.value.toMutableMap()
                    map.remove(socketId)
                    _remoteVideoTracks.value = map
                }
                if (track.kind() == "audio") {
                    remoteAudioTracks.remove(socketId)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { 
                Log.d("WebRTC", "ICE $state ($socketId)") 
                var map = _peerConnectionStates.value.toMutableMap()
                map[socketId] = state
                _peerConnectionStates.value = map
                
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    disconnectRecoveryJobs.remove(socketId)?.cancel()
                    // Reinforce and check audio session on connection
                    executor.execute {
                        var am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                            am.mode = AudioManager.MODE_IN_COMMUNICATION
                            Log.w("WebRTC", "Audio mode fixed to COMMUNICATION after ICE connected")
                        }
                        
                        // v7.2.6: Reinforce transceivers to ensure local track is still active
                        var pc = peerConnections[socketId]
                        pc?.transceivers?.forEach { t ->
                            if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                                if (t.direction == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY) {
                                    t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                                }
                                t.sender.setTrack(localAudioTrack, false)
                            } else if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                                var activeVideoTrack = activeOutgoingVideoTrack()
                                t.sender.setTrack(activeVideoTrack, false)
                                t.direction = if (activeVideoTrack != null) {
                                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                                } else {
                                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                                }
                            }
                        }
                        pc?.setAudioPlayout(remoteAudioPlaybackEnabled)
                        remoteAudioTracks[socketId]?.let { remoteTrack ->
                            remoteTrack.setEnabled(remoteAudioPlaybackEnabled)
                            remoteTrack.setVolume(if (remoteAudioPlaybackEnabled) 1.0 else 0.0)
                        }
                    }
                }
                if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    disconnectRecoveryJobs.remove(socketId)?.cancel()
                    disconnectRecoveryJobs[socketId] = asyncScope.launch {
                        delay(1_500)
                        executor.execute {
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
                            videoTransceiver.direction = if (activeVideoTrack != null) {
                                RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                            } else {
                                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                            }

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
                        var pc = peerConnections[socketId]
                        val retries = iceRetryCount[socketId] ?: 0
                        if (pc != null && pc.iceConnectionState() == PeerConnection.IceConnectionState.FAILED && retries < MAX_ICE_RETRIES) {
                            if (!shouldInitiateIceRecovery(socketId)) {
                                Log.i("WebRTC", "Polite peer waiting for remote ICE restart offer ($socketId)")
                                return@execute
                            }
                            Log.w("WebRTC", "🔄 Attempting ICE RESTART for $socketId (Attempt ${retries + 1}/$MAX_ICE_RETRIES)...")
                            iceRetryCount[socketId] = retries + 1
                            
                            // Re-fetch configuration so an ICE restart uses the latest managed TURN credentials.
                            val refreshedIceServers = currentIceServersSnapshot()
                            pc.setConfiguration(buildRtcConfiguration(refreshedIceServers))

                            asyncScope.launch {
                                delay(2000) // 2s wait before retry
                                executor.execute {
                                    requestOffer(
                                        socketId = socketId,
                                        pc = pc,
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
            override fun onSignalingChange(state: PeerConnection.SignalingState) { 
                Log.d("WebRTC", "SIG $state ($socketId)")
                var map = _signalingStates.value.toMutableMap()
                map[socketId] = state
                _signalingStates.value = map
            }
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) { Log.d("WebRTC", "ICE Receiving: $b ($socketId)") }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d("WebRTC", "📡 ICE Gathering State: $state ($socketId)")
            }
            override fun onAddStream(stream: MediaStream) {}
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
            
            val collision = (makingOffer[peerKey] == true) || (pc.signalingState() != PeerConnection.SignalingState.STABLE)
            ignoreOffer[peerKey] = !isPolite(remoteUserId, senderSocketId) && collision
            
            if (ignoreOffer[peerKey] == true) {
                scheduleGlareRecovery(peerKey, remoteUserId)
                Log.w("WebRTC", "⚔️ GLARE: Collision! IMPOLITE peer IGNORING offer from $peerKey")
                return@execute
            }
            
            if (collision) {
                // POLITE PEER: Rollback then process remote offer
                Log.i("WebRTC", "🤝 GLARE: Collision! POLITE peer rolling back for $peerKey")
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d("WebRTC", "Rollback SUCCESS for $peerKey. Processing remote offer...")
                        executor.execute { 
                            makingOffer[peerKey] = false
                            processOffer(peerKey, pc, offerSdp) 
                        }
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
        Log.d("WebRTC", "Setting Remote Description (OFFER) for $socketId")
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.d("WebRTC", "Remote Description (OFFER) SET successfully for $socketId")
                cancelGlareRecovery(socketId)
                ignoreOffer[socketId] = false
                drainIceQueue(socketId, pc)
                createAnswer(socketId, pc)
            }
            override fun onSetFailure(s: String?) {
                cancelGlareRecovery(socketId)
                ignoreOffer[socketId] = false
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
            Log.d("WebRTC", "Setting Remote Description (ANSWER) for $peerKey")
            isSettingRemoteAnswerPending[peerKey] = true
            offerRetryJobs.remove(peerKey)?.cancel()
            pc.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
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
                    Log.e("WebRTC", "Local Answer CREATE returned null desc ($socketId)")
                    return
                }
                var preferredDesc = preferCompatibleVideoCodec(desc)
                Log.d("WebRTC", "Local Answer CREATED for $socketId")
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d("WebRTC", "Local Answer SET for $socketId")
                        // v24.3: Apply Bitrate control after setting local description
                        setMaxBitrate(pc, socketId)
                        signalingEvents.onSendAnswer(socketId, JSONObject().apply { 
                            put("type", "answer")
                            put("sdp", preferredDesc.description)
                            put("userId", currentUserId)
                        })
                    }
                    override fun onSetFailure(s: String?) { Log.e("WebRTC", "Local Answer SET FAILED: $s ($socketId)") }
                }, preferredDesc)
            }
            override fun onCreateFailure(s: String?) { Log.e("WebRTC", "Local Answer CREATE FAILED: $s ($socketId)") }
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

    fun switchCamera() {
        executor.execute {
            (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
        }
    }


    fun startVideo(
        isScreen: Boolean,
        intent: android.content.Intent? = null,
        isFront: Boolean = true,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        targetFps: Int? = null
    ) {
        executor.execute {
            try {
                Log.d("WebRTCManager", "Starting video: screen=$isScreen")

                var previousCapturer = videoCapturer
                var previousHelper = surfaceTextureHelper
                var previousSource = localVideoSource
                var previousTrack = _localVideoTrackFlow.value

                // 1. Build the new pipeline first so source switches do not blank the UI.
                var capturer = if (isScreen) {
                    val projectionIntent = intent ?: run {
                        Log.e("WebRTCManager", "Screen share requested without MediaProjection intent")
                        return@execute
                    }
                    ScreenCapturerAndroid(projectionIntent, object : MediaProjection.Callback() {})
                } else {
                    createCameraCapturer(isFront)
                } ?: return@execute

                var f = factory ?: return@execute
                var source = f.createVideoSource(isScreen)
                var helper = SurfaceTextureHelper.create("CaptureThread", rootEglBase?.eglBaseContext)

                capturer.initialize(helper, context, source.capturerObserver)

                fun makeEven(value: Int): Int = (value / 2) * 2

                val metrics = context.resources.displayMetrics
                val defaultFps = if (isScreen) 15 else 24
                val fps = (targetFps ?: defaultFps).coerceIn(10, 60)

                val (finalWidth, finalHeight) = if (isScreen) {
                    // Preserve aspect ratio and fit within the requested bounds.
                    val srcW = metrics.widthPixels.coerceAtLeast(1)
                    val srcH = metrics.heightPixels.coerceAtLeast(1)
                    val boundW = (targetWidth ?: 1280).coerceAtLeast(320)
                    val boundH = (targetHeight ?: 720).coerceAtLeast(320)

                    val scale = minOf(boundW.toFloat() / srcW.toFloat(), boundH.toFloat() / srcH.toFloat())
                        .coerceIn(0.1f, 1f)
                    var w = makeEven((srcW * scale).toInt().coerceAtLeast(320))
                    var h = makeEven((srcH * scale).toInt().coerceAtLeast(320))
                    w to h
                } else {
                    var w = makeEven((targetWidth ?: 960).coerceAtLeast(320))
                    var h = makeEven((targetHeight ?: 540).coerceAtLeast(240))
                    w to h
                }

                Log.i("WebRTCManager", "Starting capturer: ${finalWidth}x$finalHeight @${fps}fps (Screen: $isScreen)")
                val captureProfiles = if (isScreen) {
                    listOf(
                        Triple(finalWidth, finalHeight, fps.coerceAtMost(15)),
                        Triple(makeEven(1280), makeEven(720), 15),
                        Triple(makeEven(960), makeEven(540), 15),
                        Triple(makeEven(640), makeEven(360), 12)
                    )
                } else {
                    listOf(
                        Triple(finalWidth, finalHeight, fps),
                        Triple(makeEven(960), makeEven(540), fps.coerceAtMost(24)),
                        Triple(makeEven(640), makeEven(480), fps.coerceAtMost(20)),
                        Triple(makeEven(480), makeEven(360), 15)
                    )
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
                    Log.e("WebRTCManager", "No compatible capture profile could be started")
                    return@execute
                }

                var trackId = if (isScreen) "SCREEN_01" else "CAMERA_01"
                var track = f.createVideoTrack(trackId, source).apply { setEnabled(true) }

                // 2. Swap atomically once the new track is actually ready.
                videoCapturer = capturer
                surfaceTextureHelper = helper
                localVideoSource = source
                _localVideoTrackFlow.value = track
                updateLocalVideoSenders(activeOutgoingVideoTrack())

                previousTrack
                    ?.takeIf { it.id() != track.id() }
                    ?.let { oldTrack ->
                        runCatching { oldTrack.setEnabled(false) }
                    }
                releaseVideoPipeline(previousCapturer, previousHelper, previousSource)
                
                // v4.7.1: Wrap suspend call properly
                asyncScope.launch {
                    delay(300)
                    renegotiateAll(iceRestart = false)
                }
            } catch (e: Exception) {
                Log.e("WebRTCManager", "Error starting video: ${e.message}")
            }
        }
    }

    fun stopVideo() {
        executor.execute {
            try {
                var previousCapturer = videoCapturer
                var previousHelper = surfaceTextureHelper
                var previousSource = localVideoSource
                var previousTrack = _localVideoTrackFlow.value

                videoCapturer = null
                surfaceTextureHelper = null
                localVideoSource = null

                previousTrack?.setEnabled(false)
                _localVideoTrackFlow.value = null
                updateLocalVideoSenders(null)
                releaseVideoPipeline(previousCapturer, previousHelper, previousSource)
                renegotiateAll(iceRestart = false)
            } catch (e: Exception) {
                Log.e("WebRTCManager", "Error in stopVideo: ${e.message}")
            }
        }
    }

    private val renegotiateScope = newSafeScope("WebRTCManagerRenegotiate", Dispatchers.Default)
    private var renegotiateJob: Job? = null

    private fun renegotiateAll(iceRestart: Boolean = false) {
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
                    val newDirection = if (vt != null) {
                        RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    } else {
                        RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                    }
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
                    if (!directionChanged && !trackChanged && !iceRestart) {
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
                var capturer = runCatching { enumerator.createCapturer(deviceName, null) }.getOrNull()
                if (capturer != null) {
                    Log.i("WebRTCManager", "Using camera capturer: $deviceName (${enumerator.javaClass.simpleName})")
                    return capturer
                }
            }
        }

        Log.e("WebRTCManager", "No compatible camera capturer found")
        return null
    }

    fun destroy() {
        executor.execute {
            destroyInternal()
        }
    }


    fun onUserLeft(sid: String) {
        executor.execute {
            var peerKey = resolvePeerKey(sid, sid) ?: sid.trim()
            Log.d("WebRTCManager", "Cleaning up state for disconnected user: $sid (resolved=$peerKey)")
            peerConnections.remove(peerKey)?.dispose() // Full dispose instead of just close
            _remoteVideoTracks.value = _remoteVideoTracks.value.toMutableMap().apply { remove(peerKey) }
            _peerConnectionStates.value = _peerConnectionStates.value.toMutableMap().apply { remove(peerKey) }
            _signalingStates.value = _signalingStates.value.toMutableMap().apply { remove(peerKey) }
            _debugInfo.value = _debugInfo.value.toMutableMap().apply { remove(peerKey) }
            remoteUserIds.remove(peerKey)
            remoteAudioTracks.remove(peerKey)
            makingOffer.remove(peerKey)
            iceRetryCount.remove(peerKey)
            iceCandidateQueue.remove(peerKey)
            disconnectRecoveryJobs.remove(peerKey)?.cancel()
            offerRetryJobs.remove(peerKey)?.cancel()
            glareRecoveryJobs.remove(peerKey)?.cancel()
            ignoreOffer.remove(peerKey)
            isSettingRemoteAnswerPending.remove(peerKey)
        }
    }
    
    fun getEglBaseContext() = rootEglBase?.eglBaseContext
    fun setAudioEnabled(e: Boolean) { 
        executor.execute { 
            try {
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
                renegotiateAll(iceRestart = false)
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
                    track.setVolume(if (enabled) 1.0 else 0.0)
                }
            }
            Log.d("WebRTC", "Remote audio playback ${if (enabled) "ENABLED" else "DISABLED"}")
        }
    }
    fun setAudioVolume(s: String, v: Double) { executor.execute { remoteAudioTracks[s]?.setVolume(v) } }
    
    /**
     * Gets the local audio level. 
     * v22.0: Now attempts to fetch real stats from active peer connections.
     */
    fun getLocalAudioLevel(): Double {
        return try {
            var track = localAudioTrack ?: return 0.0
            if (track.state() == MediaStreamTrack.State.ENDED) return 0.0
            if (!track.enabled()) return 0.0
            
            // If no peer connections, we can't get real WebRTC stats, so we return 0.1 as "active/listening"
            if (peerConnections.isEmpty()) return 0.1
            
            // We'll return the highest level among all active senders
            _lastLocalLevel
        } catch (e: Exception) {
            0.0
        }
    }

    private var _lastLocalLevel: Double = 0.0

    // This should be called by a timer or during stats collection
    private fun updateLocalAudioLevelFromStats() {
        if (peerConnections.isEmpty()) return
        
        // Get stats from the first stable connection
        var pc = peerConnections.values.firstOrNull { it.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED }
            ?: peerConnections.values.firstOrNull() ?: return
            
        pc.getStats { reports ->
            var maxLevel = 0.0
            for (report in reports.statsMap.values) {
                if (report.type == "media-source" && report.members["kind"] == "audio") {
                    var level = report.members["audioLevel"] as? Double ?: 0.0
                    if (level > maxLevel) maxLevel = level
                }
            }
            _lastLocalLevel = maxLevel
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
        executor.execute {
            val levels = mutableMapOf<String, Double>()
            val latch = java.util.concurrent.CountDownLatch(peerConnections.size)
            
            peerConnections.forEach { (socketId, pc) ->
                pc.getStats { report ->
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
                    
                    if (maxLevel > 0.0) {
                        levels[socketId] = maxLevel
                    }
                    latch.countDown()
                }
            }
            
            // Wait with short timeout to aggregate
            try {
                latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w("WebRTCManager", "Interrupted while waiting for remote audio levels", e)
            } catch (e: Exception) {
                Log.w("WebRTCManager", "Failed while waiting for remote audio levels", e)
            }
            
            callback(levels)
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
                    // Capping bitrate: 1.5Mbps for camera, 2.5Mbps for screen share
                    val isScreen = track.id().contains("SCREEN")
                    val maxBitrate = if (isScreen) 2500 * 1000 else 1500 * 1000
                    params.encodings[0].maxBitrateBps = maxBitrate
                    sender.parameters = params
                    Log.d("WebRTC", "Bitrate restricted to ${maxBitrate/1000}kbps for $socketId")
                }
            }
        }
    }
}



