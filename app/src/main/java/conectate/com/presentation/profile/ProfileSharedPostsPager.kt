package conectate.com.presentation.profile

import conectate.com.data.localfallback.LocalFallbackFeedResponse
import conectate.com.data.model.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class ProfileSharedPostsPager(
    private val scope: CoroutineScope, private val reads: ProfileReadScope,
    private val fetch: suspend (String, Int) -> Result<LocalFallbackFeedResponse>,
    private val publish: (List<Post>, Boolean) -> Unit, private val failed: () -> Unit,
) {
    private var ticket: ProfileReadScope.Ticket? = null
    private var offset: Int? = null
    private var job: Job? = null
    private var revision = 0L
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    fun clear() { revision++; job?.cancel(); ticket = null; offset = null; _loading.value = false; _hasMore.value = false }
    fun refresh(target: String) {
        clear()
        ticket = reads.snapshotFor(target) ?: return
        offset = 0
        load()
    }
    fun load() {
        val active = ticket ?: return
        val next = offset ?: return
        if (_loading.value || !reads.isCurrent(active)) return
        val version = revision
        _loading.value = true
        job = scope.launch {
            try {
                val response = fetch(active.target, next).getOrThrow()
                if (version != revision || !reads.isCurrent(active)) return@launch
                publish(response.items, next == 0)
                offset = response.nextOffset?.takeIf { response.hasMore && it > next }
                _hasMore.value = offset != null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (version == revision && reads.isCurrent(active)) { _hasMore.value = true; failed() }
            } finally { if (version == revision) _loading.value = false }
        }
    }
}
