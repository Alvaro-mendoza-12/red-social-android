package conectate.com.presentation.messages

import conectate.com.data.model.VoiceRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface VoiceRoomSearchState {
    data object Idle : VoiceRoomSearchState
    data object Searching : VoiceRoomSearchState
    data class Found(val room: VoiceRoom) : VoiceRoomSearchState
    data class Failed(val message: String) : VoiceRoomSearchState
}

/** Search only resolves room metadata. Admission and passwords are checked when joining. */
internal class VoiceRoomSearchController(
    private val scope: CoroutineScope,
    private val currentAccount: () -> String?,
    private val findRoom: suspend (String) -> Result<VoiceRoom>,
) {
    private val mutableState = MutableStateFlow<VoiceRoomSearchState>(VoiceRoomSearchState.Idle)
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var searchJob: Job? = null

    fun search(id: String) {
        reset()
        val cleanId = id.trim()
        if (cleanId.isEmpty()) {
            mutableState.value = VoiceRoomSearchState.Failed("Escribe el código de la sala.")
            return
        }
        val account = currentAccount()?.trim()?.takeIf(String::isNotEmpty) ?: return
        val attempt = generation
        mutableState.value = VoiceRoomSearchState.Searching
        searchJob = scope.launch {
            val result = try { findRoom(cleanId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
            if (attempt != generation) return@launch
            if (!account.equals(currentAccount()?.trim(), ignoreCase = true)) {
                mutableState.value = VoiceRoomSearchState.Idle
                return@launch
            }
            mutableState.value = result.fold(
                onSuccess = { VoiceRoomSearchState.Found(it) },
                onFailure = { VoiceRoomSearchState.Failed("No se pudo encontrar la sala. Revisa el código e intenta nuevamente.") },
            )
        }
    }

    fun reset() {
        generation++
        searchJob?.cancel()
        searchJob = null
        mutableState.value = VoiceRoomSearchState.Idle
    }
}
