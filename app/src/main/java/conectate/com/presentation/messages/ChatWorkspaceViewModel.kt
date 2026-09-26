package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Chat
import conectate.com.data.model.Message
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class ChatWorkspaceUiState(
    val workspace: ChatWorkspace = ChatWorkspace(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val available: Boolean = false
)

@HiltViewModel
class ChatWorkspaceViewModel @Inject constructor(
    private val store: ChatWorkspaceStore,
    private val authService: AuthService
) : ViewModel() {
    private val ownerId = authService.getCurrentUserId().orEmpty()
    private var scope: String? = null
    private var job: Job? = null
    private val _state = MutableStateFlow(ChatWorkspaceUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authService.currentSession.collect {
                if (!ownsSession()) {
                    job?.cancel()
                    scope = null
                    _state.value = ChatWorkspaceUiState(loading = false, error = "La cuenta cambió. Vuelve a abrir el chat.")
                }
            }
        }
    }

    private fun ownsSession() = ownerId.isNotBlank() && ownerId.equals(authService.getCurrentUserId(), ignoreCase = true)

    fun bind(chat: Chat) {
        if (!ownsSession()) return
        val next = chatWorkspaceScope(ownerId, chat) ?: return
        if (next == scope) return
        scope = next
        load()
    }

    fun bindConversation(type: String, id: String, channelId: String? = null) {
        if (!ownsSession()) return
        val next = conversationWorkspaceScope(type, id, channelId) ?: return
        if (next == scope) return
        scope = next
        load()
    }

    fun load() {
        val activeScope = scope ?: return
        if (!ownsSession()) return
        job?.cancel()
        _state.value = ChatWorkspaceUiState()
        job = viewModelScope.launch {
            try {
                val workspace = store.read(ownerId, activeScope)
                if (ownsSession() && scope == activeScope) {
                    _state.value = ChatWorkspaceUiState(workspace, loading = false, available = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ownsSession() && scope == activeScope) {
                    _state.value = ChatWorkspaceUiState(loading = false, error = "No se pudo abrir el espacio privado. Reintenta.")
                }
            }
        }
    }

    fun add(kind: ChatWorkspaceKind, text: String, source: Message? = null, onSaved: () -> Unit = {}) {
        if (source != null && !canSaveMessageInWorkspace(source, ownerId)) return
        update({ it.add(ChatWorkspaceEntry(kind = kind, text = text, sourceMessageId = source?.id)) }, onSaved)
    }

    fun toggleTask(id: String) = update({ it.toggleTask(id) })
    fun scheduleTask(id: String, dueAt: Long?) = update({ it.scheduleTask(id, dueAt) })
    fun remove(id: String) = update({ it.remove(id) })
    fun clearError() { _state.value = _state.value.copy(error = null) }

    private fun update(transform: (ChatWorkspace) -> ChatWorkspace, onSaved: () -> Unit = {}) {
        val activeScope = scope ?: return
        if (!ownsSession() || !_state.value.available || _state.value.saving) return
        _state.value = _state.value.copy(saving = true, error = null)
        job = viewModelScope.launch {
            try {
                val workspace = store.update(ownerId, activeScope, transform)
                if (ownsSession() && scope == activeScope) {
                    _state.value = ChatWorkspaceUiState(workspace, loading = false, available = true)
                    onSaved()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ownsSession() && scope == activeScope) {
                    _state.value = _state.value.copy(saving = false, error =
                        if (error is IllegalArgumentException) error.message else "No se pudo guardar. El contenido sigue disponible; reintenta.")
                }
            }
        }
    }
}
