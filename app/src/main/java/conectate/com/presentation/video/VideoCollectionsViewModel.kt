package conectate.com.presentation.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.localfallback.VideoCollection
import conectate.com.data.localfallback.VideoCollectionItem
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.common.AppFeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class VideoCollectionsState(
    val collections: List<VideoCollection> = emptyList(),
    val selectedId: String? = null,
    val videos: List<VideoCollectionItem> = emptyList(),
    val nextOffset: Int? = null,
    val search: String = "",
    val loading: Boolean = false,
    val loadingVideos: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class VideoCollectionsViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val auth: AuthService,
    private val realtime: UbuntuRealtimeClient,
) : ViewModel() {
    private val _state = MutableStateFlow(VideoCollectionsState())
    val state = _state.asStateFlow()
    private var accountId: String? = null
    private var ownerLease: AuthSessionLease? = null
    private var revision = 0L
    private var itemRevision = 0L
    private var searchJob: Job? = null
    private var hasLoaded = false

    init {
        viewModelScope.launch {
            combine(auth.observeSession(), auth.accountVisitChanges) { _, _ -> auth.captureAccountLease() }
                .collect { current ->
                    if (ownerLease?.sameVisit(current) != true) reset(current)
                }
        }
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (event.event == "video_collections" && event.channel == accountId?.let { "user:$it" }) {
                    if (hasLoaded) refresh(silent = true)
                }
            }
        }
    }

    private fun reset(current: AuthSessionLease) {
        ownerLease = current
        accountId = current.accountId
        revision++
        itemRevision++
        searchJob?.cancel()
        hasLoaded = false
        _state.value = VideoCollectionsState()
    }

    private fun bindAccount(): AuthSessionLease? {
        val current = auth.captureAccountLease()
        if (ownerLease?.sameVisit(current) != true) reset(current)
        return current.takeIf { it.isCurrent() }
    }

    internal fun ownerAtRender(): AuthSessionLease? = ownerLease

    fun refresh(silent: Boolean = false) {
        val account = bindAccount() ?: run {
            _state.value = VideoCollectionsState(error = "Inicia sesión para ver tus colecciones.")
            return
        }
        revision++
        hasLoaded = true
        val requestRevision = revision
        if (!silent) _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = conectate.com.core.network.runReadWithDeadline(10_000) {
                account.requireCurrent()
                if (!silent) auth.ensureValidAccessToken().onSuccess { token ->
                    account.requireCurrent()
                    realtime.updateAccessToken(token)
                    account.requireCurrent()
                    realtime.replaceUserSubscription(account.accountId!!)
                }
                repository.getVideoCollections(account).getOrThrow()
            }
            if (!account.isCurrent() || requestRevision != revision) return@launch
            result.fold(onSuccess = { catalog ->
                val selected = _state.value.selectedId?.takeIf { id ->
                    catalog.items.any { it.id == id }
                }
                if (selected != _state.value.selectedId) select(selected)
                _state.value = _state.value.copy(collections = catalog.items,
                    selectedId = selected, loading = false, error = null,
                    videos = if (selected == null) emptyList() else _state.value.videos)
                if (selected != null) loadItems()
            }, onFailure = { error ->
                _state.value = _state.value.copy(loading = false,
                    error = error.message ?: "No se pudieron cargar las colecciones.")
            })
        }
    }

    fun select(collectionId: String?) {
        if (_state.value.selectedId == collectionId) return
        itemRevision++
        searchJob?.cancel()
        _state.value = _state.value.copy(selectedId = collectionId, videos = emptyList(),
            nextOffset = null, search = "", loadingVideos = false, error = null)
        if (collectionId != null) loadItems()
    }

    fun search(query: String) {
        if (query.length > 80) return
        // Invalidate now: the previous offset and even an A -> B -> A reply are stale
        // throughout the debounce interval, before the next request is launched.
        itemRevision++
        _state.value = _state.value.copy(search = query, videos = emptyList(),
            nextOffset = null, loadingVideos = _state.value.selectedId != null, error = null)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            loadItems()
        }
    }

    fun loadMore() {
        val offset = _state.value.nextOffset ?: return
        if (_state.value.loadingVideos) return
        loadItems(offset)
    }

    private fun loadItems(offset: Int = 0) {
        val account = bindAccount() ?: return
        val collectionId = _state.value.selectedId ?: return
        val query = _state.value.search
        itemRevision++
        val requestRevision = itemRevision
        _state.value = _state.value.copy(loadingVideos = true, error = null)
        viewModelScope.launch {
            val result = conectate.com.core.network.runReadWithDeadline(10_000) { repository.getVideoCollectionItems(account, collectionId, query, offset).getOrThrow() }
            if (!account.isCurrent() || requestRevision != itemRevision ||
                collectionId != _state.value.selectedId || query != _state.value.search) return@launch
            result.fold(onSuccess = { page ->
                _state.value = _state.value.copy(
                    videos = (if (offset == 0) page.items else _state.value.videos + page.items)
                        .distinctBy(VideoCollectionItem::videoId),
                    nextOffset = page.nextOffset, loadingVideos = false, error = null)
            }, onFailure = { error ->
                _state.value = _state.value.copy(loadingVideos = false,
                    error = error.message ?: "No se pudieron cargar los videos.")
            })
        }
    }

    private fun launchMutation(account: AuthSessionLease, action: suspend () -> Unit) = viewModelScope.launch {
        try {
            account.requireCurrent()
            action()
        } finally {
            if (account.isCurrent()) _state.value = _state.value.copy(working = false)
        }
    }

    fun create(name: String, videoId: String? = null) {
        val account = bindAccount() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, error = null)
        launchMutation(account) {
            val result = repository.saveVideoCollection(account, name.trim())
            if (!account.isCurrent()) return@launchMutation
            result.fold(onSuccess = { collection ->
                run {
                    val addResult = videoId?.let { repository.setVideoCollectionItem(account, collection.id, it, true) }
                    if (!account.isCurrent()) return@launchMutation
                    addResult?.exceptionOrNull()?.let { error ->
                        AppFeedbackBus.showError(error.message ?: "No se pudo añadir el video.")
                    }
                    _state.value = _state.value.copy(working = false,
                        error = null)
                    refresh(silent = true)
                }
            }, onFailure = { error ->
                _state.value = _state.value.copy(working = false,
                    error = error.message ?: "No se pudo crear la colección.")
            })
        }
    }

    fun rename(collectionId: String, name: String) {
        val account = bindAccount() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, error = null)
        launchMutation(account) {
            val result = repository.saveVideoCollection(account, name.trim(), collectionId)
            if (!account.isCurrent()) return@launchMutation
            _state.value = _state.value.copy(working = false,
                error = result.exceptionOrNull()?.message)
            if (result.isSuccess) refresh(silent = true)
        }
    }

    fun delete(collectionId: String) {
        val account = bindAccount() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, error = null)
        launchMutation(account) {
            val result = repository.deleteVideoCollection(account, collectionId)
            if (!account.isCurrent()) return@launchMutation
            _state.value = _state.value.copy(working = false,
                error = result.exceptionOrNull()?.message)
            if (result.isSuccess) {
                if (_state.value.selectedId == collectionId) select(null)
                _state.value = _state.value.copy(
                    collections = _state.value.collections.filterNot { it.id == collectionId })
                refresh(silent = true)
            }
        }
    }

    fun add(collectionId: String, videoId: String) = mutateItem(collectionId, videoId) { lease ->
        repository.setVideoCollectionItem(lease, collectionId, videoId, true)
    }

    fun remove(collectionId: String, videoId: String) = mutateItem(collectionId, videoId) { lease ->
        repository.setVideoCollectionItem(lease, collectionId, videoId, false)
    }

    fun move(collectionId: String, videoId: String, direction: String) = mutateItem(collectionId, videoId) { lease ->
        repository.moveVideoCollectionItem(lease, collectionId, videoId, direction)
    }

    private fun mutateItem(collectionId: String, videoId: String,
                           operation: suspend (AuthSessionLease) -> Result<Unit>) {
        val account = bindAccount() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, error = null)
        launchMutation(account) {
            val result = operation(account)
            if (!account.isCurrent()) return@launchMutation
            _state.value = _state.value.copy(working = false,
                error = result.exceptionOrNull()?.message)
            if (result.isSuccess) refresh(silent = true)
        }
    }
}
