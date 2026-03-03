package conectate.com.data.repository

import conectate.com.data.model.VoiceRoom
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.*
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.content.Context
import conectate.com.data.webrtc.WebRTCManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import io.socket.client.IO
import io.socket.client.Socket
import org.webrtc.VideoTrack

@Singleton
class VoiceRoomRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    // WebRTC & Realtime
    private var webRTCManager: WebRTCManager? = null
    private var roomChannel: RealtimeChannel? = null
    private var socket: Socket? = null
    
    // URL de ngrok proporcionada por el usuario
    private val SIGNALING_URL = "https://susana-unsharing-unclearly.ngrok-free.dev" 
    
    // Exposed for notification navigation
    fun getCurrentRoomId(): String? = currentRoomId
    private var currentRoomId: String? = null
    
    private val _activeRooms = MutableStateFlow<List<VoiceRoom>>(emptyList())
    val activeRooms = _activeRooms.asStateFlow()

    private val _currentRoom = MutableStateFlow<VoiceRoom?>(null)
    val currentRoom = _currentRoom.asStateFlow()

    private val _currentParticipants = MutableStateFlow<List<conectate.com.data.model.VoiceParticipant>>(emptyList())
    val currentParticipants = _currentParticipants.asStateFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks = _remoteVideoTracks.asStateFlow()
    
    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack = _localVideoTrack.asStateFlow()

    private val _peerConnectionStates = MutableStateFlow<Map<String, org.webrtc.PeerConnection.IceConnectionState>>(emptyMap())
    val peerConnectionStates = _peerConnectionStates.asStateFlow()

    private val _signalingStates = MutableStateFlow<Map<String, org.webrtc.PeerConnection.SignalingState>>(emptyMap())
    val signalingStates = _signalingStates.asStateFlow()

    private val _debugInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    val debugInfo = _debugInfo.asStateFlow()

    // NEW: Track who is sharing camera or screen
    // Map<SocketId, Pair<isCameraSharing, isScreenSharing>>
    private val _sharingStates = MutableStateFlow<Map<String, Pair<Boolean, Boolean>>>(emptyMap())
    val sharingStates = _sharingStates.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<conectate.com.presentation.voicerooms.ChatMessage>()
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _incomingReactions = MutableSharedFlow<conectate.com.presentation.voicerooms.ReactionEvent>()
    val incomingReactions = _incomingReactions.asSharedFlow()
    
    // CRITICAL: Single source of truth for video mode
    enum class VideoMode { NONE, CAMERA, SCREEN }
    private val _currentVideoMode = MutableStateFlow(VideoMode.NONE) 

    private fun forceSpeakerOutput(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
            audioManager.isSpeakerphoneOn = true
        }
    }
    val currentVideoMode = _currentVideoMode.asStateFlow()

    private val _cinemaState = MutableStateFlow<CinemaState?>(null)
    val cinemaState = _cinemaState.asStateFlow()

    private val _cinemaSync = MutableSharedFlow<CinemaSyncEvent>()
    val cinemaSync = _cinemaSync.asSharedFlow()

    // v16.0: Universal Link Resolver
    data class CinemaResolvedItem(val url: String, val title: String, val error: String? = null)
    private val pendingResolutions = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<CinemaResolvedItem>>()

    suspend fun resolveUniversalLink(url: String): CinemaResolvedItem = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<CinemaResolvedItem>()
        pendingResolutions[requestId] = deferred
        
        try {
            socket?.emit("resolve-link", org.json.JSONObject().apply {
                put("url", url)
                put("requestId", requestId)
            })
            // Timeout safety (15s)
            withTimeoutOrNull(15000) {
                deferred.await()
            } ?: CinemaResolvedItem("", "", "Timeout: El servidor no respondió a tiempo.")
        } catch (e: Exception) {
            CinemaResolvedItem("", "", "Error de conexión: ${e.message}")
        } finally {
            pendingResolutions.remove(requestId)
        }
    }


    // v7.2-MVP: Relaxed Sync Pulse (5 seconds for stability/efficiency)
    private val SYNC_PULSE_INTERVAL = 5000L

    init {
        scope.launch {
            refreshRooms()
            subscribeToVoiceRooms()
        }
    }

    fun getActiveRooms(): Flow<List<VoiceRoom>> = activeRooms

    suspend fun refreshRooms() {
        try {
            val rooms = supabaseConfig.client.postgrest["voice_rooms"].select {
                filter { eq("is_active", true) }
            }.decodeList<VoiceRoom>()
            _activeRooms.value = rooms
        } catch (e: Exception) {
            android.util.Log.e("VoiceRoomRepository", "Error refreshing rooms", e)
        }
    }

    suspend fun getRoomById(id: String): Result<VoiceRoom> = withContext(Dispatchers.IO) {
        try {
            val room = supabaseConfig.client.postgrest["voice_rooms"].select {
                filter { eq("readable_id", id) } // Try to find by readable_id first
            }.decodeSingleOrNull<VoiceRoom>() 
                ?: supabaseConfig.client.postgrest["voice_rooms"].select {
                    filter { eq("id", id) } // Fallback to UUID
                }.decodeSingleOrNull<VoiceRoom>()
            
            if (room != null) {
                Result.success(room)
            } else {
                Result.failure(Exception("Room not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createRoom(name: String, description: String?, isPublic: Boolean, password: String?): Result<VoiceRoom> = withContext(Dispatchers.IO) {
        try {
            val userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            // We can rely on DB to generate ID, or generate here. Let's let DB handle readable_id via trigger.
            // But we need UUID for the object.
            val roomId = UUID.randomUUID().toString()
            
            val room = VoiceRoom(
                id = roomId,
                name = name,
                description = description ?: "",
                creatorId = userId,
                isPublic = isPublic,
                password = password,
                agoraChannelName = "room_$roomId"
            )

            // Insert without readable_id/created_at to let DB handle defaults
            supabaseConfig.client.postgrest["voice_rooms"].insert(room)
            
            // Re-fetch to get the generated readableID
            val createdRoom = getRoomById(roomId).getOrNull() ?: room
            
            // Artificial delay to prevent race condition on client double-tap
            delay(500)

            Result.success(createdRoom)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // v19.0: Audio Levels
    fun getLocalAudioLevel(): Double {
        return webRTCManager?.getLocalAudioLevel() ?: 0.0
    }
    
    fun getRemoteAudioLevels(callback: (Map<String, Double>) -> Unit) {
        webRTCManager?.getRemoteAudioLevels(callback) ?: callback(emptyMap())
    }

    suspend fun joinRoom(roomId: String, userId: String, displayName: String, avatarUrl: String? = null, signalingRoomId: String? = null) = withContext(Dispatchers.IO) {
        android.util.Log.w("VoiceRoomRepository", "📍 joinRoom START: roomId=$roomId, userId=$userId")
        
        val signalRoom = roomId 
        val isSocketConnected = socket?.connected() == true
        val isWebResHealthy = webRTCManager?.isHealthy() == true
        val isReentry = (currentRoomId == signalRoom && isSocketConnected && isWebResHealthy)
        
        if (isReentry) {
            android.util.Log.w("VoiceRoomRepository", "♻️ RE-ENTRY DETECTED: Already in room $signalRoom. Skipping reset.")
            
            // Ensure self exists in participants (UI sync)
            val self = conectate.com.data.model.VoiceParticipant(
                id = userId,
                name = displayName,
                avatarUrl = avatarUrl,
                socketId = socket?.id() ?: userId
            )
            if (_currentParticipants.value.none { it.id == userId }) {
                _currentParticipants.value = _currentParticipants.value + self
            }
            
            // Re-request audio focus to ensure it's not stolen
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    android.util.Log.d("VoiceRoomRepository", "Audio focus changed: $focusChange")
                }
                .build()
            am.requestAudioFocus(focusRequest)
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            forceSpeakerOutput(am)
            
            android.util.Log.w("VoiceRoomRepository", "📍 RE-ENTRY completed: Session preserved")
            return@withContext
        } else if (socket?.connected() == true) {
             // Socket connected but WebRTC not healthy? Nuke it to avoid zombies.
             android.util.Log.w("VoiceRoomRepository", "⚠️ Partial state detected (Socket OK, WebRTC Unhealthy). Forcing Hard Reset.")
             webRTCManager?.hardReset()
             // Give it a moment to clear
             kotlinx.coroutines.delay(200)
        }
        
        currentRoomId = signalRoom 
        
        // Sync currentRoom object for UI tracking
        scope.launch {
            getRoomById(signalRoom).getOrNull()?.let { room ->
                _currentRoom.value = room
            }
        }

        try {
            if (roomChannel != null) {
                android.util.Log.d("VoiceRoomRepository", "Cleaning up previous room channel...")
                try {
                    leaveRoom()
                    delay(1000) // v22.0: Increased guard delay to 1s to ensure Audio Hardware is fully released by OS
                } catch (e: Exception) {
                    android.util.Log.e("VoiceRoomRepository", "Warning during cleanup: ${e.message}")
                }
            }

            val finalRoomId = roomId 

            // v21.0: Reverting seamless re-entry due to "Zombie Session" issues (Audio/Chat loss).
            // We will fallback to a clean reconnection cycle to safeguard functionality.
            // If we are already connected to this room, we MUST leave first to ensure a fresh, valid socket ID.
            
            // Singularity Strike: Hard Reset connection ONLY if valid re-entry not detected
            try {
                if (webRTCManager == null) {
                    android.util.Log.i("VoiceRoomRepository", "Creating NEW WebRTCManager instance")
                    initializeWebRTC(roomId, userId)
                } else {
                    android.util.Log.w("VoiceRoomRepository", "♻️ DIFFERENT SESSION OR STALE: Forcing media engine REBIRTH")
                    webRTCManager?.hardReset()
                    delay(500) // v7.2.4: Increased from 100ms to 500ms for aggressive OS/Hardware Release
                }
                
                webRTCManager?.ensureLocalTracks() // v7.2.5: Recreate tracks if disposed
                webRTCManager?.setCurrentUser(userId, "")
                
                
                // Reinforce Audio Mode & Request Audio Focus
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        android.util.Log.d("VoiceRoomRepository", "Audio focus changed: $focusChange")
                    }
                    .build()
                
                am.requestAudioFocus(focusRequest)

                am.mode = AudioManager.MODE_IN_COMMUNICATION
                forceSpeakerOutput(am)
                android.util.Log.d("VoiceRoomRepository", "✅ Audio mode set to COMM + Focus Requested")
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepository", "❌ WebRTC Re-init failed: ${e.message}")
            }

            // Add self to participants
            val self = conectate.com.data.model.VoiceParticipant(
                id = userId,
                name = displayName,
                avatarUrl = avatarUrl,
                socketId = userId
            )
            _currentParticipants.value = listOf(self)

            // 1. Setup Socket.io for Signaling
            android.util.Log.i("VoiceRoomRepository", "📡 Connecting to Signaling: $SIGNALING_URL")
            setupSocketSignaling(signalRoom, userId, displayName, avatarUrl)

            // 2. Setup Realtime Channel
            try {
                android.util.Log.i("VoiceRoomRepository", "⚡ Subscribing to Supabase Realtime: room:$roomId")
                roomChannel = supabaseConfig.client.channel("room:$roomId")
                roomChannel?.subscribe()
                incrementParticipantCount(finalRoomId)
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepository", "⚠️ Supabase Realtime Subscription Error (Proceeding anyway): ${e.message}")
            }
            
            // Late sync cinema state
            getRoomById(roomId).getOrNull()?.let { r ->
                if (r.isCinemaMode) {
                    _cinemaState.value = CinemaState(
                        isCinemaMode = true,
                        videoUrl = r.currentVideoUrl,
                        videoTitle = r.currentVideoTitle,
                        isPlaying = r.isPlaying,
                        currentPosition = r.currentPosition
                    )
                }
            }
            android.util.Log.w("VoiceRoomRepository", "📍 joinRoom END: Setup complete")
        } catch (e: Exception) {
            android.util.Log.e("VoiceRoomRepository", "❌ CRITICAL error joining room", e)
            e.printStackTrace()
        }
    }

    private fun setupSocketSignaling(roomId: String, userId: String, name: String, avatarUrl: String?) {
        try {
            // v7.2.4: Strict Socket Purge - Ensure old socket is completely dead before creating new one
            socket?.let {
                android.util.Log.d("VoiceRoomRepository", "🔌 Purging old socket listeners and disconnecting...")
                it.off()
                it.disconnect()
            }

            val options = IO.Options().apply {
                forceNew = true
                reconnection = true
                timeout = 10000 
            }
            android.util.Log.i("VoiceRoomRepository", "🔌 Initializing Socket.io for: $SIGNALING_URL")
            socket = IO.socket(SIGNALING_URL, options)

            socket?.on(Socket.EVENT_CONNECT) {
                val sid = socket?.id() ?: ""
                android.util.Log.w("VoiceRoomRepository", "✅ SIGNALING CONNECTED: $sid. Joining room: $roomId")
                
                webRTCManager?.setCurrentUser(userId, sid)
                
                // Merge self with updated Socket ID
                _currentParticipants.update { current ->
                    val self = current.find { it.id == userId } ?: conectate.com.data.model.VoiceParticipant(id = userId, name = name, socketId = sid)
                    (current.filterNot { it.id == userId } + self.copy(socketId = sid)).distinctBy { it.socketId }
                }
                
                socket?.emit("join-room", org.json.JSONObject().apply {
                    put("roomId", roomId.trim()); put("userId", userId.trim()); put("name", name); put("avatarUrl", avatarUrl ?: "")
                })
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                android.util.Log.e("VoiceRoomRepository", "❌ Socket connect error: ${args[0]}")
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                android.util.Log.e("VoiceRoomRepository", "🔌 SIGNALING DISCONNECTED")
            }

            socket?.on("user-joined") { args ->
                val data = args[0] as org.json.JSONObject
                val sid = data.getString("socketId")
                val uid = data.getString("userId")
                android.util.Log.i("VoiceRoomRepository", "👤 Remote User Joined: ${data.getString("name")} ($uid) via $sid")
                
                _currentParticipants.update { current ->
                    val p = conectate.com.data.model.VoiceParticipant(
                        id = uid, 
                        name = data.getString("name"), 
                        avatarUrl = data.optString("avatarUrl"), 
                        socketId = sid
                    )
                    (current.filterNot { it.socketId == sid || it.id == uid } + p).distinctBy { it.socketId }
                }
                
                webRTCManager?.onUserJoined(sid, uid)

                // v5.0 SINGULARITY: 3-Pulse Resonance
                // Ensures newcomer sees our media even if connection is settling
                if (_currentVideoMode.value != VideoMode.NONE) {
                    scope.launch {
                        repeat(3) { i ->
                            android.util.Log.d("VoiceRoomRepository", "Resonance Pulse ${i+1}/3 for newcomer: $sid")
                            socket?.emit("media-status", org.json.JSONObject().apply {
                                put("roomId", roomId)
                                put("isCameraSharing", _currentVideoMode.value == VideoMode.CAMERA)
                                put("isScreenSharing", _currentVideoMode.value == VideoMode.SCREEN)
                                put("userId", authService.getCurrentUserId())
                            })
                            delay(1000) // 1s spacing between pulses
                        }
                    }
                }
            }
            
            socket?.on("user-left") { args ->
                val socketId = args[0] as String
                android.util.Log.d("VoiceRoomRepository", "Socket user-left: $socketId")
                
                // v7.2-MVP: Host Disconnect Safety
                val leavingParticipant = _currentParticipants.value.find { it.socketId == socketId }
                scope.launch {
                    val room = currentRoomId?.let { getRoomById(it).getOrNull() }
                    if (room != null && leavingParticipant?.id == room.creatorId) {
                        android.util.Log.i("VoiceRoomRepo", "Host left. Closing cinema.")
                        emitCinemaState(room.id, CinemaState(isCinemaMode = false))
                    }
                }

                val currentList = _currentParticipants.value.toMutableList()
                currentList.removeIf { it.socketId == socketId }
                _currentParticipants.value = currentList
                
                // CRITICAL: Clean up
                webRTCManager?.onUserLeft(socketId)
            }

            socket?.on("current-participants") { args ->
                val participantsJson = args[0] as org.json.JSONArray
                android.util.Log.i("VoiceRoomRepository", "📋 Received Participant List: ${participantsJson.length()} users in room $roomId")
                
                val selfId = authService.getCurrentUserId()
                _currentParticipants.update { current ->
                    val newList = current.toMutableList()
                    val newSharingStates = _sharingStates.value.toMutableMap()
                    
                    for (i in 0 until participantsJson.length()) {
                        val p = participantsJson.getJSONObject(i)
                        val sid = p.getString("socketId"); val uid = p.getString("userId")
                        
                        if (uid != selfId) {
                            val participant = conectate.com.data.model.VoiceParticipant(
                                id = uid, 
                                name = p.getString("name"), 
                                avatarUrl = p.optString("avatarUrl"), 
                                socketId = sid
                            )
                            
                            // Extract initial sharing state for visibility
                            val isCam = p.optBoolean("isCameraSharing", false)
                            val isScreen = p.optBoolean("isScreenSharing", false)
                            newSharingStates[sid] = Pair(isCam, isScreen)
                            
                            if (newList.none { it.socketId == sid }) {
                                newList.add(participant)
                                webRTCManager?.onUserJoined(sid, uid)
                            }
                        }
                    }
                    _sharingStates.value = newSharingStates
                    newList.distinctBy { it.socketId }
                }
            }

            socket?.on("media-status") { args ->
                val data = args[0] as org.json.JSONObject
                val sid = if (args.size > 1) args[1] as String else ""
                if (sid.isNotEmpty()) {
                    val isCamera = data.optBoolean("isCameraSharing", false)
                    val isScreen = data.optBoolean("isScreenSharing", false)
                    
                    val map = _sharingStates.value.toMutableMap()
                    map[sid] = Pair(isCamera, isScreen)
                    _sharingStates.value = map
                    android.util.Log.d("VoiceRoomRepository", "🎥 Media status update for $sid: cam=$isCamera, screen=$isScreen")
                }
            }

            socket?.on("mute-state") { args ->
                val data = args[0] as org.json.JSONObject
                val uid = data.getString("userId")
                val isMuted = data.getBoolean("isMuted")
                android.util.Log.d("VoiceRoomRepository", "🔇 Remote Mute State ($uid): $isMuted")
                
                _currentParticipants.update { current ->
                    current.map { if (it.id == uid) it.copy(isMuted = isMuted) else it }
                }
            }

            socket?.on("offer") { args ->
                val data = args[0] as org.json.JSONObject
                val senderSocketId = data.getString("sender") 
                // CRITICAL FIX: Read userId directly from payload to avoid race condition with participant list
                val senderUserId = if (data.has("userId")) data.getString("userId") else {
                    _currentParticipants.value.find { it.socketId == senderSocketId }?.id ?: ""
                }
                
                val offerSdp = data.getJSONObject("offer").getString("sdp")
                webRTCManager?.onOfferReceived(senderSocketId, senderUserId, offerSdp)
            }

            socket?.on("answer") { args ->
                val data = args[0] as org.json.JSONObject
                val sender = data.getString("sender")
                val answerSdp = data.getJSONObject("answer").getString("sdp")
                webRTCManager?.onAnswerReceived(sender, answerSdp)
            }

            socket?.on("ice-candidate") { args ->
                val data = args[0] as org.json.JSONObject
                val sender = data.getString("sender")
                val candidate = data.getJSONObject("candidate")
                webRTCManager?.onIceCandidateReceived(sender, candidate)
            }

            socket?.on("chat-message") { args ->
                 val data = args[0] as org.json.JSONObject
                 val msg = conectate.com.presentation.voicerooms.ChatMessage(
                    userId = data.getString("userId"),
                    userName = data.getString("userName"),
                    text = data.getString("text")
                )
                scope.launch { _incomingMessages.emit(msg) }
            }

            socket?.on("reaction") { args ->
                val data = args[0] as org.json.JSONObject
                val reaction = conectate.com.presentation.voicerooms.ReactionEvent(
                    userId = data.getString("userId"),
                    emoji = data.getString("emoji")
                )
                scope.launch { _incomingReactions.emit(reaction) }
            }

            socket?.on("speaking-status") { args ->
                val data = args[0] as org.json.JSONObject
                val speakerId = data.getString("userId")
                val isSpeaking = data.getBoolean("isSpeaking")
                _currentParticipants.value = _currentParticipants.value.map {
                    if (it.id == speakerId) it.copy(isSpeaking = isSpeaking) else it
                }
            }

            socket?.on("cinema-state") { args ->
                val data = args[0] as org.json.JSONObject
                val state = CinemaState(
                    isCinemaMode = data.getBoolean("isCinemaMode"),
                    videoUrl = if (data.has("videoUrl")) data.getString("videoUrl") else null,
                    videoTitle = if (data.has("videoTitle")) data.getString("videoTitle") else null,
                    isPlaying = if (data.has("isPlaying")) data.getBoolean("isPlaying") else false,
                    currentPosition = if (data.has("currentPosition")) data.getLong("currentPosition") else 0L
                )
                scope.launch { _cinemaState.value = state }
            }

            socket?.on("cinema-sync") { args ->
                val data = args[0] as org.json.JSONObject
                val sync = CinemaSyncEvent(
                    isPlaying = data.getBoolean("isPlaying"),
                    currentPosition = data.getLong("currentPosition")
                )
                scope.launch { _cinemaSync.emit(sync) }
            }



            socket?.on("link-resolved") { args ->
                val data = args[0] as org.json.JSONObject
                val reqId = data.getString("requestId")
                val deferred = pendingResolutions.remove(reqId)
                if (deferred != null) {
                    if (data.has("error")) {
                         deferred.complete(CinemaResolvedItem("", "", data.getString("error")))
                    } else {
                         deferred.complete(CinemaResolvedItem(
                             url = data.getString("stream_url"),
                             title = data.optString("title", "Video")
                         ))
                    }
                }
            }

            socket?.on("cinema-control") { args ->
                val data = args[0] as org.json.JSONObject
                val sync = CinemaSyncEvent(
                    isPlaying = if (data.has("isPlaying")) data.getBoolean("isPlaying") else true,
                    currentPosition = data.getLong("position"),
                    action = data.getString("action")
                )
                scope.launch { _cinemaSync.emit(sync) }
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                android.util.Log.e("VoiceRoomRepository", "Socket connect error: ${args[0]}")
            }

            socket?.connect()
        } catch (e: Exception) {
            android.util.Log.e("VoiceRoomRepository", "Failed to setup Socket.io", e)
        }
    }

    // Signaling Hooks called by Repository
    private fun initializeWebRTC(roomId: String, userId: String) {
        val events = object : conectate.com.data.webrtc.SignalingEvents {
            override fun onSendOffer(targetSocketId: String, offer: org.json.JSONObject) {
                val data = org.json.JSONObject().apply {
                    put("target", targetSocketId)
                    put("offer", offer)
                    put("userId", userId) // CRITICAL: Send ID for polite check
                }
                socket?.emit("offer", data)
            }

            override fun onSendAnswer(targetSocketId: String, answer: org.json.JSONObject) {
                val data = org.json.JSONObject().apply {
                    put("target", targetSocketId)
                    put("answer", answer)
                }
                socket?.emit("answer", data)
            }

            override fun onSendIceCandidate(targetSocketId: String, candidate: org.json.JSONObject) {
                val data = org.json.JSONObject().apply {
                    put("target", targetSocketId)
                    put("candidate", candidate)
                }
                socket?.emit("ice-candidate", data)
            }
        }
        
        webRTCManager = WebRTCManager(context, events)
        // INITIAL IDENTITY SET
        webRTCManager?.setCurrentUser(userId, "")
        
        // Collect video tracks
        scope.launch {
            webRTCManager?.remoteVideoTracks?.collect { tracks ->
                android.util.Log.d("VoiceRoomRepository", "Remote video tracks updated: Count=${tracks.size}, Keys=${tracks.keys}")
                _remoteVideoTracks.value = tracks
            }
        }
        
        scope.launch {
            webRTCManager?.localVideoTrack?.collect { track ->
                android.util.Log.d("VoiceRoomRepository", "Local video track updated: ${if(track != null) "ACTIVE" else "NULL"}")
                _localVideoTrack.value = track
            }
        }
        
        scope.launch {
            webRTCManager?.peerConnectionStates?.collect { states ->
                _peerConnectionStates.value = states
            }
        }
        
        scope.launch {
            webRTCManager?.signalingStates?.collect { states ->
                _signalingStates.value = states
            }
        }

        scope.launch {
            webRTCManager?.debugInfo?.collect { info ->
                _debugInfo.value = info
            }
        }
    }

    fun toggleCameraSharing(isSharing: Boolean) {
        socket?.emit("media-status", org.json.JSONObject().apply {
            put("roomId", currentRoomId)
            put("isCameraSharing", isSharing)
            put("isScreenSharing", _currentVideoMode.value == VideoMode.SCREEN)
        })
    }

    fun toggleScreenSharing(isSharing: Boolean) {
        socket?.emit("media-status", org.json.JSONObject().apply {
            put("roomId", currentRoomId)
            put("isCameraSharing", _currentVideoMode.value == VideoMode.CAMERA)
            put("isScreenSharing", isSharing)
            put("userId", authService.getCurrentUserId())
        })
    }

    fun startScreenCapture(intent: android.content.Intent) {
        webRTCManager?.startVideo(isScreen = true, intent = intent)
        _currentVideoMode.value = VideoMode.SCREEN
        // Notify others
        socket?.emit("media-status", org.json.JSONObject().apply {
            put("roomId", currentRoomId)
            put("isCameraSharing", false)
            put("isScreenSharing", true)
            put("userId", authService.getCurrentUserId())
        })
    }

    fun startCameraCapture(isFront: Boolean = true) {
        webRTCManager?.startVideo(isScreen = false, isFront = isFront)
        _currentVideoMode.value = VideoMode.CAMERA
        // Notify others
        socket?.emit("media-status", org.json.JSONObject().apply {
            put("roomId", currentRoomId)
            put("isCameraSharing", true)
            put("isScreenSharing", false)
            put("userId", authService.getCurrentUserId())
        })
    }

    fun stopVideo() {
        android.util.Log.d("VoiceRoomRepo", "Stopping local video/screen share")
        webRTCManager?.stopVideo()
        _currentVideoMode.value = VideoMode.NONE
        // Notify others
        socket?.emit("media-status", org.json.JSONObject().apply {
            put("roomId", currentRoomId)
            put("isCameraSharing", false)
            put("isScreenSharing", false)
            put("userId", authService.getCurrentUserId())
        })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun leaveRoom(roomId: String? = null) = withContext(Dispatchers.IO + NonCancellable) {
        try {
            val actualRoomId = roomId ?: currentRoomId
            android.util.Log.d("VoiceRoomRepository", "Leaving room: $actualRoomId")

            // 1. Disconnect Socket and Realtime FIRST to avoid incoming signal processing
            socket?.disconnect()
            socket?.off()
            socket = null

            roomChannel?.unsubscribe()
            roomChannel = null

            // 2. Update DB while we still have data
            if (actualRoomId != null) {
                try {
                    decrementParticipantCount(actualRoomId)
                    delay(50)
                } catch (e: Exception) { }
            }

            // 3. Purge media state but KEEP factory alive for rapid re-entry
            // v6.0: Changed destroy() to hardReset() to avoid hardware driver locks.
            webRTCManager?.hardReset()
            // webRTCManager = null // Keep instance alive for warm launch next time

            // 4. Reset UI state & Cinema State
            _currentParticipants.value = emptyList()
            _remoteVideoTracks.value = emptyMap()
            _peerConnectionStates.value = emptyMap()
            _sharingStates.value = emptyMap()
            _currentVideoMode.value = VideoMode.NONE
            _localVideoTrack.value = null
            _cinemaState.value = null // Clear cinema state
            _cinemaSync.resetReplayCache() // Purge sync events
            _currentRoom.value = null

            currentRoomId = null
            android.util.Log.d("VoiceRoomRepository", "Cleanup complete")
            Unit
        } catch (e: Exception) {
            android.util.Log.e("VoiceRoomRepository", "Error leaving room: ${e.message}")
        }
    }

    fun toggleMute(isMuted: Boolean) {
        android.util.Log.d("VoiceRoomRepository", "🎤 NUCLEAR MUTE: $isMuted")
        
        // 1. Update Participants UI
        _currentParticipants.value = _currentParticipants.value.map { 
            if (it.id == authService.getCurrentUserId()) it.copy(isMuted = isMuted) else it
        }
        
        // 2. HARDWARE MUTE (System Level) -> This is the nuclear option
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.isMicrophoneMute = isMuted
            android.util.Log.w("VoiceRoomRepository", "System Microphone Mute set to: ${audioManager.isMicrophoneMute}")
        } catch (e: Exception) {
            android.util.Log.e("VoiceRoomRepository", "Failed to set System Mic Mute", e)
        }

        // 3. WebRTC Track Disable (Redundancy)
        webRTCManager?.setAudioEnabled(!isMuted)
        
        // 4. Broadcast mute state
        scope.launch {
             socket?.emit("mute-state", org.json.JSONObject().apply {
                put("roomId", currentRoomId ?: "")
                put("userId", authService.getCurrentUserId() ?: "")
                put("isMuted", isMuted)
             })
        }
    }
    
    fun getEglBaseContext(): org.webrtc.EglBase.Context? {
        return webRTCManager?.getEglBaseContext()
    }

    fun toggleSpeaker(on: Boolean) {
        webRTCManager?.setSpeakerphoneOn(on)
    }

    fun setParticipantVolume(socketId: String, volume: Double) {
        webRTCManager?.setAudioVolume(socketId, volume)
    }

    fun setSpeakingStatus(roomId: String, userId: String, isSpeaking: Boolean) {
        scope.launch {
            try {
                socket?.emit("speaking-status", org.json.JSONObject().apply {
                    put("roomId", roomId)
                    put("userId", userId)
                    put("isSpeaking", isSpeaking)
                })
            } catch (e: Exception) { }
        }
    }

    suspend fun deleteRoom(roomId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseConfig.client.postgrest["voice_rooms"].delete {
                filter { eq("id", roomId) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun subscribeToVoiceRooms() {
        try {
            val channel = supabaseConfig.client.channel("voice-rooms-global")
            val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "voice_rooms"
            }

            scope.launch {
                changes.collect { change ->
                    refreshRooms()
                }
            }
            channel.subscribe()
        } catch (e: Exception) {
            delay(5000)
            subscribeToVoiceRooms()
        }
    }
    
    private suspend fun incrementParticipantCount(roomId: String) {
        try {
            android.util.Log.d("VoiceRoomRepository", "Incrementing participant count for room: $roomId")
            // Cast to UUID explicitly to avoid ambiguity with overloaded functions
            supabaseConfig.client.postgrest.rpc("increment_participant_count", buildJsonObject {
                put("room_id", roomId) // Supabase will handle UUID casting
            })
            android.util.Log.d("VoiceRoomRepository", "Successfully incremented participant count")
            
            // Force immediate refresh to update UI
            refreshRooms()
            android.util.Log.d("VoiceRoomRepository", "Triggered room list refresh after increment")
        } catch (e: Exception) {
           android.util.Log.e("VoiceRoomRepository", "Failed to increment participant count for $roomId", e)
           android.util.Log.e("VoiceRoomRepository", "Error details: ${e.message}")
        }
    }

    private suspend fun decrementParticipantCount(roomId: String) {
        var attempts = 0
        val maxAttempts = 3
        
        while (attempts < maxAttempts) {
            try {
                attempts++
                android.util.Log.d("VoiceRoomRepository", "Decrementing participant count for room: $roomId (attempt $attempts/$maxAttempts)")
                
                // Cast to UUID explicitly to avoid ambiguity with overloaded functions
                supabaseConfig.client.postgrest.rpc("decrement_participant_count", buildJsonObject {
                    put("room_id", roomId) // Supabase will handle UUID casting
                })
                
                android.util.Log.d("VoiceRoomRepository", "Successfully decremented participant count")
                
                // Force immediate refresh to update UI
                refreshRooms()
                android.util.Log.d("VoiceRoomRepository", "Triggered room list refresh after decrement")
                break
                
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepository", "Failed to decrement participant count for $roomId (attempt $attempts/$maxAttempts)", e)
                android.util.Log.e("VoiceRoomRepository", "Error details: ${e.message}")
                
                if (attempts < maxAttempts) {
                    delay(500) // Wait before retry
                } else {
                    android.util.Log.e("VoiceRoomRepository", "All decrement attempts failed, giving up")
                }
            }
        }
    }

    // Invitations
    private val _incomingInvitation = MutableStateFlow<Invitation?>(null)
    val incomingInvitation = _incomingInvitation.asStateFlow()

    suspend fun subscribeToInvitations(userId: String) {
        val channelName = "user:$userId"
        android.util.Log.d("VoiceRoomRepo", "=== SUBSCRIBING TO INVITATIONS ===")
        android.util.Log.d("VoiceRoomRepo", "User ID: $userId")
        android.util.Log.d("VoiceRoomRepo", "Channel: $channelName")
        
        val userChannel = supabaseConfig.client.channel(channelName)
        
        scope.launch {
            android.util.Log.d("VoiceRoomRepo", "Starting to listen for invitation broadcasts...")
            userChannel.broadcastFlow<Map<String, Any>>("invitation").collect { payload ->
                try {
                    android.util.Log.d("VoiceRoomRepo", "=== INVITATION RECEIVED ===")
                    android.util.Log.d("VoiceRoomRepo", "Raw payload: $payload")
                    
                    val inviterName = payload["inviterName"].toString().replace("\"", "")
                    val roomName = payload["roomName"].toString().replace("\"", "")
                    val roomId = payload["roomId"].toString().replace("\"", "")
                    
                    android.util.Log.d("VoiceRoomRepo", "Parsed - Inviter: $inviterName, Room: $roomName, ID: $roomId")
                    
                    _incomingInvitation.value = Invitation(roomId, roomName, inviterName)
                    android.util.Log.d("VoiceRoomRepo", "Invitation state updated, showing overlay")
                    
                    // Auto-dismiss after 10 seconds
                    scope.launch {
                        delay(10000)
                        if (_incomingInvitation.value?.roomId == roomId) {
                            android.util.Log.d("VoiceRoomRepo", "Auto-dismissing invitation for room $roomId")
                            _incomingInvitation.value = null
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VoiceRoomRepo", "Error parsing invitation", e)
                    android.util.Log.e("VoiceRoomRepo", "Payload was: $payload")
                }
            }
        }
        
        android.util.Log.d("VoiceRoomRepo", "Subscribing to channel...")
        userChannel.subscribe()
        android.util.Log.d("VoiceRoomRepo", "=== SUBSCRIPTION ACTIVE ===")
    }

    suspend fun sendInvitation(targetUserId: String, roomId: String, roomName: String, inviterName: String) {
         try {
             android.util.Log.d("VoiceRoomRepo", "=== SENDING INVITATION ===")
             android.util.Log.d("VoiceRoomRepo", "Target User: $targetUserId")
             android.util.Log.d("VoiceRoomRepo", "Room: $roomName ($roomId)")
             android.util.Log.d("VoiceRoomRepo", "Inviter: $inviterName")
             
             val channelName = "user:$targetUserId"
             android.util.Log.d("VoiceRoomRepo", "Creating channel: $channelName")
             val targetChannel = supabaseConfig.client.channel(channelName)
             
             // Ensure we are subscribed before broadcasting
             android.util.Log.d("VoiceRoomRepo", "Subscribing to channel...")
             targetChannel.subscribe(blockUntilSubscribed = true)
             android.util.Log.d("VoiceRoomRepo", "Channel subscribed successfully")
             
             // Use JsonObject for proper serialization
             val invitationData = buildJsonObject {
                 put("inviterName", inviterName)
                 put("roomName", roomName)
                 put("roomId", roomId)
             }
             android.util.Log.d("VoiceRoomRepo", "Broadcasting invitation data: $invitationData")
             
             targetChannel.broadcast(
                 event = "invitation",
                 message = invitationData
             )
             
             android.util.Log.d("VoiceRoomRepo", "Broadcast sent, waiting 2s for propagation...")
             delay(2000) // Increased delay for better reliability
             
             android.util.Log.d("VoiceRoomRepo", "Unsubscribing from channel")
             targetChannel.unsubscribe()
             android.util.Log.d("VoiceRoomRepo", "=== INVITATION SENT SUCCESSFULLY ===")
         } catch (e: Exception) {
             android.util.Log.e("VoiceRoomRepo", "=== INVITATION FAILED ===", e)
             android.util.Log.e("VoiceRoomRepo", "Error details: ${e.message}")
             android.util.Log.e("VoiceRoomRepo", "Stack trace: ${e.stackTraceToString()}")
         }
    }
    
    fun dismissInvitation() {
        _incomingInvitation.value = null
    }

    suspend fun sendMessage(message: conectate.com.presentation.voicerooms.ChatMessage) = withContext(Dispatchers.IO) {
        socket?.emit("chat-message", org.json.JSONObject().apply {
            put("roomId", currentRoomId ?: "")
            put("userId", message.userId)
            put("userName", message.userName)
            put("text", message.text)
        })
    }

    suspend fun sendReaction(reaction: conectate.com.presentation.voicerooms.ReactionEvent) = withContext(Dispatchers.IO) {
        socket?.emit("reaction", org.json.JSONObject().apply {
            put("roomId", currentRoomId ?: "")
            put("userId", reaction.userId)
            put("emoji", reaction.emoji)
        })
    }
    suspend fun updateRoom(roomId: String, name: String, isPublic: Boolean, password: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updateData = mutableMapOf<String, Any>(
                "name" to name,
                "is_public" to isPublic
            )
            if (password != null) {
                updateData["password"] = password
            }

            supabaseConfig.client.postgrest["voice_rooms"].update(updateData) {
                filter { eq("id", roomId) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun emitCinemaState(roomId: String, state: CinemaState) {
        val userId = authService.getCurrentUserId() ?: return
        
        // v7.2.4: Update local state IMMEDIATELY so Creator sees the change without waiting for broadcast
        _cinemaState.value = state
        
        // v7.2.3: Offload everything to IO and minimize blocking logic
        scope.launch {
            // we trust the ViewModel to have checked hostId for the UI part, 
            // but we'll do a quick check here against current roomId if we have it
            if (currentRoomId != roomId) return@launch

            // 1. Alert others via Socket (Instant)
            try {
                socket?.emit("cinema-state", org.json.JSONObject().apply {
                    put("roomId", roomId)
                    put("isCinemaMode", state.isCinemaMode)
                    put("videoUrl", state.videoUrl ?: "")
                    put("videoTitle", state.videoTitle ?: "")
                    put("isPlaying", state.isPlaying)
                    put("currentPosition", state.currentPosition)
                })
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepo", "Socket emit error", e)
            }

            // 2. Persist to Supabase (Non-blocking Persistence)
            try {
                supabaseConfig.client.postgrest["voice_rooms"].update(mapOf(
                    "is_cinema_mode" to state.isCinemaMode,
                    "current_video_url" to state.videoUrl,
                    "current_video_title" to state.videoTitle,
                    "is_playing" to state.isPlaying,
                    "current_position" to state.currentPosition
                )) {
                    filter { eq("id", roomId) }
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepo", "Failed to persist cinema state", e)
            }
        }
    }

    fun emitCinemaSync(roomId: String, isPlaying: Boolean, position: Long) {
        val userId = authService.getCurrentUserId() ?: return
        scope.launch {
            // v7.2.3: Defensive check to avoid signaling storms
            if (roomChannel == null) return@launch 
            
            try {
                socket?.emit("cinema-sync", org.json.JSONObject().apply {
                    put("roomId", roomId)
                    put("isPlaying", isPlaying)
                    put("currentPosition", position)
                })
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepo", "Cinema sync error", e)
            }
        }
    }

    fun sendMessage(roomId: String, message: String) {
        val userId = authService.getCurrentUserId() ?: return
        // v23.0: Restoring chat functionality with fresh socket reference
        scope.launch {
            try {
                android.util.Log.d("VoiceRoomRepo", "📤 Sending message: $message")
                socket?.emit("chat-message", org.json.JSONObject().apply {
                    put("roomId", roomId)
                    put("userId", userId)
                    put("userName", _currentParticipants.value.find { it.id == userId }?.name ?: "User")
                    put("text", message)
                })
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepo", "Failed to send message", e)
            }
        }
    }

    fun sendReaction(roomId: String, emoji: String) {
        val userId = authService.getCurrentUserId() ?: return
        scope.launch {
            try {
                socket?.emit("reaction", org.json.JSONObject().apply {
                    put("roomId", roomId)
                    put("userId", userId)
                    put("emoji", emoji)
                })
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepo", "Failed to send reaction", e)
            }
        }
    }

    fun emitCinemaControl(roomId: String, action: String, position: Long) {
        val userId = authService.getCurrentUserId() ?: return
        scope.launch {
            // v7.2.3: Force IO and defensive checks
            if (roomId.isEmpty()) return@launch
            try {
                socket?.emit("cinema-control", org.json.JSONObject().apply {
                    put("roomId", roomId)
                    put("action", action)
                    put("position", position)
                })
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepo", "Cinema control error", e)
            }
        }
    }
}

data class Invitation(
    val roomId: String,
    val roomName: String,
    val inviterName: String
)

data class CinemaState(
    val isCinemaMode: Boolean,
    val videoUrl: String? = null,
    val videoTitle: String? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L
)

data class CinemaSyncEvent(
    val isPlaying: Boolean,
    val currentPosition: Long,
    val action: String? = null
)
