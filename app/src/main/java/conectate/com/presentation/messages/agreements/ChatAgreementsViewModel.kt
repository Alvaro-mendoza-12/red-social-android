package conectate.com.presentation.messages.agreements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ChatAgreementsState(
    val accountId: String? = null, val chatId: String? = null, val supported: Boolean = false,
    val open: Boolean = false, val loading: Boolean = false, val busy: Boolean = false,
    val agreements: List<ChatAgreement> = emptyList(), val error: String? = null,
    val editingId: String? = null, val editingVersion: Int? = null, val sourceId: String? = null, val draft: String = "",
    val retryPending: Boolean = false, val source: AgreementSource? = null,
)

@HiltViewModel
class ChatAgreementsViewModel internal constructor(
    private val capture: () -> AuthSessionLease,
    changes: Flow<*>, events: Flow<AgreementChange>,
    private val capability: suspend (AuthSessionLease) -> Boolean,
    private val load: suspend (AuthSessionLease, String) -> List<ChatAgreement>,
    private val send: suspend (AuthSessionLease, String, String, AgreementCommand) -> ChatAgreement,
    private val source: suspend (AuthSessionLease, String, String) -> AgreementSource?,
) : ViewModel() {
    @Inject internal constructor(auth: AuthService, api: ChatAgreementApi, realtime: UbuntuRealtimeClient) : this(
        auth::captureAccountLease, auth.currentSession,
        realtime.events.mapNotNull { event ->
            if (event.event == "chat_agreement") event.payload["chatId"]?.jsonPrimitive?.contentOrNull?.let { AgreementChange(it, event.channel) } else null
        }, api::supported, api::load, api::send, api::source,
    )
    private val _state = MutableStateFlow(ChatAgreementsState())
    val state = _state.asStateFlow()
    private var lease: AuthSessionLease? = null
    private var target: String? = null
    private var generation = 0L
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var pending: Pair<String, AgreementCommand>? = null
    private var readingVersion = 0L

    init {
        viewModelScope.launch { changes.collect { if (lease?.isCurrent() != true) bindTarget(target, force = true) } }
        viewModelScope.launch { events.collect { event ->
            if (event.chatId == target && event.accountChannel == "user:${_state.value.accountId}" && _state.value.open) refresh()
        } }
    }
    fun bind(chatId: String?, direct: Boolean = true) = bindTarget(chatId.takeIf { direct }, force = false)
    private fun bindTarget(chatId: String?, force: Boolean) {
        val clean = chatId?.lowercase()?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
        if (!force && target == clean && lease?.isCurrent() == true) return
        generation++; readingVersion++; readJob?.cancel(); writeJob?.cancel(); pending = null
        target = clean; lease = capture()
        val current = checkNotNull(lease)
        _state.value = ChatAgreementsState(accountId = current.accountId, chatId = clean)
        if (clean == null || !current.isCurrent()) return
        val epoch = generation
        readJob = viewModelScope.launch {
            try {
                val supported = runReadWithDeadline(10_000) { capability(current) }.getOrThrow()
                if (valid(epoch, current)) _state.value = _state.value.copy(supported = supported)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional capability stays hidden. Opening a chat does not show an unrelated error. */ }
        }
    }
    fun open() { if (active() && _state.value.supported) { _state.value = _state.value.copy(open = true); refresh() } }
    fun close() {
        readingVersion++; readJob?.cancel()
        _state.value = _state.value.copy(open = false, loading = false, source = null)
    }
    fun refresh() = refresh(keepError = false)
    private fun refresh(keepError: Boolean) {
        val current = lease ?: return
        val chat = target ?: return
        if (!active() || !_state.value.supported || _state.value.busy) return
        val epoch = generation; val read = ++readingVersion
        readJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = if (keepError || pending != null) _state.value.error else null)
        readJob = viewModelScope.launch {
            try {
                val rows = runReadWithDeadline(10_000) { load(current, chat) }.getOrThrow()
                if (valid(epoch, current) && read == readingVersion) _state.value = _state.value.copy(agreements = rows, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (valid(epoch, current) && read == readingVersion) {
                    val denied = error is AgreementApiException && error.statusCode in setOf(401, 403, 404)
                    _state.value = _state.value.copy(loading = false, error = feedback(error),
                        agreements = if (denied) emptyList() else _state.value.agreements, source = null)
                }
            }
        }
    }
    fun propose(sourceId: String) {
        if (!active() || !_state.value.supported || _state.value.busy || pending != null) return
        _state.value = _state.value.copy(open = true, sourceId = sourceId, editingId = null, editingVersion = null, draft = "", error = null)
        refresh()
    }
    fun edit(agreement: ChatAgreement) {
        if (!active() || _state.value.busy || pending != null || agreement.authorId != _state.value.accountId || agreement.cancelled) return
        _state.value = _state.value.copy(editingId = agreement.id, editingVersion = agreement.version, sourceId = agreement.sourceMessageId, draft = agreement.text, error = null)
    }
    fun changeDraft(value: String) { if (!_state.value.busy && pending == null) _state.value = _state.value.copy(draft = value.take(1000)) }
    fun keepDraftAgainstLatestRevision() {
        val snapshot = _state.value
        val current = snapshot.agreements.find { it.id == snapshot.editingId } ?: return
        if (!active() || snapshot.busy || pending != null || current.cancelled || current.authorId != snapshot.accountId) return
        _state.value = snapshot.copy(editingVersion = current.version, error = null)
    }
    fun dismissEditor() { if (!_state.value.busy && pending == null) _state.value = _state.value.copy(sourceId = null, editingId = null, editingVersion = null, draft = "") }
    fun saveDraft() {
        if (!active() || _state.value.busy || pending != null || _state.value.draft.isBlank()) return
        val snapshot = _state.value
        val original = snapshot.agreements.find { it.id == snapshot.editingId }
        if (snapshot.editingId != null && original == null) return
        val id = original?.id ?: UUID.randomUUID().toString()
        val command = if (original != null) AgreementCommand(UUID.randomUUID().toString(), text = snapshot.draft,
            expectedVersion = snapshot.editingVersion ?: return, action = "revise")
        else AgreementCommand(UUID.randomUUID().toString(), id = id, sourceMessageId = snapshot.sourceId ?: return, text = snapshot.draft)
        dispatch(id to command)
    }
    fun respond(agreement: ChatAgreement, action: String) {
        if (!active() || _state.value.busy || pending != null || action !in setOf("accept", "changes", "withdraw", "cancel")) return
        dispatch(agreement.id to AgreementCommand(UUID.randomUUID().toString(), expectedVersion = agreement.version, action = action))
    }
    fun retry() { pending?.let(::dispatch) }
    private fun dispatch(operation: Pair<String, AgreementCommand>) {
        val current = lease ?: return; val chat = target ?: return
        if (!active() || _state.value.busy) return
        pending = operation
        val epoch = generation
        readingVersion++; readJob?.cancel()
        _state.value = _state.value.copy(busy = true, loading = false, retryPending = false, error = null)
        writeJob = viewModelScope.launch {
            try {
                val result = send(current, chat, operation.first, operation.second)
                if (!valid(epoch, current)) return@launch
                pending = null
                _state.value = _state.value.copy(agreements = listOf(result) + _state.value.agreements.filterNot { it.id == result.id },
                    sourceId = null, editingId = null, editingVersion = null, draft = "", busy = false, retryPending = false)
                if (_state.value.open) refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (valid(epoch, current)) {
                    val definitive = error is AgreementApiException && error.definitive
                    if (definitive) pending = null
                    _state.value = _state.value.copy(busy = false, retryPending = !definitive, error = feedback(error))
                    if (error is AgreementApiException && error.code == "AGREEMENT_VERSION_CONFLICT") refresh(keepError = true)
                }
            }
        }
    }
    fun showSource(agreement: ChatAgreement) {
        val current = lease ?: return; val chat = target ?: return
        if (!active() || _state.value.busy) return
        val epoch = generation; val read = ++readingVersion
        readJob?.cancel(); _state.value = _state.value.copy(loading = true, error = null)
        readJob = viewModelScope.launch {
            try {
                val found = runReadWithDeadline(10_000) { source(current, chat, agreement.id) }.getOrThrow()
                if (valid(epoch, current) && read == readingVersion) _state.value = _state.value.copy(loading = false, source = found,
                    error = if (found == null) "El mensaje de origen ya no está disponible." else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (valid(epoch, current) && read == readingVersion) _state.value = _state.value.copy(loading = false, error = feedback(error)) }
        }
    }
    fun dismissSource() { _state.value = _state.value.copy(source = null) }
    private fun active(): Boolean {
        if (lease?.isCurrent() != true) { bindTarget(target, force = true); return false }
        return true
    }
    private fun valid(epoch: Long, current: AuthSessionLease) = generation == epoch && current.isCurrent()
    private fun feedback(error: Exception): String = when ((error as? AgreementApiException)?.code) {
        "AGREEMENT_VERSION_CONFLICT" -> "La propuesta cambió. Revisa la versión actual antes de confirmar."
        "AGREEMENT_CHAT_UNAVAILABLE", "AGREEMENT_ACCESS_DENIED" -> "Este acuerdo ya no está disponible para esta conversación."
        "AGREEMENT_SOURCE_UNAVAILABLE" -> "El mensaje de origen ya no está disponible. Elige otro mensaje."
        "AGREEMENT_ROOM_LIMIT" -> "Este chat llegó al límite de 50 acuerdos."
        "AGREEMENT_CANCELLED" -> "La propuesta se canceló. Actualiza para ver su estado."
        else -> "No se pudo completar la acción. Conservamos lo que escribiste para reintentar."
    }
}
