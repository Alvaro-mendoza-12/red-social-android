package conectate.com.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import conectate.com.core.concurrency.nextRetryDelayMs
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.model.ActiveLiveSession
import conectate.com.data.model.LiveComment
import conectate.com.data.model.LiveCollaborationCapabilities
import conectate.com.data.model.LiveQuality
import conectate.com.data.model.LiveReaction
import conectate.com.data.model.LiveModerationState
import conectate.com.data.model.LiveRole
import conectate.com.data.model.LiveSource
import conectate.com.data.model.LiveStream
import conectate.com.data.model.LiveViewerParticipant
import conectate.com.data.model.LiveGiftEvent
import conectate.com.data.model.GiftReceipt
import conectate.com.data.model.VirtualGift
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.local.prefs.LivePreferences
import conectate.com.data.signaling.SignalingConfig
import conectate.com.data.signaling.attachBearerToken
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.supabase.StorageService
import conectate.com.data.webrtc.LiveCameraFilter
import conectate.com.data.webrtc.LiveFilterPerformanceState
import conectate.com.data.webrtc.WebRTCManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.*
import io.socket.client.IO
import io.socket.client.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.VideoTrack
import org.webrtc.EglBase
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import conectate.com.services.LiveForegroundService
import java.time.Instant
import java.net.HttpURLConnection
import java.net.URL
import conectate.com.util.VideoUtils

