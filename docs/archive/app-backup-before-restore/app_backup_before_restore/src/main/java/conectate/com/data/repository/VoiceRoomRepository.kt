package conectate.com.data.repository

import conectate.com.data.model.VoiceParticipant
import conectate.com.data.model.VoiceRoom
import conectate.com.core.concurrency.nextRetryDelayMs
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.crash.CrashReporter
import conectate.com.core.observability.AppMetrics
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.*
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
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
import io.socket.client.Manager
import io.socket.client.Socket
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.VideoTrack
import conectate.com.data.signaling.SignalingConfig
import conectate.com.data.signaling.attachBearerToken
import conectate.com.util.AppPerformanceProfiles

enum class VoiceConnectionState(val label: String) {
    IDLE("Sin sala"),
    CONNECTING("Conectando"),
    CONNECTED("Conectado"),
    RECOVERING_AUDIO("Recuperando audio"),
    RECONNECTING("Reconectando"),
    ERROR("Error")
}

@Singleton
class VoiceRoomRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val appMetrics: AppMetrics,
    private val crashReporter: CrashReporter
) {
    private fun extractJsonPayload(args: Array<Any>): JSONObject? {
        var first = args.firstOrNull() ?: return null
        return first as? JSONObject
    }

    private fun extractSocketIdPayload(args: Array<Any>): String {
        var first = args.firstOrNull()
        return when (first) {
            is String -> first.trim()
            is JSONObject -> first.optString("socketId").trim()
            else -> ""
        }
    }

    private companion object {
        private const val DEFAULT_ROOM_CAPACITY = 50
        private const val SOCKET_RECONNECT_ATTEMPTS = 10
        private const val SOCKET_RECONNECT_DELAY_MS = 1_000L
        private const val SOCKET_RECONNECT_DELAY_MAX_MS = 12_000L
        private const val SUBSCRIPTION_RETRY_INITIAL_MS = 2_000L
        private const val SUBSCRIPTION_RETRY_MAX_MS = 30_000L
        private const val SUBSCRIPTION_RESTART_GRACE_MS = 1_000L
        private const val AUDIO_RECOVERY_ATTEMPTS = 4
        private const val AUDIO_RECOVERY_DELAY_MS = 275L
    }

    private data class PendingSocketRoomEvent(
        val roomId: String,
        val event: String,
        val payload: String
    )

    private val scope = newSafeScope("VoiceRoomRepository", Dispatchers.IO)
    private var json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json charset=utf-8".toMediaTypeOrNull()
    private val performanceProfile by lazy(LazyThreadSafetyMode.NONE) {
        AppPerformanceProfiles.detect(context)
    }

    // WebRTC & Realtime
    private var webRTCManager: WebRTCManager? = null
    private var roomChannel: RealtimeChannel? = null
    private var socket: Socket? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioRecoveryJob: Job? = null
    private val pendingSocketRoomEvents = mutableListOf<PendingSocketRoomEvent>()
    private var pendingConnectMetricName: String? = null
    private var pendingConnectStartedAtMs: Long? = null
    private var reconnectFailureCount = 0
    
    // VPS signaling server (Socket.IO over HTTPS)
    private val SIGNALING_URL = SignalingConfig.VOICE_URL
    
    // Exposed for notification navigation
    fun getCurrentRoomId(): String? = activeRoomId
    private val _currentRoomId = MutableStateFlow<String?>(null)
    private var activeRoomId: String?
        get() = _currentRoomId.value
        set(value) {
            _currentRoomId.value = value
        }
    
    private val _activeRooms = MutableStateFlow<List<VoiceRoom>>(emptyList())
    val activeRooms = _activeRooms.asStateFlow()

    private val _currentRoom = MutableStateFlow<VoiceRoom?>(null)
    val currentRoom = _currentRoom.asStateFlow()

    private val _currentParticipants = MutableStateFlow<List<VoiceParticipant>>(emptyList())
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
    var sharingStates = _sharingStates.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<conectate.com.presentation.voicerooms.ChatMessage>()
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _incomingReactions = MutableSharedFlow<conectate.com.presentation.voicerooms.ReactionEvent>()
    val incomingReactions = _incomingReactions.asSharedFlow()

    private val _connectionState = MutableStateFlow(VoiceConnectionState.IDLE)
    var connectionState = _connectionState.asStateFlow()

    private val _lastConnectionError = MutableStateFlow<String?>(null)
    val lastConnectionError = _lastConnectionError.asStateFlow()
    
    // CRITICAL: Single source of truth for video mode
    enum class VideoMode { NONE, CAMERA, SCREEN }
    private val _currentVideoMode = MutableStateFlow(VideoMode.NONE) 

    private val baseSessionState: Flow<VoiceSessionState> = combine(
        _currentRoomId,
        _currentRoom,
        _currentParticipants,
        _currentVideoMode,
        _connectionState
    ) { roomId, room, participants, videoMode, connectionState ->
        var resolvedRoomId = roomId ?: room?.id?.takeIf { it.isNotBlank() }
        var participantCount = maxOf(room?.participantCount ?: 0, participants.size)
        val normalizedRoom = room?.let(::normalizeRoomForUi)?.copy(participantCount = participantCount)
        VoiceSessionState(
            roomId = resolvedRoomId,
            room = normalizedRoom,
            roomName = normalizedRoom?.name?.takeIf { it.isNotBlank() } ?: "Sala de Voz",
            readableId = normalizedRoom?.readableId,
            participantCount = participantCount,
            isMuted = isSelfMuted(participants),
            isConnected = !resolvedRoomId.isNullOrBlank(),
            videoMode = videoMode,
            connectionState = connectionState
        )
    }

    val sessionState: StateFlow<VoiceSessionState> = combine(
        baseSessionState,
        _lastConnectionError
    ) { session, lastConnectionError ->
        session.copy(connectionError = lastConnectionError)
    }.stateIn(scope, SharingStarted.Eagerly, VoiceSessionState())

    private fun isSelfMuted(participants: List<VoiceParticipant> = _currentParticipants.value): Boolean {
        var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase().orEmpty()
        if (currentUserId.isBlank()) return false
        return participants.any { participant ->
            participant.id.trim().lowercase() == currentUserId && participant.isMuted
        }
    }

    private fun updateConnectionState(
        state: VoiceConnectionState,
        errorMessage: String? = null
    ) {
        _connectionState.value = state
        _lastConnectionError.value = if (state == VoiceConnectionState.ERROR) {
            errorMessage
        } else {
            null
        }
    }

    private fun currentMetricAttributes(
        reason: String? = null,
        roomId: String? = activeRoomId
    ): Map<String, String> {
        var resolvedRoomId = roomId
            ?: sessionState.value.roomId
            ?: _currentRoom.value?.id
            ?: ""
        var videoMode = _currentVideoMode.value.name.lowercase()
        var isSocketConnected = (socket?.connected() == true).toString()
        val hasActiveSession = sessionState.value.hasActiveSession.toString()
        return buildMap {
            put("roomId", resolvedRoomId.ifBlank { "none" })
            put("videoMode", videoMode)
            put("hasActiveSession", hasActiveSession)
            put("socketConnected", isSocketConnected)
            reason?.takeIf { it.isNotBlank() }?.let { put("audioRecoveryReason", it) }
        }
    }

    private fun matchesActiveRoom(roomId: String): Boolean {
        val normalized = roomId.trim().lowercase()
        if (normalized.isBlank()) return false
        val session = sessionState.value
        return listOfNotNull(
            activeRoomId,
            session.roomId,
            session.room?.id,
            session.room?.readableId,
            _currentRoom.value?.id,
            _currentRoom.value?.readableId
        ).any { candidate ->
            candidate.trim().lowercase() == normalized
        }
    }

    fun hasActiveSessionFor(roomId: String): Boolean {
        return sessionState.value.hasActiveSession && matchesActiveRoom(roomId)
    }

    suspend fun attachToActiveSession(
        roomId: String,
        reason: String = "screen_attach"
    ): Result<VoiceRoom?> = withContext(Dispatchers.IO) {
        if (!hasActiveSessionFor(roomId)) {
            return@withContext Result.failure(IllegalStateException("No hay una sala activa compatible para adjuntar."))
        }

        appMetrics.incrementCounter(
            metric = "voice_room_attach",
            attributes = currentMetricAttributes(reason = reason, roomId = roomId)
        )
        pendingConnectMetricName = "voice_room_attach"
        pendingConnectStartedAtMs = System.currentTimeMillis()

        val attachedRoom = _currentRoom.value ?: getRoomById(roomId).getOrNull()?.let(::normalizeRoomForUi)
        if (attachedRoom != null) {
            _currentRoom.value = attachedRoom
            activeRoomId = attachedRoom.id
        }
        restoreActiveSession(reason)
        Result.success(_currentRoom.value)
    }

    private fun prepareVoiceAudioSession(forceMicUnmute: Boolean = true) {
        var audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                var request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        android.util.Log.d("VoiceRoomRepository", "Audio focus changed: $focusChange")
                    }
                    .build()
                audioFocusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (forceMicUnmute) {
                audioManager.isMicrophoneMute = false
            }
            forceSpeakerOutput(audioManager)
        } catch (e: Exception) {
            android.util.Log.w("VoiceRoomRepository", "Failed to prepare voice audio session", e)
        }
    }

    private fun reinforceAudioAfterVideoTransition(
        reason: String,
        attempts: Int = AUDIO_RECOVERY_ATTEMPTS,
        delayMs: Long = AUDIO_RECOVERY_DELAY_MS
    ) {
        audioRecoveryJob?.cancel()
        audioRecoveryJob = scope.launch {
            updateConnectionState(VoiceConnectionState.RECOVERING_AUDIO)
            appMetrics.incrementCounter(
                metric = "voice_audio_recovery",
                attributes = currentMetricAttributes(reason = reason)
            )

            var recovered = false
            var lastFailure: Throwable? = null
            for (index in 0 until attempts.coerceAtLeast(1)) {
                runCatching {
                    prepareVoiceAudioSession(forceMicUnmute = true)
                    webRTCManager?.ensureLocalTracks()
                    webRTCManager?.setRemoteAudioPlaybackEnabled(true)
                    webRTCManager?.setAudioEnabled(!isSelfMuted())
                    if (socket?.connected() == true) {
                        emitCurrentMediaState()
                        flushPendingRoomSocketEvents()
                    }
                    recovered = webRTCManager?.isHealthy() != false
                }.onFailure { error ->
                    lastFailure = error
                    crashReporter.recordException(
                        throwable = error,
                        extra = currentMetricAttributes(reason = reason)
                    )
                }

                if (recovered || index >= attempts - 1) {
                    break
                }
                delay(delayMs)
            }

            if (recovered) {
                updateConnectionState(
                    if (socket?.connected() == true) {
                        VoiceConnectionState.CONNECTED
                    } else {
                        VoiceConnectionState.RECONNECTING
                    }
                )
            } else {
                var message = lastFailure?.message?.takeIf { it.isNotBlank() }
                    ?: "No pudimos recuperar el audio de la sala."
                appMetrics.recordFailure(
                    metric = "voice_audio_recovery",
                    throwable = lastFailure,
                    attributes = currentMetricAttributes(reason = reason)
                )
                updateConnectionState(VoiceConnectionState.ERROR, message)
            }
            android.util.Log.d("VoiceRoomRepository", "Audio reinforcement complete after $reason")
        }
    }

    fun restoreActiveSession(reason: String) {
        if (!sessionState.value.hasActiveSession && activeRoomId.isNullOrBlank()) return
        reinforceAudioAfterVideoTransition(reason = reason)
    }

    private fun releaseVoiceAudioSession() {
        var audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            android.util.Log.d("VoiceRoomRepository", "Releasing voice audio session (Mode: ${audioManager.mode})")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { 
                    val result = audioManager.abandonAudioFocusRequest(it)
                    android.util.Log.d("VoiceRoomRepository", "Abandon audio focus result: $result")
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            audioManager.isMicrophoneMute = false
            // v25.0: Delayed mode reset to ensure WebRTC has fully detached from hardware
            scope.launch {
                delay(300)
                audioManager.mode = AudioManager.MODE_NORMAL
                android.util.Log.d("VoiceRoomRepository", "Audio mode reset to NORMAL")
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceRoomRepository", "Failed to release voice audio session", e)
        }
    }

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

    private fun currentSocketId(): String = socket?.id()?.trim().orEmpty()

    private fun <T> rekeyWithCurrentParticipants(source: Map<String, T>): Map<String, T> {
        if (source.isEmpty()) return emptyMap()

        var participants = _currentParticipants.value
        val participantsBySocket = participants
            .mapNotNull { participant ->
                var socketId = participant.socketId.trim()
                if (socketId.isBlank()) null else socketId to participant
            }
            .toMap()
        val participantsByUserId = participants
            .mapNotNull { participant ->
                var userId = participant.id.trim().lowercase()
                if (userId.isBlank()) null else userId to participant
            }
            .toMap()

        val rebound = LinkedHashMap<String, T>(source.size * 2)
        source.forEach { (rawKey, value) ->
            val cleanKey = rawKey.trim()
            if (cleanKey.isBlank()) return@forEach

            rebound[cleanKey] = value

            var participant = participantsBySocket[cleanKey]
                ?: participantsByUserId[cleanKey.lowercase()]

            participant?.let {
                val socketAlias = it.socketId.trim()
                val userAlias = it.id.trim().lowercase()
                if (socketAlias.isNotBlank()) rebound[socketAlias] = value
                if (userAlias.isNotBlank()) rebound[userAlias] = value
            }
        }

        return rebound
    }

    private fun refreshParticipantAliases() {
        _remoteVideoTracks.value = rekeyWithCurrentParticipants(_remoteVideoTracks.value)
        _peerConnectionStates.value = rekeyWithCurrentParticipants(_peerConnectionStates.value)
        _signalingStates.value = rekeyWithCurrentParticipants(_signalingStates.value)
        _debugInfo.value = rekeyWithCurrentParticipants(_debugInfo.value)
        _sharingStates.value = rekeyWithCurrentParticipants(_sharingStates.value)
    }

    private fun updateSharingState(
        socketId: String?,
        userId: String?,
        isCameraSharing: Boolean,
        isScreenSharing: Boolean
    ) {
        var state = isCameraSharing to isScreenSharing
        val updated = _sharingStates.value.toMutableMap()

        val normalizedSocketId = socketId?.trim().orEmpty()
        if (normalizedSocketId.isNotBlank()) {
            updated[normalizedSocketId] = state
        }

        var normalizedUserId = userId?.trim()?.lowercase().orEmpty()
        if (normalizedUserId.isNotBlank()) {
            updated[normalizedUserId] = state
        }

        _sharingStates.value = updated
        refreshParticipantAliases()
    }

    private fun emitCurrentMediaState() {
        socket?.emit("media-status", org.json.JSONObject().apply {
            put("roomId", activeRoomId)
            put("socketId", currentSocketId())
            put("userId", authService.getCurrentUserId())
            put("isCameraSharing", _currentVideoMode.value == VideoMode.CAMERA)
            put("isScreenSharing", _currentVideoMode.value == VideoMode.SCREEN)
        })
    }

    private fun broadcastCurrentMediaState(repeatCount: Int = 1, intervalMs: Long = 0L) {
        scope.launch {
            repeat(repeatCount.coerceAtLeast(1)) { attempt ->
                emitCurrentMediaState()
                if (attempt < repeatCount - 1 && intervalMs > 0L) {
                    delay(intervalMs)
                }
            }
        }
    }

    private fun emitOrQueueRoomSocketEvent(
        roomId: String?,
        event: String,
        payloadBuilder: JSONObject.() -> Unit
    ) {
        var normalizedRoomId = roomId?.trim().orEmpty()
        if (normalizedRoomId.isBlank()) return

        var payload = JSONObject().apply(payloadBuilder)
        var socketRef = socket
        if (socketRef?.connected() == true) {
            socketRef.emit(event, payload)
            return
        }

        synchronized(pendingSocketRoomEvents) {
            pendingSocketRoomEvents += PendingSocketRoomEvent(
                roomId = normalizedRoomId,
                event = event,
                payload = payload.toString()
            )
        }
    }

    private fun flushPendingRoomSocketEvents(roomId: String? = activeRoomId) {
        var normalizedRoomId = roomId?.trim().orEmpty()
        if (normalizedRoomId.isBlank()) return

        var socketRef = socket
        if (socketRef?.connected() != true) return

        val readyEvents = synchronized(pendingSocketRoomEvents) {
            val matches = pendingSocketRoomEvents.filter { pending ->
                pending.roomId.equals(normalizedRoomId, ignoreCase = true)
            }
            pendingSocketRoomEvents.removeAll(matches.toSet())
            matches
        }

        readyEvents.forEach { pending ->
            runCatching {
                socketRef.emit(pending.event, JSONObject(pending.payload))
            }.onFailure { error ->
                android.util.Log.w(
                    "VoiceRoomRepository",
                    "Failed to flush queued room event ${pending.event} for ${pending.roomId}",
                    error
                )
            }
        }
    }

    private fun reinforceVideoPublishing() {
        scope.launch {
            delay(450)
            if (_currentVideoMode.value != VideoMode.NONE) {
                webRTCManager?.setVideoSendEnabled(true, renegotiate = false)
                emitCurrentMediaState()
            }

            delay(900)
            if (_currentVideoMode.value != VideoMode.NONE) {
                webRTCManager?.setVideoSendEnabled(true, renegotiate = false)
                emitCurrentMediaState()
            }
        }
    }

    // v16.0: Universal Link Resolver
    data class CinemaResolvedItem(val url: String, val title: String, val error: String? = null)
    private val pendingResolutions = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<CinemaResolvedItem>>()

    suspend fun resolveUniversalLink(url: String): CinemaResolvedItem = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        var deferred = CompletableDeferred<CinemaResolvedItem>()
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

    suspend fun resolveCinemaItem(item: conectate.com.presentation.voicerooms.ContentItem): CinemaResolvedItem =
        withContext(Dispatchers.IO) {
            val accessToken = authService.getAccessTokenAwait()?.trim().orEmpty()
            if (accessToken.isBlank()) {
                return@withContext CinemaResolvedItem(
                    url = "",
                    title = item.title,
                    error = "No hay una sesión autenticada para resolver contenido premium."
                )
            }

            var payload = JSONObject().apply {
                put("title", item.title)
                put("provider", item.provider)
                put("providerId", item.providerId)
            }

            var request = Request.Builder()
                .url(SignalingConfig.CINEMA_RESOLVE_URL)
                .addHeader("Authorization", "Bearer $accessToken")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    var json = JSONObject(body.ifBlank { "{}" })
                    val resolvedUrl = json.optString("stream_url").trim()
                    val resolvedTitle = json.optString("title").ifBlank { item.title }
                    var error = json.optString("error").ifBlank {
                        if (response.isSuccessful && resolvedUrl.isNotBlank()) {
                            ""
                        } else {
                            "El servidor no pudo resolver este contenido premium."
                        }
                    }

                    if (!response.isSuccessful || resolvedUrl.isBlank()) {
                        return@withContext CinemaResolvedItem(
                            url = "",
                            title = resolvedTitle,
                            error = error
                        )
                    }

                    CinemaResolvedItem(
                        url = resolvedUrl,
                        title = resolvedTitle
                    )
                }
            } catch (e: Exception) {
                CinemaResolvedItem(
                    url = "",
                    title = item.title,
                    error = "Error de conexión con el resolver premium: ${e.message}"
                )
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

    private fun normalizeRoomForUi(room: VoiceRoom): VoiceRoom {
        return room.copy(
            maxParticipants = room.maxParticipants.coerceAtLeast(DEFAULT_ROOM_CAPACITY)
        )
    }

    private fun isHiddenEphemeralRoom(
        room: VoiceRoom,
        currentUserId: String?
    ): Boolean {
        var normalizedUserId = currentUserId?.trim()?.lowercase().orEmpty()
        if (normalizedUserId.isBlank()) return false
        var normalizedRoomId = room.id.trim().lowercase()
        val normalizedCurrentRoomId = activeRoomId?.trim()?.lowercase()
        var normalizedName = room.name.trim().lowercase()
        return !room.isPublic &&
            room.participantCount == 0 &&
            room.creatorId.trim().lowercase() == normalizedUserId &&
            normalizedRoomId != normalizedCurrentRoomId &&
            (normalizedName == "llamada privada" || normalizedName == "sala privada")
    }

    private fun shouldShowRoomInLobby(
        room: VoiceRoom,
        currentUserId: String?
    ): Boolean {
        if (!room.isActive) return false
        if (isHiddenEphemeralRoom(room, currentUserId)) return false
        if (room.isPublic) return true
        var normalizedUserId = currentUserId?.trim()?.lowercase().orEmpty()
        if (normalizedUserId.isBlank()) return false
        return room.creatorId.trim().lowercase() == normalizedUserId
    }

    suspend fun refreshRooms(): Result<List<VoiceRoom>> {
        try {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
            var rooms = supabaseConfig.client.postgrest["voice_rooms"].select {
                filter {
                    eq("is_active", true)
                    if (!currentUserId.isNullOrBlank()) {
                        or {
                            eq("is_public", true)
                            eq("creator_id", currentUserId)
                        }
                    } else {
                        eq("is_public", true)
                    }
                }
            }.decodeList<VoiceRoom>().map(::normalizeRoomForUi)
            val visibleRooms = rooms.filter { shouldShowRoomInLobby(it, currentUserId) }
            _activeRooms.value = visibleRooms

            val normalizedActiveRoomId = activeRoomId?.trim()?.lowercase()
            if (!normalizedActiveRoomId.isNullOrBlank()) {
                _currentRoom.value = rooms.firstOrNull { it.id.trim().lowercase() == normalizedActiveRoomId }
                    ?: getRoomById(normalizedActiveRoomId).getOrNull()?.let(::normalizeRoomForUi)
                    ?: _currentRoom.value
            }
            return Result.success(visibleRooms)
        } catch (e: Exception) {
            android.util.Log.e("VoiceRoomRepository", "Error refreshing rooms", e)
            return Result.failure(e)
        }
    }

    suspend fun getRoomById(id: String): Result<VoiceRoom> = withContext(Dispatchers.IO) {
        try {
            var room = supabaseConfig.client.postgrest["voice_rooms"].select {
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
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            // We can rely on DB to generate ID, or generate here. Let's let DB handle readable_id via trigger.
            // But we need UUID for the object.
            var roomId = UUID.randomUUID().toString()
            
            var room = VoiceRoom(
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
            var normalizedUserId = userId.trim().lowercase()
            val visibleRoom = createdRoom.copy(
                creatorId = createdRoom.creatorId.ifBlank { normalizedUserId }
            ).let(::normalizeRoomForUi)
            if (shouldShowRoomInLobby(visibleRoom, normalizedUserId)) {
                _activeRooms.update { current ->
                    listOf(visibleRoom) + current.filterNot { existing ->
                        existing.id.trim().lowercase() == visibleRoom.id.trim().lowercase()
                    }
                }
            }
            scope.launch { refreshRooms() }
            
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
        pendingConnectMetricName = "voice_room_join"
        pendingConnectStartedAtMs = System.currentTimeMillis()
        reconnectFailureCount = 0
        updateConnectionState(VoiceConnectionState.CONNECTING)
        appMetrics.incrementCounter(
            metric = "voice_room_join_attempt",
            attributes = currentMetricAttributes(roomId = roomId)
        )

        val signalRoom = roomId
        val roomLookupId = signalingRoomId?.takeIf { it.isNotBlank() } ?: roomId
        var isSocketConnected = socket?.connected() == true
        val isWebResHealthy = webRTCManager?.isHealthy() == true
        val isReentry = (activeRoomId == signalRoom && isSocketConnected && isWebResHealthy)
        
        if (isReentry) {
            android.util.Log.w("VoiceRoomRepository", "♻️ RE-ENTRY DETECTED: Already in room $signalRoom. Skipping reset.")
            
            // Ensure self exists in participants (UI sync)
            var self = conectate.com.data.model.VoiceParticipant(
                id = userId,
                name = displayName,
                avatarUrl = avatarUrl,
                socketId = socket?.id() ?: userId
            )
            if (_currentParticipants.value.none { it.id == userId }) {
                _currentParticipants.value = _currentParticipants.value + self
            }
            
            prepareVoiceAudioSession(forceMicUnmute = true)
            webRTCManager?.ensureLocalTracks()
            webRTCManager?.setRemoteAudioPlaybackEnabled(true)
            updateConnectionState(VoiceConnectionState.CONNECTED)
            appMetrics.incrementCounter(
                metric = "voice_room_rejoin_preserved",
                attributes = currentMetricAttributes(roomId = roomId)
            )
            android.util.Log.w("VoiceRoomRepository", "📍 RE-ENTRY completed: Session preserved")
            return@withContext
        } else if (socket?.connected() == true) {
             // Socket connected but WebRTC not healthy? Nuke it to avoid zombies.
             android.util.Log.w("VoiceRoomRepository", "⚠️ Partial state detected (Socket OK, WebRTC Unhealthy). Forcing Hard Reset.")
             webRTCManager?.hardReset()
             // Give it a moment to clear
             kotlinx.coroutines.delay(200)
        }
        
        activeRoomId = signalRoom 
        
        // Sync currentRoom object for UI tracking
        scope.launch {
            getRoomById(roomLookupId).getOrNull()?.let { room ->
                _currentRoom.value = normalizeRoomForUi(room)
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
                    crashReporter.recordException(
                        throwable = e,
                        extra = currentMetricAttributes(roomId = roomId)
                    )
                }
            }

            // v21.0: Reverting seamless re-entry due to "Zombie Session" issues (Audio/Chat loss).
            // We will fallback to a clean reconnection cycle to safeguard functionality.
            // If we are already connected to this room, we MUST leave first to ensure a fresh, valid socket ID.
            
            // Singularity Strike: Hard Reset connection ONLY if valid re-entry not detected
            try {
                if (webRTCManager == null) {
                    android.util.Log.i("VoiceRoomRepository", "Creating NEW WebRTCManager instance")
                    initializeWebRTC(userId)
                } else {
                    android.util.Log.w("VoiceRoomRepository", "♻️ DIFFERENT SESSION OR STALE: Forcing media engine REBIRTH")
                    webRTCManager?.hardReset()
                    delay(500) // v7.2.4: Increased from 100ms to 500ms for aggressive OS/Hardware Release
                }
                
                webRTCManager?.ensureLocalTracks() // v7.2.5: Recreate tracks if disposed
                webRTCManager?.setRemoteAudioPlaybackEnabled(true)
                webRTCManager?.setCurrentUser(userId, "")

                prepareVoiceAudioSession(forceMicUnmute = true)
                android.util.Log.d("VoiceRoomRepository", "✅ Audio mode set to COMM + Focus Requested")
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepository", "❌ WebRTC Re-init failed: ${e.message}")
                crashReporter.recordException(
                    throwable = e,
                    extra = currentMetricAttributes(roomId = roomId)
                )
                appMetrics.recordFailure(
                    metric = "voice_room_join_reinit",
                    throwable = e,
                    attributes = currentMetricAttributes(roomId = roomId)
                )
            }

            // Add self to participants
            var self = conectate.com.data.model.VoiceParticipant(
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
                roomChannel?.let {
                    supabaseConfig.subscribeRealtimeChannel(
                        channel = it,
                        reason = "voice-room-$roomId"
                    )
                }
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
            updateConnectionState(
                VoiceConnectionState.ERROR,
                e.message?.takeIf { it.isNotBlank() } ?: "No pudimos abrir esta sala de voz."
            )
            crashReporter.recordException(
                throwable = e,
                extra = currentMetricAttributes(roomId = roomId)
            )
            appMetrics.recordFailure(
                metric = "voice_room_join",
                throwable = e,
                attributes = currentMetricAttributes(roomId = roomId)
            )
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
                reconnectionAttempts = SOCKET_RECONNECT_ATTEMPTS
                reconnectionDelay = SOCKET_RECONNECT_DELAY_MS
                reconnectionDelayMax = SOCKET_RECONNECT_DELAY_MAX_MS
                timeout = 10000
                attachBearerToken(authService.getAccessToken())
            }
            android.util.Log.i("VoiceRoomRepository", "🔌 Initializing Socket.io for: $SIGNALING_URL")
            socket = IO.socket(SIGNALING_URL, options)

            socket?.on(Socket.EVENT_CONNECT) {
                var sid = socket?.id() ?: ""
                android.util.Log.w("VoiceRoomRepository", "✅ SIGNALING CONNECTED: $sid. Joining room: $roomId")
                reconnectFailureCount = 0
                updateConnectionState(VoiceConnectionState.CONNECTED)
                pendingConnectStartedAtMs?.let { startedAt ->
                    appMetrics.recordLatency(
                        metric = pendingConnectMetricName ?: "voice_room_connect",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = currentMetricAttributes(roomId = roomId)
                    )
                }
                pendingConnectMetricName = null
                pendingConnectStartedAtMs = null

                webRTCManager?.setCurrentUser(userId, sid)
                webRTCManager?.refreshManagedIceServers(force = true)
                webRTCManager?.ensureLocalTracks()
                webRTCManager?.setRemoteAudioPlaybackEnabled(true)
                prepareVoiceAudioSession(forceMicUnmute = true)
                
                // Merge self with updated Socket ID
                _currentParticipants.update { current ->
                    var self = current.find { it.id == userId } ?: conectate.com.data.model.VoiceParticipant(id = userId, name = name, socketId = sid)
                    (current.filterNot { it.id == userId } + self.copy(socketId = sid)).distinctBy { it.socketId }
                }
                refreshParticipantAliases()
                
                socket?.emit("join-room", org.json.JSONObject().apply {
                    put("roomId", roomId.trim())
                    put("userId", userId.trim())
                    put("name", name)
                    put("avatarUrl", avatarUrl ?: "")
                })

                scope.launch {
                    delay(275)
                    flushPendingRoomSocketEvents(roomId)
                    restoreActiveSession("socket_connect")
                }
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val errorMessage = args.firstOrNull()?.toString().orEmpty().ifBlank { "Socket connect error" }
                android.util.Log.e("VoiceRoomRepository", "❌ Socket connect error: $errorMessage")
                reconnectFailureCount += 1
                updateConnectionState(VoiceConnectionState.RECONNECTING)
                var error = IllegalStateException(errorMessage)
                appMetrics.recordFailure(
                    metric = "voice_socket_connect_error",
                    throwable = error,
                    attributes = currentMetricAttributes(roomId = roomId)
                )
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                android.util.Log.e("VoiceRoomRepository", "🔌 SIGNALING DISCONNECTED")
                if (!activeRoomId.isNullOrBlank()) {
                    updateConnectionState(VoiceConnectionState.RECONNECTING)
                    appMetrics.incrementCounter(
                        metric = "voice_socket_disconnect",
                        attributes = currentMetricAttributes(roomId = roomId)
                    )
                }
            }

            socket?.on("user-joined") { args ->
                var data = extractJsonPayload(args) ?: return@on
                var sid = data.optString("socketId").trim()
                var uid = data.optString("userId").trim()
                if (sid.isBlank() || uid.isBlank()) return@on
                val displayName = data.optString("name", "Invitado")
                android.util.Log.i("VoiceRoomRepository", "👤 Remote User Joined: $displayName ($uid) via $sid")
                
                _currentParticipants.update { current ->
                    var p = conectate.com.data.model.VoiceParticipant(
                        id = uid, 
                        name = displayName, 
                        avatarUrl = data.optString("avatarUrl"), 
                        socketId = sid
                    )
                    (current.filterNot { it.socketId == sid || it.id == uid } + p).distinctBy { it.socketId }
                }
                refreshParticipantAliases()
                
                webRTCManager?.onUserJoined(sid, uid)

                // v5.0 SINGULARITY: 3-Pulse Resonance
                // Ensures newcomer sees our media even if connection is settling
                if (_currentVideoMode.value != VideoMode.NONE) {
                    android.util.Log.d("VoiceRoomRepository", "Resonance Pulse scheduled for newcomer: $sid")
                    broadcastCurrentMediaState(repeatCount = 3, intervalMs = 1000)
                    reinforceVideoPublishing()
                }
            }
            
            socket?.on("user-left") { args ->
                var socketId = extractSocketIdPayload(args)
                if (socketId.isBlank()) return@on
                android.util.Log.d("VoiceRoomRepository", "Socket user-left: $socketId")
                
                // v7.2-MVP: Host Disconnect Safety
                val leavingParticipant = _currentParticipants.value.find { it.socketId == socketId }
                scope.launch {
                    var room = activeRoomId?.let { getRoomById(it).getOrNull() }
                    if (room != null && leavingParticipant?.id == room.creatorId) {
                        android.util.Log.i("VoiceRoomRepo", "Host left. Closing cinema.")
                        emitCinemaState(room.id, CinemaState(isCinemaMode = false))
                    }
                }

                val currentList = _currentParticipants.value.toMutableList()
                currentList.removeIf { it.socketId == socketId }
                _currentParticipants.value = currentList
                refreshParticipantAliases()
                
                // CRITICAL: Clean up
                webRTCManager?.onUserLeft(socketId)
            }

            socket?.on("current-participants") { args ->
                val participantsJson = args.firstOrNull() as? org.json.JSONArray ?: return@on
                android.util.Log.i("VoiceRoomRepository", "📋 Received Participant List: ${participantsJson.length()} users in room $roomId")
                
                val selfId = authService.getCurrentUserId()
                val current = _currentParticipants.value
                val currentSocketIds = current
                    .map { it.socketId.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                val selfParticipant = current.firstOrNull { it.id == selfId }
                val remoteParticipants = mutableListOf<conectate.com.data.model.VoiceParticipant>()
                val rebuiltSharingStates = mutableMapOf<String, Pair<Boolean, Boolean>>()
                val pendingNewSockets = mutableListOf<Pair<String, String>>()

                for (i in 0 until participantsJson.length()) {
                    var p = participantsJson.optJSONObject(i) ?: continue
                    var sid = p.optString("socketId").trim()
                    var uid = p.optString("userId").trim()
                    if (sid.isBlank() || uid.isBlank()) continue
                    val participantName = p.optString("name", "Invitado")

                    if (uid != selfId) {
                        remoteParticipants += conectate.com.data.model.VoiceParticipant(
                            id = uid,
                            name = participantName,
                            avatarUrl = p.optString("avatarUrl"),
                            socketId = sid
                        )

                        val sharingState = p.optBoolean("isCameraSharing", false) to
                            p.optBoolean("isScreenSharing", false)
                        rebuiltSharingStates[sid.trim()] = sharingState
                        rebuiltSharingStates[uid.trim().lowercase()] = sharingState

                        if (sid.isNotBlank() && sid !in currentSocketIds) {
                            pendingNewSockets += sid to uid
                        }
                    }
                }

                _sharingStates.value = rebuiltSharingStates
                _currentParticipants.value = buildList {
                    selfParticipant?.let(::add)
                    addAll(
                        remoteParticipants.distinctBy { participant ->
                            participant.id.trim().lowercase().ifBlank { participant.socketId.trim() }
                        }
                    )
                }
                refreshParticipantAliases()
                pendingNewSockets.forEach { (sid, uid) ->
                    webRTCManager?.onUserJoined(sid, uid)
                }
                flushPendingRoomSocketEvents(roomId)
            }

            socket?.on("media-status") { args ->
                var data = extractJsonPayload(args) ?: return@on
                var sid = when {
                    args.size > 1 && args[1] is String -> args[1] as String
                    data.has("socketId") -> data.optString("socketId", "")
                    data.has("sender") -> data.optString("sender", "")
                    data.has("userId") -> {
                        var senderUserId = data.optString("userId", "")
                        _currentParticipants.value.firstOrNull { it.id == senderUserId }?.socketId.orEmpty()
                    }
                    else -> ""
                }
                if (sid.isNotEmpty()) {
                    val isCamera = data.optBoolean("isCameraSharing", false)
                    var isScreen = data.optBoolean("isScreenSharing", false)
                    var senderUserId = data.optString("userId", "")

                    updateSharingState(
                        socketId = sid,
                        userId = senderUserId,
                        isCameraSharing = isCamera,
                        isScreenSharing = isScreen
                    )
                    android.util.Log.d("VoiceRoomRepository", "🎥 Media status update for $sid: cam=$isCamera, screen=$isScreen")
                }
            }

            socket?.on("mute-state") { args ->
                var data = extractJsonPayload(args) ?: return@on
                var uid = data.optString("userId").trim()
                if (uid.isBlank()) return@on
                var isMuted = data.optBoolean("isMuted", false)
                android.util.Log.d("VoiceRoomRepository", "🔇 Remote Mute State ($uid): $isMuted")
                
                _currentParticipants.update { current ->
                    current.map { if (it.id == uid) it.copy(isMuted = isMuted) else it }
                }
            }

            socket?.on("offer") { args ->
                var data = extractJsonPayload(args) ?: return@on
                val senderSocketId = data.optString("sender").trim()
                if (senderSocketId.isBlank()) return@on
                // CRITICAL FIX: Read userId directly from payload to avoid race condition with participant list
                var senderUserId = if (data.has("userId")) data.optString("userId") else {
                    _currentParticipants.value.find { it.socketId == senderSocketId }?.id ?: ""
                }
                val offerSdp = data.optJSONObject("offer")?.optString("sdp").orEmpty()
                if (offerSdp.isBlank()) return@on
                webRTCManager?.onOfferReceived(senderSocketId, senderUserId, offerSdp)
            }

            socket?.on("answer") { args ->
                var data = extractJsonPayload(args) ?: return@on
                var sender = data.optString("sender").trim()
                if (sender.isBlank()) return@on
                var senderUserId = if (data.has("userId")) data.optString("userId") else {
                    _currentParticipants.value.find { it.socketId == sender }?.id ?: ""
                }
                val answerSdp = data.optJSONObject("answer")?.optString("sdp").orEmpty()
                if (answerSdp.isBlank()) return@on
                webRTCManager?.onAnswerReceived(sender, answerSdp, senderUserId)
            }

            socket?.on("ice-candidate") { args ->
                var data = extractJsonPayload(args) ?: return@on
                var sender = data.optString("sender").trim()
                if (sender.isBlank()) return@on
                var senderUserId = if (data.has("userId")) data.optString("userId") else {
                    _currentParticipants.value.find { it.socketId == sender }?.id ?: ""
                }
                val candidate = data.optJSONObject("candidate") ?: return@on
                webRTCManager?.onIceCandidateReceived(sender, candidate, senderUserId)
            }

            socket?.on("chat-message") { args ->
                 var data = extractJsonPayload(args) ?: return@on
                 var userIdPayload = data.optString("userId").trim()
                 val userNamePayload = data.optString("userName").trim()
                 val textPayload = data.optString("text").trim()
                 if (userIdPayload.isBlank() || textPayload.isBlank()) return@on
                 val msg = conectate.com.presentation.voicerooms.ChatMessage(
                    userId = userIdPayload,
                    userName = userNamePayload.ifBlank { "Usuario" },
                    text = textPayload
                )
                scope.launch { _incomingMessages.emit(msg) }
            }

            socket?.on("reaction") { args ->
                var data = extractJsonPayload(args) ?: return@on
                var userIdPayload = data.optString("userId").trim()
                val emojiPayload = data.optString("emoji").trim()
                if (userIdPayload.isBlank() || emojiPayload.isBlank()) return@on
                val reaction = conectate.com.presentation.voicerooms.ReactionEvent(
                    id = data.optString("reactionId").ifBlank { data.optString("id") },
                    userId = userIdPayload,
                    emoji = emojiPayload
                )
                scope.launch { _incomingReactions.emit(reaction) }
            }

            socket?.on("speaking-status") { args ->
                var data = extractJsonPayload(args) ?: return@on
                val speakerId = data.optString("userId").trim()
                if (speakerId.isBlank()) return@on
                var isSpeaking = data.optBoolean("isSpeaking", false)
                _currentParticipants.value = _currentParticipants.value.map {
                    if (it.id == speakerId) it.copy(isSpeaking = isSpeaking) else it
                }
            }

            socket?.on("cinema-state") { args ->
                var data = extractJsonPayload(args) ?: return@on
                var state = CinemaState(
                    isCinemaMode = data.optBoolean("isCinemaMode", false),
                    videoUrl = if (data.has("videoUrl")) data.getString("videoUrl") else null,
                    videoTitle = if (data.has("videoTitle")) data.getString("videoTitle") else null,
                    isPlaying = if (data.has("isPlaying")) data.optBoolean("isPlaying", false) else false,
                    currentPosition = if (data.has("currentPosition")) data.optLong("currentPosition", 0L) else 0L
                )
                scope.launch { _cinemaState.value = state }
            }

            socket?.on("cinema-sync") { args ->
                var data = extractJsonPayload(args) ?: return@on
                var sync = CinemaSyncEvent(
                    isPlaying = data.optBoolean("isPlaying", false),
                    currentPosition = data.optLong("currentPosition", 0L)
                )
                scope.launch { _cinemaSync.emit(sync) }
            }



            socket?.on("link-resolved") { args ->
                var data = extractJsonPayload(args) ?: return@on
                val reqId = data.optString("requestId").trim()
                if (reqId.isBlank()) return@on
                var deferred = pendingResolutions.remove(reqId)
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
                var data = extractJsonPayload(args) ?: return@on
                var sync = CinemaSyncEvent(
                    isPlaying = if (data.has("isPlaying")) data.optBoolean("isPlaying", true) else true,
                    currentPosition = data.optLong("position", 0L),
                    action = data.optString("action").ifBlank { null }
                )
                scope.launch { _cinemaSync.emit(sync) }
            }

            socket?.io()?.on(Manager.EVENT_RECONNECT_ATTEMPT) {
                if (!activeRoomId.isNullOrBlank()) {
                    reconnectFailureCount += 1
                    updateConnectionState(VoiceConnectionState.RECONNECTING)
                    appMetrics.incrementCounter(
                        metric = "voice_socket_reconnect_attempt",
                        attributes = currentMetricAttributes(roomId = roomId)
                    )
                }
            }

            socket?.io()?.on(Manager.EVENT_RECONNECT) {
                reconnectFailureCount = 0
                updateConnectionState(VoiceConnectionState.CONNECTED)
                appMetrics.incrementCounter(
                    metric = "voice_socket_reconnect_success",
                    attributes = currentMetricAttributes(roomId = roomId)
                )
                restoreActiveSession("socket_reconnect")
            }

            socket?.io()?.on(Manager.EVENT_RECONNECT_ERROR) { args ->
                var error = IllegalStateException(args.firstOrNull()?.toString().orEmpty().ifBlank { "Reconnect error" })
                appMetrics.recordFailure(
                    metric = "voice_socket_reconnect_error",
                    throwable = error,
                    attributes = currentMetricAttributes(roomId = roomId)
                )
                updateConnectionState(VoiceConnectionState.RECONNECTING)
            }

            socket?.io()?.on(Manager.EVENT_RECONNECT_FAILED) {
                var message = "No pudimos reconectar la sala de voz."
                updateConnectionState(VoiceConnectionState.ERROR, message)
                appMetrics.recordFailure(
                    metric = "voice_socket_reconnect_failed",
                    throwable = IllegalStateException(message),
                    attributes = currentMetricAttributes(roomId = roomId)
                )
            }

            socket?.connect()
        } catch (e: Exception) {
            android.util.Log.e("VoiceRoomRepository", "Failed to setup Socket.io", e)
            updateConnectionState(
                VoiceConnectionState.ERROR,
                e.message?.takeIf { it.isNotBlank() } ?: "No pudimos iniciar la señalización de voz."
            )
            crashReporter.recordException(
                throwable = e,
                extra = currentMetricAttributes(roomId = roomId)
            )
        }
    }

    // Signaling Hooks called by Repository
    private fun initializeWebRTC(userId: String) {
        var events = object : conectate.com.data.webrtc.SignalingEvents {
            override fun onSendOffer(targetSocketId: String, offer: org.json.JSONObject) {
                var data = org.json.JSONObject().apply {
                    put("target", targetSocketId)
                    put("offer", offer)
                    put("userId", userId) // CRITICAL: Send ID for polite check
                }
                socket?.emit("offer", data)
            }

            override fun onSendAnswer(targetSocketId: String, answer: org.json.JSONObject) {
                var data = org.json.JSONObject().apply {
                    put("target", targetSocketId)
                    put("answer", answer)
                    put("userId", userId)
                }
                socket?.emit("answer", data)
            }

            override fun onSendIceCandidate(targetSocketId: String, candidate: org.json.JSONObject) {
                var data = org.json.JSONObject().apply {
                    put("target", targetSocketId)
                    put("candidate", candidate)
                    put("userId", userId)
                }
                socket?.emit("ice-candidate", data)
            }
        }
        
        webRTCManager = WebRTCManager(
            context = context,
            signalingEvents = events,
            accessTokenProvider = { authService.getAccessToken() }
        )
        // INITIAL IDENTITY SET
        webRTCManager?.setCurrentUser(userId, "")
        webRTCManager?.refreshManagedIceServers()
        
        // Collect video tracks
        scope.launch {
            webRTCManager?.remoteVideoTracks?.collect { tracks ->
                android.util.Log.d("VoiceRoomRepository", "Remote video tracks updated: Count=${tracks.size}, Keys=${tracks.keys}")
                _remoteVideoTracks.value = rekeyWithCurrentParticipants(tracks)
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
                _peerConnectionStates.value = rekeyWithCurrentParticipants(states)
            }
        }
        
        scope.launch {
            webRTCManager?.signalingStates?.collect { states ->
                _signalingStates.value = rekeyWithCurrentParticipants(states)
            }
        }

        scope.launch {
            webRTCManager?.debugInfo?.collect { info ->
                _debugInfo.value = rekeyWithCurrentParticipants(info)
            }
        }
    }

    fun toggleCameraSharing(isSharing: Boolean) {
        _currentVideoMode.value = if (isSharing) VideoMode.CAMERA else VideoMode.NONE
        emitCurrentMediaState()
    }

    fun toggleScreenSharing(isSharing: Boolean) {
        _currentVideoMode.value = if (isSharing) VideoMode.SCREEN else VideoMode.NONE
        emitCurrentMediaState()
    }

    fun startScreenCapture(intent: android.content.Intent) {
        appMetrics.incrementCounter(
            metric = "voice_video_transition",
            attributes = currentMetricAttributes(reason = "screen_share_start") + ("targetMode" to "screen")
        )
        prepareVoiceAudioSession(forceMicUnmute = true)
        webRTCManager?.ensureLocalTracks()
        webRTCManager?.setAudioEnabled(!isSelfMuted())
        webRTCManager?.startVideo(
            isScreen = true,
            intent = intent,
            targetWidth = performanceProfile.voiceScreenWidth,
            targetHeight = performanceProfile.voiceScreenHeight,
            targetFps = performanceProfile.voiceScreenFps
        )
        _currentVideoMode.value = VideoMode.SCREEN
        webRTCManager?.setVideoSendEnabled(true, renegotiate = false)
        broadcastCurrentMediaState(
            repeatCount = if (performanceProfile.reduceMotion) 2 else 3,
            intervalMs = 700
        )
        reinforceVideoPublishing()
        reinforceAudioAfterVideoTransition("screen-share-start")
    }

    fun startCameraCapture(isFront: Boolean = true) {
        appMetrics.incrementCounter(
            metric = "voice_video_transition",
            attributes = currentMetricAttributes(reason = "camera_start") + ("targetMode" to "camera")
        )
        prepareVoiceAudioSession(forceMicUnmute = true)
        webRTCManager?.ensureLocalTracks()
        webRTCManager?.setAudioEnabled(!isSelfMuted())
        webRTCManager?.startVideo(
            isScreen = false,
            isFront = isFront,
            targetWidth = performanceProfile.voiceCameraWidth,
            targetHeight = performanceProfile.voiceCameraHeight,
            targetFps = performanceProfile.voiceCameraFps
        )
        _currentVideoMode.value = VideoMode.CAMERA
        webRTCManager?.setVideoSendEnabled(true, renegotiate = false)
        broadcastCurrentMediaState(
            repeatCount = if (performanceProfile.reduceMotion) 2 else 3,
            intervalMs = 700
        )
        reinforceVideoPublishing()
        reinforceAudioAfterVideoTransition("camera-share-start")
    }

    fun stopVideo() {
        android.util.Log.d("VoiceRoomRepo", "Stopping local video/screen share")
        appMetrics.incrementCounter(
            metric = "voice_video_transition",
            attributes = currentMetricAttributes(reason = "video_stop") + ("targetMode" to "none")
        )
        webRTCManager?.stopVideo()
        _currentVideoMode.value = VideoMode.NONE
        broadcastCurrentMediaState(repeatCount = 2, intervalMs = 250)
        reinforceAudioAfterVideoTransition("video-stop")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun leaveRoom(roomId: String? = null) = withContext(Dispatchers.IO + NonCancellable) {
        try {
            val actualRoomId = roomId ?: activeRoomId
            android.util.Log.w("VoiceRoomRepository", "📍 leaveRoom START: $actualRoomId")
            audioRecoveryJob?.cancel()
            pendingConnectMetricName = null
            pendingConnectStartedAtMs = null
            reconnectFailureCount = 0

            // 1. Disconnect Signaling FIRST to prevent new negotiation during teardown
            socket?.let {
                it.off()
                it.disconnect()
            }
            socket = null

            roomChannel?.unsubscribe()
            roomChannel = null

            // 2. Clear WebRTC State Safely
            try {
                webRTCManager?.hardReset()
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepository", "Error resetting WebRTC: ${e.message}")
            }

            // 3. Reset UI State
            _currentParticipants.value = emptyList()
            _remoteVideoTracks.value = emptyMap()
            _peerConnectionStates.value = emptyMap()
            _signalingStates.value = emptyMap()
            _sharingStates.value = emptyMap()
            _debugInfo.value = emptyMap()
            _currentVideoMode.value = VideoMode.NONE
            _localVideoTrack.value = null
            _cinemaState.value = null
            _cinemaSync.resetReplayCache()
            _currentRoom.value = null
            
            synchronized(pendingSocketRoomEvents) {
                pendingSocketRoomEvents.clear()
            }

            activeRoomId = null
            updateConnectionState(VoiceConnectionState.IDLE)
            
            // 4. Release Audio with Safety Delay
            releaseVoiceAudioSession()
            
            android.util.Log.w("VoiceRoomRepository", "📍 leaveRoom END: Cleanup complete")
        } catch (e: Exception) {
            android.util.Log.e("VoiceRoomRepository", "❌ CRITICAL error leaving room: ${e.message}")
            crashReporter.recordException(
                throwable = e,
                extra = currentMetricAttributes(roomId = roomId)
            )
        }
    }

    fun toggleMute(isMuted: Boolean) {
        android.util.Log.d("VoiceRoomRepository", "🎤 NUCLEAR MUTE: $isMuted")
        
        // 1. Update Participants UI
        _currentParticipants.value = _currentParticipants.value.map { 
            if (it.id == authService.getCurrentUserId()) it.copy(isMuted = isMuted) else it
        }
        
        if (!isMuted) {
            prepareVoiceAudioSession(forceMicUnmute = true)
            webRTCManager?.ensureLocalTracks()
        }

        // 2. WebRTC Track Disable (without muting the whole device mic)
        webRTCManager?.setAudioEnabled(!isMuted)
        
        // 3. Broadcast mute state
        scope.launch {
             socket?.emit("mute-state", org.json.JSONObject().apply {
                put("roomId", activeRoomId ?: "")
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
        val previousRooms = _activeRooms.value
        try {
            var normalizedRoomId = roomId.trim().lowercase()
            _activeRooms.update { current ->
                current.filterNot { it.id.trim().lowercase() == normalizedRoomId }
            }
            supabaseConfig.client.postgrest["voice_rooms"].delete {
                filter { eq("id", roomId) }
            }
            if (activeRoomId?.trim()?.lowercase() == normalizedRoomId) {
                leaveRoom(roomId)
            } else {
                scope.launch { refreshRooms() }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("VoiceRoomRepository", "Error deleting room $roomId", e)
            _activeRooms.value = previousRooms
            Result.failure(e)
        }
    }

    private suspend fun subscribeToVoiceRooms() {
        var retryDelayMs = SUBSCRIPTION_RETRY_INITIAL_MS
        while (currentCoroutineContext().isActive) {
            var channel: RealtimeChannel? = null
            var shouldBackoff = false
            try {
                channel = supabaseConfig.client.channel("voice-rooms-global")
                val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "voice_rooms"
                }

                supabaseConfig.subscribeRealtimeChannel(
                    channel = channel,
                    reason = "voice-rooms-global"
                )
                retryDelayMs = SUBSCRIPTION_RETRY_INITIAL_MS
                changes.collect {
                    refreshRooms()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                shouldBackoff = true
                android.util.Log.e("VoiceRoomRepository", "Voice rooms realtime subscription failed retrying", e)
            } finally {
                if (channel != null) {
                    try {
                        channel.unsubscribe()
                    } catch (_: Exception) { }
                }
            }
            if (!currentCoroutineContext().isActive) break

            if (shouldBackoff) {
                delay(retryDelayMs)
                retryDelayMs = nextRetryDelayMs(retryDelayMs, SUBSCRIPTION_RETRY_MAX_MS)
            } else {
                delay(SUBSCRIPTION_RESTART_GRACE_MS)
            }
        }
    }
    
    // Invitations
    private val _incomingInvitation = MutableStateFlow<Invitation?>(null)
    val incomingInvitation = _incomingInvitation.asStateFlow()

    suspend fun subscribeToInvitations(userId: String) {
        var channelName = "user:$userId"
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
                    var roomId = payload["roomId"].toString().replace("\"", "")
                    
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
        supabaseConfig.subscribeRealtimeChannel(
            channel = userChannel,
            reason = "voice-user-$userId"
        )
        android.util.Log.d("VoiceRoomRepo", "=== SUBSCRIPTION ACTIVE ===")
    }

    suspend fun sendInvitation(targetUserId: String, roomId: String, roomName: String, inviterName: String) {
         try {
             android.util.Log.d("VoiceRoomRepo", "=== SENDING INVITATION ===")
             android.util.Log.d("VoiceRoomRepo", "Target User: $targetUserId")
             android.util.Log.d("VoiceRoomRepo", "Room: $roomName ($roomId)")
             android.util.Log.d("VoiceRoomRepo", "Inviter: $inviterName")
             
             var channelName = "user:$targetUserId"
             android.util.Log.d("VoiceRoomRepo", "Creating channel: $channelName")
             val targetChannel = supabaseConfig.client.channel(channelName)
             
             // Ensure we are subscribed before broadcasting
             android.util.Log.d("VoiceRoomRepo", "Subscribing to channel...")
             supabaseConfig.subscribeRealtimeChannel(
                 channel = targetChannel,
                 reason = "voice-invite-$targetUserId",
                 blockUntilSubscribed = true
             )
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
             
             // Small delay to reduce risk of the channel closing before the broadcast is flushed
             delay(200)
             
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
        emitOrQueueRoomSocketEvent(activeRoomId, "chat-message") {
            put("roomId", activeRoomId ?: "")
            put("userId", message.userId)
            put("userName", message.userName)
            put("text", message.text)
        }
    }

    suspend fun sendReaction(reaction: conectate.com.presentation.voicerooms.ReactionEvent) = withContext(Dispatchers.IO) {
        val reactionId = reaction.id.ifBlank { UUID.randomUUID().toString() }
        emitOrQueueRoomSocketEvent(activeRoomId, "reaction") {
            put("roomId", activeRoomId ?: "")
            put("userId", reaction.userId)
            put("emoji", reaction.emoji)
            put("reactionId", reactionId)
        }
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
        // v7.2.4: Update local state IMMEDIATELY so Creator sees the change without waiting for broadcast
        _cinemaState.value = state
        
        // v7.2.3: Offload everything to IO and minimize blocking logic
        scope.launch {
            // we trust the ViewModel to have checked hostId for the UI part, 
            // but we'll do a quick check here against current roomId if we have it
            if (activeRoomId != roomId) return@launch

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
        var userId = authService.getCurrentUserId() ?: return
        // v23.0: Restoring chat functionality with fresh socket reference
        scope.launch {
            try {
                android.util.Log.d("VoiceRoomRepo", "Sending room message to $roomId with length=${message.length}")
                emitOrQueueRoomSocketEvent(roomId, "chat-message") {
                    put("roomId", roomId)
                    put("userId", userId)
                    put("userName", _currentParticipants.value.find { it.id == userId }?.name ?: "User")
                    put("text", message)
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepo", "Failed to send message", e)
            }
        }
    }

    fun sendReaction(roomId: String, emoji: String) {
        var userId = authService.getCurrentUserId() ?: return
        scope.launch {
            try {
                emitOrQueueRoomSocketEvent(roomId, "reaction") {
                    put("roomId", roomId)
                    put("userId", userId)
                    put("emoji", emoji)
                    put("reactionId", UUID.randomUUID().toString())
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomRepo", "Failed to send reaction", e)
            }
        }
    }

    fun emitCinemaControl(roomId: String, action: String, position: Long) {
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

data class VoiceSessionState(
    val roomId: String? = null,
    val room: VoiceRoom? = null,
    val roomName: String = "Sala de Voz",
    val readableId: String? = null,
    val participantCount: Int = 0,
    val isMuted: Boolean = false,
    val isConnected: Boolean = false,
    val videoMode: VoiceRoomRepository.VideoMode = VoiceRoomRepository.VideoMode.NONE,
    val connectionState: VoiceConnectionState = VoiceConnectionState.IDLE,
    val connectionError: String? = null
) {
    val hasActiveSession: Boolean
        get() = isConnected
}







