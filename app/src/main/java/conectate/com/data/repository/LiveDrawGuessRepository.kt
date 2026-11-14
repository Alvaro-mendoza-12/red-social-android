package conectate.com.data.repository

import conectate.com.data.localfallback.LiveDrawGuessApi
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.model.*
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

internal class LiveDrawGuessBinding internal constructor(
    val liveId: String,
    internal val owner: AuthSessionLease,
    parent: Job?,
    dispatcher: CoroutineDispatcher,
) {
    internal val lifetime = SupervisorJob(parent)
    internal val scope = CoroutineScope(lifetime + dispatcher)
    internal val ready = CompletableDeferred<Unit>(lifetime)
    internal val bindingRequests = Mutex()
    internal val requests = Mutex()
    internal val refreshHints = Channel<Unit>(Channel.CONFLATED)
    internal var started = false
    internal var roundId: String? = null
    internal val seenSequences = linkedSetOf<Long>()
}

/** REST owns game state; each account visit owns its requests, drawing and subscriptions. */
@Singleton
class LiveDrawGuessRepository internal constructor(
    private val api: LiveDrawGuessApi,
    private val captureOwner: () -> AuthSessionLease,
    private val tokenForOwner: suspend (String) -> String?,
    private val commitOwner: (AuthSessionLease, () -> Unit) -> Boolean,
    private val realtime: UbuntuRealtimeClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val accountVisitChanges: Flow<Long> = emptyFlow(),
) {
    @Inject constructor(api: LocalFallbackApiClient, auth: AuthService, realtime: UbuntuRealtimeClient) : this(
        api, auth::captureAccountLease, auth::getAccessTokenForAccountAwait, auth::commitForAccountLease,
        realtime, accountVisitChanges = auth.accountVisitChanges,
    )
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    @Volatile private var binding: LiveDrawGuessBinding? = null
    private var subscribedBinding: LiveDrawGuessBinding? = null
    private val _state = MutableStateFlow<LiveDrawGuessState?>(null)
    val state = _state.asStateFlow()
    private val _drawPackets = MutableStateFlow<List<LiveDrawPacket>>(emptyList())
    val drawPackets = _drawPackets.asStateFlow()

    init { scope.launch { accountVisitChanges.collect { invalidateAccount() } } }

    private fun invalidateAccount() {
        val current = binding ?: return
        if (!current.owner.isCurrent()) unbind(current)
    }

    /** Capture the account synchronously, before the UI can enqueue any request. */
    internal fun prepareBinding(liveId: String, replace: Boolean = false): LiveDrawGuessBinding? {
        val normalized = normalizeLiveSessionId(liveId) ?: return null
        val owner = captureOwner()
        if (!owner.isCurrent()) { invalidateAccount(); return null }
        var selected: LiveDrawGuessBinding? = null
        var previous: LiveDrawGuessBinding? = null
        commitOwner(owner) {
            synchronized(lock) {
                val current = binding
                if (!replace && current?.liveId == normalized && current.owner.sameVisit(owner) && current.lifetime.isActive) selected = current
                else {
                    previous = current
                    selected = LiveDrawGuessBinding(normalized, owner, scope.coroutineContext[Job], ioDispatcher)
                    binding = selected
                    _drawPackets.value = emptyList()
                    _state.value = null
                }
            }
        }
        previous?.let(::release)
        return selected
    }

    private fun commit(current: LiveDrawGuessBinding, update: () -> Unit): Boolean {
        if (binding !== current) return false
        var applied = false
        commitOwner(current.owner) {
            synchronized(lock) {
                if (binding === current && current.lifetime.isActive) { update(); applied = true }
            }
        }
        return applied
    }

    private fun requireCurrent(current: LiveDrawGuessBinding?) {
        if (current == null) return
        current.owner.requireCurrent()
        if (binding !== current || !current.lifetime.isActive) throw CancellationException("La visita al juego cambió.")
    }

    internal fun commitForBinding(current: LiveDrawGuessBinding, update: () -> Unit): Boolean = commit(current, update)

    suspend fun bind(liveId: String) {
        prepareBinding(liveId)?.let { bind(it) }
    }

    internal suspend fun bind(current: LiveDrawGuessBinding) {
        current.bindingRequests.withLock {
            requireCurrent(current)
            if (current.started) return
            try {
                notificationAccountCall(current.owner, tokenForOwner) { _, token ->
                    currentCoroutineContext().ensureActive()
                    requireCurrent(current)
                    var eventJob: Job? = null
                    var pollJob: Job? = null
                    if (!commit(current) {
                        current.started = true
                        // Children are registered with the visit before they can start.
                        // Cancelling that visit also cancels children that are still lazy.
                        eventJob = current.scope.launch(start = CoroutineStart.LAZY) {
                            realtime.eventsOnSubscription {
                                if (!commit(current) {
                                    subscribedBinding?.let { if (it !== current) realtime.unsubscribe(channel(it.liveId)) }
                                    realtime.updateAccessToken(token)
                                    realtime.subscribe(channel(current.liveId))
                                    subscribedBinding = current
                                }) throw CancellationException("La visita al juego cambió.")
                                current.ready.complete(Unit)
                            }.collect { envelope ->
                                requireCurrent(current)
                                if (envelope.channel != channel(current.liveId) || envelope.event != "live_draw_guess") return@collect
                                if (envelope.payload["type"]?.jsonPrimitive?.contentOrNull == "draw_packet") {
                                    val packet = envelope.payload["packet"]?.let { runCatching { json.decodeFromJsonElement<LiveDrawPacket>(it) }.getOrNull() }
                                        ?: return@collect
                                    commit(current) {
                                        val state = _state.value ?: return@commit
                                        if (realtime.isCurrent(envelope) && liveDrawPacketMatchesState(state.game.id, state.round?.id,
                                            envelope.payload["game_id"]?.jsonPrimitive?.contentOrNull,
                                            envelope.payload["round_id"]?.jsonPrimitive?.contentOrNull)) addPacket(current, packet)
                                    }
                                } else current.refreshHints.trySend(Unit)
                            }
                        }
                        eventJob?.invokeOnCompletion { error ->
                            if (!current.ready.isCompleted) current.ready.completeExceptionally(error ?: CancellationException("La suscripción terminó."))
                        }
                        pollJob = current.scope.launch(start = CoroutineStart.LAZY) {
                            current.ready.await()
                            while (isActive) {
                                withTimeoutOrNull(2_000L) { current.refreshHints.receive() }
                                if (!current.owner.isCurrent() || binding !== current) { unbind(current); break }
                                refresh(current.liveId, current)
                            }
                        }
                    }) throw CancellationException("La visita al juego cambió.")
                    eventJob?.start()
                    pollJob?.start()
                    current.ready.await() // Register the collector before the first REST snapshot.
                    Result.success(Unit)
                }.getOrThrow()
                refresh(current.liveId, current).getOrThrow()
            } catch (cancelled: CancellationException) {
                unbind(current)
                throw cancelled
            }
        }
    }

    fun unbind() { binding?.let(::unbind) }

    internal fun unbind(current: LiveDrawGuessBinding) {
        synchronized(lock) {
            if (binding !== current) return
            binding = null
            _drawPackets.value = emptyList()
            _state.value = null
        }
        release(current)
    }

    private fun release(current: LiveDrawGuessBinding) {
        current.lifetime.cancel()
        current.refreshHints.close()
        // Never borrow the next account to clean up the previous account's room.
        commitOwner(current.owner) {
            synchronized(lock) {
                if (subscribedBinding === current) {
                    realtime.unsubscribe(channel(current.liveId))
                    subscribedBinding = null
                }
            }
        }
        if (!current.owner.isCurrent()) synchronized(lock) {
            if (subscribedBinding === current) subscribedBinding = null
        }
    }

    internal suspend fun refresh(liveId: String = binding?.liveId.orEmpty(), expected: LiveDrawGuessBinding? = binding): Result<LiveDrawGuessState?> =
        authenticated(liveId, expected) { token, normalized ->
            api.loadCurrentLiveDrawGuess(token, normalized).onSuccess { value ->
                currentCoroutineContext().ensureActive(); requireCurrent(expected)
                expected?.let { commit(it) { applyState(it, value) } }
            }
        }

    internal suspend fun create(liveId: String, participantIds: List<String>, totalRounds: Int = 3, roundSeconds: Int = 75,
        expected: LiveDrawGuessBinding? = binding): Result<LiveDrawGuessState> =
        authenticated(liveId, expected) { token, normalized ->
            api.createLiveDrawGuess(token, normalized, participantIds, totalRounds, roundSeconds)
                .onSuccess { value -> currentCoroutineContext().ensureActive(); requireCurrent(expected); expected?.let { commit(it) { applyState(it, value) } } }
        }

    internal suspend fun accept(liveId: String, gameId: String, expected: LiveDrawGuessBinding? = binding): Result<LiveDrawGuessState> =
        authenticated(liveId, expected) { token, normalized -> api.acceptLiveDrawGuess(token, normalized, gameId)
            .onSuccess { value -> currentCoroutineContext().ensureActive(); requireCurrent(expected); expected?.let { commit(it) { applyState(it, value) } } } }

    internal suspend fun start(liveId: String, gameId: String, expected: LiveDrawGuessBinding? = binding): Result<LiveDrawGuessState> =
        authenticated(liveId, expected) { token, normalized -> api.startLiveDrawGuess(token, normalized, gameId)
            .onSuccess { value -> currentCoroutineContext().ensureActive(); requireCurrent(expected); expected?.let { commit(it) { applyState(it, value) } } } }

    internal suspend fun cancel(liveId: String, gameId: String, expected: LiveDrawGuessBinding? = binding): Result<LiveDrawGuessState> =
        authenticated(liveId, expected) { token, normalized -> api.cancelLiveDrawGuess(token, normalized, gameId)
            .onSuccess { value -> currentCoroutineContext().ensureActive(); requireCurrent(expected); expected?.let { commit(it) { applyState(it, value) } } } }

    internal suspend fun advance(liveId: String, gameId: String, expected: LiveDrawGuessBinding? = binding): Result<LiveDrawGuessState> =
        authenticated(liveId, expected) { token, normalized -> api.advanceLiveDrawGuess(token, normalized, gameId)
            .onSuccess { value -> currentCoroutineContext().ensureActive(); requireCurrent(expected); expected?.let { commit(it) { applyState(it, value) } } } }

    internal suspend fun guess(liveId: String, gameId: String, guess: String, expected: LiveDrawGuessBinding? = binding): Result<LiveDrawGuessGuessResult> =
        authenticated(liveId, expected) { token, normalized -> api.submitLiveDrawGuess(token, normalized, gameId, guess)
            .onSuccess { value -> currentCoroutineContext().ensureActive(); requireCurrent(expected); expected?.let { commit(it) { applyState(it, value.state) } } } }

    internal suspend fun draw(liveId: String, gameId: String, packet: LiveDrawPacket, expected: LiveDrawGuessBinding? = binding,
        expectedRoundId: String? = null): Result<LiveDrawPacketReceipt> {
        val roundId = expectedRoundId ?: synchronized(lock) { _state.value?.round?.id }
        return authenticated(liveId, expected, serialize = false) { token, normalized ->
            if (expected != null && synchronized(lock) { _state.value?.round?.id != roundId || _state.value?.game?.id != gameId }) {
                return@authenticated Result.failure(IllegalStateException("La ronda cambió. Vuelve a dibujar."))
            }
            api.sendLiveDrawPacket(token, normalized, gameId, packet).onSuccess { receipt ->
                currentCoroutineContext().ensureActive(); requireCurrent(expected)
                if (receipt.accepted && expected != null) commit(expected) {
                    if (_state.value?.game?.id == gameId && _state.value?.round?.id == roundId) addPacket(expected, packet)
                }
            }
        }
    }

    private suspend fun <T> authenticated(liveId: String, expected: LiveDrawGuessBinding?, serialize: Boolean = true,
        request: suspend (String, String) -> Result<T>): Result<T> {
        val normalized = normalizeLiveSessionId(liveId)
            ?: return Result.failure(IllegalArgumentException("El identificador del live no es válido."))
        if (expected != null && expected.liveId != normalized) throw CancellationException("La visita al juego cambió.")
        val owner = expected?.owner ?: captureOwner()
        val action: suspend () -> Result<T> = {
            notificationAccountCall(owner, tokenForOwner) { _, token ->
                requireCurrent(expected)
                val result = request(token, normalized)
                currentCoroutineContext().ensureActive()
                requireCurrent(expected)
                result
            }
        }
        return if (serialize && expected != null) expected.requests.withLock { requireCurrent(expected); action() } else action()
    }

    private fun applyState(current: LiveDrawGuessBinding, value: LiveDrawGuessState?) {
        if (current.roundId != value?.round?.id) {
            current.roundId = value?.round?.id
            current.seenSequences.clear()
            _drawPackets.value = emptyList()
        }
        _state.value = value
    }

    private fun addPacket(current: LiveDrawGuessBinding, packet: LiveDrawPacket) {
        if (!current.seenSequences.add(packet.sequence)) return
        while (current.seenSequences.size > 1_024) current.seenSequences.remove(current.seenSequences.first())
        _drawPackets.value = (_drawPackets.value + packet).takeLast(512)
    }

    private fun channel(liveId: String) = "live-game:$liveId"
}

internal fun liveDrawPacketMatchesState(currentGameId: String, currentRoundId: String?, eventGameId: String?, eventRoundId: String?): Boolean =
    currentGameId.isNotBlank() && !currentRoundId.isNullOrBlank() && currentGameId == eventGameId && currentRoundId == eventRoundId
