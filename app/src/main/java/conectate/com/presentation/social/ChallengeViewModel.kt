package conectate.com.presentation.social

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.ActiveChallenge
import conectate.com.data.model.ChallengeActionException
import conectate.com.data.repository.ChallengeRepository
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.components.Line
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ChallengeViewModel @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val authService: AuthService,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val controller = ChallengeController(viewModelScope, authService::captureAccountLease,
        object : ChallengeDataSource {
            override suspend fun round(owner: String) = challengeRepository.getActiveChallenge(owner)
            override suspend fun submissions(owner: String, round: ActiveChallenge) =
                challengeRepository.getSubmissions(round.id, round.legacyStartTime(), owner)
            override suspend fun participated(owner: String, round: ActiveChallenge) =
                challengeRepository.checkParticipation(round.id, round.legacyStartTime(), round.challengeId, owner)
            override suspend fun vote(owner: String, round: ActiveChallenge, id: String, desired: Boolean) =
                challengeRepository.toggleVote(round.id, id, desired, owner)
            override suspend fun mine(owner: String) = challengeRepository.getUserChallenges(owner)
        })

    val state = controller.state
    fun visibleState(snapshot: ChallengeUiState) = controller.visibleState(snapshot)
    fun setVisible(visible: Boolean) = controller.setVisible(visible)
    fun beginEntry() = controller.entry()
    fun loadActiveChallenge() = controller.refresh()
    fun refreshChallengeData() = controller.refresh()
    fun loadSubmissions() = controller.loadSubmissions()
    fun loadUserChallenges() = controller.loadMine()
    fun toggleChallengeVote(id: String) = controller.toggleVote(id)

    suspend fun submitText(entry: ChallengeEntry, text: String) = send(entry, "¡Respuesta enviada!") { round, owner ->
        if (text.isBlank() || text.length > 500) Result.failure(ChallengeActionException("Escribe una respuesta de hasta 500 caracteres."))
        else challengeRepository.submitText(round, text.trim(), owner)
    }

    suspend fun submitDrawing(entry: ChallengeEntry, lines: List<Line>) = send(entry, "¡Dibujo enviado!") { round, owner ->
        challengeRepository.submitDrawing(round, lines, owner)
    }

    suspend fun submitAudio(entry: ChallengeEntry, file: File) = send(entry, "¡Audio enviado!") { round, owner ->
        challengeRepository.submitAudio(round, file, owner)
    }

    suspend fun submitChallengeMediaFromUri(entry: ChallengeEntry, uri: Uri) = send(entry, "¡Archivo enviado!") { round, owner ->
        withContext(Dispatchers.IO) {
            val declared = conectate.com.util.canonicalizeMediaContentType(context.contentResolver.getType(uri))
            val mime = declared?.takeUnless { it == "application/octet-stream" }
                ?: conectate.com.util.guessMimeTypeFromUri(uri) ?: "application/octet-stream"
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            challengeRepository.submitMedia(round, uri, mime, extension, owner)
        }
    }

    private suspend fun send(entry: ChallengeEntry, message: String, operation: suspend (ActiveChallenge, String) -> Result<Unit>): Result<Unit> =
        controller.submit(entry, operation).also { feedback(it, message, "No pudimos enviar tu respuesta. Conserva el contenido y reintenta.") }

    suspend fun createChallenge(title: String, description: String, type: String, difficulty: String = "medium"): Result<Unit> =
        controller.mutate { owner -> challengeRepository.createChallengeMaster(title.trim(), description.trim(), type, difficulty, owner) }
            .also { feedback(it, "¡Ronda creada!", "No pudimos crear la ronda. Reintenta."); if (it.isSuccess) controller.loadMine() }

    suspend fun deleteUserChallenge(id: String): Result<Unit> =
        controller.mutate { owner -> challengeRepository.deleteChallengeMaster(id, owner) }
            .also { feedback(it, "Ronda eliminada", "No pudimos eliminar la ronda. Reintenta."); if (it.isSuccess) controller.loadMine() }

    suspend fun passTorch(entry: ChallengeEntry, friendId: String): Result<Unit> = controller.mutate { owner ->
        entry.lease.requireCurrent()
        if (!sameRound(state.value.active, entry.challenge) || state.value.active?.status != "active" ||
            remainingSeconds(entry.challenge, System.currentTimeMillis()) == 0)
            Result.failure(ChallengeActionException("La ronda terminó. Abre la siguiente antes de invitar."))
        else challengeRepository.passTorch(entry.challenge.challenge.title, friendId, owner)
    }.also { feedback(it, "¡Invitación enviada!", "No pudimos enviar la invitación. Reintenta.") }

    private fun feedback(result: Result<Unit>, success: String, fallback: String) {
        if (result.isSuccess) android.widget.Toast.makeText(context, success, android.widget.Toast.LENGTH_SHORT).show()
        else AppFeedbackBus.showError(challengeError(result.exceptionOrNull(), fallback))
    }

    override fun onCleared() { controller.close(); super.onCleared() }

    init {
        viewModelScope.launch { authService.observeSession().collect { controller.sessionChanged() } }
    }
}

private fun ActiveChallenge.legacyStartTime(): String? = runCatching {
    java.time.Instant.parse(expiresAt).minusSeconds(16 * 60).toString()
}.getOrNull()
