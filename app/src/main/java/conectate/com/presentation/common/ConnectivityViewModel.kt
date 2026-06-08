package conectate.com.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.sync.SyncManager
import conectate.com.util.NetworkConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ConnectivityViewModel @Inject constructor(
    networkObserver: NetworkConnectivityObserver,
    private val syncManager: SyncManager
) : ViewModel() {

    val isConnected: StateFlow<Boolean> = networkObserver.isConnected.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = networkObserver.isNetworkAvailable()
    )

    fun retrySync(reason: String = "ui_retry") {
        syncManager.triggerSync(reason)
    }
}
