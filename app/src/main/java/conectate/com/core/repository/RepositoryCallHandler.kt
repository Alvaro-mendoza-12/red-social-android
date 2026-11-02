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
import kotlinx.coroutines.CancellationException
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
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
                message = "La solicitud está tardando demasiado. Inténtalo de nuevo.",
                cause = this
            )

            is ClientRequestException -> {
                when (response.status.value) {
                    401 -> AppError.Unauthorized(
                        message = "Tu sesión venció. Inicia sesión de nuevo.",
                        cause = this
                    )
                    403 -> AppError.Forbidden(
                        message = "No tienes permiso para realizar esta acción.",
                        cause = this
                    )
                    404 -> AppError.NotFound(
                        message = "Este contenido ya no está disponible.",
                        cause = this
                    )
                    400, 422 -> AppError.Validation(
                        message = "Revisa los datos e inténtalo de nuevo.",
                        cause = this
                    )
                    else -> AppError.Network(
                        message = "No se pudo completar la solicitud. Inténtalo de nuevo.",
                        cause = this
                    )
                }
            }

            is ServerResponseException -> AppError.Network(
                message = "No se pudo completar la solicitud. Inténtalo de nuevo.",
                cause = this
            )

            is IOException -> AppError.Network(
                message = "No se pudo conectar. Revisa tu conexión e inténtalo otra vez.",
                cause = this
            )

            else -> AppError.Unexpected(
                message = "No se pudo completar la acción. Inténtalo de nuevo.",
                cause = this
            )
        }
    }
}


