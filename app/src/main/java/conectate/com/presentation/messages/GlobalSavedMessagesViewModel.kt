package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.GlobalSavedMessage
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class SavedInboxState(
    val items: List<GlobalSavedMessage> = emptyList(),
    val rooms: Map<String, String> = emptyMap(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val kind: String = "",
    val roomId: String = "",
    val type: String = "",
    val from: String = "",
    val search: String = "",
)

@HiltViewModel
class GlobalSavedMessagesViewModel @Inject constructor(
    private val auth: AuthService,
    private val api: LocalFallbackApiClient,
    private val realtime: UbuntuRealtimeClient,
) : ViewModel() {
    private val _state = MutableStateFlow(SavedInboxState())
    val state = _state.asStateFlow()
    private var accountId: String? = null
    private var revision = 0L

    init {
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (event.event == "message_marker" && event.channel == accountId?.let { "user:$it" }) refresh()
            }
        }
    }

    fun bindAccount() {
        val current = auth.getCurrentUserId()?.trim()?.lowercase()
        if (current != accountId) {
            accountId = current
            revision++
            _state.value = SavedInboxState()
        }
        refresh()
    }

    fun setFilters(kind: String = _state.value.kind, roomId: String = _state.value.roomId,
                   type: String = _state.value.type, from: String = _state.value.from,
                   search: String = _state.value.search) {
        if (listOf(kind, roomId, type, from, search) ==
            listOf(_state.value.kind, _state.value.roomId, _state.value.type, _state.value.from,
                _state.value.search)) return
        _state.value = _state.value.copy(kind = kind, roomId = roomId, type = type, from = from, search = search,
            items = emptyList(), nextCursor = null, error = null)
        refresh()
    }

    fun refresh() {
        revision++
        _state.value = _state.value.copy(items = emptyList(), nextCursor = null, error = null)
        loadPage(null)
    }

    fun loadMore() {
        val cursor = _state.value.nextCursor ?: return
        loadPage(cursor)
    }

    private fun loadPage(cursor: String?) {
        if (_state.value.loading && cursor != null) return
        val currentRevision = revision
        val currentAccount = accountId
        val filters = _state.value
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val token = auth.ensureValidAccessToken().getOrElse {
                if (revision == currentRevision) _state.value = _state.value.copy(loading = false,
                    error = "Inicia sesión para consultar tus mensajes guardados.")
                return@launch
            }
            if (revision != currentRevision || currentAccount != auth.getCurrentUserId()?.trim()?.lowercase()) return@launch
            realtime.updateAccessToken(token)
            currentAccount?.let(realtime::replaceUserSubscription)
            val result = api.getGlobalSavedMessages(token, filters.kind, filters.roomId, filters.type,
                filters.from, cursor = cursor.orEmpty(), search = filters.search)
            if (revision != currentRevision || currentAccount != auth.getCurrentUserId()?.trim()?.lowercase()) return@launch
            result.fold(onSuccess = { page ->
                val items = if (cursor == null) page.saved else (filters.items + page.saved)
                    .distinctBy { "${it.roomKind}:${it.roomId}:${it.messageId}" }
                val rooms = filters.rooms + page.saved.associate { it.roomId to it.roomName }
                _state.value = _state.value.copy(items = items, rooms = rooms,
                    nextCursor = page.nextCursor, loading = false)
            }, onFailure = { error -> _state.value = _state.value.copy(loading = false,
                error = error.message ?: "No se pudieron cargar los guardados.") })
        }
    }

    fun remove(item: GlobalSavedMessage) {
        val currentAccount = accountId
        val currentRevision = revision
        viewModelScope.launch {
            val token = auth.ensureValidAccessToken().getOrElse {
                _state.value = _state.value.copy(error = "Inicia sesión para modificar guardados.")
                return@launch
            }
            if (currentRevision != revision || currentAccount != auth.getCurrentUserId()?.trim()?.lowercase()) return@launch
            api.updateMessageMarker(token, item.roomKind, item.roomId, item.messageId, "saved", false)
                .fold(onSuccess = {
                    if (currentRevision == revision) _state.value = _state.value.copy(
                        items = _state.value.items.filterNot { it.roomKind == item.roomKind &&
                            it.roomId == item.roomId && it.messageId == item.messageId })
                }, onFailure = { error ->
                    if (currentRevision == revision) _state.value = _state.value.copy(
                        error = error.message ?: "No se pudo quitar el mensaje guardado.")
                })
        }
    }
}
