package conectate.com.presentation.messages

import conectate.com.core.concurrency.AccountTasks
import conectate.com.data.localfallback.GlobalSavedMessage
import conectate.com.data.localfallback.GlobalSavedMessagesResponse
import conectate.com.presentation.common.toUserFacingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SavedInboxState(
    val accountId: String? = null,
    val items: List<GlobalSavedMessage> = emptyList(),
    val rooms: Map<String, String> = emptyMap(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val readError: String? = null,
    val actionError: String? = null,
    val removing: Set<String> = emptySet(),
    val kind: String = "",
    val roomId: String = "",
    val type: String = "",
    val from: String = "",
    val search: String = "",
) {
    val error: String? get() = actionError ?: readError
}

internal val GlobalSavedMessage.savedInboxKey: String get() = "$roomKind:$roomId:$messageId"

/** Hide an outgoing snapshot even before the session collector gets its turn. */
internal fun SavedInboxState.forAccount(account: String?): SavedInboxState {
    val owner = account?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return SavedInboxState()
    return if (accountId == owner) this else SavedInboxState(accountId = owner, loading = true)
}

/** All UI entry points run on the owner scope; account changes cancel its work. */
internal class SavedInboxController(
    scope: CoroutineScope,
    private val currentAccount: () -> String?,
    private val read: suspend (String, SavedInboxState, String?) -> Result<GlobalSavedMessagesResponse>,
    private val removeMarker: suspend (String, GlobalSavedMessage) -> Result<Unit>,
    private val timeoutMillis: Long = 30_000,
) {
    private val tasks = AccountTasks(scope)
    private val mutableState = MutableStateFlow(SavedInboxState())
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var readJob: Job? = null
    private var removalVersion = 0L
    private val removedAt = mutableMapOf<String, Long>()
    private fun owner() = currentAccount()?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    private fun owns(account: String?) = account != null && state.value.accountId == account && owner() == account

    fun bindAccount(refreshIfIdle: Boolean = false) {
        val current = owner()
        if (state.value.accountId != current) {
            revision++
            tasks.reset()
            readJob = null
            removalVersion = 0L
            removedAt.clear()
            mutableState.value = SavedInboxState(accountId = current)
            if (current != null) refresh()
        } else if (refreshIfIdle && !state.value.loading) refresh()
    }

    fun setFilters(kind: String = state.value.kind, roomId: String = state.value.roomId,
        type: String = state.value.type, from: String = state.value.from, search: String = state.value.search) {
        if (!owns(state.value.accountId)) { bindAccount(); return }
        if (listOf(kind, roomId, type, from, search) ==
            listOf(state.value.kind, state.value.roomId, state.value.type, state.value.from, state.value.search)) return
        mutableState.value = state.value.copy(kind = kind, roomId = roomId, type = type, from = from,
            search = search, items = emptyList(), nextCursor = null, readError = null, actionError = null)
        refresh()
    }

    fun refresh() {
        if (!owns(state.value.accountId)) { bindAccount(); return }
        // Keep the confirmed list while refreshing; a failed read is not an empty collection.
        loadPage(null)
    }

    fun loadMore() {
        if (state.value.loading) return
        val cursor = state.value.nextCursor ?: return
        loadPage(cursor)
    }

    private fun loadPage(cursor: String?) {
        val account = state.value.accountId ?: return
        if (!owns(account)) { bindAccount(); return }
        readJob?.cancel()
        val requestRevision = ++revision
        val readRemovalVersion = removalVersion
        val filters = state.value
        mutableState.value = state.value.copy(loading = true, readError = null)
        readJob = tasks.scope.launch {
            try {
                val page = withTimeoutOrNull(timeoutMillis) { read(account, filters, cursor).getOrThrow() }
                    ?: throw IllegalStateException("Los mensajes guardados están tardando demasiado. Inténtalo de nuevo.")
                currentCoroutineContext().ensureActive()
                if (!owns(account) || requestRevision != revision) return@launch
                val items = (if (cursor == null) page.saved else filters.items + page.saved)
                    .distinctBy { it.savedInboxKey }
                    .filter { (removedAt[it.savedInboxKey] ?: Long.MIN_VALUE) <= readRemovalVersion }
                removedAt.entries.removeAll { it.value <= readRemovalVersion }
                mutableState.value = state.value.copy(items = items,
                    rooms = filters.rooms + page.saved.associate { it.roomId to it.roomName },
                    nextCursor = page.nextCursor, loading = false, readError = null, actionError = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (owns(account) && requestRevision == revision) mutableState.value = state.value.copy(
                    loading = false, readError = error.toUserFacingMessage("No se pudieron cargar los guardados. Inténtalo de nuevo."))
            }
        }
    }

    fun remove(item: GlobalSavedMessage, expectedAccount: String?) {
        val account = state.value.accountId ?: return
        val key = item.savedInboxKey
        if (expectedAccount != account || !owns(account) || key in state.value.removing ||
            state.value.items.none { it.savedInboxKey == key }) return
        mutableState.value = state.value.copy(removing = state.value.removing + key, actionError = null)
        tasks.scope.launch {
            try {
                withTimeoutOrNull(timeoutMillis) { removeMarker(account, item).getOrThrow() }
                    ?: throw IllegalStateException("No pudimos confirmar el cambio a tiempo. Inténtalo de nuevo.")
                currentCoroutineContext().ensureActive()
                if (!owns(account)) return@launch
                // Older reads cannot resurrect this item. Newer reads may show
                // a bookmark saved again from another device after this ACK.
                removedAt[key] = ++removalVersion
                mutableState.value = state.value.copy(items = state.value.items.filterNot { it.savedInboxKey == key },
                    actionError = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (owns(account)) mutableState.value = state.value.copy(
                    actionError = error.toUserFacingMessage("No se pudo quitar el mensaje guardado. Inténtalo de nuevo."))
            } finally {
                if (owns(account)) mutableState.value = state.value.copy(removing = state.value.removing - key)
            }
        }
    }

    fun close() { tasks.close() }
}
