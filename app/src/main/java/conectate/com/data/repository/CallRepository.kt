package conectate.com.data.repository

import android.content.pm.PackageManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.observability.AppMetrics
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.model.ActiveCall
import conectate.com.data.model.CallConnectionState
import conectate.com.data.model.CallHistoryDirection
import conectate.com.data.model.CallHistoryEntry
import conectate.com.data.model.CallHistoryStatus
import conectate.com.data.model.CallEndedSummary
import conectate.com.data.model.CallInvite
import conectate.com.data.model.CallParticipant
import conectate.com.data.model.CallReactionEvent
import conectate.com.data.signaling.SignalingConfig
import conectate.com.data.signaling.attachBearerToken
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.webrtc.WebRTCManager
import conectate.com.services.CallForegroundService
import conectate.com.util.CallTonePlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.socket.client.IO
import io.socket.client.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.webrtc.PeerConnection

@Singleton
class CallRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val groupRepository: GroupRepository,
    private val communityRepository: CommunityRepository,
    private val notificationRepository: NotificationRepository,
    private val callHistoryRepository: CallHistoryRepository,
    private val callTonePlayer: CallTonePlayer,
    private val appMetrics: AppMetrics,
    private val ubuntuRealtimeClient: UbuntuRealtimeClient
) {
    @Serializable
    private data class CallInviteBroadcastPayload(
        val callId: String,
        val title: String,
        val callerUserId: String,
        val callerName: String,
        val callerAvatarUrl: String = "",
        val contextType: String,
        val contextId: String = "",
        val isVideo: Boolean = false
    )

    @Serializable
    private data class CallResponseBroadcastPayload(
        val callId: String,
        val action: String,
        val fromUserId: String
    )

    companion object {
        private const val TAG = "CallRepository"
        private const val OUTGOING_RING_TIMEOUT_MS = 45_000L
        private const val CONNECTING_TIMEOUT_MS = 30_000L
        private const val SOCKET_RECONNECT_ATTEMPTS = 10
        private const val SOCKET_RECONNECT_DELAY_MS = 1_000L
        private const val SOCKET_RECONNECT_DELAY_MAX_MS = 12_000L
        private const val REMOTE_RECONNECT_GRACE_MS = 30_000L
    }

    private val scope = newSafeScope("CallRepository", Dispatchers.IO)

    private var socket: Socket? = null
    private val _webRTCManager = MutableStateFlow<WebRTCManager?>(null)
    private var webRTCManager: WebRTCManager?
        get() = _webRTCManager.value
        set(value) { _webRTCManager.value = value }
    private val signalingUrl: String get() = SignalingConfig.CALL_URL
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _activeCall = MutableStateFlow<ActiveCall?>(null)
    val activeCall: StateFlow<ActiveCall?> = _activeCall.asStateFlow()

    private val _participants = MutableStateFlow<List<CallParticipant>>(emptyList())
    val participants: StateFlow<List<CallParticipant>> = _participants.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    // Calls start on the loudspeaker so speech is intelligible on phones with
    // a quiet earpiece. The user can still switch to earpiece/headset at any
    // time from the call controls.
    private val _isSpeaker = MutableStateFlow(true)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker.asStateFlow()

    private val _incomingInvite = MutableStateFlow<CallInvite?>(null)
    val incomingInvite: StateFlow<CallInvite?> = _incomingInvite.asStateFlow()

    private val _endedCallSummary = MutableStateFlow<CallEndedSummary?>(null)
    val endedCallSummary: StateFlow<CallEndedSummary?> = _endedCallSummary.asStateFlow()

    private val _incomingReactions = MutableSharedFlow<CallReactionEvent>()
    val incomingReactions: SharedFlow<CallReactionEvent> = _incomingReactions.asSharedFlow()

    // WebRTC Media Exposure for UI
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val localVideoTrack: Flow<org.webrtc.VideoTrack?> = _webRTCManager.flatMapLatest { it?.localVideoTrack ?: kotlinx.coroutines.flow.flowOf(null) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val remoteVideoTracks: Flow<Map<String, org.webrtc.VideoTrack>> = _webRTCManager.flatMapLatest { it?.remoteVideoTracks ?: kotlinx.coroutines.flow.flowOf(emptyMap()) }
    val eglContext: org.webrtc.EglBase.Context? get() = webRTCManager?.eglContext

    private var callUserChannel: RealtimeChannel? = null
    private var ubuntuCallUserChannel: String? = null
    private var callUserChannelJob: Job? = null
    private var callResponseChannelJob: Job? = null
    private var outgoingRingTimeoutJob: Job? = null
    private var connectingTimeoutJob: Job? = null
    private var webRtcStateObservationJob: Job? = null
    private var socketEndpointRecoveryJob: Job? = null
    private var callEverHadRemoteParticipant: Boolean = false
    private var hasConnectedMediaPath: Boolean = false
    private var callSetupStartedAtMs: Long? = null
    private var callSetupMetricCallId: String? = null

    private val pendingInvitees = mutableSetOf<String>()

    fun getCurrentCallId(): String? = _activeCall.value?.callId
    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refreshRemoteParticipantFlag(participants: List<CallParticipant>) {
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        if (participants.any { !it.userId.equals(currentUserId, ignoreCase = true) }) {
            callEverHadRemoteParticipant = true
        }
    }

    private fun logBestEffortFailure(operation: String, throwable: Throwable, callId: String? = null) {
        val callSuffix = callId?.takeIf { it.isNotBlank() }?.let { " (callId=$it)" }.orEmpty()
        Log.w(TAG, "$operation failed$callSuffix", throwable)
    }

    suspend fun ensureSubscribedToCallUserChannel(userId: String) = withContext(Dispatchers.IO) {
        val normalizedUserId = userId.trim()
        if (normalizedUserId.isBlank()) return@withContext

        if (supabaseConfig.isLocalFallbackPrimary) {
            val channelName = "user:$normalizedUserId"
            if (ubuntuCallUserChannel == channelName && callUserChannelJob?.isActive == true) {
                return@withContext
            }

            ubuntuCallUserChannel?.let(ubuntuRealtimeClient::unsubscribe)
            callUserChannelJob?.cancel()
            callResponseChannelJob?.cancel()
            ubuntuRealtimeClient.updateAccessToken(authService.getAccessToken().orEmpty())
            ubuntuRealtimeClient.subscribe(channelName)
            ubuntuCallUserChannel = channelName
            callUserChannelJob = scope.launch {
                ubuntuRealtimeClient.events.collect { envelope ->
                    if (envelope.channel != channelName) return@collect
                    when (envelope.event) {
                        "call_invite" -> handleUbuntuCallInvite(envelope.payload)
                        "call_response" -> handleUbuntuCallResponse(envelope.payload)
                    }
                }
            }
            return@withContext
        }

        if (callUserChannel != null) return@withContext

        var channelName = "call-user:$normalizedUserId"
        var channel = supabaseConfig.client.channel(channelName)
        callUserChannel = channel

        callUserChannelJob = scope.launch {
            channel.broadcastFlow<CallInviteBroadcastPayload>("call_invite").collect { payload ->
                var callId = payload.callId.trim()
                var title = payload.title.trim()
                var callerUserId = payload.callerUserId.trim()
                var callerName = payload.callerName.trim()
                var callerAvatarUrl = payload.callerAvatarUrl.trim().ifBlank { null }
                var contextType = payload.contextType.trim()
                var contextId = payload.contextId.trim().ifBlank { null }
                val isVideo = payload.isVideo

                if (callId.isBlank() || callerUserId.isBlank()) return@collect
                if (_activeCall.value != null) {
                    // Busy: ignore (or optionally auto-decline)
                    return@collect
                }

                _incomingInvite.value = CallInvite(
                    callId = callId,
                    title = title.ifBlank { "Llamada" },
                    callerUserId = callerUserId,
                    callerName = callerName.ifBlank { "Usuario" },
                    callerAvatarUrl = callerAvatarUrl,
                    contextType = contextType.ifBlank { "dm" },
                    contextId = contextId,
                    isVideo = isVideo
                )
                startForegroundIncomingRingtone()
            }
        }

        callResponseChannelJob?.cancel()
        callResponseChannelJob = scope.launch {
            channel.broadcastFlow<CallResponseBroadcastPayload>("call_response").collect { payload ->
                var callId = payload.callId.trim()
                var action = payload.action.trim().lowercase()
                var fromUserId = payload.fromUserId.trim()

                // Incoming cancel while ringing
                val incoming = _incomingInvite.value
                if (incoming != null && incoming.callId == callId && action == "cancel") {
                    try {
                        recordIncomingInviteOutcome(incoming, "Llamada cancelada")
                    } catch (error: Exception) {
                        logBestEffortFailure(
                            operation = "recordIncomingInviteOutcome(cancel)",
                            throwable = error,
                            callId = incoming.callId
                        )
                    }
                    _incomingInvite.value = null
                    callTonePlayer.stopIncomingRingtone()
                }

                handleExternalCallResponse(callId, action, fromUserId)
            }
        }

        supabaseConfig.subscribeRealtimeChannel(
            channel = channel,
            reason = "call-user-$userId"
        )
    }

    fun handleExternalCallResponse(callId: String, action: String, fromUserId: String) {
        val safeAction = action.trim().lowercase()
        var call = _activeCall.value
        if (call != null && call.callId == callId) {
            when (safeAction) {
                "accept" -> {
                    if (call.connectionState == CallConnectionState.RINGING_OUTGOING) {
                        callTonePlayer.stopRingback()
                        clearOutgoingRingTimeout()
                        
                        // FIX: Cancel all OTHER pending invitees so they don't ring forever
                        val otherInvitees = pendingInvitees.filter { it != fromUserId }
                        if (otherInvitees.isNotEmpty()) {
                            scope.launch {
                                otherInvitees.forEach { target ->
                                    runCatching {
                                        sendCallResponse(
                                            targetUserId = target,
                                            callId = callId,
                                            action = "cancel",
                                            fromUserId = authService.getCurrentUserId() ?: ""
                                        )
                                    }
                                }
                            }
                        }
                        pendingInvitees.retainAll(setOf(fromUserId))

                        _activeCall.value = call.copy(connectionState = CallConnectionState.CONNECTING)
                        scheduleConnectingTimeout(callId)
                    }
                }
                "decline" -> {
                    pendingInvitees.remove(fromUserId)
                    if (call.connectionState == CallConnectionState.RINGING_OUTGOING && pendingInvitees.isEmpty()) {
                        endCallInternal(reason = "Llamada rechazada")
                    }
                }
                "end" -> {
                    callTonePlayer.stopRingback()
                    endCallInternal(reason = "Llamada finalizada")
                }
                "cancel" -> {
                    if (call.connectionState == CallConnectionState.RINGING_OUTGOING || call.connectionState == CallConnectionState.CONNECTING) {
                        endCallInternal(reason = "Llamada cancelada")
                    }
                }
            }
        }
    }

    suspend fun startOutgoingCall(
        title: String,
        invitedUserIds: List<String>,
        contextType: String,
        contextId: String? = null,
        isVideo: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
        if (_activeCall.value != null) return@withContext Result.failure(Exception("Ya hay una llamada activa"))

        var profile = loadUserProfileQuick(currentUserId)
        var callerName = profile?.displayName
            ?: profile?.username
            ?: authService.getCurrentUserEmail()?.substringBefore("@")
            ?: "Usuario"
        val callerAvatar = profile?.avatar_url

        var normalizedContextType = contextType.trim().lowercase().ifBlank { "dm" }
        val normalizedContextId = contextId?.trim()?.ifBlank { null }

        var callId = UUID.randomUUID().toString()
        pendingInvitees.clear()
        pendingInvitees.addAll(invitedUserIds.map { it.trim() }.filter { it.isNotBlank() }.distinct())
        callEverHadRemoteParticipant = false
        hasConnectedMediaPath = false
        callSetupStartedAtMs = System.currentTimeMillis()
        callSetupMetricCallId = callId
        clearEndedCallSummary()

        var peerUserId = pendingInvitees.firstOrNull()?.trim()?.ifBlank { null }
        val peerProfile = peerUserId?.let { loadUserProfileQuick(it) }
        var peerName = peerProfile?.displayName
            ?: peerProfile?.username
            ?: title.ifBlank { null }
        val peerAvatar = peerProfile?.avatar_url

        _activeCall.value = ActiveCall(
            callId = callId,
            title = title.ifBlank { peerName ?: "Llamada" },
            initiatorUserId = currentUserId,
            connectionState = CallConnectionState.RINGING_OUTGOING,
            contextType = normalizedContextType,
            contextId = normalizedContextId,
            peerUserId = peerUserId,
            peerName = peerName ?: title,
            peerAvatarUrl = peerAvatar,
            isVideo = isVideo
        )
        _isSpeaker.value = true
        _participants.value = listOf(
            CallParticipant(
                userId = currentUserId,
                name = callerName,
                avatarUrl = callerAvatar,
                socketId = currentUserId
            )
        )

        prepareCallAudioSession()
        startService()
        connectSocket(callId, currentUserId, callerName, callerAvatar)
        callTonePlayer.startRingback()
        scheduleOutgoingRingTimeout(callId)

        // Fire-and-forget invites
        scope.launch {
            // WhatsApp-like: write a "call started" message in group/community so late joiners can tap to join.
            runCatching {
                val ctxType = normalizedContextType
                val ctxId = normalizedContextId
                if (!ctxId.isNullOrBlank()) {
                    when (ctxType) {
                        "group" -> {
                            groupRepository.sendGroupMessage(
                                groupId = ctxId,
                                content = "Llamada de grupo iniciada",
                                contentType = "call_start",
                                messageId = callId
                            )
                        }
                        "community" -> {
                            communityRepository.sendCommunityMessage(
                                communityId = ctxId,
                                content = "Llamada de comunidad iniciada",
                                contentType = "call_start",
                                messageId = callId
                            )
                        }
                    }
                }
            }.onFailure { error ->
                logBestEffortFailure(
                    operation = "publishCallStartMessage",
                    throwable = error,
                    callId = callId
                )
            }

            val invite = CallInvite(
                callId = callId,
                title = title.ifBlank { "Llamada" },
                callerUserId = currentUserId,
                callerName = callerName,
                callerAvatarUrl = callerAvatar,
                contextType = contextType,
                contextId = contextId,
                isVideo = isVideo
            )
            pendingInvitees.forEach { target ->
                runCatching { sendCallInvite(target, invite) }
                    .onFailure { error ->
                        logBestEffortFailure(
                            operation = "sendCallInvite(targetUserId=$target)",
                            throwable = error,
                            callId = invite.callId
                        )
                    }
            }
        }

        Result.success(callId)
    }

    suspend fun joinExistingCall(
        callId: String,
        title: String,
        initiatorUserId: String,
        contextType: String,
        contextId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
        if (_activeCall.value != null) return@withContext Result.failure(Exception("Ya hay una llamada activa"))

        var profile = loadUserProfileQuick(currentUserId)
        var myName = profile?.displayName
            ?: profile?.username
            ?: authService.getCurrentUserEmail()?.substringBefore("@")
            ?: "Usuario"
        var myAvatar = profile?.avatar_url

        pendingInvitees.clear()
        callEverHadRemoteParticipant = false
        hasConnectedMediaPath = false
        callSetupStartedAtMs = System.currentTimeMillis()
        callSetupMetricCallId = callId.trim()
        clearEndedCallSummary()
        var normalizedContextType = contextType.trim().lowercase().ifBlank { "dm" }
        val peerProfile = if (normalizedContextType == "dm") {
            loadUserProfileQuick(initiatorUserId)
        } else {
            null
        }

        _activeCall.value = ActiveCall(
            callId = callId.trim(),
            title = title.ifBlank { "Llamada" },
            initiatorUserId = initiatorUserId.trim().ifBlank { initiatorUserId },
            connectionState = CallConnectionState.CONNECTING,
            contextType = normalizedContextType,
            contextId = contextId?.trim()?.ifBlank { null },
            peerUserId = if (normalizedContextType == "dm") initiatorUserId.trim().ifBlank { null } else null,
            peerName = peerProfile?.displayName ?: peerProfile?.username,
            peerAvatarUrl = peerProfile?.avatar_url,
            isVideo = false
        )
        _isSpeaker.value = true
        _participants.value = listOf(
            CallParticipant(
                userId = currentUserId,
                name = myName,
                avatarUrl = myAvatar,
                socketId = currentUserId
            )
        )

        prepareCallAudioSession()
        startService()
        scheduleConnectingTimeout(callId.trim())
        connectSocket(callId.trim(), currentUserId, myName, myAvatar)
        callTonePlayer.stopRingback()
        Result.success(Unit)
    }

    suspend fun acceptIncomingCall(invite: CallInvite): Result<Unit> = withContext(Dispatchers.IO) {
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
        if (_activeCall.value != null) return@withContext Result.failure(Exception("Ya hay una llamada activa"))

        var profile = loadUserProfileQuick(currentUserId)
        var myName = profile?.displayName
            ?: profile?.username
            ?: authService.getCurrentUserEmail()?.substringBefore("@")
            ?: "Usuario"
        var myAvatar = profile?.avatar_url

        _incomingInvite.value = null
        callTonePlayer.stopIncomingRingtone()
        pendingInvitees.clear()
        callEverHadRemoteParticipant = false
        hasConnectedMediaPath = false
        callSetupStartedAtMs = System.currentTimeMillis()
        callSetupMetricCallId = invite.callId
        clearEndedCallSummary()
        var normalizedContextType = invite.contextType.trim().lowercase().ifBlank { "dm" }
        val effectiveTitle = if (normalizedContextType == "dm") {
            invite.callerName.ifBlank { invite.title.ifBlank { "Llamada" } }
        } else {
            invite.title.ifBlank { "Llamada" }
        }

        _activeCall.value = ActiveCall(
            callId = invite.callId,
            title = effectiveTitle,
            initiatorUserId = invite.callerUserId,
            connectionState = CallConnectionState.CONNECTING,
            contextType = normalizedContextType,
            contextId = invite.contextId?.trim()?.ifBlank { null },
            peerUserId = invite.callerUserId,
            peerName = invite.callerName,
            peerAvatarUrl = invite.callerAvatarUrl,
            isVideo = invite.isVideo
        )
        _isSpeaker.value = true
        _participants.value = listOf(
            CallParticipant(
                userId = currentUserId,
                name = myName,
                avatarUrl = myAvatar,
                socketId = currentUserId
            )
        )

        scope.launch {
            runCatching {
                sendCallResponse(
                    targetUserId = invite.callerUserId,
                    callId = invite.callId,
                    action = "accept",
                    fromUserId = currentUserId
                )
            }.onFailure { error ->
                logBestEffortFailure(
                    operation = "sendCallResponse(accept)",
                    throwable = error,
                    callId = invite.callId
                )
            }
        }
        prepareCallAudioSession()
        startService()
        scheduleConnectingTimeout(invite.callId)
        connectSocket(invite.callId, currentUserId, myName, myAvatar)
        callTonePlayer.stopAll()
        Result.success(Unit)
    }

    suspend fun declineIncomingCall(invite: CallInvite): Result<Unit> = withContext(Dispatchers.IO) {
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))

        _incomingInvite.value = null
        callTonePlayer.stopAll()
        try {
            recordIncomingInviteOutcome(invite, "Llamada rechazada")
        } catch (error: Exception) {
            logBestEffortFailure(
                operation = "recordIncomingInviteOutcome(decline)",
                throwable = error,
                callId = invite.callId
            )
        }
        scope.launch {
            runCatching {
                sendCallResponse(
                    targetUserId = invite.callerUserId,
                    callId = invite.callId,
                    action = "decline",
                    fromUserId = currentUserId
                )
            }.onFailure { error ->
                logBestEffortFailure(
                    operation = "sendCallResponse(decline)",
                    throwable = error,
                    callId = invite.callId
                )
            }
        }
        Result.success(Unit)
    }

    suspend fun hangup(): Result<Unit> = withContext(Dispatchers.IO) {
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        var call = _activeCall.value ?: return@withContext Result.success(Unit)
        callTonePlayer.stopRingback()

        if (call.connectionState == CallConnectionState.RINGING_OUTGOING) {
            // Cancel pending invites (best-effort)
            var invitees = pendingInvitees.toList()
            scope.launch {
                invitees.forEach { target ->
                    runCatching {
                        sendCallResponse(
                            targetUserId = target,
                            callId = call.callId,
                            action = "cancel",
                            fromUserId = currentUserId
                        )
                    }.onFailure { error ->
                        logBestEffortFailure(
                            operation = "sendCallResponse(cancel)",
                            throwable = error,
                            callId = call.callId
                        )
                    }
                }
            }
            endCallInternal(reason = "Llamada cancelada")
            return@withContext Result.success(Unit)
        }

        scope.launch {
            val targets = buildEndSignalTargets(currentUserId)
            targets.forEach { target ->
                runCatching {
                    sendCallResponse(
                        targetUserId = target,
                        callId = call.callId,
                        action = "end",
                        fromUserId = currentUserId
                    )
                }.onFailure { error ->
                    logBestEffortFailure(
                        operation = "sendCallResponse(end)",
                        throwable = error,
                        callId = call.callId
                    )
                }
            }
        }

        endCallInternal(reason = "Llamada finalizada")
        Result.success(Unit)
    }

    fun toggleMute(muted: Boolean) {
        _isMuted.value = muted
        var myId = authService.getCurrentUserId()?.trim().orEmpty()
        if (myId.isNotBlank()) {
            _participants.update { list ->
                list.map { if (it.userId.equals(myId, ignoreCase = true)) it.copy(isMuted = muted) else it }
            }
        }

        if (!muted) {
            prepareCallAudioSession()
        }
        webRTCManager?.setAudioEnabled(!muted)

        scope.launch {
            var callId = _activeCall.value?.callId ?: return@launch
            socket?.emit(
                "mute-state",
                org.json.JSONObject().apply {
                    put("callId", callId)
                    put("userId", myId)
                    put("isMuted", muted)
                }
            )
        }

        updateServiceNotification()
    }

    fun toggleSpeaker(on: Boolean) {
        _isSpeaker.value = on
        prepareCallAudioSession()
        webRTCManager?.setSpeakerphoneOn(on)
    }

    fun toggleVideo(enabled: Boolean) {
        webRTCManager?.setVideoEnabled(enabled)
        _activeCall.value = _activeCall.value?.copy(isVideo = enabled)
        val myId = authService.getCurrentUserId()?.trim().orEmpty()
        if (myId.isNotBlank()) {
            _participants.update { list ->
                list.map { participant ->
                    if (participant.userId.equals(myId, ignoreCase = true)) {
                        participant.copy(isVideoEnabled = enabled)
                    } else {
                        participant
                    }
                }
            }
        }
        _activeCall.value?.callId?.let { callId ->
            socket?.emit(
                "video-state",
                org.json.JSONObject().apply {
                    put("callId", callId)
                    put("userId", myId)
                    put("isVideoEnabled", enabled)
                }
            )
        }
        updateServiceNotification()
    }

    fun switchCamera() {
        webRTCManager?.switchCamera()
    }

    fun sendReaction(
        emoji: String,
        reactionId: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ) {
        var myId = authService.getCurrentUserId()?.trim().orEmpty()
        var callId = _activeCall.value?.callId ?: return
        socket?.emit(
            "reaction",
            org.json.JSONObject().apply {
                put("callId", callId)
                put("userId", myId)
                put("emoji", emoji)
                put("reactionId", reactionId)
                put("timestamp", timestamp)
            }
        )
    }

    private fun handleUbuntuCallInvite(payload: kotlinx.serialization.json.JsonObject) {
        val callId = payload.stringValue("callId", "call_id")
        val callerUserId = payload.stringValue("callerUserId", "caller_user_id")
        if (callId.isBlank() || callerUserId.isBlank() || _activeCall.value != null) return

        _incomingInvite.value = CallInvite(
            callId = callId,
            title = payload.stringValue("title").ifBlank { "Llamada" },
            callerUserId = callerUserId,
            callerName = payload.stringValue("callerName", "caller_name").ifBlank { "Usuario" },
            callerAvatarUrl = payload.stringValue("callerAvatarUrl", "caller_avatar_url").ifBlank { null },
            contextType = payload.stringValue("contextType", "context_type").ifBlank { "dm" },
            contextId = payload.stringValue("contextId", "context_id").ifBlank { null },
            isVideo = payload.booleanValue("isVideo", "is_video")
        )
        startForegroundIncomingRingtone()
    }

    private fun handleUbuntuCallResponse(payload: kotlinx.serialization.json.JsonObject) {
        val callId = payload.stringValue("callId", "call_id")
        val action = payload.stringValue("action", "call_action")
        if (callId.isBlank() || action.isBlank()) return
        val incoming = _incomingInvite.value
        if (
            incoming?.callId.equals(callId, ignoreCase = true) &&
            action.trim().lowercase() in setOf("cancel", "decline", "missed", "ended")
        ) {
            _incomingInvite.value = null
            callTonePlayer.stopIncomingRingtone()
        }
        handleExternalCallResponse(
            callId = callId,
            action = action,
            fromUserId = payload.stringValue("fromUserId", "from_user_id")
        )
    }

    private fun kotlinx.serialization.json.JsonObject.stringValue(vararg keys: String): String {
        keys.forEach { key ->
            this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val extraData = this["extraData"]?.jsonObject
        keys.forEach { key ->
            extraData?.get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun kotlinx.serialization.json.JsonObject.booleanValue(vararg keys: String): Boolean {
        keys.forEach { key ->
            this[key]?.jsonPrimitive?.let { value ->
                value.booleanOrNull?.let { return it }
                value.contentOrNull?.let { return it == "1" || it.equals("true", ignoreCase = true) }
            }
        }
        return false
    }

    fun dismissIncomingInvite() {
        _incomingInvite.value = null
        callTonePlayer.stopIncomingRingtone()
    }

    fun presentIncomingInvite(invite: CallInvite) {
        if (_activeCall.value != null) return
        clearEndedCallSummary()
        _incomingInvite.value = invite
        startForegroundIncomingRingtone()
    }

    private fun startForegroundIncomingRingtone() {
        val isForeground = ProcessLifecycleOwner.get()
            .lifecycle
            .currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            callTonePlayer.startIncomingRingtone()
        }
    }

    fun clearEndedCallSummary(callId: String? = null) {
        var normalized = callId?.trim()
        val current = _endedCallSummary.value ?: return
        if (normalized.isNullOrBlank() || current.callId.equals(normalized, ignoreCase = true)) {
            _endedCallSummary.value = null
        }
    }

    fun refreshAudioSession() {
        ensureWebRtcInitializedAfterPermission()
        prepareCallAudioSession()
        webRTCManager?.ensureLocalTracks()
        webRTCManager?.setRemoteAudioPlaybackEnabled(true)
        webRTCManager?.setSpeakerphoneOn(_isSpeaker.value)
        if (hasMicrophonePermission()) {
            webRTCManager?.setAudioEnabled(!_isMuted.value)
        }
    }

    fun refreshAudioSessionRepeatedly(attempts: Int = 3, delayMs: Long = 450L) {
        scope.launch {
            repeat(attempts.coerceAtLeast(1)) { index ->
                refreshAudioSession()
                if (index < attempts - 1) {
                    delay(delayMs)
                }
            }
        }
    }

    private suspend fun sendCallInvite(targetUserId: String, invite: CallInvite) {
        val callCreatedAt = java.time.Instant.now().toString()
        val notificationResult = notificationRepository.sendNotification(
            recipientId = targetUserId.trim(),
            type = "call_invite",
            targetId = invite.callId,
            content = if (invite.isVideo) "Videollamada entrante" else "Llamada entrante",
            extraData = buildJsonObject {
                put("call_id", invite.callId)
                put("call_context_type", invite.contextType)
                put("call_context_id", invite.contextId ?: "")
                put("call_title", invite.title)
                put("call_created_at", callCreatedAt)
                put("call_is_video", invite.isVideo)
            }
        ).onFailure { error ->
            android.util.Log.e(
                "CallRepository",
                "Failed to enqueue call_invite notification for ${targetUserId.trim()}: ${error.message}",
                error
            )
        }
        if (supabaseConfig.isLocalFallbackPrimary) {
            notificationResult.getOrThrow()
            return
        }

        try {
            var channelName = "call-user:${targetUserId.trim()}"
            var channel = supabaseConfig.client.channel(channelName)
            supabaseConfig.subscribeRealtimeChannel(
                channel = channel,
                reason = "call-invite-$targetUserId",
                blockUntilSubscribed = true
            )

            channel.broadcast(
                event = "call_invite",
                message = CallInviteBroadcastPayload(
                    callId = invite.callId,
                    title = invite.title,
                    callerUserId = invite.callerUserId,
                    callerName = invite.callerName,
                    callerAvatarUrl = invite.callerAvatarUrl ?: "",
                    contextType = invite.contextType,
                    contextId = invite.contextId ?: "",
                    isVideo = invite.isVideo
                )
            )
            delay(200)
            channel.unsubscribe()
        } catch (e: Exception) {
            android.util.Log.e("CallRepository", "Failed to send call invite", e)
        }
    }

    private suspend fun sendCallResponse(
        targetUserId: String,
        callId: String,
        action: String,
        fromUserId: String,
        notificationAction: String = action
    ) {
        if (supabaseConfig.isLocalFallbackPrimary) {
            val notificationType = if (action == "accept") "call_response" else "call_cancel"
            notificationRepository.sendNotification(
                recipientId = targetUserId.trim(),
                type = notificationType,
                targetId = callId,
                content = when (notificationAction.trim().lowercase()) {
                    "accept" -> "Llamada aceptada"
                    "missed" -> "Llamada no contestada"
                    "end" -> "Llamada finalizada"
                    "decline" -> "Llamada rechazada"
                    else -> "Llamada cancelada"
                },
                extraData = buildJsonObject {
                    put("call_id", callId)
                    put("call_action", action)
                    put("from_user_id", fromUserId)
                }
            ).getOrThrow()
            return
        }

        if (action == "cancel" || action == "end" || action == "decline") {
            runCatching {
                notificationRepository.sendNotification(
                    recipientId = targetUserId.trim(),
                    type = "call_cancel",
                    targetId = callId,
                    content = when (notificationAction.trim().lowercase()) {
                        "missed" -> "Llamada no contestada"
                        "end" -> "Llamada finalizada"
                        "decline" -> "Llamada rechazada"
                        else -> "Llamada cancelada"
                    },
                    extraData = buildJsonObject {
                        put("call_id", callId)
                        put("call_action", notificationAction)
                    }
                )
            }.onFailure { error ->
                logBestEffortFailure(
                    operation = "sendCallCancellationNotification(action=$notificationAction)",
                    throwable = error,
                    callId = callId
                )
            }
        }

        try {
            var channelName = "call-user:${targetUserId.trim()}"
            var channel = supabaseConfig.client.channel(channelName)
            supabaseConfig.subscribeRealtimeChannel(
                channel = channel,
                reason = "call-signal-$targetUserId",
                blockUntilSubscribed = true
            )

            channel.broadcast(
                event = "call_response",
                message = CallResponseBroadcastPayload(
                    callId = callId,
                    action = action,
                    fromUserId = fromUserId
                )
            )
            delay(800)
            channel.unsubscribe()
        } catch (e: Exception) {
            android.util.Log.e("CallRepository", "Failed to send call response", e)
        }
    }

    private fun connectSocket(callId: String, userId: String, name: String, avatarUrl: String?) {
        try {
            socket?.off()
            socket?.disconnect()
            socket = null

            if (hasMicrophonePermission()) {
                initializeWebRTC(userId)
            } else {
                android.util.Log.e("CallRepository", "Microphone permission missing, skipping WebRTC init")
            }
            refreshAudioSession()

            val options = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionAttempts = SOCKET_RECONNECT_ATTEMPTS
                reconnectionDelay = SOCKET_RECONNECT_DELAY_MS
                reconnectionDelayMax = SOCKET_RECONNECT_DELAY_MAX_MS
                timeout = 10_000
                transports = arrayOf(io.socket.engineio.client.transports.WebSocket.NAME) // FIX: Bypass polling
                attachBearerToken(authService.getAccessToken())
            }
            val endpointBaseUrl = conectate.com.data.localfallback.RuntimeBackendEndpoint.baseUrl
            val signalingEndpoint = signalingUrl
            val createdSocket = IO.socket(signalingEndpoint, options)
            socket = createdSocket

            createdSocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                if (socket !== createdSocket) return@on
                val detail = args.firstOrNull()?.toString().orEmpty().ifBlank { "socket_connect_error" }
                recoverSocketAfterEndpointFailure(
                    failedSocket = createdSocket,
                    failedBaseUrl = endpointBaseUrl,
                    failedSignalingEndpoint = signalingEndpoint,
                    callId = callId,
                    userId = userId,
                    name = name,
                    avatarUrl = avatarUrl
                )
                _activeCall.value?.let { active ->
                    if (active.callId.equals(callId, ignoreCase = true)) {
                        appMetrics.recordFailure(
                            metric = "call_connect",
                            attributes = mapOf(
                                "call_id" to active.callId,
                                "context_type" to active.contextType.trim().lowercase().ifBlank { "dm" },
                                "reason" to "socket_connect_error",
                                "detail" to detail.take(120)
                            )
                        )
                    }
                }
            }

            createdSocket.on(Socket.EVENT_CONNECT) {
                if (socket !== createdSocket) return@on
                var sid = createdSocket.id() ?: ""

                // IDENTITY DIAGNOSTIC: Log what userId is being sent vs what authService reports
                val authCurrentId = authService.getCurrentUserId().orEmpty()
                android.util.Log.w("IDENTITY", "=== CALL CONNECT IDENTITY CHECK ===")
                android.util.Log.w("IDENTITY", "  socketId       = $sid")
                android.util.Log.w("IDENTITY", "  callId         = $callId")
                android.util.Log.w("IDENTITY", "  claimedUserId  = ${userId.trim()} (param passed to connectSocket)")
                android.util.Log.w("IDENTITY", "  authCurrentId  = $authCurrentId (from authService.getCurrentUserId)")
                android.util.Log.w("IDENTITY", "  signalingUrl   = $signalingEndpoint")
                if (userId.trim() != authCurrentId && authCurrentId.isNotBlank()) {
                    android.util.Log.e("IDENTITY", "⚠️ MISMATCH! claimedUserId != authCurrentId. Possible stale session or wrong account. Clear app data and re-login!")
                }

                // *** CRITICAL: Emit join-call FIRST before any WebRTC/audio setup ***
                createdSocket.emit(
                    "join-call",
                    org.json.JSONObject().apply {
                        put("callId", callId.trim())
                        put("userId", userId.trim())
                        put("name", name)
                        put("avatarUrl", avatarUrl ?: "")
                        put("videoEnabled", webRTCManager?.hasLocalVideoTrack() == true)
                    }
                )
                android.util.Log.i("CallRepository", "📤 join-call EMITTED for call=$callId sid=$sid")

                // Now do WebRTC/audio setup in a protected block
                try {
                    webRTCManager?.setCurrentUser(userId, sid)
                    webRTCManager?.refreshManagedIceServers(force = true)
                    refreshAudioSession()
                } catch (e: Exception) {
                    android.util.Log.e("CallRepository", "❌ WebRTC/audio setup failed AFTER join-call was sent", e)
                }

                // Sync self socketId
                _participants.update { current ->
                    var self = current.firstOrNull { it.userId.equals(userId, ignoreCase = true) }
                        ?: CallParticipant(userId = userId, name = name, avatarUrl = avatarUrl, socketId = sid)
                    (current.filterNot { it.userId.equals(userId, ignoreCase = true) } + self.copy(socketId = sid))
                        .distinctBy { it.socketId }
                }
            }

            socket?.on("user-joined") { args ->
                val data = args.getOrNull(0) as? org.json.JSONObject ?: return@on
                var sid = data.getString("socketId")
                var uid = data.getString("userId")
                var pname = data.optString("name", "Usuario")
                var pavatar = data.optString("avatarUrl", "").ifBlank { null }
                val videoEnabled = data.optBoolean("videoEnabled", false)

                _participants.update { current ->
                    var p = CallParticipant(
                        userId = uid,
                        name = pname,
                        avatarUrl = pavatar,
                        socketId = sid,
                        isVideoEnabled = videoEnabled
                    )
                    (current.filterNot { it.socketId == sid || it.userId.equals(uid, ignoreCase = true) } + p)
                        .distinctBy { it.socketId }
                }
                updateDirectPeer(uid, pname, pavatar)
                refreshRemoteParticipantFlag(_participants.value)

                webRTCManager?.onUserJoined(sid, uid)
                promoteConnectedIfReady()
                refreshAudioSessionRepeatedly(attempts = 4, delayMs = 500L)
            }

            socket?.on("user-left") { args ->
                val sid = args.getOrNull(0) as? String ?: return@on
                _participants.update { list -> list.filterNot { it.socketId == sid } }
                webRTCManager?.onUserLeft(sid)
                autoEndIfAlone(graceMs = REMOTE_RECONNECT_GRACE_MS)
            }

            socket?.on("current-participants") { args ->
                val participantsJson = args.getOrNull(0) as? org.json.JSONArray ?: return@on
                val mySocketId = socket?.id() ?: ""
                val previousRemoteSockets = _participants.value
                    .filterNot { it.userId.equals(userId, ignoreCase = true) }
                    .map { it.socketId.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()

                val remoteParticipants = mutableListOf<CallParticipant>()

                for (i in 0 until participantsJson.length()) {
                    var p = participantsJson.getJSONObject(i)
                    var sid = p.getString("socketId")
                    var uid = p.getString("userId")
                    var pname = p.optString("name", "Usuario")
                    var pavatar = p.optString("avatarUrl", "").ifBlank { null }
                    val videoEnabled = p.optBoolean("videoEnabled", false)
                    if (sid == mySocketId) continue

                    remoteParticipants.add(
                        CallParticipant(
                            userId = uid,
                            name = pname,
                            avatarUrl = pavatar,
                            socketId = sid,
                            isVideoEnabled = videoEnabled
                        )
                    )

                    if (sid !in previousRemoteSockets) {
                        webRTCManager?.onUserJoined(sid, uid)
                    }
                }

                val existingSelf = _participants.value.firstOrNull { it.userId.equals(userId, ignoreCase = true) }
                    ?: CallParticipant(userId = userId, name = name, avatarUrl = avatarUrl, socketId = mySocketId)
                val authoritativeList = listOf(existingSelf.copy(socketId = mySocketId)) +
                    remoteParticipants.distinctBy { it.socketId }

                val currentRemoteSockets = remoteParticipants
                    .map { it.socketId.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                previousRemoteSockets
                    .filterNot { it in currentRemoteSockets }
                    .forEach { staleSocketId ->
                        webRTCManager?.onUserLeft(staleSocketId)
                    }

                _participants.value = authoritativeList
                remoteParticipants.firstOrNull()?.let {
                    updateDirectPeer(it.userId, it.name, it.avatarUrl)
                }
                refreshRemoteParticipantFlag(authoritativeList)
                promoteConnectedIfReady()
                if (remoteParticipants.isNotEmpty()) {
                    refreshAudioSessionRepeatedly(attempts = 4, delayMs = 500L)
                }
            }

            socket?.on("mute-state") { args ->
                val data = args.getOrNull(0) as? org.json.JSONObject ?: return@on
                var uid = data.getString("userId")
                var muted = data.getBoolean("isMuted")
                _participants.update { list ->
                    list.map { if (it.userId.equals(uid, ignoreCase = true)) it.copy(isMuted = muted) else it }
                }
            }

            socket?.on("video-state") { args ->
                val data = args.getOrNull(0) as? org.json.JSONObject ?: return@on
                val uid = data.optString("userId", "").trim()
                val sid = data.optString("socketId", "").trim()
                val enabled = data.optBoolean("isVideoEnabled", false)
                _participants.update { list ->
                    list.map { participant ->
                        if (
                            (uid.isNotBlank() && participant.userId.equals(uid, ignoreCase = true)) ||
                            (sid.isNotBlank() && participant.socketId == sid)
                        ) {
                            participant.copy(isVideoEnabled = enabled)
                        } else {
                            participant
                        }
                    }
                }
            }

            socket?.on("reaction") { args ->
                val data = args.getOrNull(0) as? org.json.JSONObject ?: return@on
                val reaction = CallReactionEvent(
                    id = data.optString("reactionId", data.optString("id", "")),
                    userId = data.getString("userId"),
                    emoji = data.getString("emoji"),
                    timestamp = data.optLong("timestamp", System.currentTimeMillis())
                )
                scope.launch { _incomingReactions.emit(reaction) }
            }

            socket?.on("offer") { args ->
                val data = args.getOrNull(0) as? org.json.JSONObject ?: return@on
                val senderSocketId = data.getString("sender")
                var senderUserId = if (data.has("userId")) data.getString("userId") else {
                    _participants.value.find { it.socketId == senderSocketId }?.userId ?: ""
                }
                val offerSdp = data.getJSONObject("offer").getString("sdp")
                webRTCManager?.onOfferReceived(senderSocketId, senderUserId, offerSdp)
            }

            socket?.on("answer") { args ->
                val data = args.getOrNull(0) as? org.json.JSONObject ?: return@on
                var sender = data.getString("sender")
                var senderUserId = if (data.has("userId")) data.getString("userId") else {
                    _participants.value.find { it.socketId == sender }?.userId ?: ""
                }
                val answerSdp = data.getJSONObject("answer").getString("sdp")
                webRTCManager?.onAnswerReceived(sender, answerSdp, senderUserId)
            }

            socket?.on("ice-candidate") { args ->
                val data = args.getOrNull(0) as? org.json.JSONObject ?: return@on
                var sender = data.getString("sender")
                var senderUserId = if (data.has("userId")) data.getString("userId") else {
                    _participants.value.find { it.socketId == sender }?.userId ?: ""
                }
                val candidate = data.getJSONObject("candidate")
                webRTCManager?.onIceCandidateReceived(sender, candidate, senderUserId)
            }

            createdSocket.on(Socket.EVENT_DISCONNECT) {
                android.util.Log.w(
                    "CallRepository",
                    "Call signaling socket disconnected; preserving any active WebRTC media path while Socket.IO reconnects."
                )
            }

            createdSocket.connect()
        } catch (e: Exception) {
            android.util.Log.e("CallRepository", "Failed to setup call socket", e)
        }
    }

    @Synchronized
    private fun ensureWebRtcInitializedAfterPermission() {
        if (!hasMicrophonePermission()) return
        val userId = authService.getCurrentUserId()?.trim().orEmpty()
        if (userId.isBlank() || _activeCall.value == null) return

        if (webRTCManager == null) {
            initializeWebRTC(userId)
        }

        webRTCManager?.setCurrentUser(userId, socket?.id()?.trim().orEmpty())
        _participants.value
            .asSequence()
            .filterNot { it.userId.equals(userId, ignoreCase = true) }
            .filter { it.socketId.isNotBlank() }
            .forEach { participant ->
                webRTCManager?.onUserJoined(participant.socketId, participant.userId)
            }
    }

    private fun recoverSocketAfterEndpointFailure(
        failedSocket: Socket,
        failedBaseUrl: String,
        failedSignalingEndpoint: String,
        callId: String,
        userId: String,
        name: String,
        avatarUrl: String?
    ) {
        if (socketEndpointRecoveryJob?.isActive == true) return
        socketEndpointRecoveryJob = scope.launch {
            try {
                val refreshed = conectate.com.data.localfallback.RuntimeBackendEndpoint
                    .refreshAfterEndpointFailure(failedBaseUrl)
                val active = _activeCall.value
                if (
                    !refreshed ||
                    socket !== failedSocket ||
                    active == null ||
                    !active.callId.equals(callId, ignoreCase = true)
                ) {
                    return@launch
                }
                val refreshedEndpoint = signalingUrl
                if (refreshedEndpoint == failedSignalingEndpoint) return@launch
                android.util.Log.w("CallRepository", "Rebuilding call socket after backend endpoint changed.")
                connectSocket(callId, userId, name, avatarUrl)
            } finally {
                socketEndpointRecoveryJob = null
            }
        }
    }

    private fun initializeWebRTC(userId: String) {
        var events = object : conectate.com.data.webrtc.SignalingEvents {
            override fun onSendOffer(targetSocketId: String, offer: org.json.JSONObject) {
                var data = org.json.JSONObject().apply {
                    put("target", targetSocketId)
                    put("offer", offer)
                    put("userId", userId)
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

        if (webRTCManager == null) {
            webRTCManager = WebRTCManager(
                context = context,
                signalingEvents = events,
                accessTokenProvider = { authService.getAccessToken() }
            )
        } else {
            webRTCManager?.hardReset()
        }
        webRTCManager?.let(::observeWebRtcState)
        webRTCManager?.refreshManagedIceServers()
        webRTCManager?.ensureLocalTracks()
        webRTCManager?.setRemoteAudioPlaybackEnabled(true)
        webRTCManager?.setCurrentUser(userId, "")
        if (hasMicrophonePermission()) {
            webRTCManager?.setAudioEnabled(!_isMuted.value)
        }
    }

    private fun observeWebRtcState(manager: WebRTCManager) {
        webRtcStateObservationJob?.cancel()
        webRtcStateObservationJob = scope.launch {
            manager.peerConnectionStates.collectLatest { states ->
                val hasConnectedPeer = states.values.any {
                    it == PeerConnection.IceConnectionState.CONNECTED ||
                        it == PeerConnection.IceConnectionState.COMPLETED
                }
                if (hasConnectedPeer && !hasConnectedMediaPath) {
                    hasConnectedMediaPath = true
                    clearConnectingTimeout()
                    android.util.Log.i("CallRepository", "WebRTC media path connected. Reinforcing audio session.")
                    promoteConnectedIfReady()
                    refreshAudioSessionRepeatedly(attempts = 6, delayMs = 350L)
                } else if (!hasConnectedPeer) {
                    hasConnectedMediaPath = false
                }
            }
        }
    }

    private fun hasConnectedWebRtcMedia(): Boolean {
        return webRTCManager
            ?.peerConnectionStates
            ?.value
            ?.values
            ?.any {
                it == PeerConnection.IceConnectionState.CONNECTED ||
                    it == PeerConnection.IceConnectionState.COMPLETED
            } == true
    }

    private fun promoteConnectedIfReady() {
        var call = _activeCall.value ?: return
        if (call.connectionState == CallConnectionState.CONNECTED) return
        if (_participants.value.size < 2) return
        if (!hasConnectedWebRtcMedia()) {
            if (call.connectionState == CallConnectionState.RINGING_OUTGOING) {
                _activeCall.value = call.copy(connectionState = CallConnectionState.CONNECTING)
            }
            return
        }
        callEverHadRemoteParticipant = true

        _activeCall.value = call.copy(
            connectionState = CallConnectionState.CONNECTED,
            startedAtEpochMs = System.currentTimeMillis()
        )
        recordCallSetupSuccess(call)
        clearConnectingTimeout()
        pendingInvitees.clear()
        clearOutgoingRingTimeout()
        callTonePlayer.stopRingback()
    }

    private fun recordCallSetupSuccess(call: ActiveCall) {
        var startedAt = callSetupStartedAtMs ?: return
        if (!call.callId.equals(callSetupMetricCallId, ignoreCase = true)) return
        appMetrics.recordLatency(
            metric = "call_connect",
            durationMs = System.currentTimeMillis() - startedAt,
            attributes = mapOf(
                "call_id" to call.callId,
                "context_type" to call.contextType.trim().lowercase().ifBlank { "dm" },
                "participant_count" to _participants.value.size.toString()
            )
        )
        callSetupStartedAtMs = null
        callSetupMetricCallId = null
    }

    private fun recordCallSetupFailureIfPending(call: ActiveCall, reason: String) {
        var startedAt = callSetupStartedAtMs ?: return
        if (!call.callId.equals(callSetupMetricCallId, ignoreCase = true)) return
        appMetrics.recordFailure(
            metric = "call_connect",
            attributes = mapOf(
                "call_id" to call.callId,
                "context_type" to call.contextType.trim().lowercase().ifBlank { "dm" },
                "elapsed_ms" to (System.currentTimeMillis() - startedAt).toString(),
                "reason" to reason.trim().lowercase().replace(' ', '_')
            )
        )
        callSetupStartedAtMs = null
        callSetupMetricCallId = null
    }

    private fun autoEndIfAlone(graceMs: Long = 800L) {
        var call = _activeCall.value ?: return
        if (call.connectionState == CallConnectionState.ENDED) return
        if (call.connectionState != CallConnectionState.CONNECTED && !callEverHadRemoteParticipant) return
        if (_participants.value.size > 1) return

        scope.launch {
            delay(graceMs)
            val stillAlone = _participants.value.size <= 1
            if (stillAlone) {
                endCallInternal(reason = "Llamada finalizada")
            }
        }
    }

    private fun endCallInternal(reason: String) {
        var callSnapshot = _activeCall.value
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        callTonePlayer.stopRingback()
        clearOutgoingRingTimeout()
        clearConnectingTimeout()
        callSnapshot?.let { recordCallSetupFailureIfPending(it, reason) }
        if (callSnapshot != null) {
            scope.launch {
                try {
                    persistCallOutcome(
                        callSnapshot = callSnapshot,
                        currentUserId = currentUserId,
                        reason = reason
                    )
                } catch (error: Exception) {
                    logBestEffortFailure(
                        operation = "persistCallOutcome(reason=$reason)",
                        throwable = error,
                        callId = callSnapshot.callId
                    )
                }
            }
        }

        pendingInvitees.clear()
        callEverHadRemoteParticipant = false
        hasConnectedMediaPath = false
        callSetupStartedAtMs = null
        callSetupMetricCallId = null
        webRtcStateObservationJob?.cancel()
        webRtcStateObservationJob = null
        socketEndpointRecoveryJob?.cancel()
        socketEndpointRecoveryJob = null

        try {
            socket?.off()
            socket?.disconnect()
        } catch (error: Exception) {
            logBestEffortFailure(
                operation = "disconnectCallSocket",
                throwable = error,
                callId = callSnapshot?.callId
            )
        }
        socket = null

        try {
            webRTCManager?.hardReset()
        } catch (error: Exception) {
            logBestEffortFailure(
                operation = "hardResetWebRtcManager",
                throwable = error,
                callId = callSnapshot?.callId
            )
        }
        releaseCallAudioSession()

        _endedCallSummary.value = callSnapshot?.toEndedSummary(reason)
        _activeCall.value = _activeCall.value?.copy(connectionState = CallConnectionState.ENDED)
        _participants.value = emptyList()
        _isMuted.value = false

        stopService()

        // Clear state immediately to avoid leaving "No hay llamada activa" screens/bars.
        _activeCall.value = null
    }

    private fun scheduleOutgoingRingTimeout(callId: String) {
        clearOutgoingRingTimeout()
        outgoingRingTimeoutJob = scope.launch {
            delay(OUTGOING_RING_TIMEOUT_MS)
            var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
            var active = _activeCall.value ?: return@launch
            if (!active.callId.equals(callId, ignoreCase = true)) return@launch
            if (active.connectionState != CallConnectionState.RINGING_OUTGOING &&
                active.connectionState != CallConnectionState.CONNECTING) {
                return@launch
            }

            var invitees = pendingInvitees.toList()
            invitees.forEach { target ->
                runCatching {
                    sendCallResponse(
                        targetUserId = target,
                        callId = active.callId,
                        action = "cancel",
                        fromUserId = currentUserId,
                        notificationAction = "missed"
                    )
                }.onFailure { error ->
                    logBestEffortFailure(
                        operation = "sendCallResponse(missed-timeout)",
                        throwable = error,
                        callId = active.callId
                    )
                }
            }
            endCallInternal(reason = "Llamada no contestada")
        }
    }

    private fun clearOutgoingRingTimeout() {
        outgoingRingTimeoutJob?.cancel()
        outgoingRingTimeoutJob = null
    }

    private fun scheduleConnectingTimeout(callId: String) {
        clearConnectingTimeout()
        connectingTimeoutJob = scope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            var active = _activeCall.value ?: return@launch
            if (!active.callId.equals(callId, ignoreCase = true)) return@launch
            if (active.connectionState != CallConnectionState.CONNECTING) return@launch
            if (hasConnectedWebRtcMedia()) return@launch

            android.util.Log.w(
                "CallRepository",
                "Call setup timed out after ${CONNECTING_TIMEOUT_MS}ms without a connected WebRTC media path."
            )
            endCallInternal(reason = "No se pudo establecer la conexion")
        }
    }

    private fun clearConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
    }

    private fun formatCallDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).toInt().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun startService() {
        try {
            var intent = android.content.Intent(context, CallForegroundService::class.java).apply {
                action = "START"
                putExtra(CallForegroundService.EXTRA_CALL_TITLE, _activeCall.value?.title ?: "Llamada")
                putExtra(CallForegroundService.EXTRA_IS_MUTED, _isMuted.value)
                putExtra(CallForegroundService.EXTRA_IS_VIDEO, _activeCall.value?.isVideo == true)
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            android.util.Log.e("CallRepository", "Failed to start CallForegroundService", e)
        }
    }

    private fun updateServiceNotification() {
        try {
            var intent = android.content.Intent(context, CallForegroundService::class.java).apply {
                action = "UPDATE"
                putExtra(CallForegroundService.EXTRA_CALL_TITLE, _activeCall.value?.title ?: "Llamada")
                putExtra(CallForegroundService.EXTRA_IS_MUTED, _isMuted.value)
                putExtra(CallForegroundService.EXTRA_IS_VIDEO, _activeCall.value?.isVideo == true)
            }
            context.startService(intent)
        } catch (error: Exception) {
            logBestEffortFailure(
                operation = "updateCallForegroundService",
                throwable = error,
                callId = _activeCall.value?.callId
            )
        }
    }

    private fun stopService() {
        try {
            var intent = android.content.Intent(context, CallForegroundService::class.java).apply {
                action = CallForegroundService.ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (error: Exception) {
            logBestEffortFailure(
                operation = "stopCallForegroundService",
                throwable = error,
                callId = _activeCall.value?.callId
            )
        }
    }

    private fun prepareCallAudioSession() {
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
                    .setOnAudioFocusChangeListener { }
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
            audioManager.isMicrophoneMute = false
            routeSpeakerphone(audioManager, _isSpeaker.value)
            webRTCManager?.setSpeakerphoneOn(_isSpeaker.value)
        } catch (e: Exception) {
            android.util.Log.w("CallRepository", "Failed to prepare call audio session", e)
        }
    }

    private suspend fun loadUserProfileQuick(userId: String, timeoutMs: Long = 900L) =
        withTimeoutOrNull(timeoutMs) {
            userRepository.getUserById(userId.trim()).getOrNull()
        }

    private fun updateDirectPeer(userId: String, name: String, avatarUrl: String?) {
        val current = _activeCall.value ?: return
        if (current.contextType.trim().lowercase() != "dm") return
        _activeCall.value = current.copy(
            peerUserId = userId.trim().takeIf { it.isNotBlank() } ?: current.peerUserId,
            peerName = name.trim().takeIf { it.isNotBlank() } ?: current.peerName,
            peerAvatarUrl = avatarUrl?.trim()?.ifBlank { null } ?: current.peerAvatarUrl
        )
    }

    private fun releaseCallAudioSession() {
        var audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            android.util.Log.w("CallRepository", "Failed to release call audio session", e)
        }
    }

    private fun buildEndSignalTargets(currentUserId: String): List<String> {
        return ((pendingInvitees.toList()) + _participants.value.map { it.userId })
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(currentUserId, ignoreCase = true) }
            .distinct()
    }

    private suspend fun persistCallOutcome(
        callSnapshot: ActiveCall,
        currentUserId: String,
        reason: String
    ) {
        var normalizedContextType = callSnapshot.contextType.trim().lowercase().ifBlank { "dm" }
        val resolvedConversationId = resolveConversationIdForHistory(callSnapshot)
        var wasConnected =
            callSnapshot.connectionState == CallConnectionState.CONNECTED && callSnapshot.startedAtEpochMs != null
        val durationMs = if (wasConnected) {
            (System.currentTimeMillis() - (callSnapshot.startedAtEpochMs ?: System.currentTimeMillis()))
                .coerceAtLeast(0L)
        } else {
            null
        }
        val durationLabel = durationMs?.let(::formatCallDuration)
        var direction = if (callSnapshot.initiatorUserId.equals(currentUserId, ignoreCase = true)) {
            CallHistoryDirection.OUTGOING
        } else {
            CallHistoryDirection.INCOMING
        }
        var historyTitle = when (normalizedContextType) {
            "dm" -> callSnapshot.peerName?.trim()?.ifBlank { null } ?: callSnapshot.title.ifBlank { "Llamada" }
            else -> callSnapshot.title.ifBlank { "Llamada" }
        }
        val historySubtitle = durationLabel?.let { "Duración $it" } ?: when (normalizedContextType) {
            "group" -> historyTitle
            "community" -> historyTitle
            else -> callSnapshot.peerName?.trim()?.ifBlank { null } ?: "Chat directo"
        }

        callHistoryRepository.record(
            CallHistoryEntry(
                callId = callSnapshot.callId,
                title = historyTitle,
                subtitle = historySubtitle,
                contextType = normalizedContextType,
                contextId = when (normalizedContextType) {
                    "dm" -> resolvedConversationId ?: callSnapshot.contextId?.trim()?.ifBlank { null }
                    else -> callSnapshot.contextId?.trim()?.ifBlank { null }
                },
                peerUserId = callSnapshot.peerUserId?.trim()?.ifBlank { null },
                peerName = callSnapshot.peerName?.trim()?.ifBlank { null } ?: historyTitle,
                peerAvatarUrl = callSnapshot.peerAvatarUrl,
                direction = direction,
                status = mapCallHistoryStatus(reason, wasConnected),
                statusLabel = if (durationLabel != null) {
                    "Llamada completada • $durationLabel"
                } else {
                    reason.ifBlank { "Llamada finalizada" }
                },
                isVideo = callSnapshot.isVideo
            )
        )

        if (!callSnapshot.initiatorUserId.equals(currentUserId, ignoreCase = true)) {
            return
        }

        val callKindLabel = if (callSnapshot.isVideo) "Videollamada" else "Llamada de voz"
        var content = if (durationLabel != null) {
            "$callKindLabel • duración $durationLabel"
        } else {
            reason.ifBlank { "Llamada finalizada" }
        }

        when (normalizedContextType) {
            "dm" -> {
                var chatId = resolvedConversationId ?: return
                messageRepository.sendMessage(
                    chatId = chatId,
                    content = content,
                    mediaType = "call"
                )
            }
            "group" -> {
                var groupId = callSnapshot.contextId?.trim()?.ifBlank { null } ?: return
                groupRepository.sendGroupMessage(
                    groupId = groupId,
                    content = content,
                    contentType = "call"
                )
            }
            "community" -> {
                var communityId = callSnapshot.contextId?.trim()?.ifBlank { null } ?: return
                communityRepository.sendCommunityMessage(
                    communityId = communityId,
                    content = content,
                    contentType = "call"
                )
            }
        }
    }

    private suspend fun recordIncomingInviteOutcome(
        invite: CallInvite,
        reason: String
    ) {
        var normalizedContextType = invite.contextType.trim().lowercase().ifBlank { "dm" }
        var historyTitle = when (normalizedContextType) {
            "dm" -> invite.callerName.ifBlank { invite.title.ifBlank { "Llamada" } }
            else -> invite.title.ifBlank { "Llamada" }
        }

        callHistoryRepository.record(
            CallHistoryEntry(
                callId = invite.callId,
                title = historyTitle,
                subtitle = when (normalizedContextType) {
                    "dm" -> invite.callerName.ifBlank { "Chat directo" }
                    else -> historyTitle
                },
                contextType = normalizedContextType,
                contextId = invite.contextId?.trim()?.ifBlank { null },
                peerUserId = invite.callerUserId.trim().ifBlank { null },
                peerName = invite.callerName.ifBlank { historyTitle },
                peerAvatarUrl = invite.callerAvatarUrl,
                direction = CallHistoryDirection.INCOMING,
                status = mapCallHistoryStatus(reason, wasConnected = false),
                statusLabel = reason.ifBlank { "Llamada finalizada" },
                isVideo = invite.isVideo
            )
        )
    }

    private suspend fun resolveConversationIdForHistory(callSnapshot: ActiveCall): String? {
        var normalizedContextType = callSnapshot.contextType.trim().lowercase().ifBlank { "dm" }
        if (normalizedContextType != "dm") {
            return callSnapshot.contextId?.trim()?.ifBlank { null }
        }

        var peerUserId = callSnapshot.peerUserId?.trim()?.ifBlank { null }
        if (!peerUserId.isNullOrBlank()) {
            return messageRepository.getOrCreateChat(peerUserId)
                .getOrNull()
                ?.id
                ?.trim()
                ?.ifBlank { null }
                ?: callSnapshot.contextId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && !it.startsWith("placeholder_", ignoreCase = true) }
        }

        return callSnapshot.contextId
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("placeholder_", ignoreCase = true) }
    }

    private fun mapCallHistoryStatus(reason: String, wasConnected: Boolean): CallHistoryStatus {
        if (wasConnected) return CallHistoryStatus.ANSWERED
        var normalized = reason.trim().lowercase()
        return when {
            normalized.contains("rechazada") -> CallHistoryStatus.REJECTED
            normalized.contains("cancelada") -> CallHistoryStatus.CANCELLED
            normalized.contains("no contestada") -> CallHistoryStatus.NO_ANSWER
            else -> CallHistoryStatus.ENDED
        }
    }

    private fun routeSpeakerphone(audioManager: AudioManager, enabled: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val preferredDeviceTypes = if (enabled) {
                    listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                } else {
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
        } catch (e: Exception) {
            android.util.Log.w("CallRepository", "Failed to route speakerphone", e)
        }
    }

    private fun ActiveCall.toEndedSummary(reason: String): CallEndedSummary {
        var normalizedContextType = contextType.trim().lowercase()
        val resolvedPeerName = peerName?.ifBlank { null }
            ?: title.ifBlank { "Llamada" }
        var canMessage = normalizedContextType == "dm" && !peerUserId.isNullOrBlank()
        return CallEndedSummary(
            callId = callId,
            title = title,
            peerName = resolvedPeerName,
            peerAvatarUrl = peerAvatarUrl,
            statusText = reason,
            canMessage = canMessage,
            messageTargetId = if (canMessage) peerUserId else null
        )
    }
}





