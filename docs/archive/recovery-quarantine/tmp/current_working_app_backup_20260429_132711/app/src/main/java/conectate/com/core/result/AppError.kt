package conectate.com.core.result

sealed class AppError(
    open val message: String,
    open val cause: Throwable? = null
) {
    data class Network(
        override val message: String = "Network error",
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    data class Timeout(
        override val message: String = "Request timeout",
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    data class Unauthorized(
        override val message: String = "Unauthorized",
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    data class Forbidden(
        override val message: String = "Forbidden",
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    data class NotFound(
        override val message: String = "Not found",
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    data class Validation(
        override val message: String = "Validation error",
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    data class Unexpected(
        override val message: String = "Unexpected error",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
}

