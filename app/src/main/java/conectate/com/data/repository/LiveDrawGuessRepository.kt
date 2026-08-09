package conectate.com.data.repository

import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.model.LiveDrawGuessGuessResult
import conectate.com.data.model.LiveDrawGuessState
import conectate.com.data.model.LiveDrawPacket
import conectate.com.data.model.LiveDrawPacketReceipt
import conectate.com.data.supabase.AuthService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * REST is authoritative for game, round, prompt and score state. Realtime is
 * used only for lossy drawing packets and as a refresh hint; LiveKit metadata
 * is deliberately not part of the game protocol.
 */
@Singleton
class LiveDrawGuessRepository @Inject constructor(
    private val api: LocalFallbackApiClient,
    private val authService: AuthService,
    private val realtime: UbuntuRealtimeClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val bindingMutex = Mutex()
    private val stateRequestMutex = Mutex()
    private val _state = MutableStateFlow<LiveDrawGuessState?>(null)
    val state: StateFlow<LiveDrawGuessState?> = _state.asStateFlow()
    private val _drawPackets = MutableStateFlow<List<LiveDrawPacket>>(emptyList())
    val drawPackets: StateFlow<List<LiveDrawPacket>> = _drawPackets.asStateFlow()

    @Volatile private var desiredLiveId: String? = null
    @Volatile private var boundLiveId: String? = null
    @Volatile private var boundRoundId: String? = null
    private var pollJob: Job? = null
    private var eventJob: Job? = null
    private val seenSequences = linkedSetOf<Long>()

    suspend fun bind(liveId: String) {
        val normalized = normalizeLiveSessionId(liveId) ?: return
        desiredLiveId = normalized
        bindingMutex.withLock {
            if (desiredLiveId != normalized) return@withLock
            if (boundLiveId == normalized && pollJob?.isActive == true && eventJob?.isActive == true) {
                return@withLock
            }
            unbindLocked()
            val token = accessToken().getOrNull() ?: return@withLock
            if (desiredLiveId != normalized) return@withLock
            boundLiveId = normalized
            realtime.updateAccessToken(token)
            realtime.subscribe(channel(normalized))
            refresh(normalized)
            eventJob = scope.launch {
                realtime.events.collect { envelope ->
                    if (envelope.channel != channel(normalized) || envelope.event != "live_draw_guess") return@collect
                    when (envelope.payload["type"]?.jsonPrimitive?.contentOrNull) {
                        "draw_packet" -> {
                            val current = _state.value ?: return@collect
                            val eventGameId = envelope.payload["game_id"]?.jsonPrimitive?.contentOrNull
                            val eventRoundId = envelope.payload["round_id"]?.jsonPrimitive?.contentOrNull
                            val belongsToCurrentRound = liveDrawPacketMatchesState(
                                currentGameId = current.game.id,
                                currentRoundId = current.round?.id,
                                eventGameId = eventGameId,
                                eventRoundId = eventRoundId
                            )
                            if (!belongsToCurrentRound) return@collect
                            val packetElement = envelope.payload["packet"] ?: return@collect
                            runCatching { json.decodeFromJsonElement<LiveDrawPacket>(packetElement) }
                                .getOrNull()
                                ?.let(::addPacket)
                        }
                        else -> refresh(normalized)
                    }
                }
            }
            pollJob = scope.launch {
                while (currentCoroutineContext().isActive && boundLiveId == normalized) {
                    delay(2_000L)
                    refresh(normalized)
                }
            }
        }
    }

    suspend fun unbind() {
        desiredLiveId = null
        bindingMutex.withLock { unbindLocked() }
    }

    private fun unbindLocked() {
        val previous = boundLiveId
        boundLiveId = null
        pollJob?.cancel()
        eventJob?.cancel()
        pollJob = null
        eventJob = null
        if (previous != null) realtime.unsubscribe(channel(previous))
        boundRoundId = null
        seenSequences.clear()
        _drawPackets.value = emptyList()
        _state.value = null
    }

    suspend fun refresh(liveId: String = boundLiveId.orEmpty()): Result<LiveDrawGuessState?> {
        val normalized = normalizeLiveSessionId(liveId)
            ?: return Result.failure(IllegalArgumentException("El identificador del live no es válido."))
        val token = accessToken().getOrElse { return Result.failure(it) }
        return stateRequestMutex.withLock {
            api.loadCurrentLiveDrawGuess(token, normalized)
                .onSuccess { applyStateForLive(normalized, it) }
        }
    }

    suspend fun create(
        liveId: String,
        participantIds: List<String>,
        totalRounds: Int = 3,
        roundSeconds: Int = 75
    ): Result<LiveDrawGuessState> = authenticated(liveId) { token, normalized ->
        stateRequestMutex.withLock {
            api.createLiveDrawGuess(token, normalized, participantIds, totalRounds, roundSeconds)
                .onSuccess { applyStateForLive(normalized, it) }
        }
    }

    suspend fun accept(liveId: String, gameId: String): Result<LiveDrawGuessState> =
        authenticated(liveId) { token, normalized ->
            stateRequestMutex.withLock {
                api.acceptLiveDrawGuess(token, normalized, gameId)
                    .onSuccess { applyStateForLive(normalized, it) }
            }
        }

    suspend fun start(liveId: String, gameId: String): Result<LiveDrawGuessState> =
        authenticated(liveId) { token, normalized ->
            stateRequestMutex.withLock {
                api.startLiveDrawGuess(token, normalized, gameId)
                    .onSuccess { applyStateForLive(normalized, it) }
            }
        }

    suspend fun cancel(liveId: String, gameId: String): Result<LiveDrawGuessState> =
        authenticated(liveId) { token, normalized ->
            stateRequestMutex.withLock {
                api.cancelLiveDrawGuess(token, normalized, gameId)
                    .onSuccess { applyStateForLive(normalized, it) }
            }
        }

    suspend fun advance(liveId: String, gameId: String): Result<LiveDrawGuessState> =
        authenticated(liveId) { token, normalized ->
            stateRequestMutex.withLock {
                api.advanceLiveDrawGuess(token, normalized, gameId)
                    .onSuccess { applyStateForLive(normalized, it) }
            }
        }

    suspend fun guess(liveId: String, gameId: String, guess: String): Result<LiveDrawGuessGuessResult> =
        authenticated(liveId) { token, normalized ->
            stateRequestMutex.withLock {
                api.submitLiveDrawGuess(token, normalized, gameId, guess)
                    .onSuccess { applyStateForLive(normalized, it.state) }
            }
        }

    suspend fun draw(liveId: String, gameId: String, packet: LiveDrawPacket): Result<LiveDrawPacketReceipt> =
        authenticated(liveId) { token, normalized ->
            api.sendLiveDrawPacket(token, normalized, gameId, packet).onSuccess { receipt ->
                if (receipt.accepted && boundLiveId == normalized) addPacket(packet)
            }
        }

    private suspend fun <T> authenticated(
        liveId: String,
        request: suspend (String, String) -> Result<T>
    ): Result<T> {
        val normalized = normalizeLiveSessionId(liveId)
            ?: return Result.failure(IllegalArgumentException("El identificador del live no es válido."))
        val token = accessToken().getOrElse { return Result.failure(it) }
        return try {
            request(token, normalized)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun accessToken(): Result<String> {
        return try {
            val token = authService.getAccessTokenAwait()?.trim().orEmpty()
            if (token.isBlank()) Result.failure(IllegalStateException("Sesión no válida."))
            else Result.success(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun applyState(value: LiveDrawGuessState?) {
        val nextRoundId = value?.round?.id
        if (boundRoundId != nextRoundId) {
            boundRoundId = nextRoundId
            synchronized(seenSequences) {
                seenSequences.clear()
                _drawPackets.value = emptyList()
            }
        }
        _state.value = value
    }

    private fun applyStateForLive(liveId: String, value: LiveDrawGuessState?) {
        if (boundLiveId == liveId) applyState(value)
    }

    private fun addPacket(packet: LiveDrawPacket) = synchronized(seenSequences) {
        if (!seenSequences.add(packet.sequence)) return@synchronized
        while (seenSequences.size > 1_024) seenSequences.remove(seenSequences.first())
        _drawPackets.value = (_drawPackets.value + packet).takeLast(512)
    }

    private fun channel(liveId: String): String = "live-game:$liveId"
}

internal fun liveDrawPacketMatchesState(
    currentGameId: String,
    currentRoundId: String?,
    eventGameId: String?,
    eventRoundId: String?
): Boolean =
    currentGameId.isNotBlank() &&
        !currentRoundId.isNullOrBlank() &&
        currentGameId == eventGameId &&
        currentRoundId == eventRoundId
