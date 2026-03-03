package conectate.com.core.crash

import conectate.com.core.logging.AppLogger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidCrashReporter @Inject constructor(
    private val logger: AppLogger
) : CrashReporter {

    private val currentUserId = AtomicReference<String?>(null)

    override fun setUserId(userId: String?) {
        currentUserId.set(userId)
    }

    override fun recordException(throwable: Throwable, extra: Map<String, String>) {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val origin = throwable.stackTrace.firstOrNull()
        val message = buildString {
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
        logger.e("CrashReporter", message, throwable)
    }

    override fun recordFatalException(threadName: String, throwable: Throwable) {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val origin = throwable.stackTrace.firstOrNull()
        val message = buildString {
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
        logger.e("CrashReporter", message, throwable)
    }

    override fun recordMessage(message: String, attributes: Map<String, String>) {
        val payload = if (attributes.isEmpty()) {
            message
        } else {
            "$message | ${attributes.entries.joinToString(",") { "${it.key}=${it.value}" }}"
        }
        logger.i("CrashReporter", payload)
    }
}
