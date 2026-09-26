package conectate.com.presentation.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import java.time.Instant

data class CreatorAnalyticsUiState(
    val isLoading: Boolean = true,
    val isReportLoading: Boolean = false,
    val items: List<CreatorAnalyticsContent> = emptyList(),
    val selected: CreatorAnalyticsContent? = null,
    val period: CreatorAnalyticsPeriod = CreatorAnalyticsPeriod.THIRTY_DAYS,
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
            _state.value = CreatorAnalyticsUiState(isLoading = true, period = _state.value.period)
            repository.loadContent().fold(
                onSuccess = { catalog ->
                    val supported = catalog.items.filter { it.isVideo || it.isLive }
                    _state.update { it.copy(isLoading = false, items = supported, error = null) }
                    supported.firstOrNull()?.let(::select)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
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
            val expectedPeriod = _state.value.period
            val range = expectedPeriod.rangeEndingAt(Instant.now())
            val result = if (content.isVideo) {
                repository.loadVideo(expectedId, range).map { Report.Video(it) }
            } else {
                repository.loadLive(expectedId, range).map { Report.Live(it) }
            }
            result.fold(
                onSuccess = { report ->
                    if (_state.value.selected?.contentId != expectedId ||
                        _state.value.selected?.contentType != content.contentType ||
                        _state.value.period != expectedPeriod) return@fold
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
                    if (_state.value.selected?.contentId != expectedId ||
                        _state.value.selected?.contentType != content.contentType ||
                        _state.value.period != expectedPeriod) return@fold
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

    fun setPeriod(period: CreatorAnalyticsPeriod) {
        if (_state.value.period == period) return
        val selected = _state.value.selected
        _state.update { it.copy(period = period) }
        if (selected != null) select(selected)
    }

    private sealed interface Report {
        data class Video(val value: VideoAnalyticsReport) : Report
        data class Live(val value: LiveAnalyticsReport) : Report
    }
}
