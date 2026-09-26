package conectate.com.presentation.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.localfallback.VideoCollection
import conectate.com.data.localfallback.VideoCollectionItem
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.common.AppFeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private var revision = 0L
    private var itemRevision = 0L
    private var searchJob: Job? = null
    private var hasLoaded = false

    init {
        viewModelScope.launch {
            auth.observeSession().map { it?.user?.id?.trim()?.lowercase() }
                .distinctUntilChanged().collect { current ->
                    if (accountId != current) reset(current)
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

    private fun reset(current: String?) {
        accountId = current
        revision++
        itemRevision++
        searchJob?.cancel()
        hasLoaded = false
        _state.value = VideoCollectionsState()
    }

    private fun bindAccount(): String? {
        val current = auth.getCurrentUserId()?.trim()?.lowercase()
        if (current != accountId) reset(current)
        return current
    }

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
            if (!silent) auth.ensureValidAccessToken().onSuccess { token ->
                realtime.updateAccessToken(token)
                realtime.replaceUserSubscription(account)
            }
            val result = repository.getVideoCollections()
            if (account != bindAccount() || requestRevision != revision) return@launch
            result.fold(onSuccess = { catalog ->
                val selected = _state.value.selectedId?.takeIf { id ->
                    catalog.items.any { it.id == id }
                }
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
            nextOffset = null, search = "", error = null)
        if (collectionId != null) loadItems()
    }

    fun search(query: String) {
        if (query.length > 80) return
        _state.value = _state.value.copy(search = query)
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
            val result = repository.getVideoCollectionItems(collectionId, query, offset)
            if (account != bindAccount() || requestRevision != itemRevision ||
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

    fun create(name: String, videoId: String? = null) {
        val account = bindAccount() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, error = null)
        viewModelScope.launch {
            val result = repository.saveVideoCollection(name.trim())
            if (account != bindAccount()) return@launch
            result.fold(onSuccess = { collection ->
                viewModelScope.launch {
                    val addResult = videoId?.let { repository.setVideoCollectionItem(collection.id, it, true) }
                    if (account != bindAccount()) return@launch
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
        viewModelScope.launch {
            val result = repository.saveVideoCollection(name.trim(), collectionId)
            if (account != bindAccount()) return@launch
            _state.value = _state.value.copy(working = false,
                error = result.exceptionOrNull()?.message)
            if (result.isSuccess) refresh(silent = true)
        }
    }

    fun delete(collectionId: String) {
        val account = bindAccount() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, error = null)
        viewModelScope.launch {
            val result = repository.deleteVideoCollection(collectionId)
            if (account != bindAccount()) return@launch
            _state.value = _state.value.copy(working = false,
                selectedId = _state.value.selectedId.takeUnless { it == collectionId },
                error = result.exceptionOrNull()?.message)
            if (result.isSuccess) refresh(silent = true)
        }
    }

    fun add(collectionId: String, videoId: String) = mutateItem(collectionId, videoId) {
        repository.setVideoCollectionItem(collectionId, videoId, true)
    }

    fun remove(collectionId: String, videoId: String) = mutateItem(collectionId, videoId) {
        repository.setVideoCollectionItem(collectionId, videoId, false)
    }

    fun move(collectionId: String, videoId: String, direction: String) = mutateItem(collectionId, videoId) {
        repository.moveVideoCollectionItem(collectionId, videoId, direction)
    }

    private fun mutateItem(collectionId: String, videoId: String,
                           operation: suspend () -> Result<Unit>) {
        val account = bindAccount() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, error = null)
        viewModelScope.launch {
            val result = operation()
            if (account != bindAccount()) return@launch
            _state.value = _state.value.copy(working = false,
                error = result.exceptionOrNull()?.message)
            if (result.isSuccess) refresh(silent = true)
        }
    }
}
