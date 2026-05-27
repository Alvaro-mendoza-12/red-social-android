package conectate.com.data.repository

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import conectate.com.core.concurrency.nextRetryDelayMs
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.model.ActiveLiveSession
import conectate.com.data.model.LiveComment
import conectate.com.data.model.LiveQuality
import conectate.com.data.model.LiveReaction
import conectate.com.data.model.LiveRole
import conectate.com.data.model.LiveSource
import conectate.com.data.model.LiveStream
import conectate.com.data.signaling.SignalingConfig
import conectate.com.data.signaling.attachBearerToken
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.webrtc.WebRTCManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.*
import io.socket.client.IO
import io.socket.client.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.VideoTrack
import org.webrtc.EglBase
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import conectate.com.services.LiveForegroundService
import java.time.Instant
import java.net.HttpURLConnection
import java.net.URL

@Singleton
class LiveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val userRepository: UserRepository
) {
    private companion object {
        private const val SOCKET_RECONNECT_ATTEMPTS = 10
        private const val SOCKET_RECONNECT_DELAY_MS = 1_000L
        private const val SOCKET_RECONNECT_DELAY_MAX_MS = 12_000L
        private const val SUBSCRIPTION_RETRY_INITIAL_MS = 2_000L
        private const val SUBSCRIPTION_RETRY_MAX_MS = 30_000L
        private const val SUBSCRIPTION_RESTART_GRACE_MS = 1_000L
    }

    private val scope = newSafeScope("LiveRepository", Dispatchers.IO)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val livePresenceUrl = "${SignalingConfig.BASE_URL}/live-active-ids"

    private val signalingUrl = SignalingConfig.LIVE_URL
    private var socket: Socket? = null
    private var webRTCManager: WebRTCManager? = null
    private var commentsChannel: RealtimeChannel? = null
    private var commentsJob: Job? = null
    private var adaptiveQualityJob: Job? = null
    private val reactionExpiryJobs = mutableMapOf<String, Job>()
    private var lastAdaptiveQualityChangeAtMs: Long = 0L
    @Volatile
    private var lastGhostReconciliationUserId: String? = null

    // Live stats (host-side)
    private var peakViewerCount: Int = 0
    private var sessionCommentCount: Int = 0
    private var sessionReactionCount: Int = 0

    private val _activeStreams = MutableStateFlow<List<LiveStream>>(emptyList())
    val activeStreams: StateFlow<List<LiveStream>> = _activeStreams.asStateFlow()

    val liveHostUserIds: StateFlow<Set<String>> = activeStreams
        .map { streams ->
            streams.map { it.hostUserId }.filter { it.isNotBlank() }.toSet()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _activeSession = MutableStateFlow<ActiveLiveSession?>(null)
    val activeSession: StateFlow<ActiveLiveSession?> = _activeSession.asStateFlow()

    private val _comments = MutableStateFlow<List<LiveComment>>(emptyList())
    val comments: StateFlow<List<LiveComment>> = _comments.asStateFlow()

    private val _reactions = MutableStateFlow<List<LiveReaction>>(emptyList())
    val reactions: StateFlow<List<LiveReaction>> = _reactions.asStateFlow()

    private val _viewerCount = MutableStateFlow(0)
    val viewerCount: StateFlow<Int> = _viewerCount.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(true)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isMediaReady = MutableStateFlow(false)
    val isMediaReady: StateFlow<Boolean> = _isMediaReady.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    private val _currentSource = MutableStateFlow(LiveSource.CAMERA)
    val currentSource: StateFlow<LiveSource> = _currentSource.asStateFlow()

    private val _quality = MutableStateFlow(LiveQuality.P720)
    val quality: StateFlow<LiveQuality> = _quality.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    private val _eglBaseContext = MutableStateFlow<EglBase.Context?>(null)
    val eglBaseContext: StateFlow<EglBase.Context?> = _eglBaseContext.asStateFlow()

    private var lastScreenShareIntent: Intent? = null

    init {
        scope.launch {
            refreshActiveStreams()
            subscribeToLiveStreams()
        }
    }

    suspend fun refreshActiveStreams() = withContext(Dispatchers.IO) {
        try {
            reconcileOwnedGhostStreamsIfNeeded()
            var streams = supabaseConfig.client.postgrest["live_streams"].select {
                filter { eq("is_active", true) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                limit(60)
            }.decodeList<LiveStream>()
            val normalized = normalizeActiveStreams(streams)
            _activeStreams.value = filterToServerActiveStreams(normalized)
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Error refreshing live streams", e)
        }
    }

    private suspend fun reconcileOwnedGhostStreamsIfNeeded() {
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        if (currentUserId.isBlank()) {
            lastGhostReconciliationUserId = null
            return }
        if (lastGhostReconciliationUserId == currentUserId) return

        val ownActiveStreams = try {
            supabaseConfig.client.postgrest["live_streams"].select {
                filter { eq("host_user_id", currentUserId) }
                filter { eq("is_active", true) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                limit(10)
            }.decodeList<LiveStream>()
        } catch (e: Exception) {
            android.util.Log.w("LiveRepository", "Unable to inspect owned live rows for ghost cleanup", e)
            return
        }

        if (ownActiveStreams.isEmpty()) {
            lastGhostReconciliationUserId = currentUserId
            return
        }

        var activeIds = fetchServerActiveLiveIds(ownActiveStreams.map { it.id }) ?: return
        val localSessionId = _activeSession.value?.stream?.id?.trim().orEmpty()
        val ghosts = ownActiveStreams.filter { stream ->
            var streamId = stream.id.trim()
            streamId.isNotBlank() &&
                streamId != localSessionId &&
                !activeIds.contains(streamId)
        }

        ghosts.forEach { stream ->
            deactivateOwnedGhostStream(streamId = stream.id, currentUserId = currentUserId)
        }

        if (ghosts.isNotEmpty()) {
            android.util.Log.i(
                "LiveRepository",
                "Reconciled ${ghosts.size} ghost live row(s) for current user."
            )
        }
        lastGhostReconciliationUserId = currentUserId
    }

    private suspend fun deactivateOwnedGhostStream(streamId: String, currentUserId: String) {
        val cleanStreamId = streamId.trim()
        if (cleanStreamId.isBlank()) return

        var endedAt = Instant.now().toString()
        runCatching {
            try {
                supabaseConfig.client.postgrest["live_streams"].update({
                    set("is_active", false)
                    set("viewer_count", 0.toInt())
                    set("ended_at", endedAt)
                }) {
                    filter { eq("id", cleanStreamId) }
                    filter { eq("host_user_id", currentUserId) }
                }
            } catch (_: Exception) {
                supabaseConfig.client.postgrest["live_streams"].update({
                    set("is_active", false)
                    set("viewer_count", 0.toInt())
                }) {
                    filter { eq("id", cleanStreamId) }
                    filter { eq("host_user_id", currentUserId) }
                }
            }
        }.onFailure { error ->
            android.util.Log.w("LiveRepository", "Unable to deactivate ghost live $cleanStreamId", error)
        }
    }

    private fun normalizeActiveStreams(streams: List<LiveStream>): List<LiveStream> {
        return streams
            .filter { it.isActive }
            .distinctBy { stream ->
                val hostKey = stream.hostUserId.trim().lowercase()
                if (hostKey.isNotBlank()) hostKey else stream.id.trim()
            }
    }

    private fun filterToServerActiveStreams(streams: List<LiveStream>): List<LiveStream> {
        if (streams.isEmpty()) return emptyList()

        var activeIds = fetchServerActiveLiveIds(streams.map { it.id })
        if (activeIds == null) {
            android.util.Log.w(
                "LiveRepository",
                "Live presence endpoint unavailable. Falling back to DB active lives."
            )
            return streams
        }

        val filtered = streams.filter { stream -> activeIds.contains(stream.id.trim()) }
        val removed = streams.size - filtered.size
        if (removed > 0) {
            android.util.Log.i(
                "LiveRepository",
                "Filtered $removed ghost live(s) using signaling server presence."
            )
        }
        return filtered
    }

    private fun fetchServerActiveLiveIds(candidateIds: List<String>): Set<String>? {
        val normalizedIds = candidateIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedIds.isEmpty()) return emptySet()

        return try {
            val query = normalizedIds.joinToString(",")
            var connection = URL("$livePresenceUrl?ids=$query").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() }
                android.util.Log.w(
                    "LiveRepository",
                    "Live presence endpoint returned $responseCode: $errorMsg"
                )
                return null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val body = org.json.JSONObject(response)
            val ids = body.optJSONArray("liveIds") ?: return emptySet()
            buildSet {
                for (i in 0 until ids.length()) {
                    var value = ids.optString(i).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LiveRepository", "Failed to fetch live presence from signaling", e)
            null
        }
    }

    private suspend fun subscribeToLiveStreams() {
        var retryDelayMs = SUBSCRIPTION_RETRY_INITIAL_MS
        while (currentCoroutineContext().isActive) {
            var channel: RealtimeChannel? = null
            var shouldBackoff = false
            try {
                channel = supabaseConfig.client.channel("live-streams-global")
                val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "live_streams"
                }

                supabaseConfig.subscribeRealtimeChannel(
                    channel = channel,
                    reason = "live-streams-global"
                )
                retryDelayMs = SUBSCRIPTION_RETRY_INITIAL_MS
                changes.collect {
                    refreshActiveStreams()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                shouldBackoff = true
                android.util.Log.e("LiveRepository", "Live streams realtime subscription failed retrying", e)
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

    suspend fun getLiveStreamById(id: String): Result<LiveStream> = withContext(Dispatchers.IO) {
        try {
            var stream = supabaseConfig.client.postgrest["live_streams"].select {
                filter { eq("id", id.trim()) }
            }.decodeSingle<LiveStream>()
            Result.success(stream)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createLiveStream(
        title: String,
        source: LiveSource,
        hashtags: List<String> = emptyList()
    ): Result<LiveStream> = withContext(Dispatchers.IO) {
        try {
            var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
            if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))

            var profile = userRepository.getUserById(currentUserId).getOrNull()
            val hostName = profile?.displayName ?: profile?.username ?: "Usuario"
            val hostAvatar = profile?.avatar_url
            var streamId = UUID.randomUUID().toString()

            val normalizedHashtags = hashtags
                .map { it.trim() }
                .map { it.removePrefix("#") }
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(10)

            fun buildPayload(includeHashtags: Boolean) = buildJsonObject {
                // IMPORTANT: do not send created_at=null (would override DB default and break NOT NULL).
                // A live becomes active only after the host actually joins the signaling session.
                put("id", streamId)
                put("title", title.trim().take(80))
                put("host_user_id", currentUserId)
                put("host_name", hostName)
                if (!hostAvatar.isNullOrBlank()) put("host_avatar_url", hostAvatar)
                put("source", source.name.lowercase())
                put("is_active", false)
                put("viewer_count", 0)
                if (includeHashtags && normalizedHashtags.isNotEmpty()) {
                    put(
                        "hashtags",
                        buildJsonArray {
                            normalizedHashtags.forEach { tag ->
                                add(JsonPrimitive(tag))
                            }
                        }
                    )
                }
            }

            try {
                supabaseConfig.client.postgrest["live_streams"].insert(buildPayload(includeHashtags = true))
            } catch (e: Exception) {
                var lowered = e.message?.lowercase().orEmpty()
                val isHashtagsColumnMissing = normalizedHashtags.isNotEmpty() &&
                    lowered.contains("hashtags") &&
                    (lowered.contains("column") || lowered.contains("does not exist"))
                if (isHashtagsColumnMissing) {
                    // Backwards compatibility: if DB hasn't been migrated yet, retry without hashtags.
                    supabaseConfig.client.postgrest["live_streams"].insert(buildPayload(includeHashtags = false))
                } else {
                    throw e
                }
            }

            val created = supabaseConfig.client.postgrest["live_streams"].select {
                filter { eq("id", streamId) }
            }.decodeSingle<LiveStream>()

            refreshActiveStreams()
            Result.success(created)
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Error creating live stream", e)
            val msg = e.message?.lowercase().orEmpty()
            val safe = if (msg.contains("created_at") && msg.contains("not-null")) {
                "Error de base de datos al iniciar el live. Ejecuta el SQL de live_streams y vuelve a intentar."
            } else {
                "No se pudo iniciar el live."
            }
            Result.failure(Exception(safe))
        }
    }

    suspend fun hostLive(streamId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
            if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
            if (_activeSession.value != null) return@withContext Result.failure(Exception("Ya hay un live activo"))

            var stream = getLiveStreamById(streamId).getOrNull()
                ?: return@withContext Result.failure(Exception("Live no encontrado"))
            if (!stream.hostUserId.equals(currentUserId, ignoreCase = true)) {
                return@withContext Result.failure(Exception("No eres el anfitrión de este live"))
            }

            _viewerCount.value = stream.viewerCount
            peakViewerCount = stream.viewerCount.coerceAtLeast(0)
            sessionCommentCount = 0
            sessionReactionCount = 0
            _isMediaReady.value = false

            if (!initializeWebRTC(userId = currentUserId)) {
                disconnectAndClear()
                return@withContext Result.failure(Exception("No se pudo preparar la transmisión en este dispositivo."))
            }

            webRTCManager?.ensureLocalTracks()
            _activeSession.value = ActiveLiveSession(stream = stream, role = LiveRole.HOST, viewerCount = stream.viewerCount)
            _isMicEnabled.value = true
            _isPaused.value = false
            _isVideoEnabled.value = true
            _currentSource.value = stream.source.toLiveSource()
            lastScreenShareIntent = null
            if (_currentSource.value == LiveSource.CAMERA && _isVideoEnabled.value) {
                startCamera()
            }
            startAdaptiveQualityMonitor()
            startService(stream = stream, role = LiveRole.HOST)

            connectSocket(
                liveId = stream.id,
                role = LiveRole.HOST,
                userId = currentUserId,
                name = stream.hostName,
                avatarUrl = stream.hostAvatarUrl
            )
            subscribeToComments(stream.id)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Host live failed", e)
            disconnectAndClear()
            Result.failure(Exception(resolveLiveStartupError(e, fallback = "No se pudo iniciar el live.")))
        }
    }

    suspend fun joinLiveAsViewer(streamId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
            if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
            if (_activeSession.value != null) return@withContext Result.failure(Exception("Ya hay un live activo"))

            var stream = getLiveStreamById(streamId).getOrNull()
                ?: return@withContext Result.failure(Exception("Live no encontrado"))

            _viewerCount.value = stream.viewerCount
            peakViewerCount = stream.viewerCount.coerceAtLeast(0)
            sessionCommentCount = 0
            sessionReactionCount = 0
            _isMediaReady.value = false

            if (!initializeWebRTC(userId = currentUserId)) {
                disconnectAndClear()
                return@withContext Result.failure(Exception("No se pudo abrir el motor del live en este dispositivo."))
            }

            webRTCManager?.ensureLocalTracks()
            webRTCManager?.setVideoSendEnabled(false, renegotiate = false)
            _activeSession.value = ActiveLiveSession(stream = stream, role = LiveRole.VIEWER, viewerCount = stream.viewerCount)

            _isMicEnabled.value = false
            _isPaused.value = false
            _isVideoEnabled.value = true
            _currentSource.value = stream.source.toLiveSource()
            stopAdaptiveQualityMonitor()
            startService(stream = stream, role = LiveRole.VIEWER)
            var profile = userRepository.getUserById(currentUserId).getOrNull()
            val viewerName = profile?.displayName ?: profile?.username ?: "Usuario"
            val viewerAvatar = profile?.avatar_url

            connectSocket(
                liveId = stream.id,
                role = LiveRole.VIEWER,
                userId = currentUserId,
                name = viewerName,
                avatarUrl = viewerAvatar
            )
            subscribeToComments(stream.id)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Join live as viewer failed", e)
            disconnectAndClear()
            Result.failure(Exception(resolveLiveStartupError(e, fallback = "No se pudo abrir el live.")))
        }
    }

    fun toggleMic(enabled: Boolean) {
        _isMicEnabled.value = enabled
        webRTCManager?.setAudioEnabled(enabled)
        emitLiveState()
    }

    fun setQuality(value: LiveQuality) {
        _quality.value = value
    }

    fun startCamera(): Boolean {
        var q = _quality.value
        var manager = webRTCManager ?: return false
        lastScreenShareIntent = null
        _currentSource.value = LiveSource.CAMERA
        _isVideoEnabled.value = true
        manager.ensureLocalTracks()
        manager.setVideoSendEnabled(!_isPaused.value, renegotiate = false)
        manager.startVideo(
            isScreen = false,
            intent = null,
            isFront = true,
            targetWidth = q.width,
            targetHeight = q.height,
            targetFps = 24
        )
        refreshForegroundServiceType()
        emitLiveState()
        return true
    }

    fun startScreenShare(intent: Intent): Boolean {
        var q = _quality.value
        var manager = webRTCManager ?: return false
        val metrics = context.resources.displayMetrics
        val isPortrait = metrics.heightPixels >= metrics.widthPixels
        val boundW = if (isPortrait) q.height else q.width
        val boundH = if (isPortrait) q.width else q.height
        lastScreenShareIntent = Intent(intent)
        _currentSource.value = LiveSource.SCREEN
        _isVideoEnabled.value = true
        manager.ensureLocalTracks()
        manager.setVideoSendEnabled(!_isPaused.value, renegotiate = false)

        manager.startVideo(
            isScreen = true,
            intent = intent,
            isFront = true,
            targetWidth = boundW,
            targetHeight = boundH,
            targetFps = 15
        )
        refreshForegroundServiceType()
        emitLiveState()
        return true
    }

    fun stopVideo() {
        webRTCManager?.setVideoSendEnabled(false, renegotiate = false)
        webRTCManager?.stopVideo()
    }

    fun canReuseScreenShareIntent(): Boolean = lastScreenShareIntent != null

    fun pauseLive() {
        var session = _activeSession.value ?: return
        if (session.role != LiveRole.HOST || _isPaused.value) return
        _isPaused.value = true
        webRTCManager?.setVideoSendEnabled(false)
        emitLiveState()
    }

    fun resumeLive(screenIntent: Intent? = null): Boolean {
        var session = _activeSession.value ?: return false
        if (session.role != LiveRole.HOST) return false
        _isPaused.value = false
        if (!_isVideoEnabled.value) {
            webRTCManager?.setVideoSendEnabled(false)
            emitLiveState()
            return true
        }

        if (webRTCManager?.hasLocalVideoTrack() == true) {
            webRTCManager?.setVideoSendEnabled(true)
            emitLiveState()
            return true
        }

        var started = startConfiguredVideo(screenIntent)
        if (started) {
            webRTCManager?.setVideoSendEnabled(true, renegotiate = false)
            emitLiveState()
        } else {
            _isPaused.value = true
        }
        return started
    }

    fun updateHostConfiguration(
        source: LiveSource,
        micEnabled: Boolean,
        videoEnabled: Boolean,
        projectionIntent: Intent? = null
    ): Boolean {
        var session = _activeSession.value ?: return false
        if (session.role != LiveRole.HOST) return false
        var previousSource = _currentSource.value

        _isMicEnabled.value = micEnabled
        webRTCManager?.setAudioEnabled(micEnabled)
        _currentSource.value = source
        _isVideoEnabled.value = videoEnabled
        if (source == LiveSource.SCREEN && projectionIntent != null) {
            lastScreenShareIntent = Intent(projectionIntent)
        } else if (source != LiveSource.SCREEN) {
            lastScreenShareIntent = null
        }
        refreshForegroundServiceType()

        if (_isPaused.value) {
            if (!videoEnabled) {
                webRTCManager?.stopVideo()
                webRTCManager?.setVideoSendEnabled(false, renegotiate = false)
                emitLiveState()
                return true
            }

            var canReuseCurrentTrack = previousSource == source && webRTCManager?.hasLocalVideoTrack() == true
            val prepared = if (canReuseCurrentTrack) {
                true
            } else {
                startConfiguredVideo(projectionIntent)
            }
            if (prepared) {
                webRTCManager?.setVideoSendEnabled(false, renegotiate = false)
                emitLiveState()
            }
            return prepared
        }

        if (!videoEnabled) {
            webRTCManager?.setVideoSendEnabled(false, renegotiate = false)
            webRTCManager?.stopVideo()
            emitLiveState()
            return true
        }

        var canReuseCurrentTrack = previousSource == source && webRTCManager?.hasLocalVideoTrack() == true
        var started = if (canReuseCurrentTrack) {
            webRTCManager?.setVideoSendEnabled(true)
            true
        } else {
            startConfiguredVideo(projectionIntent)
        }
        if (started) {
            webRTCManager?.setVideoSendEnabled(true, renegotiate = false)
            emitLiveState()
        }
        return started
    }

    suspend fun sendComment(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        var session = _activeSession.value ?: return@withContext Result.failure(Exception("No hay live activo"))
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
        var text = content.trim()
        if (text.isBlank()) return@withContext Result.success(Unit)

        var profile = userRepository.getUserById(currentUserId).getOrNull()
        val userName = profile?.displayName ?: profile?.username ?: "Usuario"
        val avatar = profile?.avatar_url

        // IMPORTANT: do not send created_at=null (would override DB default and break NOT NULL).
        var payload = buildJsonObject {
            put("id", UUID.randomUUID().toString())
            put("live_id", session.stream.id)
            put("user_id", currentUserId)
            put("user_name", userName)
            if (!avatar.isNullOrBlank()) put("avatar_url", avatar)
            put("content", text.take(300))
        }

        return@withContext try {
            supabaseConfig.client.postgrest["live_comments"].insert(payload)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Error sending live comment", e)
            Result.failure(Exception("No se pudo enviar el comentario."))
        }
    }

    fun sendReaction(emoji: String = "❤️") {
        var session = _activeSession.value ?: return
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        if (currentUserId.isBlank()) return

        var payload = org.json.JSONObject().apply {
            put("liveId", session.stream.id)
            put("userId", currentUserId)
            put("emoji", emoji)
        }
        runCatching { socket?.emit("send-reaction", payload) }
    }

    suspend fun endLive(): Result<Unit> = withContext(Dispatchers.IO) {
        var session = _activeSession.value ?: return@withContext Result.success(Unit)
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()

        // Best-effort DB update (host only)
        if (session.role == LiveRole.HOST && currentUserId.isNotBlank()) {
            var endedAt = Instant.now().toString()
            val peak = maxOf(peakViewerCount, _viewerCount.value).coerceAtLeast(0)
            val totalComments = sessionCommentCount.coerceAtLeast(0)
            val totalReactions = sessionReactionCount.coerceAtLeast(0)
            runCatching {
                try {
                    supabaseConfig.client.postgrest["live_streams"].update({
                        set("is_active", false)
                        set("ended_at", endedAt)
                        set("peak_viewer_count", peak)
                        set("total_comments", totalComments)
                        set("total_reactions", totalReactions)
                    }) {
                        filter { eq("id", session.stream.id) }
                        filter { eq("host_user_id", currentUserId) }
                    }
                } catch (e: Exception) {
                    // Backwards compatibility: if stats columns don't exist yet, just end the live.
                    supabaseConfig.client.postgrest["live_streams"].update({
                        set("is_active", false)
                    }) {
                        filter { eq("id", session.stream.id) }
                        filter { eq("host_user_id", currentUserId) }
                    }
                }
            }
            runCatching {
                socket?.emit(
                    "end-live",
                    org.json.JSONObject().apply {
                        put("liveId", session.stream.id)
                        put("userId", currentUserId)
                    }
                )
            }
        }

        disconnectAndClear()
        refreshActiveStreams()
        Result.success(Unit)
    }

    private fun connectSocket(liveId: String, role: LiveRole, userId: String, name: String, avatarUrl: String?) {
        try {
            socket?.off()
            socket?.disconnect()
            socket = null
            if (userId.isBlank()) return

            if (webRTCManager == null) {
                initializeWebRTC(userId = userId)
            }
            webRTCManager?.setRemoteAudioPlaybackEnabled(role == LiveRole.VIEWER)
            webRTCManager?.setAudioEnabled(role == LiveRole.HOST && _isMicEnabled.value)
            webRTCManager?.setVideoSendEnabled(
                enabled = role == LiveRole.HOST && !_isPaused.value && _isVideoEnabled.value,
                renegotiate = false
            )

            val options = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionAttempts = SOCKET_RECONNECT_ATTEMPTS
                reconnectionDelay = SOCKET_RECONNECT_DELAY_MS
                reconnectionDelayMax = SOCKET_RECONNECT_DELAY_MAX_MS
                timeout = 10_000
                attachBearerToken(authService.getAccessToken())
            }
            socket = IO.socket(signalingUrl, options)

            socket?.on(Socket.EVENT_CONNECT) {
                var sid = socket?.id() ?: ""
                webRTCManager?.setCurrentUser(userId, sid)
                webRTCManager?.refreshManagedIceServers(force = true)

                socket?.emit(
                    "join-live",
                    org.json.JSONObject().apply {
                        put("liveId", liveId.trim())
                        put("userId", userId)
                        put("name", name)
                        put("avatarUrl", avatarUrl ?: "")
                        put("role", if (role == LiveRole.HOST) "host" else "viewer")
                        put("source", _currentSource.value.name.lowercase())
                        put("isPaused", _isPaused.value)
                        put("videoEnabled", _isVideoEnabled.value)
                        put("micEnabled", _isMicEnabled.value)
                    }
                )
            }

            // Host gets viewer list + updates
            socket?.on("current-viewers") { args ->
                if (role != LiveRole.HOST) return@on
                val arr = args[0] as org.json.JSONArray
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    var sid = v.getString("socketId")
                    var uid = v.getString("userId")
                    webRTCManager?.onUserJoined(sid, uid)
                }
            }

            socket?.on("viewer-joined") { args ->
                if (role != LiveRole.HOST) return@on
                var data = args[0] as org.json.JSONObject
                var sid = data.getString("socketId")
                var uid = data.getString("userId")
                webRTCManager?.onUserJoined(sid, uid)
            }

            socket?.on("viewer-left") { args ->
                if (role != LiveRole.HOST) return@on
                var sid = args[0] as String
                webRTCManager?.onUserLeft(sid)
            }

            // Viewers get host info
            socket?.on("host-info") { args ->
                if (role != LiveRole.VIEWER) return@on
                var data = args[0] as org.json.JSONObject
                var sid = data.getString("socketId")
                var uid = data.getString("userId")
                webRTCManager?.onUserJoined(sid, uid)
            }

            socket?.on("viewer-count") { args ->
                var data = args[0] as org.json.JSONObject
                var count = data.optInt("viewerCount", 0).coerceAtLeast(0)
                _viewerCount.value = count
                _activeSession.update { s -> s?.copy(viewerCount = count) }
                if (count > peakViewerCount) peakViewerCount = count
            }

            socket?.on("live-state") { args ->
                runCatching {
                    var data = args[0] as org.json.JSONObject
                    var source = data.optString("source", _currentSource.value.name.lowercase()).toLiveSource()
                    _currentSource.value = source
                    _isPaused.value = data.optBoolean("isPaused", false)
                    _isVideoEnabled.value = data.optBoolean("videoEnabled", true)
                    _isMicEnabled.value = data.optBoolean("micEnabled", _isMicEnabled.value)
                }
            }

            socket?.on("live-ended") {
                disconnectAndClear()
            }

            socket?.on("reaction") { args ->
                runCatching {
                    var data = args[0] as org.json.JSONObject
                    var emoji = data.optString("emoji", "❤️").ifBlank { "❤️" }
                    val fromUserId = data.optString("userId", "").trim()
                    val reaction = LiveReaction(emoji = emoji, userId = fromUserId)
                    _reactions.update { (it + listOf(reaction)).takeLast(40) }
                    scheduleReactionExpiry(reaction)
                    sessionReactionCount += 1
                }
            }

            // WebRTC signaling
            socket?.on("offer") { args ->
                var data = args[0] as org.json.JSONObject
                val senderSocketId = data.getString("sender")
                var senderUserId = if (data.has("userId")) data.getString("userId") else ""
                val offerSdp = data.getJSONObject("offer").getString("sdp")
                webRTCManager?.onOfferReceived(senderSocketId, senderUserId, offerSdp)
            }

            socket?.on("answer") { args ->
                var data = args[0] as org.json.JSONObject
                var sender = data.getString("sender")
                var senderUserId = if (data.has("userId")) data.getString("userId") else ""
                val answerSdp = data.getJSONObject("answer").getString("sdp")
                webRTCManager?.onAnswerReceived(sender, answerSdp, senderUserId)
            }

            socket?.on("ice-candidate") { args ->
                var data = args[0] as org.json.JSONObject
                var sender = data.getString("sender")
                var senderUserId = if (data.has("userId")) data.getString("userId") else ""
                val candidate = data.getJSONObject("candidate")
                webRTCManager?.onIceCandidateReceived(sender, candidate, senderUserId)
            }

            socket?.connect()
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Failed to setup live socket", e)
        }
    }

    private fun initializeWebRTC(userId: String): Boolean {
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

        return try {
            var manager = webRTCManager
            if (manager == null) {
                webRTCManager = WebRTCManager(
                    context = context,
                    signalingEvents = events,
                    accessTokenProvider = { authService.getAccessToken() }
                ).also { created ->
                    created.setCurrentUser(userId, "")
                    created.refreshManagedIceServers()
                    _isMediaReady.value = true
                    _eglBaseContext.value = created.getEglBaseContext()
                    scope.launch {
                        created.remoteVideoTracks.collect { tracks ->
                            _remoteVideoTracks.value = tracks
                        }
                    }
                    scope.launch {
                        created.localVideoTrack.collect { track ->
                            _localVideoTrack.value = track
                        }
                    }
                }
            } else {
                manager.hardReset()
                manager.setCurrentUser(userId, "")
                manager.refreshManagedIceServers()
                _isMediaReady.value = true
                _eglBaseContext.value = manager.getEglBaseContext()
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "initializeWebRTC failed", e)
            _isMediaReady.value = false
            _eglBaseContext.value = null
            _localVideoTrack.value = null
            _remoteVideoTracks.value = emptyMap()
            false
        }
    }

    private fun resolveLiveStartupError(error: Throwable, fallback: String): String {
        val message = error.message?.trim().orEmpty()
        var lowered = message.lowercase()
        return when {
            lowered.contains("camera") || lowered.contains("capture") -> {
                "No se pudo iniciar la cámara del live. Revisa permisos y vuelve a intentar."
            }
            lowered.contains("egl") || lowered.contains("opengl") || lowered.contains("webrtc") -> {
                "No se pudo preparar el motor de video del live en este dispositivo."
            }
            lowered.contains("foreground") || lowered.contains("service") -> {
                "No se pudo arrancar el servicio del live."
            }
            lowered.contains("projection") || lowered.contains("screen") -> {
                "No se pudo iniciar la compartición de pantalla."
            }
            message.isNotBlank() -> message
            else -> fallback
        }
    }

    private suspend fun subscribeToComments(liveId: String) {
        commentsJob?.cancel()
        commentsJob = null

        try {
            commentsChannel?.unsubscribe()
        } catch (_: Exception) { }
        commentsChannel = null
        _comments.value = emptyList()

        // Initial fetch
        runCatching {
            val history = supabaseConfig.client.postgrest["live_comments"].select {
                filter { eq("live_id", liveId) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                limit(80)
            }.decodeList<LiveComment>()
            val ordered = history.reversed()
            _comments.value = ordered
        }

        var channel = supabaseConfig.client.channel("live_comments_$liveId")
        commentsChannel = channel

        commentsJob = scope.launch {
            try {
                val flow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    this.table = "live_comments"
                    this.filter = "live_id=eq.$liveId"
                }
                supabaseConfig.subscribeRealtimeChannel(
                    channel = channel,
                    reason = "live-comments-$liveId"
                )

                flow.collect { change ->
                    when (change) {
                        is PostgresAction.Insert -> {
                            runCatching {
                                val comment = change.decodeRecord<LiveComment>()
                                _comments.update { (it + listOf(comment)).takeLast(200) }
                                sessionCommentCount += 1
                            }
                        }
                        is PostgresAction.Delete -> {
                            var id = change.oldRecord["id"]?.jsonPrimitive?.contentOrNull
                            if (!id.isNullOrBlank()) {
                                _comments.update { list -> list.filterNot { it.id == id } }
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LiveRepository", "Live comments subscription failed", e)
            }
        }
    }

    private fun disconnectAndClear() {
        stopService()
        try {
            socket?.off()
            socket?.disconnect()
        } catch (_: Exception) { }
        socket = null

        runCatching { webRTCManager?.hardReset() }

        commentsJob?.cancel()
        commentsJob = null
        stopAdaptiveQualityMonitor()
        reactionExpiryJobs.values.forEach { it.cancel() }
        reactionExpiryJobs.clear()

        val channelToClose = commentsChannel
        commentsChannel = null
        if (channelToClose != null) {
            scope.launch {
                try {
                    channelToClose.unsubscribe()
                } catch (_: Exception) { }
            }
        }

        _activeSession.value = null
        _comments.value = emptyList()
        _reactions.value = emptyList()
        _viewerCount.value = 0
        _isMediaReady.value = false
        _isPaused.value = false
        _isVideoEnabled.value = true
        _currentSource.value = LiveSource.CAMERA
        _eglBaseContext.value = null
        _localVideoTrack.value = null
        _remoteVideoTracks.value = emptyMap()
        lastScreenShareIntent = null
    }

    private fun scheduleReactionExpiry(reaction: LiveReaction) {
        var id = reaction.id.trim()
        if (id.isBlank()) return
        if (reactionExpiryJobs.containsKey(id)) return

        // Short TTL to keep the overlay lightweight
        val ttlMs = 2_800L
        reactionExpiryJobs[id] = scope.launch {
            delay(ttlMs)
            _reactions.update { it.filterNot { r -> r.id == id } }
            reactionExpiryJobs.remove(id)?.cancel()
        }
    }

    private fun startAdaptiveQualityMonitor() {
        adaptiveQualityJob?.cancel()
        lastAdaptiveQualityChangeAtMs = 0L
        adaptiveQualityJob = scope.launch {
            applyAdaptiveQuality(force = true)
            while (_activeSession.value?.role == LiveRole.HOST) {
                delay(15_000L)
                applyAdaptiveQuality(force = false)
            }
        }
    }

    private fun stopAdaptiveQualityMonitor() {
        adaptiveQualityJob?.cancel()
        adaptiveQualityJob = null
        lastAdaptiveQualityChangeAtMs = 0L
    }

    private fun applyAdaptiveQuality(force: Boolean) {
        var session = _activeSession.value ?: return
        if (session.role != LiveRole.HOST || _isPaused.value || !_isVideoEnabled.value) return

        if (_currentSource.value == LiveSource.SCREEN) {
            // Screen capture tokens from MediaProjection are single-use for virtual display creation.
            // Rebuilding the capturer here would try to reuse a consumed token and break screen share.
            return
        }

        var recommended = getAdaptiveQualityRecommendation() ?: return
        val current = _quality.value
        if (recommended == current) return

        val now = System.currentTimeMillis()
        val minIntervalMs = if (recommended.width < current.width) 15_000L else 45_000L
        if (!force && now - lastAdaptiveQualityChangeAtMs < minIntervalMs) return

        _quality.value = recommended
        lastAdaptiveQualityChangeAtMs = now
        android.util.Log.i(
            "LiveRepository",
            "Adaptive live quality changed to ${recommended.label} for live ${session.stream.id}"
        )

        startCamera()
    }

    private fun getAdaptiveQualityRecommendation(): LiveQuality? {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null

        val upstreamKbps = capabilities.linkUpstreamBandwidthKbps
        val downstreamKbps = capabilities.linkDownstreamBandwidthKbps
        val fastTransport =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val canUse1080 =
            upstreamKbps >= 6_000 &&
                downstreamKbps >= 12_000 &&
                (fastTransport || notMetered)

        return if (canUse1080) LiveQuality.P1080 else LiveQuality.P720
    }

    private fun startService(stream: LiveStream, role: LiveRole) {
        try {
            var type = computeForegroundType(role = role, source = stream.source)
            var intent = Intent(context, LiveForegroundService::class.java).apply {
                action = "START"
                putExtra(LiveForegroundService.EXTRA_LIVE_ID, stream.id)
                putExtra(LiveForegroundService.EXTRA_LIVE_TITLE, stream.title.ifBlank { stream.hostName.ifBlank { "Live" } })
                putExtra(LiveForegroundService.EXTRA_LIVE_ROLE, role.name.lowercase())
                putExtra(LiveForegroundService.EXTRA_TYPE, type)
            }
            context.startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Failed to start LiveForegroundService", e)
        }
    }

    private fun refreshForegroundServiceType() {
        var session = _activeSession.value ?: return
        if (session.role != LiveRole.HOST) return

        val updatedStream = session.stream.copy(source = _currentSource.value.name.lowercase())
        _activeSession.value = session.copy(stream = updatedStream)
        startService(stream = updatedStream, role = session.role)
    }

    private fun stopService() {
        try {
            context.stopService(Intent(context, LiveForegroundService::class.java))
        } catch (_: Exception) { }
    }

    private fun computeForegroundType(role: LiveRole, source: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        if (role != LiveRole.HOST) return 0

        var type = 0
        if (_isMicEnabled.value) {
            type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (_isVideoEnabled.value) {
            if (source.trim().lowercase() == "screen") {
                if (lastScreenShareIntent != null) {
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            } else {
                type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        return type
    }

    private fun startConfiguredVideo(projectionIntent: Intent? = null): Boolean {
        return when (_currentSource.value) {
            LiveSource.CAMERA -> startCamera()
            LiveSource.SCREEN -> {
                var intentToUse = projectionIntent ?: lastScreenShareIntent
                if (intentToUse == null) {
                    false
                } else {
                    startScreenShare(intentToUse)
                }
            }
        }
    }

    private fun emitLiveState() {
        var session = _activeSession.value ?: return
        if (session.role != LiveRole.HOST) return

        var payload = org.json.JSONObject().apply {
            put("liveId", session.stream.id)
            put("source", _currentSource.value.name.lowercase())
            put("isPaused", _isPaused.value)
            put("videoEnabled", _isVideoEnabled.value)
            put("micEnabled", _isMicEnabled.value)
        }
        runCatching { socket?.emit("update-live-state", payload) }
    }

    private fun String.toLiveSource(): LiveSource {
        return if (trim().equals("screen", ignoreCase = true)) LiveSource.SCREEN else LiveSource.CAMERA
    }
}





