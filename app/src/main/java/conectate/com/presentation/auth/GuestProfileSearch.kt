package conectate.com.presentation.auth

import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.localfallback.GuestProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface GuestProfileSearchState {
    val query: String

    data class Loading(override val query: String) : GuestProfileSearchState
    data class Results(override val query: String, val profiles: List<GuestProfile>) : GuestProfileSearchState
    data class Failed(override val query: String, val message: String) : GuestProfileSearchState
}

internal class GuestProfileSearch(
    private val scope: CoroutineScope,
    private val read: suspend (String) -> List<GuestProfile>,
) {
    private val current = MutableStateFlow<GuestProfileSearchState?>(null)
    val state = current.asStateFlow()
    private var searchJob: Job? = null

    fun submit(input: String) {
        val query = input.trim().take(50)
        if (query.length < 2) clear()
        else if (current.value?.query != query) load(query, debounce = true)
    }

    fun retry(failed: GuestProfileSearchState.Failed) {
        if (current.value === failed) load(failed.query, debounce = false)
    }

    fun clear() {
        searchJob?.cancel()
        searchJob = null
        current.value = null
    }

    private fun load(query: String, debounce: Boolean) {
        searchJob?.cancel()
        current.value = GuestProfileSearchState.Loading(query)
        searchJob = scope.launch {
            if (debounce) delay(350)
            val response = runReadWithDeadline(12_000) { read(query) }
            currentCoroutineContext().ensureActive()
            current.value = response.fold(
                onSuccess = { GuestProfileSearchState.Results(query, it.distinctBy(GuestProfile::id)) },
                onFailure = { error ->
                    val message = (error as? IllegalStateException)?.message
                        ?: "No pudimos buscar personas. Revisa tu conexión y vuelve a intentarlo."
                    GuestProfileSearchState.Failed(query, message)
                },
            )
        }
    }
}
