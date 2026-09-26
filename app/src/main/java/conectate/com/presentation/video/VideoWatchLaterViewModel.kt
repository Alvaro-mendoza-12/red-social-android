package conectate.com.presentation.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.VideoWatchLaterItem
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.common.AppFeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class VideoWatchLaterState(
    val items: List<VideoWatchLaterItem> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val sort: String = "newest",
)

@HiltViewModel
class VideoWatchLaterViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val auth: AuthService,
    private val realtime: UbuntuRealtimeClient,
) : ViewModel() {
    private val _state = MutableStateFlow(VideoWatchLaterState())
    val state = _state.asStateFlow()
    private var accountId: String? = null
    private var revision = 0L
    private val pending = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            auth.observeSession().map { it?.user?.id?.trim()?.lowercase() }
                .distinctUntilChanged().collect { current ->
                    if (accountId != current) {
                        accountId = current
                        revision++
                        pending.clear()
                        _state.value = VideoWatchLaterState()
                    }
                }
        }
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (event.event == "video_watch_later" &&
                    event.channel == accountId?.let { "user:$it" }) refresh()
            }
        }
    }

    private fun bindAccount(): String? {
        val current = auth.getCurrentUserId()?.trim()?.lowercase()
        if (accountId != current) {
            accountId = current
            revision++
            pending.clear()
            _state.value = VideoWatchLaterState()
        }
        return current
    }

    fun refresh() {
        val account = bindAccount() ?: run {
            _state.value = VideoWatchLaterState(error = "Inicia sesión para ver tus videos pendientes.")
            return
        }
        revision++
        val requestRevision = revision
        val sort = _state.value.sort
        _state.value = VideoWatchLaterState(loading = true, sort = sort)
        loadPage(account, requestRevision, "", sort)
    }

    fun setSort(sort: String) {
        if (sort !in setOf("newest", "oldest") || sort == _state.value.sort) return
        _state.value = _state.value.copy(sort = sort)
        refresh()
    }

    fun loadMore() {
        val account = bindAccount() ?: return
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        loadPage(account, revision, cursor, _state.value.sort)
    }

    private fun loadPage(account: String, requestRevision: Long, cursor: String, sort: String) {
        viewModelScope.launch {
            if (cursor.isEmpty()) auth.ensureValidAccessToken().onSuccess { token ->
                realtime.updateAccessToken(token)
                realtime.replaceUserSubscription(account)
            }
            val result = repository.getVideoWatchLater(cursor, sort)
            if (requestRevision != revision || account != bindAccount()) return@launch
            result.fold(onSuccess = { page ->
                val items = (if (cursor.isEmpty()) page.items else _state.value.items + page.items)
                    .distinctBy(VideoWatchLaterItem::videoId)
                _state.value = _state.value.copy(items = items, nextCursor = page.nextCursor,
                    loading = false, error = null)
            }, onFailure = { error ->
                _state.value = _state.value.copy(loading = false,
                    error = error.message ?: "No se pudo cargar Ver después.")
            })
        }
    }

    fun save(videoId: String) {
        val account = bindAccount() ?: run {
            AppFeedbackBus.showError("Inicia sesión para guardar videos.")
            return
        }
        val id = videoId.trim().lowercase()
        val pendingKey = "$account:$id"
        if (id.isEmpty() || !pending.add(pendingKey)) return
        viewModelScope.launch {
            val result = repository.setVideoWatchLater(id, true)
            pending.remove(pendingKey)
            if (account != bindAccount()) return@launch
            result.fold(onSuccess = {
                AppFeedbackBus.showSuccess(
                    "Guardado. Abre Videos > + > Mi biblioteca > Ver después."
                )
            }, onFailure = { error ->
                AppFeedbackBus.showError(error.message ?: "No se pudo guardar el video.")
            })
        }
    }

    fun remove(videoId: String) {
        val account = bindAccount() ?: return
        val id = videoId.trim().lowercase()
        val pendingKey = "$account:$id"
        if (id.isEmpty() || !pending.add(pendingKey)) return
        viewModelScope.launch {
            val result = repository.setVideoWatchLater(id, false)
            pending.remove(pendingKey)
            if (account != bindAccount()) return@launch
            result.fold(onSuccess = {
                _state.value = _state.value.copy(items = _state.value.items.filterNot { it.videoId == id })
            }, onFailure = { error ->
                _state.value = _state.value.copy(error = error.message ?: "No se pudo quitar el video.")
            })
        }
    }
}
