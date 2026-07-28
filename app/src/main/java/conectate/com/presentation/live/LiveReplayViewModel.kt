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

    fun load(liveId: String) {
        if (_loading.value || _replay.value?.id == liveId) return
        viewModelScope.launch {
            _loading.value = true
            liveRepository.loadLiveReplay(liveId)
                .onSuccess { _replay.value = it }
                .onFailure {
                    _error.value = it.message
                        ?: "No se pudo cargar esta repetición. Verifica tu conexión."
                }
            _loading.value = false
        }
    }
}
