package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Report
import conectate.com.data.model.VideoReport
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

sealed class MyReportsUiState {
    object Loading : MyReportsUiState()
    data class Success(
        val reports: List<Report>,
        val videoReports: List<VideoReport> = emptyList(),
        val warning: String? = null,
    ) : MyReportsUiState()
    data class Error(val message: String) : MyReportsUiState()
}

@HiltViewModel
class MyReportsViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val videoRepository: VideoRepository,
    private val authService: AuthService,
) : ViewModel() {
    private val mutable = MutableStateFlow<MyReportsUiState>(MyReportsUiState.Loading)
    val uiState: StateFlow<MyReportsUiState> = mutable.asStateFlow()
    private var owner = authService.captureAccountLease()
    private var loadJob: Job? = null
    private var generation = 0L

    init {
        load(owner)
        viewModelScope.launch {
            combine(authService.observeSession(), authService.accountVisitChanges) { _, _ -> authService.captureAccountLease() }
                .collect { visit ->
                    if (!owner.sameVisit(visit)) load(visit)
                }
        }
    }

    fun loadMyReports() = load(authService.captureAccountLease())

    private fun load(lease: AuthSessionLease) {
        owner = lease
        val request = ++generation
        loadJob?.cancel()
        if (!lease.isCurrent()) {
            mutable.value = MyReportsUiState.Error("Inicia sesión para consultar tus reportes.")
            return
        }
        mutable.value = MyReportsUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val (users, videos) = coroutineScope {
                    val userReports = async { read(lease) { messageRepository.getMyReports() } }
                    val videoReports = async { read(lease) { videoRepository.getMyVideoReports() } }
                    userReports.await() to videoReports.await()
                }
                publish(lease, request) {
                    if (users.isFailure && videos.isFailure) {
                        MyReportsUiState.Error("No se pudieron cargar tus reportes. Comprueba la conexión y reintenta.")
                    } else {
                        MyReportsUiState.Success(users.getOrDefault(emptyList()), videos.getOrDefault(emptyList()), when {
                            users.isFailure -> "No se pudieron cargar los reportes a usuarios. Puedes reintentarlo."
                            videos.isFailure -> "No se pudieron cargar los reportes a videos. Puedes reintentarlo."
                            else -> null
                        })
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                publish(lease, request) { MyReportsUiState.Error("No se pudieron cargar tus reportes. Reintenta.") }
            }
        }
    }

    private suspend fun <T> read(lease: AuthSessionLease, action: suspend () -> Result<T>): Result<T> = try {
        withTimeout(15000) {
            lease.requireCurrent()
            authService.ensureValidAccessToken().getOrThrow()
            lease.requireCurrent()
            action().also {
                currentCoroutineContext().ensureActive()
                lease.requireCurrent()
                (it.exceptionOrNull() as? CancellationException)?.let { cancelled -> throw cancelled }
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        lease.requireCurrent()
        Result.failure(timeout)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        lease.requireCurrent()
        Result.failure(error)
    }

    private fun publish(lease: AuthSessionLease, request: Long, state: () -> MyReportsUiState) {
        if (generation == request && owner === lease) authService.commitForAccountLease(lease) { mutable.value = state() }
    }
}
