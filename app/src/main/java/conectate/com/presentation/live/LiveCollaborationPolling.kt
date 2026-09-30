package conectate.com.presentation.live

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Guest acceptance stays responsive even when optional room data is slow. */
internal suspend fun pollLiveCollaboration(
    refreshInvitations: suspend () -> Unit,
    refreshCapabilities: suspend () -> Unit,
    refreshRoom: suspend () -> Unit,
    refreshBattle: suspend () -> Unit,
    refreshSupporters: suspend () -> Unit,
    refreshGifts: suspend () -> Unit,
    intervalMillis: Long = 4_000L
) = coroutineScope {
    require(intervalMillis > 0L)
    launch {
        while (isActive) {
            refreshInvitations()
            // Some API methods return cancellation as Result.failure. Never
            // start another polling cycle after our parent has been cancelled.
            ensureActive()
            delay(intervalMillis)
        }
    }
    launch {
        refreshCapabilities()
        ensureActive()
        while (isActive) {
            coroutineScope {
                launch { refreshRoom() }
                launch { refreshBattle() }
                launch { refreshSupporters() }
                launch { refreshGifts() }
            }
            ensureActive()
            delay(intervalMillis)
        }
    }
}
