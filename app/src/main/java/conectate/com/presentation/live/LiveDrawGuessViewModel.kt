package conectate.com.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.LiveDrawPoint
import conectate.com.data.model.LiveDrawGuessGuessResult
import conectate.com.data.model.LiveDrawPacket
import conectate.com.data.repository.LiveDrawGuessRepository
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LiveDrawGuessViewModel @Inject constructor(
    private val repository: LiveDrawGuessRepository
) : ViewModel() {
    val gameState = repository.state
    val drawPackets = repository.drawPackets

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _guessResult = MutableStateFlow<LiveDrawGuessGuessResult?>(null)
    val guessResult: StateFlow<LiveDrawGuessGuessResult?> = _guessResult.asStateFlow()
    private val drawSequence = AtomicLong(0L)
    private var boundLiveId: String? = null

    fun bind(liveId: String) {
        if (boundLiveId == liveId && liveId.isNotBlank()) return
        boundLiveId = liveId
        viewModelScope.launch(Dispatchers.IO) {
            repository.bind(liveId)
            repository.state.value?.round?.lastDrawSequence?.let { current ->
                drawSequence.updateAndGet { previous -> maxOf(previous, current + 1L) }
            }
        }
    }

    fun unbind() {
        boundLiveId = null
        viewModelScope.launch(Dispatchers.IO) { repository.unbind() }
    }

    fun create(participantIds: List<String>, totalRounds: Int = 3, roundSeconds: Int = 75) {
        val liveId = boundLiveId ?: return
        launchAction("No se pudo crear el juego.") {
            repository.create(liveId, participantIds, totalRounds, roundSeconds)
        }
    }

    fun accept() {
        val liveId = boundLiveId ?: return
        val gameId = gameState.value?.game?.id.orEmpty()
        if (gameId.isBlank()) return
        launchAction("No se pudo aceptar el juego.") { repository.accept(liveId, gameId) }
    }

    fun start() {
        val liveId = boundLiveId ?: return
        val gameId = gameState.value?.game?.id.orEmpty()
        if (gameId.isBlank()) return
        launchAction("No se pudo iniciar el juego.") { repository.start(liveId, gameId) }
    }

    fun advance() {
        val liveId = boundLiveId ?: return
        val gameId = gameState.value?.game?.id.orEmpty()
        if (gameId.isBlank()) return
        launchAction("La ronda todavía no puede avanzar.") { repository.advance(liveId, gameId) }
    }

    fun cancel() {
        val liveId = boundLiveId ?: return
        val gameId = gameState.value?.game?.id.orEmpty()
        if (gameId.isBlank()) return
        launchAction("No se pudo cancelar el juego.") { repository.cancel(liveId, gameId) }
    }

    fun submitGuess(value: String) {
        val clean = value.trim()
        val liveId = boundLiveId ?: return
        val gameId = gameState.value?.game?.id.orEmpty()
        if (gameId.isBlank() || clean.isBlank()) return
        _error.value = null
        _guessResult.value = null
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            repository.guess(liveId, gameId, clean)
                .onSuccess { _guessResult.value = it }
                .onFailure { _error.value = it.toLiveDrawGuessMessage("No se pudo comprobar la respuesta.") }
            _busy.value = false
        }
    }

    fun sendStroke(points: List<LiveDrawPoint>) {
        if (points.isEmpty()) return
        val liveId = boundLiveId ?: return
        val state = gameState.value ?: return
        val gameId = state.game.id
        if (gameId.isBlank() || state.round == null) return
        val firstSequence = state.round.lastDrawSequence + 1L
        drawSequence.updateAndGet { previous -> maxOf(previous, firstSequence) }
        val packet = LiveDrawPacket(
            sequence = drawSequence.getAndIncrement(),
            points = points.take(64)
        )
        viewModelScope.launch(Dispatchers.IO) {
            repository.draw(liveId, gameId, packet)
                .onFailure { _error.value = it.toLiveDrawGuessMessage("No se pudo enviar el trazo.") }
        }
    }

    fun clearFeedback() {
        _error.value = null
        _guessResult.value = null
    }

    private fun <T> launchAction(fallback: String, action: suspend () -> Result<T>) {
        if (_busy.value) return
        _error.value = null
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            action().onFailure { _error.value = it.toLiveDrawGuessMessage(fallback) }
            _busy.value = false
        }
    }
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
