package conectate.com.data.localfallback

/** Retains the response status so a rejected message is never queued as a transport failure. */
internal class MessageSendHttpException(
    val statusCode: Int,
    val errorCode: String,
    cause: Exception,
) : Exception(cause.message, cause) {
    val isRetryable: Boolean get() = isRetryableMessageHttpStatus(statusCode)
}

internal fun isRetryableMessageHttpStatus(statusCode: Int): Boolean =
    statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599
