package conectate.com.presentation.video.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.core.network.InteractiveReadDeadlines
import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.model.Video
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class VideoRoutesState(
    val accountId: String? = null,
    val route: VideoRoute? = null,
    val query: String = "",
    val requestedCount: Int = 3,
    val conclusion: String = "",
    val composerVisible: Boolean = true,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val storageBlocked: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class VideoRoutesViewModel internal constructor(
    private val captureAccount: () -> AuthSessionLease,
    accountChanges: Flow<*>,
    private val search: suspend (String) -> Result<List<Video>>,
    private val readStored: suspend (AuthSessionLease) -> VideoRoute?,
    private val replaceStored: suspend (AuthSessionLease, VideoRoute?, VideoRoute?) -> Unit,
    private val eraseStored: suspend (AuthSessionLease) -> Unit,
) : ViewModel() {
    @Inject constructor(auth: AuthService, videos: VideoRepository, store: VideoRouteStore) : this(
        captureAccount = auth::captureAccountLease,
        accountChanges = auth.currentSession,
        search = videos::searchVideos,
        readStored = store::read,
        replaceStored = store::replace,
        eraseStored = store::erase,
    )

    private val _state = MutableStateFlow(VideoRoutesState())
    val state = _state.asStateFlow()
    private var owner: AuthSessionLease? = null
    private var operation: Job? = null
    private var requestVersion = 0L

    init {
        bind(captureAccount())
        viewModelScope.launch {
            accountChanges.collect {
                val current = captureAccount()
                if (owner?.isCurrent() != true || current.accountId != owner?.accountId) bind(current)
            }
        }
    }

    fun reload() = bind(captureAccount())

    private fun bind(lease: AuthSessionLease) {
        owner = lease
        val version = ++requestVersion
        operation?.cancel()
        _state.value = VideoRoutesState(accountId = lease.accountId)
        if (lease.accountId == null) {
            _state.value = _state.value.copy(loading = false, error = "Inicia sesión para crear una ruta de videos.")
            return
        }
        operation = viewModelScope.launch {
            try {
                val route = readStored(lease)
                if (!isCurrent(version, lease)) return@launch
                _state.value = _state.value.copy(
                    route = route, query = route?.topic.orEmpty(), requestedCount = route?.requestedCount ?: 3,
                    conclusion = route?.conclusion.orEmpty(), composerVisible = route == null, loading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(version, lease)) _state.value = _state.value.copy(
                    loading = false, storageBlocked = true,
                    error = "No se pudo leer tu ruta guardada. Puedes volver a cargarla o borrarla de este dispositivo.",
                )
            }
        }
    }

    fun changeQuery(value: String) {
        if (!_state.value.busy && !_state.value.loading) {
            _state.value = _state.value.copy(query = value.take(VideoRoute.MAX_TOPIC), error = null)
        }
    }

    fun changeSize(count: Int) {
        if (!_state.value.busy && count in VideoRoute.SIZES) _state.value = _state.value.copy(requestedCount = count)
    }

    fun changeConclusion(value: String) {
        if (!_state.value.busy) _state.value = _state.value.copy(conclusion = value.take(VideoRoute.MAX_CONCLUSION))
    }

    fun showComposer() {
        if (!_state.value.busy) _state.value = _state.value.copy(composerVisible = true, error = null)
    }

    fun showRoute() {
        if (_state.value.route != null && !_state.value.busy) _state.value = _state.value.copy(composerVisible = false)
    }

    fun prepare() {
        val lease = activeLease() ?: return
        val snapshot = _state.value
        if (snapshot.loading || snapshot.busy || snapshot.storageBlocked) return
        val query = snapshot.query.trim()
        if (query.isEmpty()) {
            _state.value = snapshot.copy(error = "Escribe el tema que quieres explorar.")
            return
        }
        val version = ++requestVersion
        _state.value = snapshot.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                val videos = runReadWithDeadline(InteractiveReadDeadlines.FirstPageMillis) {
                    search(query).getOrThrow()
                }.getOrThrow()
                if (!isCurrent(version, lease)) return@launch
                val route = prepareVideoRoute(query, snapshot.requestedCount, videos)
                if (route == null) {
                    _state.value = _state.value.copy(error = "No encontramos videos públicos para ese tema. Prueba otras palabras.")
                    return@launch
                }
                replaceStored(lease, snapshot.route, route)
                if (isCurrent(version, lease)) _state.value = _state.value.copy(
                    route = route, query = route.topic, conclusion = "", composerVisible = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(version, lease)) _state.value = _state.value.copy(
                    error = "No se pudo preparar la ruta. Revisa tu conexión y vuelve a intentarlo.",
                )
            } finally {
                if (isCurrent(version, lease)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun openVideo(videoId: String, onOpen: (String) -> Unit) {
        val lease = activeLease() ?: return
        val snapshot = _state.value
        val item = snapshot.route?.items?.find { it.videoId.equals(videoId, ignoreCase = true) } ?: return
        if (snapshot.busy || snapshot.storageBlocked) return
        if (item.opened) {
            lease.requireCurrent()
            onOpen(item.videoId)
        } else {
            updateRoute({ it.open(item.videoId) }) { onOpen(item.videoId) }
        }
    }

    fun toggleUseful(videoId: String) = updateRoute({ it.toggleUseful(videoId) })

    fun saveConclusion(onSaved: () -> Unit = {}) {
        val note = _state.value.conclusion
        updateRoute({ it.copy(conclusion = note) }, onSaved)
    }

    fun setCompleted(completed: Boolean) {
        val note = _state.value.conclusion
        updateRoute({ it.copy(conclusion = note, completed = completed) })
    }

    private fun updateRoute(transform: (VideoRoute) -> VideoRoute, onSaved: () -> Unit = {}) {
        val lease = activeLease() ?: return
        val snapshot = _state.value
        val previous = snapshot.route ?: return
        if (snapshot.loading || snapshot.busy || snapshot.storageBlocked) return
        val version = ++requestVersion
        _state.value = snapshot.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                val next = transform(previous).copy(revision = Math.addExact(previous.revision, 1)).validate()
                replaceStored(lease, previous, next)
                if (isCurrent(version, lease)) {
                    _state.value = _state.value.copy(route = next)
                    onSaved()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(version, lease)) _state.value = _state.value.copy(
                    error = "No se pudo guardar el cambio. Vuelve a intentarlo o carga la ruta guardada.",
                )
            } finally {
                if (isCurrent(version, lease)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun erase() {
        val lease = activeLease() ?: return
        val snapshot = _state.value
        if (snapshot.busy || snapshot.loading || (snapshot.route == null && !snapshot.storageBlocked)) return
        val version = ++requestVersion
        _state.value = _state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                if (snapshot.route == null) eraseStored(lease)
                else replaceStored(lease, snapshot.route, null)
                if (isCurrent(version, lease)) _state.value = VideoRoutesState(accountId = lease.accountId, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(version, lease)) _state.value = _state.value.copy(error = "No se pudo borrar la ruta. Vuelve a intentarlo.")
            } finally {
                if (isCurrent(version, lease)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private fun activeLease(): AuthSessionLease? {
        val current = captureAccount()
        if (owner?.isCurrent() != true || current.accountId != owner?.accountId) {
            bind(current)
            return null
        }
        return current.takeIf { it.isCurrent() }
    }

    private fun isCurrent(version: Long, lease: AuthSessionLease): Boolean =
        version == requestVersion && lease.isCurrent() && lease.accountId == _state.value.accountId
}
