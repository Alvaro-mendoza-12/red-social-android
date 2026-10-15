package conectate.com.core.observability

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel

/** Debug-only dependency preparation timing, with no account or content fields. */
@Composable
internal inline fun <reified T : ViewModel> startupViewModel(label: String): T {
    val started = if (conectate.com.BuildConfig.DEBUG) android.os.SystemClock.elapsedRealtime() else 0L
    val model = hiltViewModel<T>()
    if (conectate.com.BuildConfig.DEBUG) {
        android.util.Log.d("StartupTiming", "$label completed in ${android.os.SystemClock.elapsedRealtime() - started} ms")
    }
    return model
}
