package conectate.com.presentation.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.localfallback.VideoHistoryItem
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class VideoHistoryState(
    val items: List<VideoHistoryItem> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val deleting: Boolean = false,
)

@HiltViewModel
class VideoHistoryViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val auth: AuthService,
    private val realtime: UbuntuRealtimeClient,
) : ViewModel() {
    private val _state = MutableStateFlow(VideoHistoryState())
    val state = _state.asStateFlow()
    private var accountId: String? = null
    private var revision = 0L
    private val recentProgress = mutableMapOf<String, Long>()
    private val pendingProgress = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            auth.observeSession().map { it?.user?.id?.trim()?.lowercase() }
                .distinctUntilChanged().collect { current ->
                    if (accountId != current) resetAccount(current)
                }
        }
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (event.event == "video_history" && event.channel == accountId?.let { "user:$it" }) {
                    if (_state.value.items.isNotEmpty()) refreshSilently()
                }
            }
        }
    }

    private fun resetAccount(current: String?) {
        accountId = current
        revision++
        recentProgress.clear()
        pendingProgress.clear()
        _state.value = VideoHistoryState()
    }

    private fun bindAccount(): String? {
        val current = auth.getCurrentUserId()?.trim()?.lowercase()
        if (current != accountId) resetAccount(current)
        return current
    }

    fun refresh() {
        val account = bindAccount() ?: run {
            _state.value = VideoHistoryState(error = "Inicia sesión para ver tu historial.")
            return
        }
        revision++
        _state.value = VideoHistoryState(loading = true)
        loadPage(account, revision, "")
    }

    private fun refreshSilently() {
        val account = bindAccount() ?: return
        revision++
        loadPage(account, revision, "")
    }

    fun loadMore() {
        val account = bindAccount() ?: return
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        loadPage(account, revision, cursor)
    }

    private fun loadPage(account: String, requestRevision: Long, cursor: String) {
        viewModelScope.launch {
            if (cursor.isEmpty()) auth.ensureValidAccessToken().onSuccess { token ->
                realtime.updateAccessToken(token)
                realtime.replaceUserSubscription(account)
            }
            val result = repository.getVideoHistory(cursor)
            if (requestRevision != revision || account != bindAccount()) return@launch
            result.fold(onSuccess = { page ->
                val items = (if (cursor.isEmpty()) page.items else _state.value.items + page.items)
                    .distinctBy(VideoHistoryItem::videoId)
                _state.value = _state.value.copy(items = items, nextCursor = page.nextCursor,
                    loading = false, error = null)
            }, onFailure = { error ->
                _state.value = _state.value.copy(loading = false,
                    error = error.message ?: "No se pudo cargar el historial.")
            })
        }
    }

    fun recordProgress(videoId: String?, positionMs: Long, durationMs: Long) {
        val account = bindAccount() ?: return
        val id = videoId?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return
        if (durationMs <= 0 || durationMs > 86_400_000L || positionMs < 0 || positionMs > durationMs) return
        if (positionMs < minOf(1_500L, durationMs / 2L)) return
        val key = "$account:$id"
        val now = android.os.SystemClock.elapsedRealtime()
        if (pendingProgress.contains(key) ||
            (recentProgress.containsKey(key) && now - recentProgress.getValue(key) < 5_000L)) return
        recentProgress[key] = now
        pendingProgress.add(key)
        viewModelScope.launch {
            try {
                if (account != bindAccount()) return@launch
                repository.recordVideoHistory(id, positionMs, durationMs)
            } finally {
                pendingProgress.remove(key)
            }
        }
    }

    fun remove(videoId: String?) {
        val account = bindAccount() ?: return
        if (_state.value.deleting) return
        _state.value = _state.value.copy(deleting = true, error = null)
        viewModelScope.launch {
            if (account != bindAccount()) return@launch
            val result = repository.deleteVideoHistory(videoId)
            if (account != bindAccount()) return@launch
            result.fold(onSuccess = {
                _state.value = if (videoId == null) VideoHistoryState() else _state.value.copy(
                    items = _state.value.items.filterNot { it.videoId == videoId }, deleting = false)
            }, onFailure = { error ->
                _state.value = _state.value.copy(deleting = false,
                    error = error.message ?: "No se pudo borrar el historial.")
            })
        }
    }
}
