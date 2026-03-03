package conectate.com.data.webrtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import io.socket.client.Socket
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
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
    private val signalingEvents: SignalingEvents
) {
    private fun routeSpeakerphone(audioManager: AudioManager, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!enabled) {
                audioManager.clearCommunicationDevice()
                return
            }
            val speaker = audioManager.availableCommunicationDevices.firstOrNull {
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

    private var currentUserId: String = ""
    private var localSocketId: String = ""
    
    private var factory: PeerConnectionFactory? = null
    private var rootEglBase: EglBase? = EglBase.create()
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    
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
    
    private val makingOffer = mutableMapOf<String, Boolean>()
    private val iceCandidateQueue = mutableMapOf<String, MutableList<IceCandidate>>()
    private val iceRetryCount = mutableMapOf<String, Int>()
    private val ignoreOffer = mutableMapOf<String, Boolean>()
    private val isSettingRemoteAnswerPending = mutableMapOf<String, Boolean>()
    private val MAX_ICE_RETRIES = 3
    
    // XIRSYS PRO-TURN Configuration
    private val XIRSYS_IDENT = "Conectate"
    private val XIRSYS_SECRET = "0c22f30a-ff19-11f0-b2db-0242ac150002"
    private val XIRSYS_CHANNEL = "red-social"
    private val dynamicIceServers = mutableListOf<PeerConnection.IceServer>()
    private val xirsysLatch = java.util.concurrent.CountDownLatch(1)
    
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // Fallback Servers (Used while Xirsys is fetching or if it fails)
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        // OpenRelay as secondary fallback
        PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
    )

    private val statsScope = CoroutineScope(Dispatchers.Default + Job())
    
    init {
        try {
            Log.w("WebRTCManager", "🚀 WebRTC Initializing (Standard Mode) 🚀")
            
            // v24.1: Configure AudioManager FIRST, before creating any tracks
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            routeSpeakerphone(am, enabled = true)
            if (am.isMicrophoneMute) {
                Log.w("WebRTC", "Mic was muted - unmuting")
                am.isMicrophoneMute = false
            }
            Log.i("WebRTC", "Audio pre-configured: Mode=COMMUNICATION, Speaker=ON")
            
            initializeFactory()
            createLocalAudioTrack()
            
            // Xirsys fetch
            executor.execute {
                fetchXirsysIceServers()
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
            remoteUserIds.clear() // v7.2.4: Clear User ID mapping
            _remoteVideoTracks.value = emptyMap()
            _peerConnectionStates.value = emptyMap()
            _signalingStates.value = emptyMap()
            _debugInfo.value = emptyMap() // v7.2.4: Clear Debug Info mapping
            ignoreOffer.clear()
            isSettingRemoteAnswerPending.clear()
            
            // 3. Stop local captures but keep factory alive
            videoCapturer?.stopCapture()
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            videoCapturer?.dispose()
            videoCapturer = null
            
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
        val track = localAudioTrack
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
            val track = localAudioTrack
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
        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false) 
            .setUseHardwareNoiseSuppressor(false)
            // v24.3: Use MIC source for better software AEC compatibility
            .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
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
        
        localAudioTrack?.dispose()
        localAudioTrack = null
        
        peerConnections.values.forEach { it.dispose() }
        peerConnections.clear()
        
        remoteAudioTracks.clear()
        _remoteVideoTracks.value = emptyMap()
        _peerConnectionStates.value = emptyMap()
        _signalingStates.value = emptyMap()
        _debugInfo.value = emptyMap()
        
        factory?.dispose()
        factory = null
        
        audioDeviceModule?.release()
        audioDeviceModule = null
    }

    private fun createLocalAudioTrack() {
        val f = factory ?: return
        val audioConstraints = MediaConstraints()
        val audioSource = f.createAudioSource(audioConstraints)
        localAudioTrack = f.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack?.setEnabled(true)
        Log.i("WebRTC", "Audio track created: ${localAudioTrack?.id()}, enabled=${localAudioTrack?.enabled()}")
    }


    fun onUserJoined(remoteSocketId: String, remoteUserId: String) {
        val rsid = remoteSocketId.trim()
        val ruid = remoteUserId.trim()
        executor.execute {
            Log.d("WebRTCManager", "onUserJoined: $ruid ($rsid)")
            val pc = getOrCreatePC(rsid, ruid) ?: return@execute
            
            val isPolite = isPolite(ruid, rsid)
            updateDebugInfo(rsid, "Role: ${if(isPolite) "POLITE" else "IMPOLITE"}\nUserID: $ruid")
            
            if (!isPolite) {
                 Log.i("WebRTC", "Impolite peer initiating connection to $rsid")
                 makingOffer[rsid] = true
                 createOffer(rsid, pc, iceRestart = false)
            } else {
                 Log.i("WebRTC", "Polite peer waiting for offer from $rsid")
            }
        }
    }

    private fun getOrCreatePC(socketId: String, userId: String): PeerConnection? {
        val rsid = socketId.trim()
        val ruid = userId.trim()
        
        remoteUserIds[rsid] = ruid
        peerConnections[rsid]?.let { return it }
        
        val pc = createPeerConnection(rsid, ruid) ?: return null
        peerConnections[rsid] = pc
        
        // INITIALIZE STATE TO NEW
        val map = _peerConnectionStates.value.toMutableMap()
        map[rsid] = PeerConnection.IceConnectionState.NEW
        _peerConnectionStates.value = map
        
        // v24.2: Standard Transceiver Setup
        // 1. Audio: Always SEND_RECV to ensure we can talk and hear
        val audioTransceiver = pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("stream"))
        )
        localAudioTrack?.let { track ->
            audioTransceiver.sender.setTrack(track, false)
            Log.i("WebRTC", "🎤 Audio track attached to $rsid")
        } ?: Log.w("WebRTC", "⚠️ No local audio track available for $rsid")

        // 2. Video: Start as SEND_RECV but only attach track if active
        val videoTransceiver = pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("stream"))
        )
        _localVideoTrackFlow.value?.let { track ->
            videoTransceiver.sender.setTrack(track, false)
            Log.i("WebRTC", "📹 Video track attached to $rsid")
        } ?: run {
            videoTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            Log.d("WebRTC", "📹 No local video track, setting to RECV_ONLY for $rsid")
        }
        
        return pc
    }

    private fun fetchXirsysIceServers() {
        try {
            val endpoint = "https://global.xirsys.net/_turn/$XIRSYS_CHANNEL"
            Log.i("WebRTC", "🌐 Fetching Xirsys ICE Servers from: $endpoint")
            val url = java.net.URL(endpoint)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "PUT"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            
            val auth = "$XIRSYS_IDENT:$XIRSYS_SECRET"
            Log.d("WebRTC", "📡 Using Ident: $XIRSYS_IDENT (Secret length: ${XIRSYS_SECRET.length})")
            val encodedAuth = android.util.Base64.encodeToString(auth.toByteArray(), android.util.Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $encodedAuth")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val body = JSONObject().apply { put("format", "urls") }.toString()
            connection.outputStream.write(body.toByteArray())
            
            val responseCode = connection.responseCode
            Log.d("WebRTC", "📡 Xirsys API Response Code: $responseCode")
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                Log.d("WebRTC", "📡 Xirsys Response: $response")
                val jsonResponse = JSONObject(response)
                val v = jsonResponse.optJSONObject("v") ?: jsonResponse.optJSONObject("d")
                
                if (v != null && v.has("iceServers")) {
                    synchronized(dynamicIceServers) {
                        dynamicIceServers.clear()
                        val iceServersObj = v.get("iceServers")
                        if (iceServersObj is org.json.JSONArray) {
                            for (i in 0 until iceServersObj.length()) {
                                addIceServerFromJSON(iceServersObj.getJSONObject(i))
                            }
                        } else if (iceServersObj is JSONObject) {
                            addIceServerFromJSON(iceServersObj)
                        }
                    }
                    Log.w("WebRTC", "✅ XIRSYS RELAY LOADED: ${dynamicIceServers.size} professional nodes online")
                    // FORCE LOG for User Verification (using VoiceRoomRepository tag to ensure visibility in current filter)
                    Log.e("VoiceRoomRepository", "✅✅✅ XIRSYS CONFIG LOADED: ${dynamicIceServers.size} TURN SERVERS READY ✅✅✅")
                } else {
                    Log.e("WebRTC", "❌ Xirsys Response Missing 'v' or 'iceServers' envelope: $response")
                    Log.e("VoiceRoomRepository", "❌ XIRSYS LOAD FAILED: Invalid Response")
                }
            } else {
                val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e("WebRTC", "❌ Xirsys API Error $responseCode: $errorMsg")
            }
        } catch (e: Exception) {
            Log.e("WebRTC", "❌ Xirsys Fetch Exception: ${e.message}")
            e.printStackTrace()
        }
 finally {
            xirsysLatch.countDown()
            Log.d("WebRTC", "Xirsys latch counted down. Status: ${if (dynamicIceServers.isNotEmpty()) "READY" else "FAILED/FALLBACK"}")
        }
    }

    private fun createPeerConnection(socketId: String, remoteUserId: String): PeerConnection? {
        // Wait for Xirsys up to 5 seconds if not ready
        try {
            if (xirsysLatch.count > 0) {
                Log.w("WebRTC", "⌛ createPeerConnection: Waiting for Xirsys TURN nodes...")
                xirsysLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            Log.e("WebRTC", "⌛ Latency Error: Timed out waiting for Xirsys")
        }

        val combinedIceServers = synchronized(dynamicIceServers) {
            if (dynamicIceServers.isEmpty()) {
                Log.w("WebRTC", "⚠️ No XIRSYS servers found, using fallbacks.")
                iceServers
            } else dynamicIceServers + iceServers
        }
        
        val config = PeerConnection.RTCConfiguration(combinedIceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            
            // v17.2: Force Modern Policies for better NAT traversal
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            keyType = PeerConnection.KeyType.ECDSA
        }

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
                    val pc = peerConnections[socketId] ?: return@execute
                    val sigState = pc.signalingState()
                    val iceState = pc.iceConnectionState()
                    
                    Log.d("WebRTC", "RenegotiationNeeded for $socketId (Sig: $sigState, Ice: $iceState)")
                    
                    if (sigState == PeerConnection.SignalingState.STABLE) {
                        // PERFECT NEGOTIATION: Only initiate if connection is established 
                        // OR if we are the Impolite peer.
                        // If connection is NEW and we are POLITE, we MUST let the other peer initiate.
                        if (iceState == PeerConnection.IceConnectionState.NEW && isPolite(remoteUserId, socketId)) {
                            Log.i("WebRTC", "☕ Polite peer waiting for initial offer from $remoteUserId ($socketId)")
                            return@execute
                        }
                        
                        Log.i("WebRTC", "🚀 Initiating negotiation for $socketId")
                        makingOffer[socketId] = true
                        createOffer(socketId, pc, iceRestart = false)
                    } else {
                        Log.w("WebRTC", "Ignoring RenegotiationNeeded: current state is $sigState")
                    }
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() ?: return
                Log.i("WebRTCManager", "Add Remote Track: ${track.kind()} ($socketId)")
                if (track.kind() == "video" && track is VideoTrack) {
                    track.setEnabled(true)
                    val map = _remoteVideoTracks.value.toMutableMap()
                    map[socketId] = track
                    _remoteVideoTracks.value = map
                }
                if (track.kind() == "audio" && track is AudioTrack) {
                    Log.i("WebRTCManager", "🔊 Remote Audio Track Received for $socketId")
                    track.setEnabled(true)
                    track.setVolume(1.0)
                    remoteAudioTracks[socketId] = track
                    Log.i("WebRTCManager", "Remote Audio Track configured and enabled for $socketId")
                }
            }

            override fun onRemoveTrack(receiver: RtpReceiver?) {
                val track = receiver?.track() ?: return
                Log.w("WebRTCManager", "Remove Remote Track: ${track.kind()} ($socketId)")
                if (track.kind() == "video") {
                    val map = _remoteVideoTracks.value.toMutableMap()
                    map.remove(socketId)
                    _remoteVideoTracks.value = map
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { 
                Log.d("WebRTC", "ICE $state ($socketId)") 
                val map = _peerConnectionStates.value.toMutableMap()
                map[socketId] = state
                _peerConnectionStates.value = map
                
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    // Reinforce and check audio session on connection
                    executor.execute {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                            am.mode = AudioManager.MODE_IN_COMMUNICATION
                            Log.w("WebRTC", "Audio mode fixed to COMMUNICATION after ICE connected")
                        }
                        
                        // v7.2.6: Reinforce transceivers to ensure local track is still active
                        val pc = peerConnections[socketId]
                        pc?.transceivers?.forEach { t ->
                            if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                                if (t.direction == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY) {
                                    t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                                }
                                t.sender.setTrack(localAudioTrack, false)
                            }
                        }
                    }
                }
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    Log.e("WebRTC", "❌ ICE FAILED! Possible firewall/networking issue ($socketId)")
                    // v17.0: Dynamic Ice Restart on Failure with limit and delay
                    executor.execute {
                        val pc = peerConnections[socketId]
                        val retries = iceRetryCount[socketId] ?: 0
                        if (pc != null && pc.iceConnectionState() == PeerConnection.IceConnectionState.FAILED && retries < MAX_ICE_RETRIES) {
                            Log.w("WebRTC", "🔄 Attempting ICE RESTART for $socketId (Attempt ${retries + 1}/$MAX_ICE_RETRIES)...")
                            iceRetryCount[socketId] = retries + 1
                            
                            // v17.5: Re-fetch configuration to include latest Xirsys nodes during restart
                            val combinedIceServers = synchronized(dynamicIceServers) {
                                if (dynamicIceServers.isEmpty()) iceServers else dynamicIceServers + iceServers
                            }
                            pc.setConfiguration(PeerConnection.RTCConfiguration(combinedIceServers).apply {
                                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                                iceTransportsType = PeerConnection.IceTransportsType.ALL
                                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                                keyType = PeerConnection.KeyType.ECDSA
                            })

                            CoroutineScope(Dispatchers.Default).launch {
                                delay(2000) // 2s wait before retry
                                executor.execute {
                                    makingOffer[socketId] = true
                                    createOffer(socketId, pc, iceRestart = true)
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
                val map = _signalingStates.value.toMutableMap()
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
            val pc = getOrCreatePC(senderSocketId, remoteUserId) ?: return@execute
            
            val collision = (makingOffer[senderSocketId] == true) || (pc.signalingState() != PeerConnection.SignalingState.STABLE)
            ignoreOffer[senderSocketId] = !isPolite(remoteUserId, senderSocketId) && collision
            
            if (ignoreOffer[senderSocketId] == true) {
                Log.w("WebRTC", "⚔️ GLARE: Collision! IMPOLITE peer IGNORING offer from $senderSocketId")
                return@execute
            }
            
            if (collision) {
                // POLITE PEER: Rollback then process remote offer
                Log.i("WebRTC", "🤝 GLARE: Collision! POLITE peer rolling back for $senderSocketId")
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d("WebRTC", "Rollback SUCCESS for $senderSocketId. Processing remote offer...")
                        executor.execute { 
                            makingOffer[senderSocketId] = false
                            processOffer(senderSocketId, pc, offerSdp) 
                        }
                    }
                    override fun onSetFailure(s: String?) {
                        Log.e("WebRTC", "Rollback FAILED for $senderSocketId: $s. Trying to proceed anyway...")
                        executor.execute { processOffer(senderSocketId, pc, offerSdp) }
                    }
                }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                return@execute
            }
            
            processOffer(senderSocketId, pc, offerSdp)
        }
    }

    private fun processOffer(socketId: String, pc: PeerConnection, sdp: String) {
        Log.d("WebRTC", "Setting Remote Description (OFFER) for $socketId")
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.d("WebRTC", "Remote Description (OFFER) SET successfully for $socketId")
                drainIceQueue(socketId, pc)
                createAnswer(socketId, pc)
            }
            override fun onSetFailure(s: String?) { Log.e("WebRTC", "Remote Description (OFFER) FAILED: $s ($socketId)") }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun onAnswerReceived(senderSocketId: String, answerSdp: String) {
        executor.execute {
            val pc = peerConnections[senderSocketId] ?: return@execute
            Log.d("WebRTC", "Setting Remote Description (ANSWER) for $senderSocketId")
            isSettingRemoteAnswerPending[senderSocketId] = true
            pc.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    Log.d("WebRTC", "Remote Description (ANSWER) SET successfully for $senderSocketId")
                    isSettingRemoteAnswerPending[senderSocketId] = false
                    drainIceQueue(senderSocketId, pc)
                    makingOffer[senderSocketId] = false 
                }
                override fun onSetFailure(s: String?) { 
                    Log.e("WebRTC", "Remote Description (ANSWER) FAILED: $s ($senderSocketId)")
                    isSettingRemoteAnswerPending[senderSocketId] = false
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        }
    }

    fun onIceCandidateReceived(socketId: String, json: JSONObject) {
        executor.execute {
            val pc = peerConnections[socketId]
            val candidate = IceCandidate(json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate"))
            
            if (ignoreOffer[socketId] == true) {
                Log.d("WebRTC", "Ignoring candidate because of active GLARE/ignoreOffer ($socketId)")
                return@execute
            }

            if (pc != null) {
                // BUG FIX: Add if we have remote description, regardless of stability
                if (pc.remoteDescription != null && isSettingRemoteAnswerPending[socketId] != true) {
                    val candidateStr = json.getString("candidate")
                    val parts = candidateStr.split(' ')
                    val type = if (parts.size > 7) {
                        when (parts[7]) {
                            "host" -> "HOST"
                            "srflx" -> "SRFLX"
                            "relay" -> "RELAY"
                            else -> "UNK"
                        }
                    } else "UNK"
                    
                    val logPart = if (parts.size > 7) parts[7] else "unknown"
                    Log.d("WebRTC", "Adding ICE Candidate [$type]: $logPart ($socketId)")
                    pc.addIceCandidate(candidate)
                } else {
                    iceCandidateQueue.getOrPut(socketId) { mutableListOf() }.add(candidate)
                    Log.d("WebRTC", "Queueing ICE Candidate (no remote desc) ($socketId)")
                }
            } else {
                iceCandidateQueue.getOrPut(socketId) { mutableListOf() }.add(candidate)
                Log.d("WebRTC", "Queueing ICE Candidate (PC null) ($socketId)")
            }
        }
    }

    private fun drainIceQueue(socketId: String, pc: PeerConnection) {
        iceCandidateQueue[socketId]?.forEach { pc.addIceCandidate(it) }
        iceCandidateQueue.remove(socketId)
    }

    private fun createOffer(socketId: String, pc: PeerConnection, iceRestart: Boolean = false) {
        val constraints = MediaConstraints()
        if (iceRestart) {
            constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.d("WebRTC", "Local Offer CREATED for $socketId")
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d("WebRTC", "Local Offer SET for $socketId")
                        drainIceQueue(socketId, pc)
                        // v24.3: Apply Bitrate control after setting local description
                        setMaxBitrate(pc, socketId)
                        signalingEvents.onSendOffer(socketId, JSONObject().apply { 
                            put("type", "offer")
                            put("sdp", desc!!.description)
                            put("userId", currentUserId)
                        })
                    }
                    override fun onSetFailure(s: String?) { Log.e("WebRTC", "Local Offer SET FAILED: $s ($socketId)") }
                }, desc!!)
            }
            override fun onCreateFailure(s: String?) { Log.e("WebRTC", "Local Offer CREATE FAILED: $s ($socketId)") }
        }, constraints)
    }

    private fun createAnswer(socketId: String, pc: PeerConnection) {
        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.d("WebRTC", "Local Answer CREATED for $socketId")
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d("WebRTC", "Local Answer SET for $socketId")
                        drainIceQueue(socketId, pc)
                        // v24.3: Apply Bitrate control after setting local description
                        setMaxBitrate(pc, socketId)
                        signalingEvents.onSendAnswer(socketId, JSONObject().apply { 
                            put("type", "answer")
                            put("sdp", desc!!.description)
                        })
                    }
                    override fun onSetFailure(s: String?) { Log.e("WebRTC", "Local Answer SET FAILED: $s ($socketId)") }
                }, desc!!)
            }
            override fun onCreateFailure(s: String?) { Log.e("WebRTC", "Local Answer CREATE FAILED: $s ($socketId)") }
        }, constraints)
    }

    // --- MEDIA CONTROL ---

    fun startVideo(isScreen: Boolean, intent: android.content.Intent? = null, isFront: Boolean = true) {
        executor.execute {
            try {
                Log.d("WebRTCManager", "Starting video: screen=$isScreen")
                
                // 1. Clean up existing
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoCapturer = null
                
                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = null
                
                localVideoSource?.dispose()
                localVideoSource = null
                
                // 2. Initialize new capturer
                val capturer = (if (isScreen) ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {}) else createCameraCapturer(isFront)) ?: return@execute
                videoCapturer = capturer
                
                val f = factory ?: return@execute
                val source = f.createVideoSource(isScreen)
                localVideoSource = source
                
                val helper = SurfaceTextureHelper.create("CaptureThread", rootEglBase?.eglBaseContext)
                surfaceTextureHelper = helper
                
                capturer.initialize(helper, context, source.capturerObserver)
                
                // v7.2.7: Use DisplayMetrics for better Screen Share compatibility
                val metrics = context.resources.displayMetrics
                val width = if (isScreen) metrics.widthPixels else 1280
                val height = if (isScreen) metrics.heightPixels else 720
                
                // v24.3: Optimized Resolution for Screen Share (capping at 720p 15fps for stability)
                val finalWidth = if (isScreen) 1280 else if (width > 1280) 1280 else width
                val finalHeight = if (isScreen) 720 else (finalWidth.toFloat() * (height.toFloat() / width.toFloat())).toInt()
    
                Log.i("WebRTCManager", "Starting capturer: ${finalWidth}x$finalHeight (Screen: $isScreen)")
                capturer.startCapture(finalWidth, finalHeight, if (isScreen) 15 else 24)
                
                val trackId = if (isScreen) "SCREEN_01" else "CAMERA_01"
                val track = f.createVideoTrack(trackId, source).apply { setEnabled(true) }
                _localVideoTrackFlow.value = track
                
                // v4.7.1: Wrap suspend call properly
                CoroutineScope(Dispatchers.Default).launch {
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
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoCapturer = null
                
                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = null
                
                _localVideoTrackFlow.value?.setEnabled(false)
                _localVideoTrackFlow.value = null
                renegotiateAll(iceRestart = false)
            } catch (e: Exception) {
                Log.e("WebRTCManager", "Error in stopVideo: ${e.message}")
            }
        }
    }

    private val renegotiateScope = CoroutineScope(Dispatchers.Default + Job())
    private var renegotiateJob: Job? = null

    private fun renegotiateAll(iceRestart: Boolean = false) {
        renegotiateJob?.cancel()
        renegotiateJob = renegotiateScope.launch {
            delay(100) // Debounce reduced for faster sync
            executor.execute {
                val vt = _localVideoTrackFlow.value
                Log.d("WebRTCManager", "Renegotiating all connections with videoTrack=${vt != null}")
                
                peerConnections.forEach { (id, pc) ->
                    val videoTransceiver = pc.transceivers.find { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                    
                    if (videoTransceiver != null) {
                        videoTransceiver.sender.setTrack(vt, false)
                        val oldDir = videoTransceiver.direction
                        videoTransceiver.direction = if (vt != null) RtpTransceiver.RtpTransceiverDirection.SEND_RECV else RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                        
                        Log.d("WebRTCManager", "Connection $id: Video direction set to ${videoTransceiver.direction}")
                        
                        // Aggressive re-negotiation: ensure the other side sees the new track state immediately.
                        // Rollback logic handles any glare/collision.
                        if (pc.signalingState() == PeerConnection.SignalingState.STABLE || oldDir != videoTransceiver.direction || vt != null) {
                            // Reinforce Audio
                            pc.transceivers.find { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }?.let { at ->
                                at.sender.setTrack(localAudioTrack, false)
                                if (at.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                                    at.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                                }
                            }

                            Log.i("WebRTC", "🚀 Renegotiating with $id (Video active: ${vt != null})")
                            makingOffer[id] = true
                            createOffer(id, pc, iceRestart = iceRestart)
                        }
                    }
                }
            }
        }
    }

    private fun createCameraCapturer(isFront: Boolean): VideoCapturer? {
        val enum = Camera2Enumerator(context)
        val dev = enum.deviceNames.firstOrNull { if (isFront) enum.isFrontFacing(it) else enum.isBackFacing(it) } ?: enum.deviceNames.firstOrNull()
        return if (dev != null) enum.createCapturer(dev, null) else null
    }

    fun destroy() {
        executor.execute {
            destroyInternal()
        }
    }


    fun onUserLeft(sid: String) {
        executor.execute {
            Log.d("WebRTCManager", "Cleaning up state for disconnected user: $sid")
            peerConnections.remove(sid)?.dispose() // Full dispose instead of just close
            _remoteVideoTracks.value = _remoteVideoTracks.value.toMutableMap().apply { remove(sid) }
            _peerConnectionStates.value = _peerConnectionStates.value.toMutableMap().apply { remove(sid) }
            _signalingStates.value = _signalingStates.value.toMutableMap().apply { remove(sid) }
            _debugInfo.value = _debugInfo.value.toMutableMap().apply { remove(sid) }
            remoteUserIds.remove(sid)
            remoteAudioTracks.remove(sid)
            makingOffer.remove(sid)
            iceCandidateQueue.remove(sid)
            ignoreOffer.remove(sid)
            isSettingRemoteAnswerPending.remove(sid)
        }
    }
    
    fun getEglBaseContext() = rootEglBase?.eglBaseContext
    fun setAudioEnabled(e: Boolean) { 
        executor.execute { 
            try {
                localAudioTrack?.setEnabled(e)
                Log.d("WebRTC", "Audio track ${if(e) "ENABLED" else "DISABLED"}")
            } catch (ex: Exception) {
                Log.e("WebRTC", "Error in setAudioEnabled: ${ex.message}")
            }
        } 
    }
    fun setAudioVolume(s: String, v: Double) { executor.execute { remoteAudioTracks[s]?.setVolume(v) } }
    
    /**
     * Gets the local audio level. 
     * v22.0: Now attempts to fetch real stats from active peer connections.
     */
    fun getLocalAudioLevel(): Double {
        return try {
            val track = localAudioTrack ?: return 0.0
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
        val pc = peerConnections.values.firstOrNull { it.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED }
            ?: peerConnections.values.firstOrNull() ?: return
            
        pc.getStats { reports ->
            var maxLevel = 0.0
            for (report in reports.statsMap.values) {
                if (report.type == "media-source" && report.members["kind"] == "audio") {
                    val level = report.members["audioLevel"] as? Double ?: 0.0
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
        } catch (e: Exception) {}
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) {}
        override fun onSetFailure(s: String?) {}
    }
    
    private fun updateDebugInfo(id: String, info: String) {
        val map = _debugInfo.value.toMutableMap()
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
                                
                            val level = stats.members["audioLevel"]
                            if (level is Double) {
                                if (level > maxLevel) maxLevel = level
                            }
                        }
                        // Fallback/Legacy
                        if (stats.type == "track" && stats.members["kind"] as? String == "audio") {
                             val level = stats.members["audioLevel"]
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
            } catch (e: Exception) {}
            
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

            val builder = PeerConnection.IceServer.builder(urlsList)
            if (server.has("username")) builder.setUsername(server.getString("username"))
            if (server.has("credential")) builder.setPassword(server.getString("credential"))
            
            val iceServer = builder.createIceServer()
            synchronized(dynamicIceServers) {
                dynamicIceServers.add(iceServer)
            }
            Log.d("WebRTC", "   + Added Relay Node: ${urlsList.firstOrNull() ?: "unknown"}")
        } catch (e: Exception) {
            Log.e("WebRTC", "❌ Failed to parse individual ICE server: ${e.message}")
        }
    }

    private fun setMaxBitrate(pc: PeerConnection, socketId: String) {
        pc.senders.forEach { sender ->
            val track = sender.track() ?: return@forEach
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
