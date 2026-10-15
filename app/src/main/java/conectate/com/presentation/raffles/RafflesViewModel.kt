package conectate.com.presentation.raffles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.raffles.*
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class RafflesUiState(
    val accountId: String = "",
    val items: List<Raffle> = emptyList(), val detail: RaffleDetail? = null,
    val members: List<RaffleParticipant> = emptyList(), val totalMembers: Int = 0,
    val canManage: Boolean = false, val loading: Boolean = true, val busy: Boolean = false,
    val membersLoading: Boolean = false, val nextCursor: String? = null,
    val membersComplete: Boolean = false,
    val error: String? = null, val retryOperation: Boolean = false,
    val canCreate: Boolean = canManage,
)

@HiltViewModel
class RafflesViewModel @Inject constructor(
    private val repository: RaffleRepository,
    private val auth: AuthService,
    private val realtime: UbuntuRealtimeClient,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val kind: String = saved.get<String>("kind").orEmpty()
    val roomId: String = saved.get<String>("roomId").orEmpty()
    private val channel = "raffles:$kind:$roomId"
    private val gate = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(RafflesUiState())
    val state = _state.asStateFlow()
    private var subscribed = false
    private var selectedId: String?
        get() = saved.get<String>("selectedRaffleId") ?: saved.get<String>("id")?.takeIf { it.isNotBlank() }
        set(value) { saved["selectedRaffleId"] = value; saved["id"] = "" }

    init {
        viewModelScope.launch {
            auth.currentSession.collect { session ->
                accountIsCurrent()
                if (subscribed) {
                    session?.accessToken?.let { realtime.updateAccessToken(it); realtime.subscribe(channel) }
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (subscribed && event.channel == channel && event.event in setOf("raffle_changed", "subscription_ready")) refresh()
            }
        }
    }
    private fun accountIsCurrent(): Boolean {
        val account = auth.getCurrentUserId().orEmpty()
        val previous = saved.get<String>("raffleAccount")
        if (previous != null && previous != account) {
            clearPending()
            selectedId = null
            _state.value = RafflesUiState()
            saved["raffleDraftName"] = ""
            saved["raffleDraftMembers"] = emptyList<String>()
            saved["raffleDraftManual"] = emptyList<String>()
        }
        saved["raffleAccount"] = account
        _state.value = _state.value.copy(accountId = account)
        return account.isNotBlank()
    }
    fun resume() {
        subscribed = true
        viewModelScope.launch {
            if (!accountIsCurrent()) { failed(RaffleApiException("AUTH_REQUIRED", 401)); return@launch }
            auth.ensureValidAccessToken().onSuccess { realtime.updateAccessToken(it); realtime.subscribe(channel) }
            refresh()
        }
    }
    fun pause() { subscribed = false; realtime.unsubscribe(channel) }
    fun open(id: String) { selectedId = id; _state.value = _state.value.copy(detail = null, loading = true); refresh() }
    fun backToList() { selectedId = null; _state.value = _state.value.copy(detail = null, loading = true); refresh() }

    fun refresh() {
        viewModelScope.launch {
            if (!gate.tryLock()) return@launch
            try {
                if (!accountIsCurrent()) { failed(RaffleApiException("AUTH_REQUIRED", 401)); return@launch }
                val account = auth.getCurrentUserId()
                val id = selectedId
                if (id == null) repository.list(kind, roomId).fold(
                    { if (account == auth.getCurrentUserId() && selectedId == null) {
                        val previous = _state.value.items
                        val items = (it.raffles + previous).distinctBy { row -> row.id }.sortedByDescending { row -> row.createdAt }
                        _state.value = _state.value.copy(items = items, canManage = it.canManage, canCreate = it.canCreate,
                            nextCursor = if (previous.size > 20) _state.value.nextCursor else it.nextCursor, error = null, loading = false)
                    } }, { if (account == auth.getCurrentUserId()) failed(it) })
                else repository.detail(kind, roomId, id).fold(
                    { if (account == auth.getCurrentUserId() && selectedId == id) applyDetail(it) },
                    { if (account == auth.getCurrentUserId()) failed(it) })
                _state.value = _state.value.copy(retryOperation = saved.get<String>("rafflePendingAction") != null)
            } finally { gate.unlock() }
        }
    }
    private fun applyDetail(detail: RaffleDetail) {
        // Keep already loaded historical pages while refreshing only the latest server state.
        val oldRounds = _state.value.detail?.takeIf { it.raffle.id == detail.raffle.id }?.rounds.orEmpty()
        val rounds = (detail.rounds + oldRounds).distinctBy { it.id }.sortedByDescending { it.number }
        _state.value = _state.value.copy(detail = detail.copy(rounds = rounds,
            nextRound = if (oldRounds.size > 10) _state.value.detail?.nextRound else detail.nextRound),
            canManage = detail.canManage, loading = false, busy = false, error = null)
    }
    fun loadMore() {
        viewModelScope.launch {
            if (!gate.tryLock()) return@launch
            try {
                if (!accountIsCurrent()) return@launch
                val account = auth.getCurrentUserId()
                val detail = _state.value.detail
                if (detail != null) {
                    val before = detail.nextRound ?: return@launch
                    repository.detail(kind, roomId, detail.raffle.id, before).fold({ page ->
                        if (account != auth.getCurrentUserId() || selectedId != detail.raffle.id) return@fold
                        _state.value = _state.value.copy(detail = detail.copy(rounds = (detail.rounds + page.rounds).distinctBy { it.id }, nextRound = page.nextRound))
                    }, { if (account == auth.getCurrentUserId()) failed(it) })
                } else {
                    val cursor = _state.value.nextCursor ?: return@launch
                    repository.list(kind, roomId, cursor).fold({ page ->
                        if (account != auth.getCurrentUserId() || selectedId != null) return@fold
                        _state.value = _state.value.copy(items = (_state.value.items + page.raffles).distinctBy { it.id }, nextCursor = page.nextCursor)
                    }, { if (account == auth.getCurrentUserId()) failed(it) })
                }
            } finally { gate.unlock() }
        }
    }
    fun loadMembers() {
        viewModelScope.launch {
            if (_state.value.membersLoading) return@launch
            _state.value = _state.value.copy(membersLoading = true, membersComplete = false, error = null)
            val account = auth.getCurrentUserId()
            val members = mutableListOf<RaffleParticipant>()
            var offset = 0
            try {
                do {
                    val page = repository.members(kind, roomId, offset).getOrThrow()
                    if (account != auth.getCurrentUserId()) return@launch
                    members += page.participants
                    _state.value = _state.value.copy(members = members.toList(), totalMembers = page.total,
                        canManage = page.canManage, canCreate = page.canCreate)
                    offset = page.nextOffset ?: -1
                } while (offset >= 0)
                _state.value = _state.value.copy(membersComplete = true)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { failed(error) }
            finally { _state.value = _state.value.copy(membersLoading = false) }
        }
    }
    fun create(name: String, description: String, members: List<String>, manuals: List<String>, start: Boolean) {
        if (!_state.value.canCreate || _state.value.busy || !accountIsCurrent()) return
        if (saved.get<String>("rafflePendingAction") != null) { retryPending(); return }
        saved["rafflePendingAction"] = "create"
        saved["rafflePendingBody"] = json.encodeToString(CreateRaffleRequest(UUID.randomUUID().toString(), name, description, members, manuals, start, start))
        retryPending()
    }
    fun command(action: String, automatic: Boolean = false) {
        val detail = _state.value.detail ?: return
        if (!detail.canManage || _state.value.busy) return
        if (saved.get<String>("rafflePendingAction") != null) { retryPending(); return }
        saved["rafflePendingAction"] = action
        saved["rafflePendingId"] = detail.raffle.id
        saved["rafflePendingBody"] = json.encodeToString(RaffleCommandRequest(UUID.randomUUID().toString(), detail.raffle.version, automatic))
        retryPending()
    }
    fun confirmNewRound(members: List<String>, manuals: List<String>) {
        val state = _state.value
        val detail = state.detail ?: return
        if (!detail.canManage || state.busy || !state.membersComplete || detail.raffle.status != "completed" || !accountIsCurrent()) return
        if (saved.get<String>("rafflePendingAction") != null) { retryPending(); return }
        val snapshot = state.members.mapNotNull { it.userId }.distinct().sorted().joinToString("\n")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        saved["rafflePendingAction"] = "new-round"
        saved["rafflePendingId"] = detail.raffle.id
        saved["rafflePendingBody"] = json.encodeToString(RaffleCommandRequest(UUID.randomUUID().toString(), detail.raffle.version,
            automatic = false, memberIds = members, manualNames = manuals, memberSnapshotHash = hash))
        retryPending()
    }
    fun retryPending() {
        viewModelScope.launch {
            if (!gate.tryLock()) return@launch
            try {
                if (!accountIsCurrent()) return@launch
                val account = auth.getCurrentUserId()
                val action = saved.get<String>("rafflePendingAction") ?: return@launch
                val body = saved.get<String>("rafflePendingBody") ?: return@launch
                _state.value = _state.value.copy(busy = true, error = null)
                val result = if (action == "create") repository.create(kind, roomId, json.decodeFromString(body))
                else repository.command(kind, roomId, requireNotNull(saved.get<String>("rafflePendingId")), action, json.decodeFromString(body))
                if (account != auth.getCurrentUserId()) { accountIsCurrent(); return@launch }
                result.fold({ detail -> clearPending(); selectedId = detail.raffle.id; applyDetail(detail) }, { error ->
                    if (error is RaffleApiException && error.status in 400..499) clearPending()
                    failed(error)
                })
            } finally { _state.value = _state.value.copy(busy = false, retryOperation = saved.get<String>("rafflePendingAction") != null); gate.unlock() }
        }
    }
    private fun clearPending() { saved.remove<String>("rafflePendingAction"); saved.remove<String>("rafflePendingBody"); saved.remove<String>("rafflePendingId") }
    private fun failed(error: Throwable) {
        val denied = error is RaffleApiException && error.status in setOf(401, 403, 404)
        _state.value = _state.value.copy(error = if (error is RaffleApiException) error.message else "Sin conexión o error de red. Reintenta para recuperar el estado del servidor.",
            loading = false, busy = false, detail = if (denied) null else _state.value.detail,
            items = if (denied) emptyList() else _state.value.items, members = if (denied) emptyList() else _state.value.members,
            canManage = if (denied) false else _state.value.canManage,
            canCreate = if (denied) false else _state.value.canCreate,
            membersComplete = if (denied) false else _state.value.membersComplete)
    }
    override fun onCleared() { pause(); super.onCleared() }
}
