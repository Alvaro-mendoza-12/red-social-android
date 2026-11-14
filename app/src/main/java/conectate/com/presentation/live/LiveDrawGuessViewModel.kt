package conectate.com.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.LiveDrawPoint
import conectate.com.data.model.LiveDrawGuessGuessResult
import conectate.com.data.model.LiveDrawPacket
import conectate.com.data.repository.LiveDrawGuessBinding
import conectate.com.data.repository.LiveDrawGuessRepository
import conectate.com.data.repository.normalizeLiveSessionId
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@HiltViewModel
class LiveDrawGuessViewModel internal constructor(
    private val repository: LiveDrawGuessRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(repository: LiveDrawGuessRepository) : this(repository, Dispatchers.IO)

    private class Visit(val binding: LiveDrawGuessBinding, val tasks: CoroutineScope)
    private val lock = Any()
    @Volatile private var visit: Visit? = null
    val gameState = repository.state
    val drawPackets = repository.drawPackets
    val accountVisitGeneration = repository.accountVisitChanges.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _guessResult = MutableStateFlow<LiveDrawGuessGuessResult?>(null)
    val guessResult = _guessResult.asStateFlow()
    private val drawSequence = AtomicLong(0L)

    init {
        viewModelScope.launch {
            repository.accountVisitChanges.collect {
                visit?.let { if (!it.binding.owner.isCurrent()) unbind(it.binding) }
            }
        }
    }

    internal fun bind(liveId: String): LiveDrawGuessBinding? {
        val normalized = normalizeLiveSessionId(liveId) ?: return null
        visit?.let { current ->
            if (current.binding.liveId == normalized && commit(current) {}) return current.binding
        }
        unbind()
        val binding = repository.prepareBinding(normalized, replace = true) ?: return null
        val current = Visit(binding, CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + ioDispatcher))
        if (!repository.commitForBinding(binding) {
            synchronized(lock) { visit = current }
        }) {
            current.tasks.cancel()
            repository.unbind(binding)
            return null
        }
        current.tasks.launch {
            try {
                repository.bind(binding)
                commit(current) {
                    gameState.value?.round?.lastDrawSequence?.let { next -> drawSequence.updateAndGet { maxOf(it, next + 1L) } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { commit(current) { _error.value = error.toLiveDrawGuessMessage("No se pudo cargar el juego.") } }
        }
        return binding
    }

    internal fun unbind(expected: LiveDrawGuessBinding? = visit?.binding) {
        val previous = synchronized(lock) {
            val current = visit ?: return
            if (expected == null || current.binding !== expected) return
            visit = null
            _busy.value = false
            _error.value = null
            _guessResult.value = null
            drawSequence.set(0L)
            current
        }
        // Lock order is auth -> repository -> view model. Release this monitor
        // before cancelling work or asking the repository to clear its binding.
        previous.tasks.cancel()
        repository.unbind(previous.binding)
    }

    private fun commit(current: Visit, update: () -> Unit): Boolean {
        var applied = false
        repository.commitForBinding(current.binding) {
            synchronized(lock) {
                if (visit === current) { update(); applied = true }
            }
        }
        return applied
    }

    internal fun isCurrentVisit(liveId: String, accountId: String): Boolean {
        val current = visit ?: return false
        if (current.binding.liveId != liveId || !current.binding.owner.accountId.equals(accountId, ignoreCase = true)) return false
        return commit(current) {}
    }

    fun create(participantIds: List<String>, totalRounds: Int = 3, roundSeconds: Int = 75) {
        val current = visit ?: return
        launchAction(current, "No se pudo crear el juego.") {
            repository.create(current.binding.liveId, participantIds, totalRounds, roundSeconds, current.binding)
        }
    }

    fun accept() = gameAction("No se pudo aceptar el juego.") { current, gameId -> repository.accept(current.liveId, gameId, current) }
    fun start() = gameAction("No se pudo iniciar el juego.") { current, gameId -> repository.start(current.liveId, gameId, current) }
    fun advance() = gameAction("La ronda todavía no puede avanzar.") { current, gameId -> repository.advance(current.liveId, gameId, current) }
    fun cancel() = gameAction("No se pudo cancelar el juego.") { current, gameId -> repository.cancel(current.liveId, gameId, current) }

    private fun <T> gameAction(fallback: String, action: suspend (LiveDrawGuessBinding, String) -> Result<T>) {
        val current = visit ?: return
        val gameId = gameState.value?.game?.id?.takeIf(String::isNotBlank) ?: return
        launchAction(current, fallback) { action(current.binding, gameId) }
    }

    fun submitGuess(value: String) {
        val current = visit ?: return
        val clean = value.trim().takeIf(String::isNotBlank) ?: return
        val state = gameState.value ?: return
        launchAction(current, "No se pudo comprobar la respuesta.", clearGuess = true, onSuccess = {
            if (gameState.value?.round?.id == state.round?.id && it.state.round?.id == state.round?.id) _guessResult.value = it
        }) { repository.guess(current.binding.liveId, state.game.id, clean, current.binding) }
    }

    fun sendStroke(points: List<LiveDrawPoint>) {
        if (points.isEmpty()) return
        val current = visit ?: return
        val state = gameState.value ?: return
        val round = state.round ?: return
        if (state.game.id.isBlank()) return
        var packet: LiveDrawPacket? = null
        if (!commit(current) {
            drawSequence.updateAndGet { maxOf(it, round.lastDrawSequence + 1L) }
            packet = LiveDrawPacket(sequence = drawSequence.getAndIncrement(), points = points.take(64))
        }) return
        current.tasks.launch {
            try {
                repository.draw(current.binding.liveId, state.game.id, packet ?: return@launch, current.binding, round.id)
                    .onFailure { error -> commit(current) {
                        if (gameState.value?.round?.id == round.id) _error.value = error.toLiveDrawGuessMessage("No se pudo enviar el trazo.")
                    } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { commit(current) {
                if (gameState.value?.round?.id == round.id) _error.value = error.toLiveDrawGuessMessage("No se pudo enviar el trazo.")
            } }
        }
    }

    fun clearFeedback() {
        val current = visit ?: return
        commit(current) { _error.value = null; _guessResult.value = null }
    }

    private fun <T> launchAction(current: Visit, fallback: String, clearGuess: Boolean = false, onSuccess: (T) -> Unit = {}, action: suspend () -> Result<T>) {
        var accepted = false
        if (!commit(current) {
            if (!_busy.value) {
                _busy.value = true; _error.value = null
                if (clearGuess) _guessResult.value = null
                accepted = true
            }
        } || !accepted) return
        current.tasks.launch {
            try {
                val result = action()
                ensureActive()
                commit(current) { result.onSuccess(onSuccess).onFailure { _error.value = it.toLiveDrawGuessMessage(fallback) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { commit(current) { _error.value = error.toLiveDrawGuessMessage(fallback) } }
            finally { commit(current) { _busy.value = false } }
        }
    }

    override fun onCleared() { unbind(); super.onCleared() }
}

internal fun Throwable?.toLiveDrawGuessMessage(fallback: String): String {
    val raw = this?.message.orEmpty()
    return when {
        raw.contains("LIVE_DRAW_GUESS_DISABLED", ignoreCase = true) ->
            "Este juego todavía no está habilitado."
        raw.contains("LIVE_DRAW_PARTICIPANT_NOT_CONNECTED", ignoreCase = true) ->
            "Debes permanecer unido al directo para jugar."
        raw.contains("LIVE_DRAW_PARTICIPANTS_REQUIRED", ignoreCase = true) ->
            "Conecta e invita al menos a una persona antes de crear la partida."
        raw.contains("LIVE_DRAW_TOO_MANY_PARTICIPANTS", ignoreCase = true) ->
            "La partida supera el máximo de participantes permitido."
        raw.contains("LIVE_DRAW_GAME_ALREADY_OPEN", ignoreCase = true) ->
            "Ya hay una partida abierta en este directo."
        raw.contains("LIVE_DRAW_INVITATION_REQUIRED", ignoreCase = true) ->
            "El anfitrión debe invitarte antes de que puedas entrar."
        raw.contains("LIVE_DRAW_NOT_ENOUGH_PLAYERS", ignoreCase = true) ->
            "Se necesitan al menos dos participantes conectados."
        raw.contains("LIVE_DRAW_DRAWER_REQUIRED", ignoreCase = true) ->
            "Solo la persona que dibuja puede enviar trazos."
        raw.contains("LIVE_DRAW_DRAWER_CANNOT_GUESS", ignoreCase = true) ->
            "Quien está dibujando no puede enviar respuestas."
        raw.contains("LIVE_DRAW_ROUND_EXPIRED", ignoreCase = true) ->
            "La ronda ya terminó. Espera la siguiente."
        raw.contains("LIVE_DRAW_ALREADY_GUESSED_CORRECTLY", ignoreCase = true) ->
            "Ya acertaste en esta ronda."
        raw.contains("LIVE_DRAW_GUESS_LIMIT_REACHED", ignoreCase = true) ||
            raw.contains("LIVE_DRAW_RATE_LIMITED", ignoreCase = true) ->
            "Espera un momento antes de volver a intentarlo."
        raw.contains("LIVE_DRAW_GUESS_PII_BLOCKED", ignoreCase = true) ->
            "No compartas datos personales en las respuestas."
        raw.contains("LIVE_DRAW_GUESS_MODERATION_BLOCKED", ignoreCase = true) ->
            "Esa respuesta fue bloqueada por moderación."
        raw.contains("LIVE_NOT_ACTIVE", ignoreCase = true) ||
            raw.contains("LIVE_DRAW_GAME_NOT_ACTIVE", ignoreCase = true) ->
            "El directo o la partida ya no está activo."
        raw.contains("LIVE_DRAW_GAME_NOT_FOUND", ignoreCase = true) ->
            "La partida ya no está disponible."
        else -> this.toUserFacingMessage(fallback)
    }
}
