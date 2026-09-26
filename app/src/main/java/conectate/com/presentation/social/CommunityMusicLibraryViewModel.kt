package conectate.com.presentation.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.music.CommunityMusicTrack
import conectate.com.data.music.MomentMusicCatalogRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class CommunityMusicLibraryState(
    val tracks: List<CommunityMusicTrack> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val removingId: String? = null,
    val error: String? = null,
)

@HiltViewModel
class CommunityMusicLibraryViewModel @Inject constructor(
    private val repository: MomentMusicCatalogRepository,
    private val auth: AuthService,
) : ViewModel() {
    private val _state = MutableStateFlow(CommunityMusicLibraryState())
    val state = _state.asStateFlow()
    private var owner = auth.getCurrentUserId()
    private var job: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            auth.currentSession.collect {
                if (owner != auth.getCurrentUserId()) {
                    owner = auth.getCurrentUserId()
                    generation++
                    job?.cancel()
                    _state.value = CommunityMusicLibraryState(error = "La cuenta cambió. Actualiza tus canciones.")
                }
            }
        }
    }

    fun refresh() = load(null)
    fun more() { _state.value.nextCursor?.let(::load) }

    private fun load(cursor: String?) {
        if (_state.value.loading || _state.value.removingId != null) return
        val request = ++generation
        val account = auth.getCurrentUserId()
        if (account.isNullOrBlank()) return
        owner = account
        _state.value = _state.value.copy(loading = true, error = null)
        job = viewModelScope.launch {
            try {
                val page = withTimeoutOrNull(15_000) { repository.myCommunityUploads(cursor).getOrThrow() }
                    ?: error("La consulta tardó demasiado.")
                if (request != generation || account != auth.getCurrentUserId()) return@launch
                _state.value = CommunityMusicLibraryState(
                    tracks = ((if (cursor == null) emptyList() else _state.value.tracks) + page.items).distinctBy { it.id },
                    nextCursor = page.nextCursor?.takeUnless { it == cursor },
                )
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (request == generation) _state.value = _state.value.copy(loading = false, error = "No se pudo cargar tu historial. Comprueba la conexión y reintenta.")
            }
        }
    }

    fun remove(track: CommunityMusicTrack, onRemoved: (String) -> Unit) {
        val account = auth.getCurrentUserId() ?: return
        if (_state.value.loading || _state.value.removingId != null || _state.value.tracks.none { it.id == track.id }) return
        val request = ++generation
        _state.value = _state.value.copy(removingId = track.id, error = null)
        job = viewModelScope.launch {
            try {
                withTimeoutOrNull(15_000) { repository.deleteOwnCommunityUpload(track.id).getOrThrow() }
                    ?: error("La eliminación tardó demasiado.")
                if (request != generation || account != auth.getCurrentUserId()) return@launch
                _state.value = _state.value.copy(tracks = _state.value.tracks.filterNot { it.id == track.id }, removingId = null)
                onRemoved(track.id)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (request == generation) _state.value = _state.value.copy(removingId = null, error = "No se confirmó la eliminación. Actualiza el historial o vuelve a intentar.")
            }
        }
    }
}
