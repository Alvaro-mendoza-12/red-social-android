package conectate.com.core.observability

/** Numeric debug timing only; never records identities, tokens or user content. */
internal inline fun <T> measureStartupPhase(label: String, block: () -> T): T {
    if (!conectate.com.BuildConfig.DEBUG) return block()
    val started = android.os.SystemClock.elapsedRealtime()
    try {
        return block()
    } finally {
        android.util.Log.d("StartupTiming", "$label completed in ${android.os.SystemClock.elapsedRealtime() - started} ms")
    }
}
