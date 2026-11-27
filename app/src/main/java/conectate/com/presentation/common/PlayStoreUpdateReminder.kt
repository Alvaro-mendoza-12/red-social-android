package conectate.com.presentation.common

import conectate.com.core.network.runReadWithDeadline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class PlayStoreUpdateReminder(
    private val scope: CoroutineScope,
    private val installedVersion: Int,
    private val readAvailableVersion: suspend () -> Int?,
) {
    private val pendingVersion = MutableStateFlow<Int?>(null)
    val availableVersion = pendingVersion.asStateFlow()

    private var foreground = false
    private var check: Job? = null

    fun enterForeground() {
        if (foreground) return
        foreground = true
        check = scope.launch {
            val result = runReadWithDeadline(5_000, readAvailableVersion)
            currentCoroutineContext().ensureActive()
            pendingVersion.value = result.getOrNull()?.takeIf { it > installedVersion }
        }
    }

    fun leaveForeground() {
        foreground = false
        check?.cancel()
        check = null
        dismiss()
    }

    fun dismiss() {
        pendingVersion.value = null
    }
}
