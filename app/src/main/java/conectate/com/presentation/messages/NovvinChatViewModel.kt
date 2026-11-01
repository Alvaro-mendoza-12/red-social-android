package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.local.prefs.NovvinChatStore
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@Serializable
data class NovvinMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val fromNovvin: Boolean,
    val suggestions: List<String> = emptyList(),
)

@Serializable
data class NovvinConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<NovvinMessage>,
)

@HiltViewModel
class NovvinChatViewModel @Inject constructor(
    apiClient: LocalFallbackApiClient,
    private val authService: AuthService,
    chatStore: NovvinChatStore,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val accountId = authService.currentSession.map { authService.getCurrentUserId()?.trim()?.lowercase() }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, authService.getCurrentUserId()?.trim()?.lowercase())
    private val controller = NovvinChatController(
        viewModelScope, authService::getCurrentUserId,
        load = { owner -> chatStore.load(owner)?.let { json.decodeFromString<List<NovvinConversation>>(it) }.orEmpty() },
        save = { owner, conversations -> chatStore.save(owner, json.encodeToString(conversations)) },
        ask = { owner, id, message, history ->
            val token = authService.getAccessTokenForAccountAwait(owner)
                ?: if (owner != authService.getCurrentUserId())
                    throw CancellationException("La cuenta cambió antes de consultar a Novvin.")
                else error("No se pudo renovar la sesión para consultar a Novvin.")
            val result = apiClient.askNovvin(token, message, mapOf(
                "screen" to "android_messages", "conversationId" to id,
                "history" to history, "locale" to java.util.Locale.getDefault().toLanguageTag(),
            )).getOrThrow()
            NovvinReply(result.answer, result.suggestions)
        },
    )
    internal val uiState = controller.state

    init {
        controller.bindAccount()
        viewModelScope.launch { accountId.collect { controller.bindAccount() } }
    }

    fun startNewChat() = controller.startNewChat()
    fun selectConversation(id: String) = controller.selectConversation(id)
    fun deleteConversation(id: String) = controller.deleteConversation(id)
    fun clearError() = controller.clearError()
    fun retryLastMessage() = controller.retryLastMessage()
    fun retryHistory() = controller.retryHistory()
    fun retrySave() = controller.retrySave()
    fun send(message: String): Boolean = controller.send(message)
}
