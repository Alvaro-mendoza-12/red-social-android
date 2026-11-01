package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.GlobalSavedMessage
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class GlobalSavedMessagesViewModel @Inject constructor(
    private val auth: AuthService,
    private val api: LocalFallbackApiClient,
    private val realtime: UbuntuRealtimeClient,
) : ViewModel() {
    private val inbox = SavedInboxController(viewModelScope, auth::getCurrentUserId,
        read = { account, filters, cursor ->
            val token = auth.getAccessTokenForAccountAwait(account)
                ?: throw IllegalStateException("Inicia sesión para consultar tus mensajes guardados.")
            realtime.updateAccessToken(token)
            realtime.replaceUserSubscription(account)
            api.getGlobalSavedMessages(token, filters.kind, filters.roomId, filters.type,
                filters.from, cursor = cursor.orEmpty(), search = filters.search)
        },
        removeMarker = { account, item ->
            val token = auth.getAccessTokenForAccountAwait(account)
                ?: throw IllegalStateException("Inicia sesión para modificar guardados.")
            api.updateMessageMarker(token, item.roomKind, item.roomId, item.messageId, "saved", false).map { Unit }
        },
    )
    val state = inbox.state

    init {
        viewModelScope.launch {
            auth.currentSession.map { it?.user?.id?.trim()?.lowercase() }.distinctUntilChanged().collect { inbox.bindAccount() }
        }
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (event.event == "message_marker" && event.channel == state.value.accountId?.let { "user:$it" }) inbox.refresh()
            }
        }
    }

    fun bindAccount() = inbox.bindAccount(refreshIfIdle = true)
    fun setFilters(kind: String = state.value.kind, roomId: String = state.value.roomId,
        type: String = state.value.type, from: String = state.value.from, search: String = state.value.search) =
        inbox.setFilters(kind, roomId, type, from, search)
    fun refresh() = inbox.refresh()
    fun loadMore() = inbox.loadMore()
    fun remove(item: GlobalSavedMessage, expectedAccount: String?) = inbox.remove(item, expectedAccount)
    override fun onCleared() { inbox.close(); super.onCleared() }
}
