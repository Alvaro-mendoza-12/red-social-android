package conectate.com.core.observability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A small monotonic phase record; it contains no account, media URL or signaling payload. */
internal class MediaStartupTrace(private val nowNanos: () -> Long = System::nanoTime) {
    private var origin: Long? = null
    private val phases = MutableStateFlow<Map<String, Long>>(emptyMap())
    val snapshots = phases.asStateFlow()

    @Synchronized fun start() {
        origin = nowNanos()
        phases.value = emptyMap()
    }

    @Synchronized fun mark(phase: String) {
        val started = origin ?: return
        if (phase !in phases.value) phases.value = phases.value +
            (phase to ((nowNanos() - started).coerceAtLeast(0L) / 1_000_000L))
    }
}
