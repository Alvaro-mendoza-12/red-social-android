package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Moment
import conectate.com.data.model.MomentHighlight
import conectate.com.data.model.MomentHighlightItemPreview
import conectate.com.data.repository.MomentHighlightRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MomentHighlightUiState(
    val isLoading: Boolean = false,
    val highlights: List<MomentHighlight> = emptyList(),
    val selectedHighlightId: String? = null,
    val selectedItems: List<MomentHighlightItemPreview> = emptyList(),
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class MomentHighlightViewModel @Inject constructor(
    private val repository: MomentHighlightRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MomentHighlightUiState())
    val uiState: StateFlow<MomentHighlightUiState> = _uiState.asStateFlow()

    fun loadHighlights(userId: String) {
        if (userId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.getHighlightsForUser(userId)
                .onSuccess { highlights ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        highlights = highlights,
                        selectedHighlightId = _uiState.value.selectedHighlightId?.takeIf { id ->
                            highlights.any { it.id == id }
                        }
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "No se pudieron cargar los destacados."
                    )
                }
        }
    }

    fun loadItems(highlightId: String) {
        if (highlightId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                selectedHighlightId = highlightId,
                error = null
            )
            repository.getHighlightItems(highlightId)
                .onSuccess { items ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        selectedItems = items
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        selectedItems = emptyList(),
                        error = error.message ?: "No se pudieron cargar los momentos destacados."
                    )
                }
        }
    }

    fun saveMomentToHighlight(moment: Moment, title: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)
            repository.saveMomentToHighlight(moment, title)
                .onSuccess { highlight ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "Momento guardado en ${highlight.title}"
                    )
                    loadHighlights(moment.userId)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "No se pudo guardar el destacado."
                    )
                }
        }
    }

    fun clearTransientMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }
}
