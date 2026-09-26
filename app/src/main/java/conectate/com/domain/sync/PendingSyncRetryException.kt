package conectate.com.domain.sync

/** Durable work remains; WorkManager must retain it with backoff, not report success. */
class PendingSyncRetryException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

internal fun shouldRetrySync(error: Throwable, attempt: Int): Boolean =
    error is PendingSyncRetryException || attempt < 3
