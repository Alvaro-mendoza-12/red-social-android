package conectate.com.presentation.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.analytics.CreatorAnalyticsApiException
import conectate.com.data.model.CreatorAnalyticsContent
import conectate.com.data.model.LiveAnalyticsReport
import conectate.com.data.model.VideoAnalyticsReport
import conectate.com.data.repository.CreatorAnalyticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreatorAnalyticsUiState(
    val isLoading: Boolean = true,
    val isReportLoading: Boolean = false,
    val isUnavailable: Boolean = false,
    val items: List<CreatorAnalyticsContent> = emptyList(),
    val selected: CreatorAnalyticsContent? = null,
    val videoReport: VideoAnalyticsReport? = null,
    val liveReport: LiveAnalyticsReport? = null,
    val error: String? = null,
)

@HiltViewModel
class CreatorAnalyticsViewModel @Inject constructor(
    private val repository: CreatorAnalyticsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CreatorAnalyticsUiState())
    val state: StateFlow<CreatorAnalyticsUiState> = _state.asStateFlow()

    private var catalogJob: Job? = null
    private var reportJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        catalogJob?.cancel()
        reportJob?.cancel()
        catalogJob = viewModelScope.launch {
            _state.value = CreatorAnalyticsUiState(isLoading = true)
            repository.loadContent().fold(
                onSuccess = { catalog ->
                    val supported = catalog.items.filter { it.isVideo || it.isLive }
                    _state.update { it.copy(isLoading = false, items = supported, error = null) }
                    supported.firstOrNull()?.let(::select)
                },
                onFailure = { error ->
                    val unavailable = (error as? CreatorAnalyticsApiException)?.code == "CREATOR_ANALYTICS_DISABLED"
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isUnavailable = unavailable,
                            error = creatorAnalyticsErrorMessage(error),
                        )
                    }
                },
            )
        }
    }

    fun select(content: CreatorAnalyticsContent) {
        if (!content.isVideo && !content.isLive) return
        reportJob?.cancel()
        _state.update {
            it.copy(
                selected = content,
                isReportLoading = true,
                videoReport = null,
                liveReport = null,
                error = null,
            )
        }
        reportJob = viewModelScope.launch {
            val expectedId = content.contentId
            val result = if (content.isVideo) {
                repository.loadVideo(expectedId).map { Report.Video(it) }
            } else {
                repository.loadLive(expectedId).map { Report.Live(it) }
            }
            result.fold(
                onSuccess = { report ->
                    if (_state.value.selected?.contentId != expectedId) return@fold
                    _state.update {
                        when (report) {
                            is Report.Video -> it.copy(
                                isReportLoading = false,
                                videoReport = report.value,
                                liveReport = null,
                            )
                            is Report.Live -> it.copy(
                                isReportLoading = false,
                                liveReport = report.value,
                                videoReport = null,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (_state.value.selected?.contentId != expectedId) return@fold
                    _state.update {
                        it.copy(
                            isReportLoading = false,
                            error = creatorAnalyticsErrorMessage(error),
                        )
                    }
                },
            )
        }
    }

    private sealed interface Report {
        data class Video(val value: VideoAnalyticsReport) : Report
        data class Live(val value: LiveAnalyticsReport) : Report
    }
}
