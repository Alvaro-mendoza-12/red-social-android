package conectate.com.presentation.messages

import conectate.com.core.concurrency.AccountTasks
import conectate.com.core.network.runReadWithDeadline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class NovvinReply(val text: String, val suggestions: List<String> = emptyList())

internal data class NovvinChatState(
    val accountId: String? = null,
    val conversations: List<NovvinConversation> = emptyList(),
    val currentConversationId: String = "",
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    val saveError: String? = null,
    val errorMessage: String? = null,
    val requestingId: String? = null,
    val canRetry: Boolean = false,
) {
    val messages get() = conversations.firstOrNull { it.id == currentConversationId }?.messages.orEmpty()
    val isThinking get() = requestingId != null && requestingId == currentConversationId
    val editable get() = accountId != null && !historyLoading && historyError == null
}

internal fun NovvinChatState.forAccount(account: String?): NovvinChatState {
    val owner = account?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    return if (accountId == owner) this else NovvinChatState(accountId = owner, historyLoading = owner != null)
}

internal fun newNovvinConversation(): NovvinConversation {
    val now = System.currentTimeMillis()
    return NovvinConversation(UUID.randomUUID().toString(), "Nuevo chat", now, now, listOf(NovvinMessage(
        text = "Hola, soy Novvin. Puedo orientarte con conocimientos y reglas revisadas. No aprendo automáticamente de esta conversación.",
        fromNovvin = true, suggestions = listOf("Editar mi perfil", "Crear una publicación", "Buscar grupos"),
    )))
}

