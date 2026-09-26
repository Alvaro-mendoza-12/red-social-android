package conectate.com.presentation.social

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Refreshes only this label; no feed reloads or background polling. */
@Composable
internal fun momentTimestampLabel(createdAt: String?): String {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val label by produceState(formatMomentTimestamp(createdAt), createdAt, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = formatMomentTimestamp(createdAt)
                delay(1_000)
            }
        }
    }
    return label
}