@Singleton
class LiveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val userRepository: UserRepository,
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val storageService: StorageService,
    private val livePreferences: LivePreferences
) {
    private companion object {
        private const val SOCKET_RECONNECT_ATTEMPTS = 10
        private const val SOCKET_RECONNECT_DELAY_MS = 1_000L
        private const val SOCKET_RECONNECT_DELAY_MAX_MS = 12_000L
        private const val SUBSCRIPTION_RETRY_INITIAL_MS = 2_000L
        private const val SUBSCRIPTION_RETRY_MAX_MS = 30_000L
        private const val SUBSCRIPTION_RESTART_GRACE_MS = 1_000L
        private const val LOCAL_LIVE_REFRESH_MS = 5_000L
        private const val LOCAL_COMMENT_REFRESH_MS = 1_500L
        private const val REACTION_BATCH_WINDOW_MS = 500L
        private const val REACTION_BATCH_MAX = 10
        private const val MAX_ACTIVE_REACTION_PARTICLES = 12
        private const val SOURCE_PERSIST_TIMEOUT_MS = 4_000L
        private const val LIVE_END_CONFIRMATION_TIMEOUT_MS = 3_000L
    }

    private val scope = newSafeScope("LiveRepository", Dispatchers.IO)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val livePresenceUrl: String
        get() = "${SignalingConfig.BASE_URL}/live-active-ids"

    private val signalingUrl: String
        get() = SignalingConfig.LIVE_URL
    @Volatile
    private var socket: Socket? = null
    private var webRTCManager: WebRTCManager? = null
    private var commentsChannel: RealtimeChannel? = null
    private var commentsJob: Job? = null
    @Volatile
    private var adaptiveQualityJob: Job? = null
    private var socketEndpointRecoveryJob: Job? = null
    private var mediaConnectionTimeoutJob: Job? = null
    @Volatile
    private var mediaTransitionJob: Job? = null
    @Volatile
    private var projectionStopJob: Job? = null
    @Volatile
    private var projectionStopToken: String? = null
    private val projectionStopJobLock = Any()
    private val projectionState = LiveProjectionStateCoordinator()
    private val sourcePersistenceMutex = Mutex()
    private val sessionStartupMutex = Mutex()
    private val sessionEndMutex = Mutex()
    private val closeCoordinator = LiveSessionCloseCoordinator()
    private val mediaTransitionGate = LiveMediaTransitionGate()
    private val reactionExpiryJobs = ConcurrentHashMap<String, Job>()
    private val reactionBatchLock = Any()
    private var reactionBatchJob: Job? = null
    private var pendingReactionCount = 0
    private var pendingReactionLiveId = ""
    private var pendingReactionUserId = ""
    private val localReactionBatches = LinkedHashMap<String, Int>()
    private val liveEndConfirmationLock = Any()
    private var pendingLiveEndConfirmation: Pair<String, CompletableDeferred<Boolean>>? = null
    private val giftExpiryJobs = ConcurrentHashMap<String, Job>()
    private val knownViewerSocketIds = ConcurrentHashMap.newKeySet<String>()
    private var lastAdaptiveQualityChangeAtMs: Long = 0L
    private var hostSessionStartedElapsedMs: Long = 0L
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

    private val _liveReplays = MutableStateFlow<List<LiveStream>>(emptyList())
    val liveReplays: StateFlow<List<LiveStream>> = _liveReplays.asStateFlow()

    private val _myLiveHistory = MutableStateFlow<List<LiveStream>>(emptyList())
    val myLiveHistory: StateFlow<List<LiveStream>> = _myLiveHistory.asStateFlow()

    private val _replayUploadProgress = MutableStateFlow(0f)
    val replayUploadProgress: StateFlow<Float> = _replayUploadProgress.asStateFlow()

    private val _activeSession = MutableStateFlow<ActiveLiveSession?>(null)
    val activeSession: StateFlow<ActiveLiveSession?> = _activeSession.asStateFlow()

    private val _comments = MutableStateFlow<List<LiveComment>>(emptyList())
    val comments: StateFlow<List<LiveComment>> = _comments.asStateFlow()

    private val _reactions = MutableStateFlow<List<LiveReaction>>(emptyList())
    val reactions: StateFlow<List<LiveReaction>> = _reactions.asStateFlow()

    private val _reactionCount = MutableStateFlow(0L)
    val reactionCount: StateFlow<Long> = _reactionCount.asStateFlow()

    private val _giftEvents = MutableStateFlow<List<LiveGiftEvent>>(emptyList())
    val giftEvents: StateFlow<List<LiveGiftEvent>> = _giftEvents.asStateFlow()

    private val _viewerCount = MutableStateFlow(0)
    val viewerCount: StateFlow<Int> = _viewerCount.asStateFlow()

    private val _liveViewers = MutableStateFlow<List<LiveViewerParticipant>>(emptyList())
    val liveViewers: StateFlow<List<LiveViewerParticipant>> = _liveViewers.asStateFlow()

    private val _moderationState = MutableStateFlow(LiveModerationState())
    val moderationState: StateFlow<LiveModerationState> = _moderationState.asStateFlow()

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

    private val _cameraFilter = MutableStateFlow(livePreferences.cameraFilter)
    val cameraFilter: StateFlow<LiveCameraFilter> = _cameraFilter.asStateFlow()

    private val _cameraFilterPerformance = MutableStateFlow(
        _cameraFilter.value.toDefaultPerformanceState()
    )
    val cameraFilterPerformance: StateFlow<LiveFilterPerformanceState> =
        _cameraFilterPerformance.asStateFlow()

    private val _isMediaTransitioning = MutableStateFlow(false)
    val isMediaTransitioning: StateFlow<Boolean> = _isMediaTransitioning.asStateFlow()

    private val _quality = MutableStateFlow(LiveQuality.P720)
    val quality: StateFlow<LiveQuality> = _quality.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    private val _eglBaseContext = MutableStateFlow<EglBase.Context?>(null)
    val eglBaseContext: StateFlow<EglBase.Context?> = _eglBaseContext.asStateFlow()

    private val _runtimeErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val runtimeErrors: SharedFlow<String> = _runtimeErrors.asSharedFlow()

    @Volatile
    private var screenCaptureActive = false

    init {
        scope.launch {
            if (usesUbuntuPersistence()) {
                while (currentCoroutineContext().isActive) {
                    refreshActiveStreams()
                    delay(LOCAL_LIVE_REFRESH_MS)
                }
            } else {
                refreshActiveStreams()
                subscribeToLiveStreams()
            }
        }
    }

    private fun usesUbuntuPersistence(): Boolean =
        supabaseConfig.isLocalFallbackPrimary || authService.isLocalFallbackModeEnabled()

    private fun localAccessToken(): String = authService.getAccessToken()?.trim().orEmpty()


    suspend fun refreshActiveStreams() = withContext(Dispatchers.IO) {
        if (usesUbuntuPersistence()) {
            val token = localAccessToken()
            if (token.isBlank()) {
                _activeStreams.value = emptyList()
                return@withContext
            }
            localFallbackApiClient.loadLiveStreams(token, limit = 60)
                .onSuccess { streams ->
                    _activeStreams.value = filterToServerActiveStreams(normalizeActiveStreams(streams))
                }
                .onFailure { error ->
                    android.util.Log.e("LiveRepository", "Error refreshing Ubuntu live streams", error)
                }
            return@withContext
        }
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
            if (supabaseConfig.reportServiceRestrictionIfPresent("live-streams-refresh", e)) {
                android.util.Log.w("LiveRepository", "Live streams unavailable because the Supabase project is restricted.")
            } else {
                android.util.Log.e("LiveRepository", "Error refreshing live streams", e)
            }
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
        val normalizedId = normalizeLiveSessionId(id)
            ?: return@withContext Result.failure(Exception("El identificador del live no es válido"))
        if (usesUbuntuPersistence()) {
            val token = localAccessToken()
            if (token.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
            return@withContext localFallbackApiClient.loadLiveStream(token, normalizedId)
        }
        try {
            var stream = supabaseConfig.client.postgrest["live_streams"].select {
                filter { eq("id", normalizedId) }
            }.decodeSingle<LiveStream>()
            Result.success(stream)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLiveCollaborationCapabilities(): Result<LiveCollaborationCapabilities> =
        withContext(Dispatchers.IO) {
            if (!usesUbuntuPersistence()) {
                return@withContext Result.success(LiveCollaborationCapabilities())
            }
            val token = localAccessToken()
            if (token.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
            localFallbackApiClient.loadLiveCollaborationCapabilities(token)
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

            if (usesUbuntuPersistence()) {
                val token = localAccessToken()
                if (token.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
                return@withContext localFallbackApiClient.createLiveStream(
                    accessToken = token,
                    id = streamId,
                    title = title.trim().take(80),
                    source = source.name.lowercase(),
                    hashtags = normalizedHashtags
                ).onSuccess {
                    refreshActiveStreams()
                }
            }

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
        sessionStartupMutex.withLock {
        try {
            val normalizedStreamId = normalizeLiveSessionId(streamId)
                ?: return@withContext Result.failure(Exception("El identificador del live no es válido"))
            var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
            if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
            val existingSession = _activeSession.value
            if (existingSession != null) {
                return@withContext if (
                    existingSession.role == LiveRole.HOST &&
                    existingSession.stream.id.equals(normalizedStreamId, ignoreCase = true)
                ) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Ya hay un live activo"))
                }
            }

            if (!closeCoordinator.resetForNewSession()) {
                return@withContext Result.failure(Exception("El live anterior todavía se está cerrando"))
            }

            var stream = getLiveStreamById(normalizedStreamId).getOrNull()
                ?: return@withContext Result.failure(Exception("Live no encontrado"))
            if (!stream.hostUserId.equals(currentUserId, ignoreCase = true)) {
                return@withContext Result.failure(Exception("No eres el anfitrión de este live"))
            }

            _currentSource.value = stream.source.toLiveSource()
            _isPaused.value = false
            _isVideoEnabled.value = true
            screenCaptureActive = false
            validateHostCapturePermissions()?.let { message ->
                return@withContext Result.failure(SecurityException(message))
            }

            _viewerCount.value = stream.viewerCount
            _reactionCount.value = stream.totalReactions.coerceAtLeast(0)
            peakViewerCount = stream.viewerCount.coerceAtLeast(0)
            sessionCommentCount = 0
            sessionReactionCount = stream.totalReactions.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            _isMediaReady.value = false

            if (!initializeWebRTC(userId = currentUserId)) {
                disconnectAndClear()
                return@withContext Result.failure(Exception("No se pudo preparar la transmisión en este dispositivo."))
            }

            webRTCManager?.ensureLocalTracks()
            webRTCManager?.setAudioEnabled(_isMicEnabled.value)
            _activeSession.value = ActiveLiveSession(stream = stream, role = LiveRole.HOST, viewerCount = stream.viewerCount)
            hostSessionStartedElapsedMs = SystemClock.elapsedRealtime()
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
        } catch (cancelled: CancellationException) {
            disconnectAndClear()
            throw cancelled
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Host live failed", e)
            disconnectAndClear()
            Result.failure(Exception(resolveLiveStartupError(e, fallback = "No se pudo iniciar el live.")))
        }
        }
    }



    suspend fun joinLiveAsViewer(streamId: String): Result<Unit> = withContext(Dispatchers.IO) {
        sessionStartupMutex.withLock {
        try {
            val normalizedStreamId = normalizeLiveSessionId(streamId)
                ?: return@withContext Result.failure(Exception("El identificador del live no es válido"))
            var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
            if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
            val existingSession = _activeSession.value
            if (existingSession != null) {
                return@withContext if (
                    existingSession.role == LiveRole.VIEWER &&
                    existingSession.stream.id.equals(normalizedStreamId, ignoreCase = true)
                ) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Ya hay un live activo"))
                }
            }

            if (!closeCoordinator.resetForNewSession()) {
                return@withContext Result.failure(Exception("El live anterior todavía se está cerrando"))
            }

            var stream = getLiveStreamById(normalizedStreamId).getOrNull()
                ?: return@withContext Result.failure(Exception("Live no encontrado"))

            _viewerCount.value = stream.viewerCount
            _reactionCount.value = stream.totalReactions.coerceAtLeast(0)
            peakViewerCount = stream.viewerCount.coerceAtLeast(0)
            sessionCommentCount = 0
            sessionReactionCount = stream.totalReactions.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            _isMediaReady.value = false

            if (!initializeWebRTC(userId = currentUserId)) {
                disconnectAndClear()
                return@withContext Result.failure(Exception("No se pudo abrir el motor del live en este dispositivo."))
            }

            webRTCManager?.setAudioEnabled(false)
            webRTCManager?.setVideoSendEnabled(false, renegotiate = false)
            _activeSession.value = ActiveLiveSession(stream = stream, role = LiveRole.VIEWER, viewerCount = stream.viewerCount)

            _isMicEnabled.value = false
            _isPaused.value = false
            _isVideoEnabled.value = true
            _currentSource.value = stream.source.toLiveSource()
            screenCaptureActive = false
            stopAdaptiveQualityMonitor()
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
        } catch (cancelled: CancellationException) {
            disconnectAndClear()
            throw cancelled
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Join live as viewer failed", e)
            disconnectAndClear()
            Result.failure(Exception(resolveLiveStartupError(e, fallback = "No se pudo abrir el live.")))
        }
        }
    }

    fun toggleMic(enabled: Boolean) {
        val session = _activeSession.value
        val effectiveEnabled = when {
            session?.role == LiveRole.VIEWER -> false
            session?.role == LiveRole.HOST && enabled && !hasPermission(android.Manifest.permission.RECORD_AUDIO) -> {
                _runtimeErrors.tryEmit("Concede permiso al micrófono para activarlo en el live.")
                false
            }
            else -> enabled
        }
        _isMicEnabled.value = effectiveEnabled
        webRTCManager?.setAudioEnabled(effectiveEnabled)
        if (session?.role == LiveRole.HOST) {
            refreshForegroundServiceType()
        }
        emitLiveState()
    }

    fun setQuality(value: LiveQuality) {
        _quality.value = value
    }

    fun setCameraFilter(filter: LiveCameraFilter) {
        _cameraFilter.value = filter
        livePreferences.setCameraFilter(filter)
        updateCameraFilterPerformanceForSource()
        webRTCManager?.setCameraFilter(filter)
    }

    fun startCamera(micEnabledOverride: Boolean? = null): Boolean {
        val session = _activeSession.value ?: return false
        if (session.role != LiveRole.HOST) return false
        if (!hasPermission(android.Manifest.permission.CAMERA)) {
            _runtimeErrors.tryEmit("Concede permiso a la cámara para transmitir video.")
            return false
        }

        val manager = webRTCManager ?: return false
        if (!mediaTransitionGate.tryStart()) {
            _runtimeErrors.tryEmit("Espera a que termine el cambio de fuente actual.")
            return false
        }

        _isMediaTransitioning.value = true
        val sessionId = session.stream.id
        val quality = _quality.value
        val previousSource = _currentSource.value
        val previousVideoEnabled = _isVideoEnabled.value
        val previousProjectionToken = projectionState.activeToken()
        projectionState.suppress(previousProjectionToken)
        refreshForegroundServiceType(
            sourceOverride = LiveSource.CAMERA,
            updateSession = false,
            videoEnabledOverride = true,
            additionalSourceOverride = previousSource.takeIf { previousVideoEnabled }
        )
        mediaTransitionJob = scope.launch {
            var sourceCommitted = false
            try {
                manager.ensureLocalTracks()
                manager.setVideoSendEnabled(!_isPaused.value, renegotiate = false)
                val started = manager.startVideoAwait(
                    isScreen = false,
                    intent = null,
                    isFront = true,
                    targetWidth = quality.width,
                    targetHeight = quality.height,
                    targetFps = 24
                )
                val requestStillCurrent =
                    _activeSession.value?.role == LiveRole.HOST &&
                        _activeSession.value?.stream?.id.equals(sessionId, ignoreCase = true)
                if (started && requestStillCurrent) {
                    sourceCommitted = true
                    projectionState.clearActive()
                    screenCaptureActive = false
                    _currentSource.value = LiveSource.CAMERA
                    updateCameraFilterPerformanceForSource()
                    _isVideoEnabled.value = true
                    micEnabledOverride?.let { enabled ->
                        _isMicEnabled.value = enabled
                        manager.setAudioEnabled(enabled)
                    }
                    updateSessionSource(LiveSource.CAMERA)
                    refreshForegroundServiceType()
                    manager.setVideoSendEnabled(!_isPaused.value, renegotiate = false)
                    emitLiveState()
                    persistLiveSourceAsync(sessionId, LiveSource.CAMERA)
                } else if (requestStillCurrent) {
                    refreshForegroundServiceType()
                    _runtimeErrors.emit("No se pudo iniciar la cámara del live. Se mantuvo la fuente anterior.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("LiveRepository", "Unable to start live camera", error)
                refreshForegroundServiceType()
                _runtimeErrors.emit(
                    if (sourceCommitted) {
                        "La cámara inició, pero parte del estado remoto no pudo sincronizarse."
                    } else {
                        "No se pudo iniciar la cámara del live. Se mantuvo la fuente anterior."
                    }
                )
            } finally {
                _isMediaTransitioning.value = false
                mediaTransitionGate.finish()
                projectionState.clearSuppressed(previousProjectionToken)
                if (mediaTransitionJob === currentCoroutineContext()[Job]) {
                    mediaTransitionJob = null
                }
            }
        }
        return true
    }

    fun startScreenShare(intent: Intent, micEnabledOverride: Boolean? = null): Boolean {
        val session = _activeSession.value ?: return false
        if (session.role != LiveRole.HOST) return false

        val q = _quality.value
        val manager = webRTCManager ?: return false
        val metrics = context.resources.displayMetrics
        val isPortrait = metrics.heightPixels >= metrics.widthPixels
        val boundW = if (isPortrait) q.height else q.width
        val boundH = if (isPortrait) q.width else q.height
        if (!mediaTransitionGate.tryStart()) {
            _runtimeErrors.tryEmit("Espera a que termine el cambio de fuente actual.")
            return false
        }

        val previousSource = _currentSource.value
        val previousVideoEnabled = _isVideoEnabled.value
        val previousProjectionToken = projectionState.activeToken()
        val projectionToken = UUID.randomUUID().toString()
        val sessionId = session.stream.id
        val oneShotProjectionIntent = Intent(intent)
        _isMediaTransitioning.value = true
        // Android 14+ requires the mediaProjection foreground type before
        // ScreenCapturerAndroid creates its virtual display. This is provisional:
        // the public source is committed only after WebRTC confirms capture.
        screenCaptureActive = true
        projectionState.beginPending(projectionToken, previousProjectionToken)
        refreshForegroundServiceType(
            sourceOverride = LiveSource.SCREEN,
            updateSession = false,
            videoEnabledOverride = true,
            additionalSourceOverride = previousSource.takeIf { previousVideoEnabled }
        )

        mediaTransitionJob = scope.launch {
            var sourceCommitted = false
            try {
                val started = startScreenCaptureWhenForegroundReady(
                    sessionId = sessionId,
                    manager = manager,
                    projectionIntent = oneShotProjectionIntent,
                    width = boundW,
                    height = boundH,
                    projectionToken = projectionToken
                )
                val requestStillCurrent =
                    _activeSession.value?.role == LiveRole.HOST &&
                        _activeSession.value?.stream?.id.equals(sessionId, ignoreCase = true)
                val pendingCommit = if (started && requestStillCurrent) {
                    projectionState.commitPending(projectionToken)
                } else {
                    PendingProjectionCommit.INVALIDATED
                }
                if (
                    started &&
                    requestStillCurrent &&
                    pendingCommit == PendingProjectionCommit.ACTIVATED
                ) {
                    sourceCommitted = true
                    screenCaptureActive = true
                    _currentSource.value = LiveSource.SCREEN
                    updateCameraFilterPerformanceForSource()
                    _isVideoEnabled.value = true
                    micEnabledOverride?.let { enabled ->
                        _isMicEnabled.value = enabled
                        manager.setAudioEnabled(enabled)
                    }
                    updateSessionSource(LiveSource.SCREEN)
                    refreshForegroundServiceType()
                    manager.setVideoSendEnabled(!_isPaused.value, renegotiate = false)
                    emitLiveState()
                    persistLiveSourceAsync(sessionId, LiveSource.SCREEN)
                } else if (
                    started &&
                    requestStillCurrent &&
                    pendingCommit != PendingProjectionCommit.ACTIVATED
                ) {
                    projectionState.clearActive()
                    screenCaptureActive = false
                    _currentSource.value = LiveSource.SCREEN
                    updateCameraFilterPerformanceForSource()
                    _isVideoEnabled.value = false
                    updateSessionSource(LiveSource.SCREEN)
                    manager.setVideoSendEnabled(false, renegotiate = false)
                    manager.stopVideoAwait()
                    refreshForegroundServiceType()
                    emitLiveState()
                    persistLiveSourceAsync(sessionId, LiveSource.SCREEN)
                    _runtimeErrors.emit("La compartición de pantalla fue detenida antes de comenzar.")
                } else if (requestStillCurrent) {
                    screenCaptureActive = previousSource == LiveSource.SCREEN && previousVideoEnabled
                    _currentSource.value = previousSource
                    updateCameraFilterPerformanceForSource()
                    _isVideoEnabled.value = previousVideoEnabled
                    refreshForegroundServiceType()
                    emitLiveState()
                    _runtimeErrors.emit("No se pudo compartir la pantalla. Se mantuvo la fuente anterior.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (sourceCommitted) {
                    screenCaptureActive = true
                    _currentSource.value = LiveSource.SCREEN
                    updateCameraFilterPerformanceForSource()
                    _isVideoEnabled.value = true
                    updateSessionSource(LiveSource.SCREEN)
                } else {
                    screenCaptureActive = previousSource == LiveSource.SCREEN && previousVideoEnabled
                    _currentSource.value = previousSource
                    updateCameraFilterPerformanceForSource()
                    _isVideoEnabled.value = previousVideoEnabled
                }
                refreshForegroundServiceType()
                emitLiveState()
                android.util.Log.e("LiveRepository", "Unable to start screen capture", error)
                _runtimeErrors.emit(
                    if (sourceCommitted) {
                        "La pantalla inició, pero parte del estado remoto no pudo sincronizarse."
                    } else {
                        "No se pudo compartir la pantalla. Se mantuvo la fuente anterior."
                    }
                )
            } finally {
                _isMediaTransitioning.value = false
                mediaTransitionGate.finish()
                projectionState.clearPending(projectionToken)
                projectionState.clearSuppressed(previousProjectionToken)
                if (mediaTransitionJob === currentCoroutineContext()[Job]) {
                    mediaTransitionJob = null
                }
            }
        }
        return true
    }

    fun stopVideo() {
        if (_activeSession.value?.role != LiveRole.HOST) return
        beginVideoStop(_currentSource.value)
    }

    private fun beginVideoStop(
        source: LiveSource,
        micEnabledOverride: Boolean? = null
    ): Boolean {
        val session = _activeSession.value ?: return false
        if (session.role != LiveRole.HOST) return false
        val manager = webRTCManager ?: return false
        if (!mediaTransitionGate.tryStart()) {
            _runtimeErrors.tryEmit("Espera a que termine el cambio de fuente actual.")
            return false
        }

        _isMediaTransitioning.value = true
        projectionState.clearActive()
        screenCaptureActive = false
        _currentSource.value = source
        _isVideoEnabled.value = false
        micEnabledOverride?.let { enabled ->
            _isMicEnabled.value = enabled
            manager.setAudioEnabled(enabled)
        }
        updateSessionSource(source)
        manager.setVideoSendEnabled(false, renegotiate = false)
        mediaTransitionJob = scope.launch {
            try {
                manager.stopVideoAwait()
                refreshForegroundServiceType()
                emitLiveState()
                persistLiveSourceAsync(session.stream.id, source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("LiveRepository", "Unable to stop live video cleanly", error)
                refreshForegroundServiceType()
                emitLiveState()
                _runtimeErrors.emit("El video se desactivó, pero la cámara tardó en liberarse.")
            } finally {
                _isMediaTransitioning.value = false
                mediaTransitionGate.finish()
                if (mediaTransitionJob === currentCoroutineContext()[Job]) {
                    mediaTransitionJob = null
                }
            }
        }
        return true
    }

    fun canReuseScreenShareIntent(): Boolean =
        screenCaptureActive &&
            _currentSource.value == LiveSource.SCREEN &&
            webRTCManager?.hasLocalVideoTrack() == true

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
        val session = _activeSession.value ?: return false
        if (session.role != LiveRole.HOST) return false
        if (mediaTransitionGate.isTransitioning()) {
            _runtimeErrors.tryEmit("Espera a que termine el cambio de fuente actual.")
            return false
        }
        val previousSource = _currentSource.value
        val manager = webRTCManager ?: return false

        if (micEnabled && !hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
            _runtimeErrors.tryEmit("Concede permiso al micrófono para activarlo en el live.")
            return false
        }
        if (videoEnabled && source == LiveSource.CAMERA && !hasPermission(android.Manifest.permission.CAMERA)) {
            _runtimeErrors.tryEmit("Concede permiso a la cámara para transmitir video.")
            return false
        }

        val canReuseCurrentTrack =
            previousSource == source &&
                manager.hasLocalVideoTrack() &&
                (source != LiveSource.SCREEN || screenCaptureActive)
        if (
            videoEnabled &&
            source == LiveSource.SCREEN &&
            !canReuseCurrentTrack &&
            projectionIntent == null
        ) {
            _runtimeErrors.tryEmit("Autoriza de nuevo la captura para compartir la pantalla.")
            return false
        }

        if (!videoEnabled) {
            return beginVideoStop(source, micEnabledOverride = micEnabled)
        }

        if (canReuseCurrentTrack) {
            if (!mediaTransitionGate.tryStart()) {
                _runtimeErrors.tryEmit("Espera a que termine el cambio de fuente actual.")
                return false
            }
            _isMediaTransitioning.value = true
            try {
                _currentSource.value = source
                _isVideoEnabled.value = true
                _isMicEnabled.value = micEnabled
                manager.setAudioEnabled(micEnabled)
                if (source != LiveSource.SCREEN) {
                    projectionState.clearActive()
                    screenCaptureActive = false
                }
                updateSessionSource(source)
                refreshForegroundServiceType()
                manager.setVideoSendEnabled(!_isPaused.value, renegotiate = false)
                emitLiveState()
                persistLiveSourceAsync(session.stream.id, source)
                return true
            } finally {
                _isMediaTransitioning.value = false
                mediaTransitionGate.finish()
            }
        }

        return when (source) {
            LiveSource.CAMERA -> startCamera(micEnabledOverride = micEnabled)
            LiveSource.SCREEN -> startScreenShare(
                intent = checkNotNull(projectionIntent),
                micEnabledOverride = micEnabled
            )
        }
    }



    suspend fun sendComment(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        var session = _activeSession.value ?: return@withContext Result.failure(Exception("No hay live activo"))
        var currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        if (currentUserId.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
        var text = content.trim()
        if (text.isBlank()) return@withContext Result.success(Unit)
        if (_moderationState.value.chatPaused && !_moderationState.value.canModerate) {
            return@withContext Result.failure(
                Exception("El chat está pausado temporalmente por el equipo de moderación.")
            )
        }

        if (usesUbuntuPersistence()) {
            val token = localAccessToken()
            if (token.isBlank()) return@withContext Result.failure(Exception("Sesión no válida"))
            return@withContext localFallbackApiClient.createLiveComment(
                accessToken = token,
                liveId = session.stream.id,
                content = text.take(300)
            ).map { comment ->
                _comments.update { current ->
                    if (current.any { it.id == comment.id }) current else (current + comment).takeLast(200)
                }
                sessionCommentCount += 1
            }
        }

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

    fun sendReaction() {
        val session = _activeSession.value ?: return
        val currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
        if (currentUserId.isBlank()) return

        // Respuesta local inmediata: el contador nunca espera el viaje de red.
        _reactionCount.update { current -> current.coerceAtLeast(0L) + 1L }
        val localReaction = LiveReaction(kind = "heart", userId = currentUserId)
        _reactions.update { current -> (current + localReaction).takeLast(MAX_ACTIVE_REACTION_PARTICLES) }
        scheduleReactionExpiry(localReaction)
        val flushNow = synchronized(reactionBatchLock) {
            if (pendingReactionLiveId.isNotBlank() && pendingReactionLiveId != session.stream.id) {
                pendingReactionCount = 0
            }
            pendingReactionLiveId = session.stream.id
            pendingReactionUserId = currentUserId
            pendingReactionCount += 1
            pendingReactionCount >= REACTION_BATCH_MAX
        }
        if (flushNow) {
            reactionBatchJob?.cancel()
            reactionBatchJob = scope.launch { flushPendingReactions() }
        } else if (reactionBatchJob?.isActive != true) {
            reactionBatchJob = scope.launch {
                delay(REACTION_BATCH_WINDOW_MS)
                flushPendingReactions()
            }
        }
    }

    private fun flushPendingReactions() {
        val activeSocket = socket?.takeIf { it.connected() }
        if (activeSocket == null) {
            synchronized(reactionBatchLock) {
                reactionBatchJob = null
                if (pendingReactionCount > 0) {
                    reactionBatchJob = scope.launch {
                        delay(REACTION_BATCH_WINDOW_MS)
                        flushPendingReactions()
                    }
                }
            }
            return
        }
        val batch = synchronized(reactionBatchLock) {
            val count = pendingReactionCount.coerceIn(0, REACTION_BATCH_MAX)
            if (count <= 0 || pendingReactionLiveId.isBlank() || pendingReactionUserId.isBlank()) return
            val value = Triple(pendingReactionLiveId, pendingReactionUserId, count)
            pendingReactionCount -= count
            if (pendingReactionCount == 0) {
                pendingReactionLiveId = ""
                pendingReactionUserId = ""
            }
            value
        }
        val clientBatchId = UUID.randomUUID().toString()
        synchronized(reactionBatchLock) {
            localReactionBatches[clientBatchId] = batch.third
            while (localReactionBatches.size > 100) {
                localReactionBatches.remove(localReactionBatches.keys.first())
            }
        }
        val payload = org.json.JSONObject().apply {
            put("liveId", batch.first)
            put("userId", batch.second)
            put("kind", "heart")
            put("count", batch.third)
            put("clientBatchId", clientBatchId)
        }
        val emitted = runCatching { activeSocket.emit("send-reaction-batch", payload) }.isSuccess
        synchronized(reactionBatchLock) {
            if (!emitted) {
                localReactionBatches.remove(clientBatchId)
                if (pendingReactionLiveId.isBlank() || pendingReactionLiveId == batch.first) {
                    pendingReactionLiveId = batch.first
                    pendingReactionUserId = batch.second
                    pendingReactionCount += batch.third
                }
            }
            reactionBatchJob = null
            if (pendingReactionCount > 0) {
                reactionBatchJob = scope.launch {
                    delay(REACTION_BATCH_WINDOW_MS)
                    flushPendingReactions()
                }
            }
        }
    }

    fun announceCommittedGift(receipt: GiftReceipt) {
        val liveId = receipt.liveStreamId?.trim().orEmpty()
        if (liveId.isBlank() || receipt.transactionId.isBlank()) return
        val activeLiveId = _activeSession.value?.stream?.id?.trim().orEmpty()
        if (!activeLiveId.equals(liveId, ignoreCase = true)) return
        val event = LiveGiftEvent(
            transactionId = receipt.transactionId,
            liveStreamId = liveId,
            senderId = receipt.senderId,
            senderName = receipt.senderName,
            receiverId = receipt.receiverId,
            gift = receipt.gift
        )
        if (_giftEvents.value.none { it.transactionId == event.transactionId }) {
            _giftEvents.update { (it + event).takeLast(4) }
            scheduleGiftExpiry(event)
        }
        if (!receipt.isPublic) {
            return
        }
        val payload = org.json.JSONObject().apply {
            put("liveId", liveId)
            put("transactionId", receipt.transactionId)
        }
        runCatching { socket?.emit("gift-committed", payload) }
    }

    suspend fun refreshLiveReplays() = withContext(Dispatchers.IO) {
        if (!usesUbuntuPersistence()) {
            _liveReplays.value = emptyList()
            _myLiveHistory.value = emptyList()
            return@withContext
        }
        val token = localAccessToken()
        if (token.isBlank()) {
            _liveReplays.value = emptyList()
            _myLiveHistory.value = emptyList()
            return@withContext
        }
        localFallbackApiClient.loadLiveReplays(token, mine = false)
            .onSuccess { _liveReplays.value = it }
            .onFailure { android.util.Log.w("LiveRepository", "Unable to load public replays", it) }
        localFallbackApiClient.loadLiveReplays(token, mine = true)
            .onSuccess { _myLiveHistory.value = it }
            .onFailure { android.util.Log.w("LiveRepository", "Unable to load owned live history", it) }
    }

    suspend fun loadLiveReplay(liveId: String): Result<LiveStream> = withContext(Dispatchers.IO) {
        val token = localAccessToken()
        if (token.isBlank()) return@withContext Result.failure(Exception("Tu sesión ya no es válida."))
        localFallbackApiClient.loadLiveReplay(token, liveId)
    }

    suspend fun addLiveChapter(title: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = _activeSession.value
            ?: return@withContext Result.failure(Exception("No hay un directo activo."))
        if (!_moderationState.value.canModerate) {
            return@withContext Result.failure(Exception("No tienes permisos para marcar momentos."))
        }
        val cleanTitle = title.trim().take(80)
        if (cleanTitle.isBlank()) return@withContext Result.failure(Exception("Escribe un nombre para el momento."))
        val token = localAccessToken()
        if (token.isBlank()) return@withContext Result.failure(Exception("Tu sesión ya no es válida."))
        val elapsedMs = (SystemClock.elapsedRealtime() - hostSessionStartedElapsedMs)
            .coerceAtLeast(0L)
        localFallbackApiClient.createLiveChapter(
            accessToken = token,
            liveId = session.stream.id,
            title = cleanTitle,
            offsetMs = elapsedMs
        ).map { chapter ->
            _activeSession.update { current ->
                current?.copy(
                    stream = current.stream.copy(
                        chapters = (current.stream.chapters + chapter).sortedBy { it.offsetMs }
                    )
                )
            }
        }
    }

    suspend fun publishLiveReplay(
        liveId: String,
        sourceUri: android.net.Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!usesUbuntuPersistence()) {
            return@withContext Result.failure(Exception("Las repeticiones requieren el servidor Novvin actualizado."))
        }
        val token = localAccessToken()
        val userId = authService.getCurrentUserId()?.trim().orEmpty()
        if (token.isBlank() || userId.isBlank()) {
            return@withContext Result.failure(Exception("Tu sesión ya no es válida."))
        }
        val replay = _myLiveHistory.value.firstOrNull { it.id.equals(liveId, ignoreCase = true) }
            ?: localFallbackApiClient.loadLiveReplay(token, liveId).getOrElse {
                return@withContext Result.failure(it)
            }
        if (replay.isActive || !replay.hostUserId.equals(userId, ignoreCase = true)) {
            return@withContext Result.failure(Exception("Solo puedes publicar la repetición de un directo finalizado propio."))
        }

        val resolver = context.contentResolver
        val contentType = resolver.getType(sourceUri)?.lowercase().orEmpty()
        if (contentType != "video/mp4") {
            return@withContext Result.failure(Exception("Selecciona un video MP4 para la repetición."))
        }
        val sizeBytes = resolver.query(
            sourceUri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
        if (sizeBytes <= 0L || sizeBytes > 512L * 1024 * 1024) {
            return@withContext Result.failure(Exception("La repetición debe pesar menos de 512 MB."))
        }
        val durationMs = runCatching {
            android.media.MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, sourceUri)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
            }
        }.getOrDefault(0L)
        if (durationMs < 1_000L || durationMs > 43_200_000L) {
            return@withContext Result.failure(Exception("La duración de la repetición no es válida."))
        }

        _replayUploadProgress.value = 0.01f
        val replayPath = "$userId/replays/$liveId/${UUID.randomUUID()}.mp4"
        val upload = storageService.uploadFileFromUri(
            bucketName = "live-replays",
            fileName = replayPath,
            sourceUri = sourceUri,
            sizeBytes = sizeBytes,
            contentType = "video/mp4",
            allowOverwrite = false,
            onProgress = { _replayUploadProgress.value = it.coerceIn(0f, 0.9f) }
        )
        if (upload.isFailure) {
            _replayUploadProgress.value = 0f
            return@withContext Result.failure(upload.exceptionOrNull() ?: Exception("No se pudo subir la repetición."))
        }
        val storedReplayPath = upload.getOrThrow().Key ?: replayPath
        val replayUrl = storageService.getPublicUrl("live-replays", storedReplayPath)
        var thumbnailUrl: String? = null
        var thumbnailPath: String? = null
        runCatching {
            VideoUtils.generateVideoThumbnail(context, sourceUri)
        }.getOrNull()?.let { bytes ->
            thumbnailPath = "$userId/replays/$liveId/${UUID.randomUUID()}.jpg"
            storageService.uploadFile(
                bucketName = "thumbnails",
                fileName = thumbnailPath!!,
                bytes = bytes,
                contentType = "image/jpeg",
                allowOverwrite = false
            ).onSuccess { stored ->
                val actualPath = stored.Key ?: thumbnailPath!!
                thumbnailPath = actualPath
                thumbnailUrl = storageService.getPublicUrl("thumbnails", actualPath)
            }
        }

        val published = localFallbackApiClient.publishLiveReplay(
            accessToken = token,
            liveId = liveId,
            replayUrl = replayUrl,
            thumbnailUrl = thumbnailUrl,
            durationMs = durationMs
        )
        if (published.isFailure) {
            storageService.deleteFileByPublicUrl(replayUrl)
            thumbnailUrl?.let { storageService.deleteFileByPublicUrl(it) }
            _replayUploadProgress.value = 0f
            return@withContext Result.failure(
                published.exceptionOrNull() ?: Exception("No se pudo publicar la repetición.")
            )
        }
        _replayUploadProgress.value = 1f
        refreshLiveReplays()
        _replayUploadProgress.value = 0f
        Result.success(Unit)
    }

    fun updateLiveModerationSettings(
        chatPaused: Boolean? = null,
        blockedWords: List<String>? = null
    ) {
        val session = _activeSession.value ?: return
        val payload = org.json.JSONObject().apply {
            put("liveId", session.stream.id)
            put("command", "settings")
            chatPaused?.let { put("chatPaused", it) }
            blockedWords?.let { words ->
                put("blockedWords", org.json.JSONArray(words.map { it.trim() }.filter { it.isNotBlank() }))
            }
        }
        runCatching { socket?.emit("live-moderation-command", payload) }
            .onFailure { _runtimeErrors.tryEmit("No se pudo actualizar la moderación del directo.") }
    }

    fun setLiveModerator(userId: String, enabled: Boolean) {
        val session = _activeSession.value ?: return
        val targetUserId = userId.trim()
        if (targetUserId.isBlank()) return
        val payload = org.json.JSONObject().apply {
            put("liveId", session.stream.id)
            put("command", if (enabled) "assign-moderator" else "remove-moderator")
            put("targetUserId", targetUserId)
        }
        runCatching { socket?.emit("live-moderation-command", payload) }
            .onFailure { _runtimeErrors.tryEmit("No se pudo cambiar el rol de moderación.") }
    }

    fun moderateLiveViewer(userId: String, ban: Boolean, reason: String = "") {
        val session = _activeSession.value ?: return
        val targetUserId = userId.trim()
        if (targetUserId.isBlank()) return
        val payload = org.json.JSONObject().apply {
            put("liveId", session.stream.id)
            put("command", if (ban) "ban" else "kick")
            put("targetUserId", targetUserId)
            put("reason", reason.trim().take(300))
        }
        runCatching { socket?.emit("live-moderation-command", payload) }
            .onFailure { _runtimeErrors.tryEmit("No se pudo moderar a esta persona.") }
    }



    fun endLiveAsync() {
        scope.launch {
            endLive().onFailure { error ->
                android.util.Log.w("LiveRepository", "Unable to finish live cleanly", error)
            }
        }
    }

    suspend fun endLive(): Result<Unit> = closeCoordinator.closeOnce {
        sessionEndMutex.withLock {
            sessionStartupMutex.withLock {
                withContext(Dispatchers.IO) {
                    val session = _activeSession.value
                    val currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
                    val isHost = session?.role == LiveRole.HOST
                    val peak = maxOf(peakViewerCount, _viewerCount.value).coerceAtLeast(0)
                    val totalComments = sessionCommentCount.coerceAtLeast(0)
                    val totalReactions = sessionReactionCount.coerceAtLeast(0)

                    // Only the signaling server knows which host socket is
                    // authoritative after a same-account device replacement.
                    // Never let a stale device mark the shared live inactive.
                    val serverConfirmedEnd = if (
                        session != null && isHost && currentUserId.isNotBlank()
                    ) {
                        requestLiveEndConfirmation(session.stream.id, currentUserId)
                    } else {
                        !isHost
                    }

                    disconnectAndClear()

                    val remoteConfirmed = if (session != null && isHost) {
                        serverConfirmedEnd && currentUserId.isNotBlank() && withTimeoutOrNull(8_000L) {
                            persistEndedLiveSnapshot(
                                session = session,
                                currentUserId = currentUserId,
                                peak = peak,
                                totalComments = totalComments,
                                totalReactions = totalReactions
                            )
                        } == true
                    } else {
                        true
                    }
                    runCatching { refreshActiveStreams() }
                    if (remoteConfirmed) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("El live se cerró localmente, pero el servidor no confirmó el cierre."))
                    }
                }
            }
        }
    }

    private suspend fun requestLiveEndConfirmation(liveId: String, userId: String): Boolean {
        val activeSocket = socket?.takeIf { it.connected() } ?: return false
        val confirmation = CompletableDeferred<Boolean>()
        val normalizedLiveId = liveId.trim()
        synchronized(liveEndConfirmationLock) {
            pendingLiveEndConfirmation?.second?.complete(false)
            pendingLiveEndConfirmation = normalizedLiveId to confirmation
        }
        val emitted = runCatching {
            activeSocket.emit(
                "end-live",
                org.json.JSONObject().apply {
                    put("liveId", normalizedLiveId)
                    put("userId", userId)
                }
            )
        }.isSuccess
        if (!emitted) confirmation.complete(false)

        val confirmed = withTimeoutOrNull(LIVE_END_CONFIRMATION_TIMEOUT_MS) {
            confirmation.await()
        } == true
        synchronized(liveEndConfirmationLock) {
            if (pendingLiveEndConfirmation?.second === confirmation) {
                pendingLiveEndConfirmation = null
            }
        }
        return confirmed
    }

    private fun completeLiveEndConfirmation(liveId: String, confirmed: Boolean) {
        val normalizedLiveId = liveId.trim()
        val deferred = synchronized(liveEndConfirmationLock) {
            pendingLiveEndConfirmation
                ?.takeIf { (pendingId, _) -> pendingId.equals(normalizedLiveId, ignoreCase = true) }
                ?.second
        }
        deferred?.complete(confirmed)
    }

    private suspend fun persistEndedLiveSnapshot(
        session: ActiveLiveSession,
        currentUserId: String,
        peak: Int,
        totalComments: Int,
        totalReactions: Int
    ): Boolean {
        if (usesUbuntuPersistence()) {
            val token = localAccessToken()
            if (token.isBlank()) return false
            return localFallbackApiClient.updateLiveState(
                accessToken = token,
                liveId = session.stream.id,
                isActive = false,
                viewerCount = 0,
                peakViewerCount = peak,
                totalComments = totalComments,
                totalReactions = totalReactions
            ).onFailure { error ->
                android.util.Log.w("LiveRepository", "Unable to persist Ubuntu live end state", error)
            }.isSuccess
        }

        val endedAt = Instant.now().toString()
        return runCatching {
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Backwards compatibility for databases without stats columns.
                supabaseConfig.client.postgrest["live_streams"].update({
                    set("is_active", false)
                }) {
                    filter { eq("id", session.stream.id) }
                    filter { eq("host_user_id", currentUserId) }
                }
            }
        }.isSuccess
    }

    private fun connectSocket(liveId: String, role: LiveRole, userId: String, name: String, avatarUrl: String?) {
        try {
            socket?.off()
            socket?.disconnect()
            socket = null
            if (userId.isBlank()) return

            if (webRTCManager == null) {
                _runtimeErrors.tryEmit("El motor del live no está preparado.")
                return
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
                transports = arrayOf(io.socket.engineio.client.transports.WebSocket.NAME) // FIX: Bypass polling
                attachBearerToken(authService.getAccessToken())
            }
            val endpointBaseUrl = conectate.com.data.localfallback.RuntimeBackendEndpoint.baseUrl
            val signalingEndpoint = signalingUrl
            val createdSocket = IO.socket(signalingEndpoint, options)
            socket = createdSocket

            createdSocket.on(Socket.EVENT_CONNECT_ERROR) {
                if (socket !== createdSocket) return@on
                recoverSocketAfterEndpointFailure(
                    failedSocket = createdSocket,
                    failedBaseUrl = endpointBaseUrl,
                    failedSignalingEndpoint = signalingEndpoint,
                    liveId = liveId,
                    role = role,
                    userId = userId,
                    name = name,
                    avatarUrl = avatarUrl
                )
            }

            createdSocket.on(Socket.EVENT_CONNECT) {
                if (socket !== createdSocket) return@on
                val active = _activeSession.value
                if (
                    active?.role != role ||
                    !active.stream.id.equals(liveId, ignoreCase = true)
                ) return@on
                val sid = createdSocket.id()
                webRTCManager?.setCurrentUser(userId, sid)
                webRTCManager?.refreshManagedIceServers(force = true)

                createdSocket.emit(
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
                if (role == LiveRole.VIEWER) {
                    scheduleLiveMediaTimeout()
                }
            }

            // Host gets viewer list + updates
            createdSocket.on("current-viewers") { args ->
                if (socket !== createdSocket) return@on
                if (role != LiveRole.HOST) return@on
                runCatching {
                    val arr = args.firstOrNull() as? org.json.JSONArray ?: return@runCatching
                    val currentSocketIds = mutableSetOf<String>()
                    val currentViewers = mutableListOf<LiveViewerParticipant>()
                    for (i in 0 until arr.length()) {
                        val viewer = arr.optJSONObject(i) ?: continue
                        val sid = viewer.optString("socketId").trim()
                        val uid = viewer.optString("userId").trim()
                        if (sid.isNotBlank()) {
                            currentSocketIds += sid
                            currentViewers += LiveViewerParticipant(
                                socketId = sid,
                                userId = uid,
                                name = viewer.optString("name", "Usuario").ifBlank { "Usuario" },
                                avatarUrl = viewer.optString("avatarUrl").takeIf { it.isNotBlank() },
                                isModerator = _moderationState.value.moderatorUserIds.contains(uid)
                            )
                            webRTCManager?.onUserJoined(sid, uid)
                        }
                    }
                    val staleSocketIds = knownViewerSocketIds - currentSocketIds
                    staleSocketIds.forEach { webRTCManager?.onUserLeft(it) }
                    knownViewerSocketIds.clear()
                    knownViewerSocketIds.addAll(currentSocketIds)
                    _liveViewers.value = currentViewers
                    if (currentSocketIds.isNotEmpty()) {
                        scheduleLiveMediaTimeout()
                    }
                }.onFailure { logMalformedSignal("current-viewers", it) }
            }

            createdSocket.on("viewer-joined") { args ->
                if (socket !== createdSocket) return@on
                if (role != LiveRole.HOST) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val sid = data.optString("socketId").trim()
                    val uid = data.optString("userId").trim()
                    if (sid.isNotBlank()) {
                        knownViewerSocketIds += sid
                        _liveViewers.update { current ->
                            val participant = LiveViewerParticipant(
                                socketId = sid,
                                userId = uid,
                                name = data.optString("name", "Usuario").ifBlank { "Usuario" },
                                avatarUrl = data.optString("avatarUrl").takeIf { it.isNotBlank() },
                                isModerator = _moderationState.value.moderatorUserIds.contains(uid)
                            )
                            (current.filterNot { it.socketId == sid } + participant)
                        }
                        webRTCManager?.onUserJoined(sid, uid)
                        scheduleLiveMediaTimeout()
                    }
                }.onFailure { logMalformedSignal("viewer-joined", it) }
            }

            createdSocket.on("viewer-left") { args ->
                if (socket !== createdSocket) return@on
                if (role != LiveRole.HOST) return@on
                runCatching {
                    val sid = (args.firstOrNull() as? String)?.trim().orEmpty()
                    if (sid.isNotBlank()) {
                        knownViewerSocketIds -= sid
                        _liveViewers.update { viewers -> viewers.filterNot { it.socketId == sid } }
                        webRTCManager?.onUserLeft(sid)
                    }
                }.onFailure { logMalformedSignal("viewer-left", it) }
            }

            // Viewers get host info
            createdSocket.on("host-info") { args ->
                if (socket !== createdSocket) return@on
                if (role != LiveRole.VIEWER) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val sid = data.optString("socketId").trim()
                    val uid = data.optString("userId").trim()
                    if (sid.isNotBlank()) {
                        webRTCManager?.onUserJoined(sid, uid)
                        scheduleLiveMediaTimeout()
                    }
                }.onFailure { logMalformedSignal("host-info", it) }
            }

            createdSocket.on("host-disconnected") { args ->
                if (socket !== createdSocket) return@on
                if (role != LiveRole.VIEWER) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val sid = data.optString("socketId").trim()
                    if (sid.isNotBlank()) {
                        webRTCManager?.onUserLeft(sid)
                        scheduleLiveMediaTimeout()
                    }
                }.onFailure { logMalformedSignal("host-disconnected", it) }
            }

            createdSocket.on("viewer-count") { args ->
                if (socket !== createdSocket) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val count = data.optInt("viewerCount", 0).coerceAtLeast(0)
                    _viewerCount.value = count
                    _activeSession.update { current -> current?.copy(viewerCount = count) }
                    if (count > peakViewerCount) peakViewerCount = count
                }.onFailure { logMalformedSignal("viewer-count", it) }
            }

            createdSocket.on("live-state") { args ->
                if (socket !== createdSocket) return@on
                if (role != LiveRole.VIEWER) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val source = data.optString("source", _currentSource.value.name.lowercase()).toLiveSource()
                    _currentSource.value = source
                    _isPaused.value = data.optBoolean("isPaused", false)
                    _isVideoEnabled.value = data.optBoolean("videoEnabled", true)
                    _isMicEnabled.value = data.optBoolean("micEnabled", _isMicEnabled.value)
                }.onFailure { logMalformedSignal("live-state", it) }
            }

            createdSocket.on("end-live-confirmed") { args ->
                if (socket !== createdSocket || role != LiveRole.HOST) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    completeLiveEndConfirmation(data.optString("liveId"), confirmed = true)
                }.onFailure { logMalformedSignal("end-live-confirmed", it) }
            }

            createdSocket.on("end-live-denied") { args ->
                if (socket !== createdSocket || role != LiveRole.HOST) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    completeLiveEndConfirmation(data.optString("liveId"), confirmed = false)
                }.onFailure { logMalformedSignal("end-live-denied", it) }
            }

            createdSocket.on("live-ended") {
                if (socket !== createdSocket) return@on
                scope.launch {
                    if (!_activeSession.value?.stream?.id.equals(liveId, ignoreCase = true)) {
                        return@launch
                    }
                    closeCoordinator.closeOnce {
                        disconnectAndClear()
                        Result.success(Unit)
                    }
                }
            }

            createdSocket.on("live-session-replaced") { args ->
                if (socket !== createdSocket || role != LiveRole.HOST) return@on
                completeLiveEndConfirmation(liveId, confirmed = false)
                val message = runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject
                    data?.optString("message")?.trim().orEmpty()
                }.getOrDefault("").ifBlank {
                    "Este directo continúa desde otro dispositivo."
                }
                scope.launch {
                    if (!_activeSession.value?.stream?.id.equals(liveId, ignoreCase = true)) {
                        return@launch
                    }
                    closeCoordinator.closeOnce {
                        disconnectAndClear()
                        Result.success(Unit)
                    }
                    _runtimeErrors.emit(message)
                }
            }

            createdSocket.on("reaction-count") { args ->
                if (socket !== createdSocket) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val count = data.optLong("reactionCount", 0L).coerceAtLeast(0L)
                    // El agregado del directo es monótono. Si todavía hay taps
                    // optimistas en tránsito, no hacemos saltar el número atrás.
                    _reactionCount.update { local -> maxOf(local, count) }
                    sessionReactionCount = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }.onFailure { logMalformedSignal("reaction-count", it) }
            }

            createdSocket.on("reaction-batch-rejected") { args ->
                if (socket !== createdSocket) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val batchId = data.optString("clientBatchId").trim()
                    if (batchId.isBlank()) return@runCatching
                    val rejectedCount = synchronized(reactionBatchLock) {
                        localReactionBatches.remove(batchId)
                    } ?: return@runCatching
                    val authoritativeCount = data.optLong("reactionCount", 0L).coerceAtLeast(0L)
                    _reactionCount.update { optimistic ->
                        maxOf(authoritativeCount, (optimistic - rejectedCount).coerceAtLeast(0L))
                    }
                    sessionReactionCount = authoritativeCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }.onFailure { logMalformedSignal("reaction-batch-rejected", it) }
            }

            createdSocket.on("live-moderation-state") { args ->
                if (socket !== createdSocket) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val moderatorIds = buildSet {
                        val moderators = data.optJSONArray("moderators")
                        if (moderators != null) {
                            for (index in 0 until moderators.length()) {
                                val entry = moderators.opt(index)
                                val userId = when (entry) {
                                    is org.json.JSONObject -> entry.optString("user_id")
                                    is String -> entry
                                    else -> ""
                                }.trim()
                                if (userId.isNotBlank()) add(userId)
                            }
                        } else {
                            addAll(_moderationState.value.moderatorUserIds)
                        }
                    }
                    _moderationState.value = LiveModerationState(
                        chatPaused = data.optBoolean("chatPaused", false),
                        blockedWords = buildList {
                            val words = data.optJSONArray("blockedWords")
                            if (words != null) {
                                for (index in 0 until words.length()) {
                                    words.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                                }
                            } else {
                                addAll(_moderationState.value.blockedWords)
                            }
                        },
                        canModerate = data.optBoolean("canModerate", false),
                        isModerator = data.optBoolean("isModerator", false),
                        isHost = data.optBoolean("isHost", role == LiveRole.HOST),
                        moderatorUserIds = moderatorIds
                    )
                    _liveViewers.update { viewers ->
                        viewers.map { viewer ->
                            viewer.copy(isModerator = moderatorIds.contains(viewer.userId))
                        }
                    }
                }.onFailure { logMalformedSignal("live-moderation-state", it) }
            }

            createdSocket.on("live-moderation-viewers") { args ->
                if (socket !== createdSocket) return@on
                runCatching {
                    val viewers = args.firstOrNull() as? org.json.JSONArray ?: return@runCatching
                    _liveViewers.value = buildList {
                        for (index in 0 until viewers.length()) {
                            val viewer = viewers.optJSONObject(index) ?: continue
                            val socketId = viewer.optString("socketId").trim()
                            val viewerUserId = viewer.optString("userId").trim()
                            if (socketId.isBlank() || viewerUserId.isBlank()) continue
                            add(
                                LiveViewerParticipant(
                                    socketId = socketId,
                                    userId = viewerUserId,
                                    name = viewer.optString("name", "Usuario").ifBlank { "Usuario" },
                                    avatarUrl = viewer.optString("avatarUrl").takeIf { it.isNotBlank() },
                                    isModerator = viewer.optBoolean("isModerator", false)
                                )
                            )
                        }
                    }
                }.onFailure { logMalformedSignal("live-moderation-viewers", it) }
            }

            createdSocket.on("live-moderated") { args ->
                if (socket !== createdSocket) return@on
                val message = runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject
                    data?.optString("message")?.trim().orEmpty()
                }.getOrDefault("").ifBlank {
                    "El equipo de moderación te retiró de este directo."
                }
                scope.launch {
                    if (!_activeSession.value?.stream?.id.equals(liveId, ignoreCase = true)) {
                        return@launch
                    }
                    closeCoordinator.closeOnce {
                        disconnectAndClear()
                        Result.success(Unit)
                    }
                    _runtimeErrors.emit(message)
                }
            }

            createdSocket.on("live-moderation-error") { args ->
                if (socket !== createdSocket) return@on
                val message = runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject
                    data?.optString("error")?.trim().orEmpty()
                }.getOrDefault("").ifBlank {
                    "No se pudo completar la acción de moderación."
                }
                _runtimeErrors.tryEmit(message)
            }

            createdSocket.on("reaction") { args ->
                if (socket !== createdSocket) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val fromUserId = data.optString("userId", "").trim()
                    val batchId = data.optString("clientBatchId", "").trim()
                    val isOwnOptimisticBatch = batchId.isNotBlank() && synchronized(reactionBatchLock) {
                        localReactionBatches.remove(batchId) != null
                    }
                    if (isOwnOptimisticBatch) return@runCatching
                    val count = data.optInt("count", 1).coerceIn(1, REACTION_BATCH_MAX)
                    val incoming = List(count) { LiveReaction(kind = "heart", userId = fromUserId) }
                    _reactions.update { (it + incoming).takeLast(MAX_ACTIVE_REACTION_PARTICLES) }
                    incoming.forEach(::scheduleReactionExpiry)
                }.onFailure { logMalformedSignal("reaction", it) }
            }

            createdSocket.on("gift") { args ->
                if (socket !== createdSocket) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val giftData = data.optJSONObject("gift") ?: return@runCatching
                    val transactionId = data.optString("transactionId").trim()
                    val eventLiveId = data.optString("liveId").trim()
                    if (transactionId.isBlank() || eventLiveId != liveId) return@runCatching
                    val event = LiveGiftEvent(
                        transactionId = transactionId,
                        liveStreamId = eventLiveId,
                        senderId = data.optString("senderId").trim(),
                        senderName = data.optString("senderName", "Usuario").ifBlank { "Usuario" },
                        receiverId = data.optString("receiverId").trim(),
                        gift = VirtualGift(
                            id = giftData.optString("id").trim(),
                            name = giftData.optString("name", "Regalo Novvin").ifBlank { "Regalo Novvin" },
                            imageUrl = giftData.optString("imageUrl").takeIf { it.isNotBlank() },
                            animationUrl = giftData.optString("animationUrl").takeIf { it.isNotBlank() },
                            priceDiamonds = giftData.optInt("priceDiamonds", 0).coerceAtLeast(0)
                        )
                    )
                    if (_giftEvents.value.none { it.transactionId == transactionId }) {
                        _giftEvents.update { (it + event).takeLast(4) }
                        scheduleGiftExpiry(event)
                    }
                }.onFailure { logMalformedSignal("gift", it) }
            }

            createdSocket.on("gift-error") { args ->
                if (socket !== createdSocket) return@on
                val message = runCatching {
                    (args.firstOrNull() as? org.json.JSONObject)?.optString("message")?.trim().orEmpty()
                }.getOrDefault("").ifBlank { "No se pudo mostrar el regalo en el directo." }
                _runtimeErrors.tryEmit(message)
            }

            // WebRTC signaling
            createdSocket.on("offer") { args ->
                if (socket !== createdSocket) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val senderSocketId = data.optString("sender").trim()
                    val senderUserId = data.optString("userId").trim()
                    val offerSdp = data.optJSONObject("offer")?.optString("sdp").orEmpty()
                    if (senderSocketId.isNotBlank() && offerSdp.isNotBlank()) {
                        webRTCManager?.onOfferReceived(senderSocketId, senderUserId, offerSdp)
                    }
                }.onFailure { logMalformedSignal("offer", it) }
            }

            createdSocket.on("answer") { args ->
                if (socket !== createdSocket) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val sender = data.optString("sender").trim()
                    val senderUserId = data.optString("userId").trim()
                    val answerSdp = data.optJSONObject("answer")?.optString("sdp").orEmpty()
                    if (sender.isNotBlank() && answerSdp.isNotBlank()) {
                        webRTCManager?.onAnswerReceived(sender, answerSdp, senderUserId)
                    }
                }.onFailure { logMalformedSignal("answer", it) }
            }

            createdSocket.on("ice-candidate") { args ->
                if (socket !== createdSocket) return@on
                runCatching {
                    val data = args.firstOrNull() as? org.json.JSONObject ?: return@runCatching
                    val sender = data.optString("sender").trim()
                    val senderUserId = data.optString("userId").trim()
                    val candidate = data.optJSONObject("candidate") ?: return@runCatching
                    if (sender.isNotBlank()) {
                        webRTCManager?.onIceCandidateReceived(sender, candidate, senderUserId)
                    }
                }.onFailure { logMalformedSignal("ice-candidate", it) }
            }

            socket?.connect()
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Failed to setup live socket", e)
        }
    }

    private fun recoverSocketAfterEndpointFailure(
        failedSocket: Socket,
        failedBaseUrl: String,
        failedSignalingEndpoint: String,
        liveId: String,
        role: LiveRole,
        userId: String,
        name: String,
        avatarUrl: String?
    ) {
        if (socketEndpointRecoveryJob?.isActive == true) return
        socketEndpointRecoveryJob = scope.launch {
            try {
                val refreshed = conectate.com.data.localfallback.RuntimeBackendEndpoint
                    .refreshAfterEndpointFailure(failedBaseUrl)
                val active = _activeSession.value
                if (
                    !refreshed ||
                    socket !== failedSocket ||
                    active == null ||
                    !active.stream.id.equals(liveId, ignoreCase = true) ||
                    active.role != role
                ) {
                    return@launch
                }
                val refreshedEndpoint = signalingUrl
                if (refreshedEndpoint == failedSignalingEndpoint) return@launch
                android.util.Log.w("LiveRepository", "Rebuilding live socket after backend endpoint changed.")
                connectSocket(liveId, role, userId, name, avatarUrl)
            } finally {
                socketEndpointRecoveryJob = null
            }
        }
    }

    private suspend fun initializeWebRTC(userId: String): Boolean {
        var events = object : conectate.com.data.webrtc.SignalingEvents {
            override fun onSendOffer(targetSocketId: String, offer: org.json.JSONObject) {
                var data = org.json.JSONObject().apply {
                    put("target", targetSocketId)
                    put("offer", offer)
                    put("userId", authService.getCurrentUserId()?.trim().orEmpty())
                }
                socket?.emit("offer", data)
            }

            override fun onSendAnswer(targetSocketId: String, answer: org.json.JSONObject) {
                var data = org.json.JSONObject().apply {
                    put("target", targetSocketId)
                    put("answer", answer)
                    put("userId", authService.getCurrentUserId()?.trim().orEmpty())
                }
                socket?.emit("answer", data)
            }

            override fun onSendIceCandidate(targetSocketId: String, candidate: org.json.JSONObject) {
                var data = org.json.JSONObject().apply {
                    put("target", targetSocketId)
                    put("candidate", candidate)
                    put("userId", authService.getCurrentUserId()?.trim().orEmpty())
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
                    created.setCameraFilterPerformanceListener { state ->
                        onCameraFilterPerformanceChanged(state)
                    }
                    created.setCameraFilter(_cameraFilter.value)
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
                    scope.launch {
                        created.peerConnectionStates.collect { states ->
                            updateLiveMediaConnectionState(states)
                        }
                    }
                }
            } else {
                manager.setCameraFilterPerformanceListener { state ->
                    onCameraFilterPerformanceChanged(state)
                }
                manager.hardResetAwait()
                manager.setCurrentUser(userId, "")
                manager.setCameraFilter(_cameraFilter.value)
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

    private fun onCameraFilterPerformanceChanged(state: LiveFilterPerformanceState) {
        _cameraFilterPerformance.value = if (
            screenCaptureActive || _currentSource.value == LiveSource.SCREEN
        ) {
            LiveFilterPerformanceState.NOT_APPLIED_TO_SCREEN
        } else {
            state
        }
    }

    private fun updateCameraFilterPerformanceForSource() {
        _cameraFilterPerformance.value = if (
            screenCaptureActive || _currentSource.value == LiveSource.SCREEN
        ) {
            LiveFilterPerformanceState.NOT_APPLIED_TO_SCREEN
        } else {
            _cameraFilter.value.toDefaultPerformanceState()
        }
    }

    private fun updateLiveMediaConnectionState(
        states: Map<String, org.webrtc.PeerConnection.IceConnectionState>
    ) {
        val connected = states.values.any { state ->
            state == org.webrtc.PeerConnection.IceConnectionState.CONNECTED ||
                state == org.webrtc.PeerConnection.IceConnectionState.COMPLETED
        }
        if (connected) {
            mediaConnectionTimeoutJob?.cancel()
            mediaConnectionTimeoutJob = null
        } else if (states.isNotEmpty()) {
            scheduleLiveMediaTimeout()
        }
    }

    private fun scheduleLiveMediaTimeout() {
        if (mediaConnectionTimeoutJob?.isActive == true) return
        mediaConnectionTimeoutJob = scope.launch {
            try {
                delay(25_000L)
                val manager = webRTCManager ?: return@launch
                val states = manager.peerConnectionStates.value
                val connected = states.values.any { state ->
                    state == org.webrtc.PeerConnection.IceConnectionState.CONNECTED ||
                        state == org.webrtc.PeerConnection.IceConnectionState.COMPLETED
                }
                if (!connected && _activeSession.value != null) {
                    manager.refreshManagedIceServers(force = true)
                    _runtimeErrors.tryEmit(
                        "El directo se encontró, pero el audio o video no pudo atravesar la red. " +
                            "Se renovó la conexión; vuelve a abrir el directo si no aparece en unos segundos."
                    )
                }
            } finally {
                mediaConnectionTimeoutJob = null
            }
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

        if (usesUbuntuPersistence()) {
            commentsJob = scope.launch {
                while (
                    currentCoroutineContext().isActive &&
                    _activeSession.value?.stream?.id.equals(liveId, ignoreCase = true)
                ) {
                    val token = localAccessToken()
                    if (token.isNotBlank()) {
                        localFallbackApiClient.loadLiveComments(token, liveId, limit = 200)
                            .onSuccess { history -> _comments.value = history }
                            .onFailure { error ->
                                android.util.Log.w("LiveRepository", "Unable to refresh Ubuntu live comments", error)
                            }
                    }
                    delay(LOCAL_COMMENT_REFRESH_MS)
                }
            }
            return
        }

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

    private suspend fun disconnectAndClear() = withContext(NonCancellable) {
        synchronized(liveEndConfirmationLock) {
            pendingLiveEndConfirmation?.second?.complete(false)
            pendingLiveEndConfirmation = null
        }
        val hadRenderedVideo = _localVideoTrack.value != null || _remoteVideoTracks.value.isNotEmpty()
        _activeSession.value = null
        _eglBaseContext.value = null
        _localVideoTrack.value = null
        _remoteVideoTracks.value = emptyMap()

        stopService()
        socketEndpointRecoveryJob?.cancel()
        socketEndpointRecoveryJob = null
        mediaConnectionTimeoutJob?.cancel()
        mediaConnectionTimeoutJob = null
        knownViewerSocketIds.clear()
        val socketToClose = socket
        socket = null
        try {
            socketToClose?.off()
            socketToClose?.disconnect()
        } catch (_: Exception) { }

        val closingJob = currentCoroutineContext()[Job]
        val adaptiveJob = adaptiveQualityJob
        adaptiveQualityJob = null
        adaptiveJob
            ?.takeIf { producerJob -> producerJob !== closingJob }
            ?.cancelAndJoin()
        projectionState.reset()
        val projectionJob = synchronized(projectionStopJobLock) {
            projectionStopJob.also {
                projectionStopJob = null
                projectionStopToken = null
            }
        }
        projectionJob
            ?.takeIf { stopJob -> stopJob !== closingJob }
            ?.cancelAndJoin()
        mediaTransitionJob
            ?.takeIf { transitionJob -> transitionJob !== closingJob }
            ?.cancelAndJoin()
        mediaTransitionJob = null
        _isMediaTransitioning.value = false
        mediaTransitionGate.finish()

        commentsJob?.cancel()
        commentsJob = null
        lastAdaptiveQualityChangeAtMs = 0L
        reactionExpiryJobs.values.toList().forEach { it.cancel() }
        reactionExpiryJobs.clear()
        reactionBatchJob?.cancel()
        reactionBatchJob = null
        synchronized(reactionBatchLock) {
            pendingReactionCount = 0
            pendingReactionLiveId = ""
            pendingReactionUserId = ""
            localReactionBatches.clear()
        }
        giftExpiryJobs.values.toList().forEach { it.cancel() }
        giftExpiryJobs.clear()

        val channelToClose = commentsChannel
        commentsChannel = null
        if (channelToClose != null) {
            scope.launch {
                try {
                    channelToClose.unsubscribe()
                } catch (_: Exception) { }
            }
        }

        // Give Compose one frame to detach SurfaceViewRenderer sinks before the
        // single WebRTC executor disposes their native tracks.
        if (hadRenderedVideo) delay(96L)
        runCatching { webRTCManager?.hardResetAwait() }
            .onFailure { error ->
                android.util.Log.e("LiveRepository", "Unable to reset live media cleanly", error)
            }

        _comments.value = emptyList()
        _reactions.value = emptyList()
        _reactionCount.value = 0L
        _giftEvents.value = emptyList()
        _viewerCount.value = 0
        _liveViewers.value = emptyList()
        _moderationState.value = LiveModerationState()
        _isMicEnabled.value = true
        _isMediaReady.value = false
        _isPaused.value = false
        _isVideoEnabled.value = true
        _currentSource.value = LiveSource.CAMERA
        screenCaptureActive = false
        updateCameraFilterPerformanceForSource()
        hostSessionStartedElapsedMs = 0L
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

    private fun scheduleGiftExpiry(event: LiveGiftEvent) {
        val id = event.transactionId.trim()
        if (id.isBlank() || giftExpiryJobs.containsKey(id)) return
        giftExpiryJobs[id] = scope.launch {
            delay(4_500L)
            _giftEvents.update { it.filterNot { gift -> gift.transactionId == id } }
            giftExpiryJobs.remove(id)?.cancel()
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

    private fun startService(
        stream: LiveStream,
        role: LiveRole,
        videoEnabledOverride: Boolean? = null,
        additionalSourceOverride: LiveSource? = null
    ) {
        if (role != LiveRole.HOST) return
        val liveId = normalizeLiveSessionId(stream.id) ?: return
        val type = computeForegroundType(
            role = role,
            source = stream.source,
            videoEnabledOverride = videoEnabledOverride,
            additionalSource = additionalSourceOverride?.name?.lowercase()
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && type == 0) {
            stopService()
            return
        }

        try {
            val intent = Intent(context, LiveForegroundService::class.java).apply {
                action = LiveForegroundService.ACTION_START
                putExtra(LiveForegroundService.EXTRA_LIVE_ID, liveId)
                putExtra(LiveForegroundService.EXTRA_LIVE_TITLE, stream.title.ifBlank { stream.hostName.ifBlank { "Live" } })
                putExtra(LiveForegroundService.EXTRA_LIVE_ROLE, role.name.lowercase())
                putExtra(LiveForegroundService.EXTRA_TYPE, type)
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            android.util.Log.e("LiveRepository", "Failed to start LiveForegroundService", e)
            _runtimeErrors.tryEmit("No se pudo mantener el live activo en segundo plano.")
        }
    }

    private fun refreshForegroundServiceType(
        sourceOverride: LiveSource? = null,
        updateSession: Boolean = true,
        videoEnabledOverride: Boolean? = null,
        additionalSourceOverride: LiveSource? = null
    ) {
        var session = _activeSession.value ?: return
        if (session.role != LiveRole.HOST) return

        val serviceSource = sourceOverride ?: _currentSource.value
        val updatedStream = session.stream.copy(source = serviceSource.name.lowercase())
        if (updateSession) {
            _activeSession.value = session.copy(stream = updatedStream)
        }
        startService(
            stream = updatedStream,
            role = session.role,
            videoEnabledOverride = videoEnabledOverride,
            additionalSourceOverride = additionalSourceOverride
        )
    }

    private fun stopService() {
        try {
            context.stopService(Intent(context, LiveForegroundService::class.java))
        } catch (_: Exception) { }
    }

    private fun computeForegroundType(
        role: LiveRole,
        source: String,
        videoEnabledOverride: Boolean? = null,
        additionalSource: String? = null
    ): Int {
        if (role != LiveRole.HOST) return 0

        var type = 0
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            _isMicEnabled.value &&
            hasPermission(android.Manifest.permission.RECORD_AUDIO)
        ) {
            type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (videoEnabledOverride ?: _isVideoEnabled.value) {
            val activeSources = setOfNotNull(
                source.trim().lowercase().takeIf { it == "camera" || it == "screen" },
                additionalSource?.trim()?.lowercase()?.takeIf { it == "camera" || it == "screen" }
            )
            if ("screen" in activeSources) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && screenCaptureActive) {
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            }
            if (
                "camera" in activeSources &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                hasPermission(android.Manifest.permission.CAMERA)
            ) {
                type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        return type
    }

    private fun startConfiguredVideo(projectionIntent: Intent? = null): Boolean {
        return when (_currentSource.value) {
            LiveSource.CAMERA -> startCamera()
            LiveSource.SCREEN -> {
                when {
                    canReuseScreenShareIntent() -> true
                    projectionIntent != null -> startScreenShare(projectionIntent)
                    else -> false
                }
            }
        }
    }

    private suspend fun startScreenCaptureWhenForegroundReady(
        sessionId: String,
        manager: WebRTCManager,
        projectionIntent: Intent,
        width: Int,
        height: Int,
        projectionToken: String
    ): Boolean {
        val foregroundReady = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            true
        } else {
            withTimeoutOrNull(5_000L) {
                LiveForegroundService.foregroundState.first { state ->
                    state.liveId.equals(sessionId, ignoreCase = true) &&
                        state.types and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0
                }
            } != null
        }

        val currentSession = _activeSession.value
        val requestStillCurrent =
            currentSession?.role == LiveRole.HOST &&
                currentSession.stream.id.equals(sessionId, ignoreCase = true) &&
                screenCaptureActive &&
                mediaTransitionGate.isTransitioning()
        if (!requestStillCurrent || !foregroundReady) return false

        manager.ensureLocalTracks()
        manager.setVideoSendEnabled(!_isPaused.value, renegotiate = false)
        return manager.startVideoAwait(
            isScreen = true,
            intent = projectionIntent,
            isFront = true,
            targetWidth = width,
            targetHeight = height,
            targetFps = 15,
            onProjectionStopped = { onScreenProjectionStopped(sessionId, projectionToken) }
        )
    }

    private fun onScreenProjectionStopped(sessionId: String, projectionToken: String) {
        when (projectionState.markStopped(projectionToken)) {
            ProjectionStopDisposition.PENDING,
            ProjectionStopDisposition.IGNORED -> return
            ProjectionStopDisposition.ACTIVE -> Unit
        }

        val newStopJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var ownsTransition = false
                while (projectionState.isActive(projectionToken)) {
                    if (
                        !projectionState.isSuppressed(projectionToken) &&
                        mediaTransitionGate.tryStart()
                    ) {
                        ownsTransition = true
                        break
                    }
                    delay(16L)
                }
                if (!ownsTransition) return@launch

                _isMediaTransitioning.value = true
                try {
                    val session = _activeSession.value
                    if (
                        session?.role != LiveRole.HOST ||
                        !session.stream.id.equals(sessionId, ignoreCase = true) ||
                        _currentSource.value != LiveSource.SCREEN ||
                        !projectionState.isActive(projectionToken) ||
                        projectionState.isSuppressed(projectionToken)
                    ) {
                        return@launch
                    }

                    projectionState.clearActive(projectionToken)
                    screenCaptureActive = false
                    _currentSource.value = LiveSource.SCREEN
                    _isVideoEnabled.value = false
                    updateSessionSource(LiveSource.SCREEN)
                    webRTCManager?.setVideoSendEnabled(false, renegotiate = false)
                    webRTCManager?.stopVideoAwait()
                    refreshForegroundServiceType()
                    emitLiveState()
                    persistLiveSourceAsync(sessionId, LiveSource.SCREEN)
                    _runtimeErrors.emit("La compartición de pantalla fue detenida.")
                } finally {
                    _isMediaTransitioning.value = false
                    mediaTransitionGate.finish()
                }
            } finally {
                val completedJob = currentCoroutineContext()[Job]
                synchronized(projectionStopJobLock) {
                    if (projectionStopJob === completedJob) {
                        projectionStopJob = null
                        projectionStopToken = null
                    }
                }
            }
        }
        var installJob = false
        var previousJob: Job? = null
        synchronized(projectionStopJobLock) {
            val runningJob = projectionStopJob
            if (
                runningJob == null ||
                runningJob.isCompleted ||
                projectionStopToken != projectionToken
            ) {
                previousJob = runningJob?.takeIf { !it.isCompleted }
                projectionStopToken = projectionToken
                projectionStopJob = newStopJob
                installJob = true
            }
        }
        if (!installJob) {
            newStopJob.cancel()
            return
        }
        previousJob?.cancel()
        newStopJob.start()
    }

    private fun updateSessionSource(source: LiveSource) {
        _activeSession.update { session ->
            session?.copy(stream = session.stream.copy(source = source.name.lowercase()))
        }
    }

    private fun persistLiveSourceAsync(liveId: String, source: LiveSource) {
        scope.launch {
            sourcePersistenceMutex.withLock {
                val currentSession = _activeSession.value
                if (
                    currentSession?.role != LiveRole.HOST ||
                    !currentSession.stream.id.equals(liveId, ignoreCase = true) ||
                    _currentSource.value != source
                ) {
                    return@withLock
                }

                val completed = withTimeoutOrNull(SOURCE_PERSIST_TIMEOUT_MS) {
                    persistLiveSource(liveId, source)
                    true
                } ?: false
                if (!completed) {
                    android.util.Log.w(
                        "LiveRepository",
                        "Timed out persisting source ${source.name.lowercase()} for live $liveId"
                    )
                }
            }
        }
    }

    private suspend fun persistLiveSource(liveId: String, source: LiveSource) {
        try {
            val currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
            if (currentUserId.isBlank()) return
            val wireSource = source.name.lowercase()

            if (usesUbuntuPersistence()) {
                val token = localAccessToken()
                if (token.isBlank()) return
                localFallbackApiClient.updateLiveState(
                    accessToken = token,
                    liveId = liveId,
                    source = wireSource
                ).onFailure { error ->
                    android.util.Log.w("LiveRepository", "Unable to persist live source", error)
                }
            } else {
                runCatching {
                    supabaseConfig.client.postgrest["live_streams"].update({
                        set("source", wireSource)
                    }) {
                        filter { eq("id", liveId) }
                        filter { eq("host_user_id", currentUserId) }
                    }
                }.onFailure { error ->
                    android.util.Log.w("LiveRepository", "Unable to persist live source", error)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("LiveRepository", "Unable to persist live source", error)
        }
    }

    private fun validateHostCapturePermissions(): String? {
        if (_isMicEnabled.value && !hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
            return "Falta el permiso del micrófono para iniciar el live."
        }
        if (
            _isVideoEnabled.value &&
            _currentSource.value == LiveSource.CAMERA &&
            !hasPermission(android.Manifest.permission.CAMERA)
        ) {
            return "Falta el permiso de la cámara para iniciar el live."
        }
        return null
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun logMalformedSignal(event: String, error: Throwable) {
        android.util.Log.w("LiveRepository", "Ignoring malformed live signal: $event", error)
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

private fun LiveCameraFilter.toDefaultPerformanceState(): LiveFilterPerformanceState =
    if (this == LiveCameraFilter.NONE) {
        LiveFilterPerformanceState.NATURAL
    } else {
        LiveFilterPerformanceState.FULL
    }

internal fun normalizeLiveSessionId(rawValue: String?): String? {
    val value = rawValue?.trim().orEmpty()
    if (value.isBlank() || value.length > 128) return null
    val isSafe = value.all { character ->
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_' ||
            character == '.'
    }
    return value.takeIf { isSafe }
}
