package conectate.com.presentation.purpose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class PurposeState(
    val accountId: String? = null, val target: PurposeTarget? = null, val payload: JsonObject? = null,
    val supported: Boolean = false, val loading: Boolean = false, val busy: Boolean = false,
    val recoveryReady: Boolean = false, val draft: JsonObject? = null, val pending: Boolean = false,
    val error: String? = null, val notice: String? = null,
)
@HiltViewModel
class PurposeViewModel internal constructor(
    private val capture: () -> AuthSessionLease, changes: Flow<*>, events: Flow<Pair<String,String>>,
    private val available: suspend (AuthSessionLease) -> Boolean,
    private val read: suspend (AuthSessionLease,PurposeTarget) -> JsonObject,
    private val send: suspend (AuthSessionLease,PurposePending) -> JsonObject,
    private val recoveryRead: suspend (AuthSessionLease,PurposeTarget) -> PurposeRecovery,
    private val recoveryWrite: suspend (AuthSessionLease,PurposeTarget,PurposeRecovery,PurposeRecovery) -> PurposeRecovery,
) : ViewModel() {
    @Inject internal constructor(auth: AuthService, api: PurposeApi, recovery: PurposeRecoveryStore, realtime: UbuntuRealtimeClient) : this(
        auth::captureAccountLease, auth.currentSession,
        realtime.events.filter { it.event == "purpose_changed" }.map { it.channel to (it.payload["objectId"]?.jsonPrimitive?.contentOrNull ?: "") },
        api::available, api::read, api::send, recovery::read, recovery::replace,
    )
    private val _state = MutableStateFlow(PurposeState())
    val state = _state.asStateFlow()
    private var lease: AuthSessionLease? = null
    private var epoch = 0L
    private var reading = 0L
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var draftJob: Job? = null
    private var record = PurposeRecovery()
    private var visible = false
    private val journal = Mutex()
    init {
        viewModelScope.launch { changes.collect { if (lease?.isCurrent() != true) bind(if(visible) _state.value.target else null, true) } }
        viewModelScope.launch { events.collect { (channel,id) ->
            val current = _state.value
            if (channel == "user:${current.accountId}" && (current.target?.id == id || current.target?.kind == "gaming") && !current.busy) refresh()
        } }
    }
    fun open(target: PurposeTarget) { val force = !visible; visible = true; bind(target, force) }
    fun close(onClosed: () -> Unit) {
        val current = lease; val target = _state.value.target; val version = epoch
        if (_state.value.busy) {
            if (!_state.value.pending) return
            visible = false; epoch++; readJob?.cancel(); writeJob?.cancel(); draftJob?.cancel(); onClosed(); return
        }
        draftJob?.cancel()
        val draft = _state.value.draft
        viewModelScope.launch {
            try {
                journal.withLock { if (current != null && target != null && valid(version,current) && _state.value.recoveryReady && record.draft != draft) withContext(NonCancellable) {
                    record = recoveryWrite(current,target,record,record.copy(draft = draft))
                } }
                if (version == epoch) { visible = false; reading++; readJob?.cancel(); onClosed() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (version == epoch) _state.value = _state.value.copy(error = error.message, recoveryReady = false) }
        }
    }
    private fun bind(target: PurposeTarget?, force: Boolean) {
        if (!force && target == _state.value.target && lease?.isCurrent() == true) return
        epoch++; reading++; readJob?.cancel(); writeJob?.cancel(); draftJob?.cancel()
        record = PurposeRecovery(); lease = capture()
        val current = checkNotNull(lease); val version = epoch
        _state.value = PurposeState(accountId = current.accountId, target = target, loading = target != null)
        if (target == null || !current.isCurrent()) return
        readJob = viewModelScope.launch {
            try {
                val saved = recoveryRead(current,target)
                if (!valid(version,current)) return@launch
                val restoredTarget = if(target.kind == "proposal" && saved.pending != null) {
                    val prefix = target.readPath + "/"
                    require(saved.pending.path.startsWith(prefix))
                    val planId = saved.pending.path.removePrefix(prefix)
                    require(purposeUuid(planId))
                    target.copy(relatedId = planId)
                } else target
                record = saved
                _state.value = _state.value.copy(target = restoredTarget, recoveryReady = true, draft = saved.draft, pending = saved.pending != null)
                val supported = available(current)
                if (!valid(version,current)) return@launch
                _state.value = _state.value.copy(supported = supported)
                if (supported) load(current,restoredTarget,version,++reading)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (valid(version,current)) _state.value = _state.value.copy(error = error.message ?: "No se pudo conectar. Reintenta.") }
            finally { if (valid(version,current)) _state.value = _state.value.copy(loading = false) }
        }
    }
    fun refresh() {
        val current = lease ?: return
        val target = _state.value.target ?: return
        if (!visible || !current.isCurrent() || _state.value.busy) return
        if (!_state.value.recoveryReady || !_state.value.supported) { bind(target,true); return }
        readJob?.cancel(); val version = epoch; val sequence = ++reading
        _state.value = _state.value.copy(loading = true, error = null)
        readJob = viewModelScope.launch {
            try { load(current,target,version,sequence) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (valid(version,current) && sequence == reading) _state.value = _state.value.copy(error = error.message ?: "No se pudo actualizar. Reintenta.") }
            finally { if (valid(version,current) && sequence == reading) _state.value = _state.value.copy(loading = false) }
        }
    }
    private suspend fun load(current: AuthSessionLease, target: PurposeTarget, version: Long, sequence: Long) {
        val body = read(current,target)
        validatePurposePayload(target,body,checkNotNull(current.accountId))
        if (valid(version,current) && sequence == reading) _state.value = _state.value.copy(payload = body, error = null)
    }
    fun draft(value: JsonObject?) {
        val current = lease ?: return; val target = _state.value.target ?: return; val version = epoch
        if (!current.isCurrent() || !_state.value.recoveryReady || _state.value.busy || _state.value.pending) return
        _state.value = _state.value.copy(draft = value, notice = null)
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(300)
            try { journal.withLock { if (valid(version,current)) withContext(NonCancellable) {
                val updated = recoveryWrite(current,target,record,record.copy(draft = value))
                if (valid(version,current)) record = updated
            } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (valid(version,current)) _state.value = _state.value.copy(recoveryReady = false, error = error.message) }
        }
    }
    fun act(action: String, fields: JsonObject = buildJsonObject {}, part: String? = null, onConfirmed: (() -> Unit)? = null) {
        val snapshot = _state.value; val target = snapshot.target ?: return
        if (!snapshot.supported || !snapshot.recoveryReady || snapshot.busy || snapshot.loading || snapshot.pending || snapshot.payload == null) return
        val stateKey = when(target.kind) { "social" -> "outcome"; "community" -> if(part == "guide") "guide" else "journey"; else -> "plan" }
        val revision = (snapshot.payload[stateKey] as? JsonObject)?.get("revision")?.jsonPrimitive?.intOrNull ?: 0
        val pending = PurposePending(target.writePath(part), buildJsonObject {
            fields.forEach { (key,value) -> put(key,value) }
            put("operationId",UUID.randomUUID().toString()); put("expectedRevision",revision); put("action",action)
        })
        write(pending, false, onConfirmed)
    }
    fun retry() { if (_state.value.recoveryReady && !_state.value.busy && _state.value.supported) record.pending?.let { write(it,true) } }
    private fun write(pending: PurposePending, retry: Boolean, onConfirmed: (() -> Unit)? = null) {
        val current = lease ?: return; val target = _state.value.target ?: return; val version = epoch
        if (!current.isCurrent()) return
        require(pending.path == target.writePath(if(pending.path.endsWith("/guide")) "guide" else "community_start"))
        reading++; readJob?.cancel(); draftJob?.cancel()
        val draft = _state.value.draft
        _state.value = _state.value.copy(busy = true, loading = false, error = null, notice = null)
        writeJob = viewModelScope.launch {
            var persisted: PurposeRecovery? = null
            try {
                journal.withLock { withContext(NonCancellable) {
                    if (!valid(version,current)) return@withContext
                    val saved = if (retry) record.also { require(it.pending == pending) } else recoveryWrite(current,target,record,record.copy(draft = draft, pending = pending))
                    persisted = saved; record = saved
                    _state.value = _state.value.copy(pending = true)
                } }
                val saved = persisted ?: return@launch
                val result = send(current,pending)
                val resultTarget = if(target.kind == "proposal") PurposeTarget("plan",target.relatedId) else target
                validatePurposePayload(resultTarget,result,checkNotNull(current.accountId))
                val consumesDraft = pending.command["action"]?.jsonPrimitive?.contentOrNull in setOf("conclude","propose","replan")
                val cleared = withContext(NonCancellable) { recoveryWrite(current,target,saved,saved.copy(draft = if(consumesDraft) null else saved.draft,pending = null)) }
                if (!valid(version,current)) return@launch
                record = cleared
                if(target.kind == "proposal") { bind(resultTarget,true); return@launch }
                _state.value = _state.value.copy(payload = result, draft = cleared.draft, pending = false, busy = false, notice = "Guardado.")
                if(visible) onConfirmed?.invoke()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val definitive = error is PurposeApiException && (error.status == 400 || error.code in setOf("PURPOSE_REVISION_CONFLICT","PURPOSE_AUTHOR_REQUIRED","PURPOSE_REFERENCE_UNAVAILABLE","PURPOSE_GAME_UNAVAILABLE","PURPOSE_PLAN_EXPIRED","PURPOSE_RECONFIRM_UNAVAILABLE","PURPOSE_TOO_EARLY","PURPOSE_PLAN_LIMIT"))
                if (persisted != null && definitive) try {
                    val saved = checkNotNull(persisted)
                    val cleared = withContext(NonCancellable) { recoveryWrite(current,target,saved,saved.copy(pending = null)) }
                    if (valid(version,current)) { record = cleared; _state.value = _state.value.copy(pending = false) }
                } catch (clearError: Exception) {
                    if (valid(version,current)) _state.value = _state.value.copy(recoveryReady = false, error = clearError.message)
                }
                if (valid(version,current)) _state.value = _state.value.copy(recoveryReady = _state.value.recoveryReady && persisted != null, error = error.message ?: "No se confirmó el envío. Reintenta para comprobarlo.")
            } finally { if (valid(version,current)) _state.value = _state.value.copy(busy = false) }
        }
    }
    private fun valid(version: Long,current: AuthSessionLease) = version == epoch && current.isCurrent()
}

internal val purposeJson = Json { ignoreUnknownKeys = true }
internal fun validatePurposePayload(target: PurposeTarget, body: JsonObject, actorId: String) {
    when(target.kind) {
        "social" -> purposeJson.decodeFromJsonElement<PostOutcomeCatalog>(body).also {
            require(it.postId == target.id); require(it.outcome == null || it.outcome.postId == target.id && it.outcome.revision > 0)
            require(it.eligible.size <= 20 && it.contributions.size <= 5 && (it.eligible + it.contributions).all { row -> purposeUuid(row.id) })
        }
        "community" -> purposeJson.decodeFromJsonElement<CommunityPurposeCatalog>(body).also {
            require(it.communityId == target.id && it.targets.size <= 60)
            require(it.targets.all { row -> purposeUuid(row.id) && row.kind in setOf("question","event","guide") && (row.kind != "question" || row.messageId?.let(::purposeUuid) == true) })
            java.time.Instant.parse(it.serverTime)
        }
        "gaming" -> purposeJson.decodeFromJsonElement<GamingPlanCatalog>(body).also { require(it.plans.size <= 20 && it.plans.map { plan -> plan.id }.distinct().size == it.plans.size); require(it.plans.all { plan -> plan.participants.any { member -> member.id == actorId } }) }
        "plan" -> purposeJson.decodeFromJsonElement<GamingPlanReceipt>(body).also {
            require(it.plan.id == target.id && it.plan.revision > 0 && it.plan.participants.size == 2 && it.plan.participants.map { member -> member.id }.distinct().size == 2 && it.plan.participants.any { member -> member.id == actorId })
            java.time.Instant.parse(it.plan.startsAt); java.time.Instant.parse(it.plan.serverTime)
            require(it.plan.durationMinutes in 15..240)
        }
        "proposal" -> purposeJson.decodeFromJsonElement<GamingProposalContext>(body).also { require(it.participants.size == 2 && it.participants.any { member -> member.id == actorId } && it.participants.any { member -> member.id == target.id }) }
    }
}
