package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Retain the selected content through Activity recreation, without retaining an Activity. */
@HiltViewModel
class UniversalForwardViewModel @Inject internal constructor(
    private val authRepository: AuthRepository, private val journal: ShareBatchJournal,
) : ViewModel() {
    val coordinator = UniversalForwardCoordinator(authRepository::captureAccountLease)
    private val _pending = MutableStateFlow(PendingShareNotice())
    internal val pending = _pending.asStateFlow()
    private var account: conectate.com.data.supabase.AuthSessionLease? = null
    private var loadJob: Job? = null
    private var loadRevision = 0L

    init { viewModelScope.launch {
        authRepository.getAuthStateFlow().collect {
            if (account?.isCurrent() != true) _pending.value = PendingShareNotice()
            refreshPending()
        }
    } }

    internal fun hasCurrentAccount() = account?.isCurrent() == true
    internal fun dismissNotice() { _pending.value = _pending.value.copy(dismissed = true) }
    internal fun refreshPending() {
        loadJob?.cancel()
        val revision = ++loadRevision
        val lease = authRepository.captureAccountLease()
        account = lease
        val owner = lease.accountId ?: run { _pending.value = PendingShareNotice(); return }
        loadJob = viewModelScope.launch {
            try {
                val ids = journal.pending(owner, lease::requireCurrent)
                lease.requireCurrent()
                _pending.value = _pending.value.copy(ids = ids, error = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (lease.isCurrent() && loadRevision == revision && account === lease) {
                    android.util.Log.w("ShareRecovery", "Cannot discover pending shares", error)
                    _pending.value = _pending.value.copy(ids = emptyList(), error = true)
                }
            }
        }
    }
}

internal data class PendingShareNotice(val ids: List<String> = emptyList(), val error: Boolean = false, val dismissed: Boolean = false)
