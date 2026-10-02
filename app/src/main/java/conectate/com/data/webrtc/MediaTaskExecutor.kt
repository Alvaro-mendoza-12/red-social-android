package conectate.com.data.webrtc

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Native callbacks may arrive after teardown; they must not crash their caller. */
internal class MediaTaskExecutor(
    private val delegate: ExecutorService = Executors.newSingleThreadExecutor(),
    private val onFailure: (Throwable) -> Unit,
) {
    val isShutdown: Boolean get() = delegate.isShutdown

    fun execute(onUnavailable: () -> Unit = {}, action: () -> Unit) {
        try {
            delegate.execute {
                if (delegate.isShutdown) onUnavailable()
                else try { action() } catch (error: Exception) { onFailure(error) }
            }
        } catch (_: RejectedExecutionException) {
            onUnavailable()
        }
    }

    fun shutdown() = delegate.shutdown()
}
