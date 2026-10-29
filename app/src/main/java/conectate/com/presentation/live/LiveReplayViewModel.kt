package conectate.com.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.LiveStream
import conectate.com.data.repository.LiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LiveReplayViewModel @Inject constructor(
    private val liveRepository: LiveRepository
) : ViewModel() {
    private val _replay = MutableStateFlow<LiveStream?>(null)
    val replay = _replay.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var request: kotlinx.coroutines.Job? = null
    private var requestedId: String? = null

    fun load(liveId: String) {
        if ((_loading.value && requestedId == liveId) || _replay.value?.id == liveId) return
        request?.cancel()
        requestedId = liveId
        _loading.value = true
        _error.value = null
        _replay.value = null
        request = viewModelScope.launch {
            try {
            conectate.com.core.network.runReadWithDeadline(10_000) { liveRepository.loadLiveReplay(liveId).getOrThrow() }
                .onSuccess { if (requestedId == liveId) _replay.value = it }
                .onFailure {
                    if (requestedId == liveId) _error.value = "No se pudo cargar esta repetición. Verifica tu conexión y reintenta."
                }
            } finally { if (requestedId == liveId) _loading.value = false }
        }
    }
}
