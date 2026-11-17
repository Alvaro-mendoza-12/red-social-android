package conectate.com.presentation.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.VideoWatchLaterItem
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.common.AppFeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

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
    private var ownerLease: AuthSessionLease? = null
    private var revision = 0L
    private val pending = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            combine(auth.observeSession(), auth.accountVisitChanges) { _, _ -> auth.captureAccountLease() }
                .collect { current ->
                    if (ownerLease?.sameVisit(current) != true) resetAccount(current)
                }
        }
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (event.event == "video_watch_later" &&
                    event.channel == accountId?.let { "user:$it" }) refresh()
            }
        }
    }

    private fun resetAccount(current: AuthSessionLease) {
        ownerLease = current
        accountId = current.accountId
        revision++
        pending.clear()
        _state.value = VideoWatchLaterState()
    }

    private fun bindAccount(): AuthSessionLease? {
        val current = auth.captureAccountLease()
        if (ownerLease?.sameVisit(current) != true) resetAccount(current)
        return current.takeIf { it.isCurrent() }
    }

    internal fun ownerAtRender(): AuthSessionLease? = ownerLease

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

    private fun loadPage(account: AuthSessionLease, requestRevision: Long, cursor: String, sort: String) {
        viewModelScope.launch {
            val result = conectate.com.core.network.runReadWithDeadline(10_000) {
                account.requireCurrent()
                if (cursor.isEmpty()) auth.ensureValidAccessToken().onSuccess { token ->
                    account.requireCurrent()
                    realtime.updateAccessToken(token)
                    account.requireCurrent()
                    realtime.replaceUserSubscription(account.accountId!!)
                }
                repository.getVideoWatchLater(account, cursor, sort).getOrThrow()
            }
            if (requestRevision != revision || !account.isCurrent()) return@launch
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
        val pendingKey = "${account.accountId}:$id"
        if (id.isEmpty() || !pending.add(pendingKey)) return
        viewModelScope.launch {
            try {
                val result = repository.setVideoWatchLater(account, id, true)
                if (!account.isCurrent()) return@launch
                result.fold(onSuccess = {
                    AppFeedbackBus.showSuccess(
                        "Guardado. Abre Videos > + > Mi biblioteca > Ver después."
                    )
                }, onFailure = { error ->
                    AppFeedbackBus.showError(error.message ?: "No se pudo guardar el video.")
                })
            } finally {
                if (account.isCurrent()) pending.remove(pendingKey)
            }
        }
    }

    fun remove(videoId: String) {
        val account = bindAccount() ?: return
        val id = videoId.trim().lowercase()
        val pendingKey = "${account.accountId}:$id"
        if (id.isEmpty() || !pending.add(pendingKey)) return
        viewModelScope.launch {
            try {
                val result = repository.setVideoWatchLater(account, id, false)
                if (!account.isCurrent()) return@launch
                result.fold(onSuccess = {
                    // Retire pages captured before the confirmed removal, then reconcile the list.
                    revision++
                    _state.value = _state.value.copy(
                        items = _state.value.items.filterNot { it.videoId == id },
                        loading = true, error = null)
                    loadPage(account, revision, "", _state.value.sort)
                }, onFailure = { error ->
                    _state.value = _state.value.copy(error = error.message ?: "No se pudo quitar el video.")
                })
            } finally {
                if (account.isCurrent()) pending.remove(pendingKey)
            }
        }
    }
}
