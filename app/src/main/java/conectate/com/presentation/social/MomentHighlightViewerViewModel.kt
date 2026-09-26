package conectate.com.presentation.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.MomentHighlightRepository
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class MomentHighlightViewerViewModel @Inject constructor(
    repository: MomentHighlightRepository,
    users: UserRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MomentHighlightViewerUiState())
    val state = mutableState.asStateFlow()
    private val loader = MomentHighlightLoader(viewModelScope, mutableState,
        repository::getHighlightItems, users::getUsersByIds)

    fun load(highlightId: String, retry: Boolean = false) = loader.load(highlightId, retry)
}
