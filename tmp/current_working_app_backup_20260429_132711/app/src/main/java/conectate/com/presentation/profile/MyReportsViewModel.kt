package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Report
import conectate.com.data.model.VideoReport
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import javax.inject.Inject

sealed class MyReportsUiState {
    object Loading : MyReportsUiState()
    data class Success(
        val reports: List<Report>,
        val videoReports: List<VideoReport> = emptyList()
    ) : MyReportsUiState()
    data class Error(val message: String) : MyReportsUiState()
}

@HiltViewModel
class MyReportsViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MyReportsUiState>(MyReportsUiState.Loading)
    val uiState: StateFlow<MyReportsUiState> = _uiState.asStateFlow()

    init {
        loadMyReports()
    }

    fun loadMyReports() {
        viewModelScope.launch {
            try {
                _uiState.value = MyReportsUiState.Loading
                android.util.Log.d("MyReportsViewModel", "Loading reports...")
                val userReportsResult = async { messageRepository.getMyReports() }.await()
                val videoReportsResult = async { videoRepository.getMyVideoReports() }.await()

                val reports = userReportsResult.getOrDefault(emptyList())
                val videoReports = videoReportsResult.getOrDefault(emptyList())

                if (userReportsResult.isFailure && videoReportsResult.isFailure) {
                    val message = userReportsResult.exceptionOrNull()?.message
                        ?: videoReportsResult.exceptionOrNull()?.message
                        ?: "Error al cargar reportes"
                    _uiState.value = MyReportsUiState.Error(message)
                    return@launch
                }

                android.util.Log.d(
                    "MyReportsViewModel",
                    "Loaded ${reports.size} user reports and ${videoReports.size} video reports"
                )
                _uiState.value = MyReportsUiState.Success(reports = reports, videoReports = videoReports)
            } catch (e: Exception) {
                android.util.Log.e("MyReportsViewModel", "CRASH PREVENTED: Unexpected error in loadMyReports", e)
                _uiState.value = MyReportsUiState.Error("Error inesperado: ${e.message}")
            }
        }
    }
}
