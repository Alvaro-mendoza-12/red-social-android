package conectate.com.presentation.live

import conectate.com.data.model.LivePointRedemption
import conectate.com.data.model.LivePointReward
import conectate.com.data.model.LivePointsSettings
import conectate.com.data.points.LiveChannelPointsApiException
import conectate.com.data.repository.LiveChannelPointsRepository
import conectate.com.data.repository.normalizeLiveSessionId
import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LiveChannelPointsUiState(
    val available: Boolean? = null,
    val sessionActive: Boolean = false,
    val foreground: Boolean = true,
    val balance: Int = 0,
    val settings: LivePointsSettings? = null,
    val rewards: List<LivePointReward> = emptyList(),
    val redemptions: List<LivePointRedemption> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

internal class LiveChannelPointsController(
    private val repository: LiveChannelPointsRepository,
    private val scope: CoroutineScope,
    private val captureOwner: () -> AuthSessionLease,
    private val commitOwner: (AuthSessionLease, () -> Unit) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    accountVisitChanges: Flow<Long> = emptyFlow(),
) {
    private class Visit(val liveId: String, val owner: AuthSessionLease) {
        val requests = Mutex()
        var requestJob: Job? = null
        var sessionJob: Job? = null
        var sessionStarting = false
        var startRequested = false
        var queuedRefresh: Boolean? = null
    }

    private val lock = Any()
    private val _state = MutableStateFlow(LiveChannelPointsUiState())
    val state = _state.asStateFlow()
    @Volatile private var visit: Visit? = null
    @Volatile private var previousVisit: Visit? = null

    init { scope.launch(ioDispatcher) { accountVisitChanges.collect { invalidateAccount() } } }

    private fun invalidateAccount() {
        val old = visit ?: previousVisit ?: return
        if (!old.owner.isCurrent()) stop(old, clear = true)
    }

    private fun prepare(liveId: String): Visit? {
        val normalized = normalizeLiveSessionId(liveId) ?: return null
        val owner = captureOwner()
        if (!owner.isCurrent()) { invalidateAccount(); return null }
        var selected: Visit? = null
        var old: Visit? = null
        commitOwner(owner) {
            synchronized(lock) {
                val current = visit
                if (current?.liveId == normalized && current.owner.sameVisit(owner)) selected = current
                else {
                    old = current
                    val sameVisit = previousVisit?.let { it.liveId == normalized && it.owner.sameVisit(owner) } == true
                    selected = Visit(normalized, owner)
                    visit = selected
                    previousVisit = selected
                    if (!sameVisit) {
                        _state.value = LiveChannelPointsUiState()
                    } else _state.value = _state.value.copy(busy = false, sessionActive = false)
                }
            }
        }
        old?.requestJob?.cancel()
        old?.sessionJob?.cancel()
        return selected
    }

    private fun commit(current: Visit, action: () -> Unit): Boolean {
        if (visit !== current) return false
        var applied = false
        commitOwner(current.owner) {
            synchronized(lock) {
                if (visit === current) { action(); applied = true }
            }
        }
        return applied
    }

    private suspend fun ensureCurrent(current: Visit) {
        currentCoroutineContext().ensureActive()
        current.owner.requireCurrent()
        if (visit !== current) throw CancellationException("El panel de puntos cambió.")
    }

    private fun stop(expected: Visit? = null, clear: Boolean = false) {
        var old: Visit? = null
        synchronized(lock) {
            if (expected != null && visit !== expected && previousVisit !== expected) return
            old = visit ?: previousVisit
            visit = null
            old?.queuedRefresh = null
            old?.startRequested = false
            if (clear) {
                previousVisit = null
                _state.value = LiveChannelPointsUiState()
            } else _state.value = _state.value.copy(busy = false, sessionActive = false)
        }
        old?.requestJob?.cancel()
        old?.sessionJob?.cancel()
    }

    fun stopChannelPoints() = stop()

    fun setChannelPointsForeground(foreground: Boolean) {
        val current = visit ?: previousVisit
        if (current == null) _state.value = _state.value.copy(foreground = foreground)
        else commitOwner(current.owner) {
            synchronized(lock) {
                if (visit === current || previousVisit === current) _state.value = _state.value.copy(foreground = foreground)
            }
        }
    }

    fun refreshChannelPoints(liveId: String, includeRedemptions: Boolean = true) {
        prepare(liveId)?.let { refresh(it, includeRedemptions) }
    }

    private fun refresh(current: Visit, includeRedemptions: Boolean) {
        request(current, coalesce = includeRedemptions) { refreshState(current, includeRedemptions) }
    }

    private fun request(current: Visit, coalesce: Boolean? = null, action: suspend () -> Unit) {
        var accepted = false
        if (!commit(current) {
            if (_state.value.busy) {
                if (coalesce != null) current.queuedRefresh = (current.queuedRefresh ?: false) || coalesce
            } else {
                _state.value = _state.value.copy(busy = true, message = null)
                accepted = true
            }
        } || !accepted) return
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            try {
                current.requests.withLock { ensureCurrent(current); action() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { commit(current) { applyError(error) } }
            finally {
                var pendingRefresh: Boolean? = null
                var shouldStart = false
                commit(current) {
                    current.requestJob = null
                    _state.value = _state.value.copy(busy = false)
                    pendingRefresh = current.queuedRefresh
                    current.queuedRefresh = null
                    shouldStart = current.startRequested
                    current.startRequested = false
                }
                if (!current.owner.isCurrent()) stop(current, clear = true)
                if (shouldStart) start(current)
                pendingRefresh?.let { refresh(current, it) }
            }
        }
        if (commit(current) { current.requestJob = job }) job.start() else job.cancel()
    }

    private suspend fun refreshState(current: Visit, includeRedemptions: Boolean) {
        ensureCurrent(current)
        val balance = repository.balance(current.liveId, current.owner).getOrThrow()
        ensureCurrent(current)
        val rewards = repository.rewards(current.liveId, current.owner).getOrThrow()
        ensureCurrent(current)
        val redemptions = if (includeRedemptions) repository.redemptions(current.liveId, current.owner).getOrThrow().redemptions
            else _state.value.redemptions
        ensureCurrent(current)
        commit(current) {
            _state.value = _state.value.copy(available = true, balance = balance.balance.balance,
                settings = balance.settings, rewards = rewards.rewards, redemptions = redemptions)
        }
    }

    fun startChannelPoints(liveId: String) { prepare(liveId)?.let(::start) }

    private fun start(current: Visit) {
        var accepted = false
        if (!commit(current) {
            if (current.sessionJob?.isActive != true && !current.sessionStarting) {
                if (_state.value.busy) current.startRequested = true
                else {
                    current.sessionStarting = true
                    current.startRequested = false
                    _state.value = _state.value.copy(busy = true)
                    accepted = true
                }
            }
        } || !accepted) return
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            try {
                val session = current.requests.withLock {
                    refreshState(current, false)
                    ensureCurrent(current)
                    repository.startSession(current.liveId, current.owner).getOrThrow()
                }
                ensureCurrent(current)
                var queued: Boolean? = null
                commit(current) {
                    current.sessionStarting = false
                    _state.value = _state.value.copy(available = true, busy = false, sessionActive = true,
                        message = "Gana ${session.pointsPerInterval} puntos cada ${session.intervalSeconds / 60} min mientras miras.")
                    queued = current.queuedRefresh
                    current.queuedRefresh = null
                }
                queued?.let { refresh(current, it) }
                while (isActive) {
                    delay(30_000L)
                    val result = current.requests.withLock {
                        ensureCurrent(current)
                        val foreground = _state.value.foreground
                        repository.heartbeat(current.liveId, session.sessionToken, foreground, foreground, current.owner)
                    }
                    ensureCurrent(current)
                    commit(current) {
                        result.onSuccess { heartbeat ->
                            _state.value = _state.value.copy(balance = heartbeat.balance ?: _state.value.balance,
                                message = when {
                                    heartbeat.awardedPoints > 0 -> "+${heartbeat.awardedPoints} puntos por mirar"
                                    heartbeat.dailyCapReached -> "Alcanzaste el límite diario de puntos"
                                    else -> _state.value.message
                                })
                        }.onFailure(::applyError)
                    }
                    if (_state.value.available == false) break
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { commit(current) { applyError(error) } }
            finally {
                var queued: Boolean? = null
                commit(current) {
                    current.sessionJob = null
                    _state.value = _state.value.copy(sessionActive = false,
                        busy = if (current.sessionStarting) false else _state.value.busy)
                    current.sessionStarting = false
                    queued = current.queuedRefresh
                    current.queuedRefresh = null
                }
                if (!current.owner.isCurrent()) stop(current, clear = true)
                queued?.let { refresh(current, it) }
            }
        }
        if (commit(current) { current.sessionJob = job }) job.start() else job.cancel()
    }

    fun redeemChannelPointReward(liveId: String, rewardId: String) {
        val current = prepare(liveId) ?: return
        request(current) {
            ensureCurrent(current)
            val response = repository.redeem(current.liveId, rewardId, current.owner).getOrThrow()
            ensureCurrent(current)
            commit(current) {
                _state.value = _state.value.copy(balance = response.balance ?: _state.value.balance,
                    message = "Recompensa solicitada al creador")
            }
            refreshState(current, false)
        }
    }

    fun updateChannelPointSettings(liveId: String, enabled: Boolean, interval: Int, points: Int, cap: Int) {
        val current = prepare(liveId) ?: return
        request(current) {
            val response = repository.updateSettings(current.liveId, enabled, interval, points, cap, current.owner).getOrThrow()
            ensureCurrent(current)
            commit(current) { _state.value = _state.value.copy(settings = response.settings, message = "Puntos actualizados") }
        }
    }

    fun createChannelPointReward(liveId: String, title: String, description: String, cost: Int, stock: Int?, cooldown: Int) {
        val current = prepare(liveId) ?: return
        request(current) {
            repository.createReward(current.liveId, title, description, cost, stock, cooldown, current.owner).getOrThrow()
            refreshState(current, true)
        }
    }

    fun decideChannelPointRedemption(liveId: String, redemptionId: String, status: String) {
        val current = prepare(liveId) ?: return
        request(current) {
            repository.updateRedemption(current.liveId, redemptionId, status, current.owner).getOrThrow()
            refreshState(current, true)
        }
    }

    private fun applyError(error: Throwable?) {
        val code = (error as? LiveChannelPointsApiException)?.code ?: error?.message.orEmpty()
        if (code == "LIVE_CHANNEL_POINTS_DISABLED" || code == "LIVE_POINTS_DISABLED_FOR_LIVE") {
            _state.value = _state.value.copy(available = false, sessionActive = false, message = null)
            return
        }
        _state.value = _state.value.copy(message = when (code) {
            "LIVE_POINT_BALANCE_INSUFFICIENT" -> "No tienes puntos suficientes"
            "LIVE_POINT_REWARD_COOLDOWN" -> "Esta recompensa todavía está en espera"
            "LIVE_POINT_REWARD_UNAVAILABLE" -> "La recompensa ya no está disponible"
            "LIVE_POINT_REDEMPTION_NONCE_CONFLICT" -> "No se pudo verificar el canje anterior. Revisa tu historial antes de reintentar."
            "LIVE_POINTS_NOT_CONFIGURED" -> "Los puntos aún no están configurados en el servidor"
            else -> "No se pudieron actualizar los puntos del canal"
        })
    }
}
