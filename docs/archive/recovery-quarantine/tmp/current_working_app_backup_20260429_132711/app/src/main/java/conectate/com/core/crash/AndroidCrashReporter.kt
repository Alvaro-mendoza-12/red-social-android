package conectate.com.core.crash

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.crashlytics.ktx.crashlytics
import conectate.com.core.logging.AppLogger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidCrashReporter @Inject constructor(
    private val logger: AppLogger
) : CrashReporter {

    private val currentUserId = AtomicReference<String?>(null)
    private val crashlytics = Firebase.crashlytics

    override fun setUserId(userId: String?) {
        currentUserId.set(userId)
        crashlytics.setCustomKey("user_id", userId ?: "anonymous")
    }

    override fun recordException(throwable: Throwable, extra: Map<String, String>) {
        var runtime = Runtime.getRuntime()
        var usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        var maxMb = runtime.maxMemory() / (1024 * 1024)
        var origin = throwable.stackTrace.firstOrNull()
        var message = buildString {
            append("CrashReporter exception")
            append(" type=")
            append(throwable::class.java.name)
            origin?.let {
                append(" origin=")
                append(it.className)
                append(":")
                append(it.lineNumber)
            }
            append(" heapMb=")
            append(usedMb)
            append("/")
            append(maxMb)
            currentUserId.get()?.let { append(" userId=$it") }
            if (extra.isNotEmpty()) {
                append(" extra=")
                append(extra.entries.joinToString(",") { "${it.key}=${it.value}" })
            }
        }

        // Log local
        logger.e("CrashReporter", message, throwable)

        // Report to Crashlytics
        extra.forEach { (key, value) -> crashlytics.setCustomKey(key, value) }
        currentUserId.get()?.let { crashlytics.setCustomKey("user_id", it) }
        crashlytics.setCustomKey("heap_used_mb", usedMb.toInt())
        crashlytics.setCustomKey("heap_max_mb", maxMb.toInt())
        if (origin != null) {
            crashlytics.setCustomKey("origin_class", origin.className)
            crashlytics.setCustomKey("origin_line", origin.lineNumber)
        }
        crashlytics.recordException(throwable)
    }

    override fun recordFatalException(threadName: String, throwable: Throwable) {
        var runtime = Runtime.getRuntime()
        var usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        var maxMb = runtime.maxMemory() / (1024 * 1024)
        var origin = throwable.stackTrace.firstOrNull()
        var message = buildString {
            append("Fatal uncaught exception thread=")
            append(threadName)
            append(" type=")
            append(throwable::class.java.name)
            origin?.let {
                append(" origin=")
                append(it.className)
                append(":")
                append(it.lineNumber)
            }
            append(" heapMb=")
            append(usedMb)
            append("/")
            append(maxMb)
            currentUserId.get()?.let { append(" userId=$it") }
        }

        // Log local
        logger.e("CrashReporter", message, throwable)

        // Report to Crashlytics
        crashlytics.setCustomKey("thread_name", threadName)
        crashlytics.setCustomKey("heap_used_mb", usedMb.toInt())
        crashlytics.setCustomKey("heap_max_mb", maxMb.toInt())
        currentUserId.get()?.let { crashlytics.setCustomKey("user_id", it) }
        if (origin != null) {
            crashlytics.setCustomKey("origin_class", origin.className)
            crashlytics.setCustomKey("origin_line", origin.lineNumber)
        }
        crashlytics.recordException(throwable)
    }

    override fun recordMessage(message: String, attributes: Map<String, String>) {
        val payload = if (attributes.isEmpty()) {
            message
        } else {
            "$message | ${attributes.entries.joinToString(",") { "${it.key}=${it.value}" }}"
        }

        // Log local
        logger.i("CrashReporter", payload)

        // Log to Crashlytics as non-fatal info
        crashlytics.log(payload)
        attributes.forEach { (key, value) ->
            crashlytics.setCustomKey(key, value)
        }
    }
}



