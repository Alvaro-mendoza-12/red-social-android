package conectate.com.core.crash

interface CrashReporter {
    fun setUserId(userId: String?)
    fun recordException(throwable: Throwable, extra: Map<String, String> = emptyMap())
    fun recordFatalException(threadName: String, throwable: Throwable)
    fun recordMessage(message: String, attributes: Map<String, String> = emptyMap())
}