/** UI state, credentials, disk snapshots and asynchronous replies all retain their original owner. */
internal class NovvinChatController(
    scope: CoroutineScope,
    private val currentAccount: () -> String?,
    private val load: suspend (String) -> List<NovvinConversation>,
    private val save: suspend (String, List<NovvinConversation>) -> Unit,
    private val ask: suspend (String, String, String, String) -> NovvinReply,
    private val newConversation: () -> NovvinConversation = ::newNovvinConversation,
    private val readTimeoutMillis: Long = 10_000,
    private val requestTimeoutMillis: Long = 45_000,
) {
    private val tasks = AccountTasks(scope)
    private val persistenceMutex = Mutex()
    private val mutableState = MutableStateFlow(NovvinChatState())
    val state = mutableState.asStateFlow()
    private var bound = false
    private var epoch = 0L
    private var readRevision = 0L
    private var requestRevision = 0L
    private var saveRevision = 0L
    private var readJob: Job? = null
    private var requestJob: Job? = null
    private var saveJob: Job? = null
    private var pending: Pair<String, String>? = null
    private val failed = mutableMapOf<String, String>()

    private fun owner() = currentAccount()?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    private fun owns(account: String?, version: Long = epoch) =
        account != null && version == epoch && owner() == account && state.value.accountId == account
    private fun publish(next: NovvinChatState) {
        mutableState.value = next.copy(canRetry = failed.containsKey(next.currentConversationId))
    }

    fun bindAccount() {
        val account = owner()
        if (bound && state.value.accountId == account) return
        bound = true
        epoch++
        tasks.reset()
        readJob = null; requestJob = null; saveJob = null; pending = null
        failed.clear()
        publish(NovvinChatState(accountId = account))
        if (account != null) retryHistory()
    }

    fun retryHistory() {
        val account = state.value.accountId ?: return
        if (!owns(account) || state.value.historyLoading) return
        val version = epoch
        val revision = ++readRevision
        readJob?.cancel()
        publish(state.value.copy(historyLoading = true, historyError = null))
        readJob = tasks.scope.launch {
            val result = runReadWithDeadline(readTimeoutMillis) { normalize(load(account)) }
            currentCoroutineContext().ensureActive()
            if (!owns(account, version) || revision != readRevision) return@launch
            result.fold(onSuccess = { restored ->
                val conversations = restored.ifEmpty { listOf(newConversation()) }
                failed.clear()
                conversations.forEach { conversation ->
                    conversation.messages.lastOrNull()?.takeIf { !it.fromNovvin }?.let {
                        failed[conversation.id] = it.text
                    }
                }
                publish(NovvinChatState(accountId = account, conversations = conversations,
                    currentConversationId = conversations.first().id))
            }, onFailure = {
                publish(state.value.copy(historyLoading = false,
                    historyError = "No pudimos abrir tus chats guardados. Reintenta para conservar el historial."))
            })
        }
    }

    private fun canEdit() = owns(state.value.accountId) && state.value.editable

    fun startNewChat() {
        if (!canEdit()) return
        val conversation = newConversation()
        val conversations = (listOf(conversation) + state.value.conversations).take(40)
        val retained = conversations.mapTo(mutableSetOf()) { it.id }
        if (pending?.first !in retained) cancelRequest(false)
        failed.keys.retainAll(retained)
        publish(state.value.copy(conversations = conversations, currentConversationId = conversation.id, errorMessage = null))
        retrySave()
    }

    fun selectConversation(id: String) {
        if (!canEdit() || state.value.conversations.none { it.id == id }) return
        publish(state.value.copy(currentConversationId = id, errorMessage = null))
    }

    fun deleteConversation(id: String) {
        if (!canEdit() || state.value.conversations.none { it.id == id }) return
        if (pending?.first == id) cancelRequest(false)
        failed.remove(id)
        val remaining = state.value.conversations.filterNot { it.id == id }.ifEmpty { listOf(newConversation()) }
        val selected = state.value.currentConversationId.takeIf { selected -> remaining.any { it.id == selected } }
            ?: remaining.first().id
        publish(state.value.copy(conversations = remaining, currentConversationId = selected, errorMessage = null))
        retrySave()
    }

    fun send(text: String): Boolean {
        val message = text.trim().take(1000)
        if (message.isBlank() || !canEdit() || state.value.isThinking) return false
        cancelRequest(true)
        val id = state.value.currentConversationId
        val userMessage = NovvinMessage(text = message, fromNovvin = false)
        updateConversation(id) { conversation -> conversation.copy(
            title = if (conversation.messages.none { !it.fromNovvin }) message.take(42) else conversation.title,
            updatedAt = System.currentTimeMillis(), messages = (conversation.messages + userMessage).takeLast(100),
        ) }
        failed.remove(id)
        retrySave()
        request(id, message)
        return true
    }

    fun retryLastMessage() {
        if (!canEdit() || state.value.isThinking) return
        val id = state.value.currentConversationId
        val message = failed[id] ?: return
        cancelRequest(true)
        request(id, message)
    }

    private fun request(id: String, message: String) {
        val account = state.value.accountId ?: return
        val version = epoch
        val revision = ++requestRevision
        pending = id to message
        val history = state.value.conversations.firstOrNull { it.id == id }?.messages?.takeLast(10)
            ?.joinToString("\n") { "${if (it.fromNovvin) "Novvin" else "Usuario"}: ${it.text}" }.orEmpty().takeLast(4000)
        publish(state.value.copy(requestingId = id, errorMessage = null))
        requestJob = tasks.scope.launch {
            try {
                val result = runReadWithDeadline(requestTimeoutMillis) {
                    ask(account, id, message, history).also { check(it.text.isNotBlank()) }
                }
                currentCoroutineContext().ensureActive()
                if (!owns(account, version) || revision != requestRevision || state.value.conversations.none { it.id == id }) return@launch
                result.fold(onSuccess = { answer ->
                    failed.remove(id)
                    updateConversation(id) { conversation -> conversation.copy(
                        updatedAt = System.currentTimeMillis(),
                        messages = (conversation.messages + NovvinMessage(text = answer.text,
                            fromNovvin = true, suggestions = answer.suggestions.take(3))).takeLast(100),
                    ) }
                    retrySave()
                }, onFailure = {
                    failed[id] = message
                    publish(state.value.copy(errorMessage = if (state.value.currentConversationId == id)
                        "No pudimos obtener la respuesta de Novvin. Puedes reintentar." else null))
                })
            } finally {
                if (owns(account, version) && revision == requestRevision) {
                    pending = null
                    publish(state.value.copy(requestingId = null))
                }
            }
        }
    }

    private fun cancelRequest(offerRetry: Boolean) {
        pending?.let { (id, text) -> if (offerRetry) failed[id] = text }
        requestRevision++
        requestJob?.cancel(); requestJob = null; pending = null
        publish(state.value.copy(requestingId = null))
    }

    fun clearError() = publish(state.value.copy(errorMessage = null))

    fun retrySave() {
        val account = state.value.accountId ?: return
        if (!canEdit()) return
        val version = epoch
        val revision = ++saveRevision
        val snapshot = state.value.conversations
        saveJob?.cancel()
        saveJob = tasks.scope.launch {
            val result = runReadWithDeadline(readTimeoutMillis) {
                persistenceMutex.withLock { save(account, snapshot) }
            }
            currentCoroutineContext().ensureActive()
            if (owns(account, version) && revision == saveRevision) publish(state.value.copy(
                saveError = if (result.isFailure) "El chat sigue abierto, pero no pudimos guardar el historial en este dispositivo." else null,
            ))
        }
    }

    private fun updateConversation(id: String, transform: (NovvinConversation) -> NovvinConversation) = publish(
        state.value.copy(conversations = state.value.conversations.map { if (it.id == id) transform(it) else it }
            .sortedByDescending { it.updatedAt }),
    )

    private fun normalize(conversations: List<NovvinConversation>) = conversations.asSequence()
        .filter { it.id.isNotBlank() }.distinctBy { it.id }.map { conversation -> conversation.copy(
            messages = conversation.messages.filter { it.id.isNotBlank() }.distinctBy { it.id }.takeLast(100),
        ) }.filter { it.messages.isNotEmpty() }.sortedByDescending { it.updatedAt }.take(40).toList()
}
