package conectate.com.core.repository

import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.core.crash.CrashReporter
import conectate.com.core.logging.AppLogger
import conectate.com.core.result.AppError
import conectate.com.core.result.AppResult
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.network.sockets.SocketTimeoutException
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepositoryCallHandler @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger,
    private val crashReporter: CrashReporter
) {

    suspend fun <T> io(
        operation: String,
        block: suspend () -> T
    ): AppResult<T> = withContext(dispatchers.io) {
        try {
            AppResult.Success(block())
        } catch (throwable: Throwable) {
            val error = throwable.toAppError()
            logger.e("RepositoryCallHandler", "Operation failed: $operation (${error.message})", throwable)
            crashReporter.recordException(
                throwable = throwable,
                extra = mapOf(
                    "operation" to operation,
                    "error_type" to error::class.simpleName.orEmpty()
                )
            )
            AppResult.Error(error)
        }
    }

    private fun Throwable.toAppError(): AppError {
        return when (this) {
            is HttpRequestTimeoutException,
            is SocketTimeoutException -> AppError.Timeout(
                message = message ?: "Request timeout",
                cause = this
            )

            is ClientRequestException -> {
                when (response.status.value) {
                    401 -> AppError.Unauthorized(
                        message = "Unauthorized request",
                        cause = this
                    )
                    403 -> AppError.Forbidden(
                        message = "Forbidden request",
                        cause = this
                    )
                    404 -> AppError.NotFound(
                        message = "Resource not found",
                        cause = this
                    )
                    400, 422 -> AppError.Validation(
                        message = message,
                        cause = this
                    )
                    else -> AppError.Network(
                        message = message,
                        cause = this
                    )
                }
            }

            is ServerResponseException -> AppError.Network(
                message = message,
                cause = this
            )

            is IOException -> AppError.Network(
                message = message ?: "I/O error",
                cause = this
            )

            else -> AppError.Unexpected(
                message = message ?: "Unexpected repository error",
                cause = this
            )
        }
    }
}


