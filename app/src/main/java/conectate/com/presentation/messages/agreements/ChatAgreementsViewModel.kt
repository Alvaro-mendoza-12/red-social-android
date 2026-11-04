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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ChatAgreementsState(
    val accountId: String? = null, val chatId: String? = null, val supported: Boolean = false,
    val open: Boolean = false, val loading: Boolean = false, val busy: Boolean = false,
    val agreements: List<ChatAgreement> = emptyList(), val error: String? = null,
    val editingId: String? = null, val editingVersion: Int? = null, val sourceId: String? = null, val draft: String = "",
    val retryPending: Boolean = false, val source: AgreementSource? = null,
    val savingDraft: Boolean = false, val draftSaved: Boolean = false,
    val recoveryConflict: Boolean = false, val recoveryUnavailable: Boolean = false,
    val serverAvailable: Boolean = true,
)

@HiltViewModel
class ChatAgreementsViewModel internal constructor(
    private val capture: () -> AuthSessionLease,
    changes: Flow<*>, events: Flow<AgreementChange>,
    private val capability: suspend (AuthSessionLease) -> Boolean,
    private val load: suspend (AuthSessionLease, String) -> List<ChatAgreement>,
    private val send: suspend (AuthSessionLease, String, String, AgreementCommand) -> ChatAgreement,
    private val source: suspend (AuthSessionLease, String, String) -> AgreementSource?,
    private val recovery: AgreementRecoveryPersistence,
) : ViewModel() {
    @Inject internal constructor(auth: AuthService, api: ChatAgreementApi, realtime: UbuntuRealtimeClient, recovery: AgreementRecoveryStore) : this(
        auth::captureAccountLease, auth.currentSession,
        realtime.events.mapNotNull { event ->
            if (event.event == "chat_agreement") event.payload["chatId"]?.jsonPrimitive?.contentOrNull?.let { AgreementChange(it, event.channel) } else null
        }, api::supported, api::load, api::send, api::source, recovery,
    )
    private val _state = MutableStateFlow(ChatAgreementsState(serverAvailable = false))
    val state = _state.asStateFlow()
    private var lease: AuthSessionLease? = null
    private var target: String? = null
    private var generation = 0L
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var pending: Pair<String, AgreementCommand>? = null
    private var readingVersion = 0L
    private var draftJob: Job? = null
    private var draftVersion = 0L
    private var draftDirty = false
    private var recoveryReady = false
    private var recoveryRecord: AgreementRecovery? = null
    private val recoveryMutex = Mutex()

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
        generation++; readingVersion++; draftVersion++; readJob?.cancel(); writeJob?.cancel(); draftJob?.cancel(); pending = null
        recoveryReady = false; recoveryRecord = null; draftDirty = false
        target = clean; lease = capture()
        val current = checkNotNull(lease)
        _state.value = ChatAgreementsState(accountId = current.accountId, chatId = clean, serverAvailable = false)
        if (clean == null || !current.isCurrent()) return
        val epoch = generation
        readJob = viewModelScope.launch {
            try {
                runReadWithDeadline(10_000) { restoreRecovery(epoch, current, clean) }.getOrThrow()
                if (valid(epoch, current) && hasRecovery()) _state.value = _state.value.copy(supported = true)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (valid(epoch, current)) _state.value = _state.value.copy(
                recoveryUnavailable = true, error = "No se pudo recuperar el borrador de este dispositivo. Reintenta antes de escribir.") }
            try {
                val supported = runReadWithDeadline(10_000) { capability(current) }.getOrThrow()
                if (valid(epoch, current)) _state.value = _state.value.copy(supported = supported || hasRecovery(), serverAvailable = supported,
                    error = if (!supported && hasRecovery()) "Acuerdos no está disponible ahora. El borrador y los envíos pendientes siguen guardados." else _state.value.error)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (valid(epoch, current)) _state.value = _state.value.copy(supported = hasRecovery(), serverAvailable = false,
                error = if (hasRecovery()) "No se pudo conectar. Puedes continuar el borrador y volver a conectar para enviarlo." else _state.value.error) }
        }
    }
    fun open() { if (active() && _state.value.supported) { _state.value = _state.value.copy(open = true); refresh() } }
    fun close() {
        readingVersion++; readJob?.cancel()
        _state.value = _state.value.copy(loading = false, source = null)
        val current = lease; val chat = target
        if (draftDirty && recoveryReady && pending == null && !_state.value.busy && current != null && chat != null && active()) {
            val epoch = generation; val draft = currentDraft()
            draftVersion++; draftJob?.cancel()
            _state.value = _state.value.copy(busy = true, savingDraft = true)
            writeJob = viewModelScope.launch {
                try {
                    persistRecovery(epoch, current, chat, draft, null)
                    if (valid(epoch, current)) {
                        draftDirty = false
                        _state.value = _state.value.copy(open = false, busy = false, savingDraft = false,
                            draftSaved = draft != null, recoveryUnavailable = false, error = null)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (valid(epoch, current)) storageFailure(error) }
            }
        } else _state.value = _state.value.copy(open = false)
    }
    fun refresh() = refresh(keepError = false)
    fun recoverSavedState() {
        if (!active() || _state.value.busy) return
        recoveryReady = false; draftDirty = false
        draftVersion++; draftJob?.cancel()
        _state.value = _state.value.copy(recoveryConflict = false)
        refresh()
    }
    private fun refresh(keepError: Boolean) {
        val current = lease ?: return
        val chat = target ?: return
        if (!active() || !_state.value.supported || _state.value.busy || _state.value.recoveryConflict) return
        val epoch = generation; val read = ++readingVersion
        readJob?.cancel()
        if (draftDirty && recoveryReady && pending == null) saveLocalDraft(immediate = true)
        _state.value = _state.value.copy(loading = true, error = if (keepError || pending != null || draftDirty) _state.value.error else null)
        readJob = viewModelScope.launch {
            try {
                if (!recoveryReady) restoreRecovery(epoch, current, chat)
                if (!_state.value.serverAvailable) {
                    val available = runReadWithDeadline(10_000) { capability(current) }.getOrThrow()
                    if (!valid(epoch, current) || read != readingVersion) return@launch
                    _state.value = _state.value.copy(serverAvailable = available)
                    if (!available) throw AgreementApiException(503, "AGREEMENT_DISABLED")
                }
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
        if (!active() || !_state.value.supported) return
        if (!recoveryReady || _state.value.busy || pending != null) { open(); return }
        if (!agreementUuid(sourceId)) {
            _state.value = _state.value.copy(open = true, error = "No se pudo usar ese mensaje como origen. Elige otro mensaje enviado."); return
        }
        if (_state.value.sourceId != null && _state.value.draft.isNotBlank()) {
            _state.value = _state.value.copy(open = true, error = "Tienes un borrador guardado. Continúa o descártalo antes de proponer otro acuerdo.")
            return
        }
        _state.value = _state.value.copy(open = true, sourceId = sourceId, editingId = null, editingVersion = null, draft = "", error = null)
        draftDirty = true; saveLocalDraft()
        refresh()
    }
    fun edit(agreement: ChatAgreement) {
        if (!active() || !recoveryReady || _state.value.busy || pending != null || agreement.authorId != _state.value.accountId || agreement.cancelled) return
        if (_state.value.sourceId != null && _state.value.draft.isNotBlank()) {
            _state.value = _state.value.copy(error = "Continúa o descarta el borrador actual antes de editar otra propuesta."); return
        }
        _state.value = _state.value.copy(editingId = agreement.id, editingVersion = agreement.version, sourceId = agreement.sourceMessageId, draft = agreement.text, error = null)
        draftDirty = true; saveLocalDraft()
    }
    fun changeDraft(value: String) {
        if (!active() || !recoveryReady || _state.value.busy || pending != null || _state.value.sourceId == null) return
        _state.value = _state.value.copy(draft = value.take(1000)); draftDirty = true; saveLocalDraft()
    }
    fun keepDraftAgainstLatestRevision() {
        val snapshot = _state.value
        val current = snapshot.agreements.find { it.id == snapshot.editingId } ?: return
        if (!active() || snapshot.busy || pending != null || current.cancelled || current.authorId != snapshot.accountId) return
        _state.value = snapshot.copy(editingVersion = current.version, error = null)
        draftDirty = true; saveLocalDraft()
    }
    fun dismissEditor() {
        val current = lease ?: return; val chat = target ?: return
        if (!active() || !recoveryReady || _state.value.busy || pending != null) return
        val epoch = generation
        draftVersion++; draftJob?.cancel()
        _state.value = _state.value.copy(busy = true, savingDraft = false)
        writeJob = viewModelScope.launch {
            try {
                persistRecovery(epoch, current, chat, null, null)
                if (valid(epoch, current)) {
                    draftDirty = false
                    _state.value = _state.value.copy(sourceId = null, editingId = null, editingVersion = null,
                        draft = "", busy = false, draftSaved = false, error = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (valid(epoch, current)) storageFailure(error) }
        }
    }
    fun saveDraft() {
        if (!active() || !recoveryReady || !_state.value.serverAvailable || _state.value.busy || pending != null || _state.value.draft.isBlank()) return
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
        if (!active() || !recoveryReady || !_state.value.serverAvailable || _state.value.busy || pending != null || _state.value.sourceId != null || action !in setOf("accept", "changes", "withdraw", "cancel")) return
        dispatch(agreement.id to AgreementCommand(UUID.randomUUID().toString(), expectedVersion = agreement.version, action = action))
    }
    fun retry() { pending?.let(::dispatch) }
    private fun dispatch(operation: Pair<String, AgreementCommand>) {
        val current = lease ?: return; val chat = target ?: return
        if (!active() || !recoveryReady || !_state.value.serverAvailable || _state.value.busy) return
        pending = operation
        val epoch = generation
        val draft = currentDraft()
        draftVersion++; draftJob?.cancel()
        readingVersion++; readJob?.cancel()
        _state.value = _state.value.copy(busy = true, loading = false, savingDraft = false, retryPending = false, error = null)
        writeJob = viewModelScope.launch {
            try {
                persistRecovery(epoch, current, chat, draft, AgreementPendingWrite(operation.first, operation.second))
                val result = send(current, chat, operation.first, operation.second)
                persistRecovery(epoch, current, chat, null, null)
                if (!valid(epoch, current)) return@launch
                pending = null; draftDirty = false
                _state.value = _state.value.copy(agreements = listOf(result) + _state.value.agreements.filterNot { it.id == result.id },
                    sourceId = null, editingId = null, editingVersion = null, draft = "", busy = false, retryPending = false, draftSaved = false)
                if (_state.value.open) refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (valid(epoch, current)) {
                    val definitive = error is AgreementApiException && error.definitive
                    if (definitive) {
                        try { persistRecovery(epoch, current, chat, draft, null); pending = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (local: Exception) { if (valid(epoch, current)) storageFailure(local); return@launch }
                    }
                    if (error is AgreementRecoveryConflict) { storageFailure(error); return@launch }
                    _state.value = _state.value.copy(busy = false, retryPending = !definitive, error = feedback(error))
                    if (error is AgreementApiException && error.code == "AGREEMENT_VERSION_CONFLICT") refresh(keepError = true)
                }
            }
        }
    }
    private fun currentDraft(): AgreementDraft? = _state.value.let { snapshot -> snapshot.sourceId?.let {
        AgreementDraft(it, snapshot.draft, snapshot.editingId, snapshot.editingVersion)
    } }
    private fun hasRecovery() = _state.value.sourceId != null || pending != null
    private suspend fun restoreRecovery(epoch: Long, current: AuthSessionLease, chat: String) = recoveryMutex.withLock {
        val saved = recovery.read(current, chat)
        if (!valid(epoch, current)) throw CancellationException()
        recoveryRecord = saved; recoveryReady = true; draftDirty = false
        pending = saved?.pending?.let { it.agreementId to it.command }
        _state.value = _state.value.copy(sourceId = saved?.draft?.sourceId, editingId = saved?.draft?.editingId,
            editingVersion = saved?.draft?.editingVersion, draft = saved?.draft?.text.orEmpty(),
            retryPending = pending != null, recoveryConflict = false, recoveryUnavailable = false,
            draftSaved = saved?.draft != null, savingDraft = false,
            error = if (pending != null) "Hay un envío sin confirmar. Reintenta para consultar su resultado." else null)
    }
    private suspend fun persistRecovery(epoch: Long, current: AuthSessionLease, chat: String,
                                       draft: AgreementDraft?, operation: AgreementPendingWrite?) = recoveryMutex.withLock {
        if (!valid(epoch, current)) throw CancellationException()
        // Finish the local commit before acknowledging it, even if this screen is disposed.
        val saved = withContext(NonCancellable) { recovery.replace(current, chat, recoveryRecord, draft, operation) }
        if (!valid(epoch, current)) throw CancellationException()
        recoveryRecord = saved
    }
    private fun saveLocalDraft(immediate: Boolean = false) {
        val current = lease ?: return; val chat = target ?: return
        if (!active() || !recoveryReady || _state.value.busy || pending != null || !draftDirty) return
        val epoch = generation; val revision = ++draftVersion; val draft = currentDraft()
        draftJob?.cancel()
        _state.value = _state.value.copy(savingDraft = true, draftSaved = false)
        draftJob = viewModelScope.launch {
            try {
                if (!immediate) delay(150)
                persistRecovery(epoch, current, chat, draft, null)
                if (valid(epoch, current) && revision == draftVersion) {
                    draftDirty = false
                    _state.value = _state.value.copy(savingDraft = false, draftSaved = draft != null, recoveryUnavailable = false,
                        error = if (_state.value.recoveryUnavailable) null else _state.value.error)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (valid(epoch, current) && revision == draftVersion) storageFailure(error) }
        }
    }
    private fun storageFailure(error: Exception) {
        val conflict = error is AgreementRecoveryConflict
        if (conflict) recoveryReady = false
        _state.value = _state.value.copy(busy = false, savingDraft = false, draftSaved = false,
            recoveryConflict = conflict, recoveryUnavailable = true, retryPending = pending != null && !conflict,
            error = if (conflict) error.message else "No se pudo guardar la recuperación en este dispositivo. Conservamos el texto; vuelve a intentarlo.")
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
        "AGREEMENT_DISABLED" -> "Acuerdos no está disponible ahora. El borrador y los envíos pendientes siguen guardados."
        else -> "No se pudo completar la acción. Conservamos lo que escribiste para reintentar."
    }
}
