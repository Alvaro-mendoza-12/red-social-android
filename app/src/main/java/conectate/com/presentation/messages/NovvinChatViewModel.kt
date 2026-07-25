package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.local.prefs.NovvinChatStore
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val suggestions: List<String> = emptyList()
)

@Serializable
data class NovvinConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<NovvinMessage>
)

@HiltViewModel
class NovvinChatViewModel @Inject constructor(
    private val apiClient: LocalFallbackApiClient,
    private val authService: AuthService,
    private val chatStore: NovvinChatStore
) : ViewModel() {
    private companion object {
        const val MAX_CONVERSATIONS = 40
        const val MAX_MESSAGES_PER_CONVERSATION = 100
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val persistenceMutex = Mutex()
    private val ownerId = authService.getCurrentUserId()?.trim().orEmpty()

    private fun welcome() = NovvinMessage(
        text = "Hola, soy Novvin. Puedo orientarte con conocimientos y reglas revisadas. No aprendo automaticamente de esta conversacion.",
        fromNovvin = true,
        suggestions = listOf("Editar mi perfil", "Crear una publicacion", "Buscar grupos")
    )

    private fun newConversation(): NovvinConversation {
        val now = System.currentTimeMillis()
        return NovvinConversation(
            id = UUID.randomUUID().toString(),
            title = "Nuevo chat",
            createdAt = now,
            updatedAt = now,
            messages = listOf(welcome())
        )
    }

    private val initialConversation = newConversation()
    private val _conversations = MutableStateFlow(listOf(initialConversation))
    val conversations: StateFlow<List<NovvinConversation>> = _conversations.asStateFlow()
    private val _currentConversationId = MutableStateFlow(initialConversation.id)
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()
    private val _messages = MutableStateFlow(initialConversation.messages)
    val messages: StateFlow<List<NovvinMessage>> = _messages.asStateFlow()
    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()
    private val _isHistoryLoading = MutableStateFlow(true)
    val isHistoryLoading: StateFlow<Boolean> = _isHistoryLoading.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private val _canRetry = MutableStateFlow(false)
    val canRetry: StateFlow<Boolean> = _canRetry.asStateFlow()
    private var failedRequest: Pair<String, String>? = null

    init {
        viewModelScope.launch {
            val restored = if (ownerId.isBlank()) {
                emptyList()
            } else {
                runCatching {
                    chatStore.load(ownerId)
                        ?.let { json.decodeFromString<List<NovvinConversation>>(it) }
                        ?.filter { it.messages.isNotEmpty() }
                        ?.sortedByDescending { it.updatedAt }
                        ?.take(MAX_CONVERSATIONS)
                }.getOrNull().orEmpty()
            }
            if (restored.isNotEmpty()) {
                _conversations.value = restored
                _currentConversationId.value = restored.first().id
                _messages.value = restored.first().messages
            } else if (ownerId.isNotBlank()) {
                persistLatest()
            }
            _isHistoryLoading.value = false
        }
    }

    fun startNewChat() {
        val conversation = newConversation()
        _conversations.value = (listOf(conversation) + _conversations.value).take(MAX_CONVERSATIONS)
        _currentConversationId.value = conversation.id
        _messages.value = conversation.messages
        _errorMessage.value = null
        clearFailedRequest()
        persistAsync()
    }

    fun selectConversation(conversationId: String) {
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        _currentConversationId.value = conversation.id
        _messages.value = conversation.messages
        _errorMessage.value = null
        clearFailedRequest()
    }

    fun deleteConversation(conversationId: String) {
        var remaining = _conversations.value.filterNot { it.id == conversationId }
        if (remaining.isEmpty()) remaining = listOf(newConversation())
        _conversations.value = remaining
        if (_currentConversationId.value == conversationId) {
            _currentConversationId.value = remaining.first().id
            _messages.value = remaining.first().messages
        }
        if (failedRequest?.first == conversationId) clearFailedRequest()
        persistAsync()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun retryLastMessage() {
        val (targetId, message) = failedRequest ?: return
        if (_isThinking.value || _isHistoryLoading.value) return
        launchRequest(targetId, message)
    }

    fun send(message: String) {
        val cleanMessage = message.trim().take(1000)
        if (cleanMessage.isBlank() || _isThinking.value || _isHistoryLoading.value) return

        val targetId = _currentConversationId.value
        val userMessage = NovvinMessage(text = cleanMessage, fromNovvin = false)
        updateConversation(targetId) { conversation ->
            val firstUserMessage = conversation.messages.none { !it.fromNovvin }
            conversation.copy(
                title = if (firstUserMessage) cleanMessage.take(42) else conversation.title,
                updatedAt = System.currentTimeMillis(),
                messages = (conversation.messages + userMessage).takeLast(MAX_MESSAGES_PER_CONVERSATION)
            )
        }
        _errorMessage.value = null
        clearFailedRequest()
        persistAsync()

        launchRequest(targetId, cleanMessage)
    }

    private fun launchRequest(targetId: String, cleanMessage: String) {
        _isThinking.value = true
        _errorMessage.value = null
        _canRetry.value = false
        viewModelScope.launch {
            val target = _conversations.value.firstOrNull { it.id == targetId }
            val history = target?.messages?.takeLast(10)?.joinToString("\n") {
                "${if (it.fromNovvin) "Novvin" else "Usuario"}: ${it.text}"
            }.orEmpty()
            val result = runCatching {
                val token = authService.ensureValidAccessToken().getOrThrow()
                apiClient.askNovvin(
                    accessToken = token,
                    message = cleanMessage,
                    context = mapOf(
                        "screen" to "android_messages",
                        "conversationId" to targetId,
                        "history" to history.take(4000),
                        "locale" to "es-CO"
                    )
                ).getOrThrow()
            }
            result.onSuccess { answer ->
                clearFailedRequest()
                updateConversation(targetId) { conversation ->
                    conversation.copy(
                        updatedAt = System.currentTimeMillis(),
                        messages = (conversation.messages + NovvinMessage(
                            text = answer.answer.ifBlank { "No pude preparar una respuesta todavia. Intenta de nuevo." },
                            fromNovvin = true,
                            suggestions = answer.suggestions.take(3)
                        )).takeLast(MAX_MESSAGES_PER_CONVERSATION)
                    )
                }
                persistAsync()
            }.onFailure { error ->
                failedRequest = targetId to cleanMessage
                _canRetry.value = true
                _errorMessage.value = error.message ?: "No fue posible conectar con Novvin."
            }
            _isThinking.value = false
        }
    }

    private fun clearFailedRequest() {
        failedRequest = null
        _canRetry.value = false
    }

    private fun updateConversation(
        conversationId: String,
        transform: (NovvinConversation) -> NovvinConversation
    ) {
        _conversations.value = _conversations.value
            .map { if (it.id == conversationId) transform(it) else it }
            .sortedByDescending { it.updatedAt }
        if (_currentConversationId.value == conversationId) {
            _messages.value = _conversations.value.firstOrNull { it.id == conversationId }?.messages.orEmpty()
        }
    }

    private fun persistAsync() {
        viewModelScope.launch { persistLatest() }
    }

    private suspend fun persistLatest() {
        if (ownerId.isBlank()) return
        persistenceMutex.withLock {
            chatStore.save(ownerId, json.encodeToString(_conversations.value.take(MAX_CONVERSATIONS)))
        }
    }
}
