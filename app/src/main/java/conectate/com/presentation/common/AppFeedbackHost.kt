package conectate.com.presentation.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class AppFeedbackStyle {
    Info,
    Success,
    Error
}

data class AppFeedbackMessage(
    val message: String,
    val style: AppFeedbackStyle = AppFeedbackStyle.Info,
    val duration: SnackbarDuration = SnackbarDuration.Short
)

object AppFeedbackBus {
    private val _messages = MutableSharedFlow<AppFeedbackMessage>(extraBufferCapacity = 32)
    val messages = _messages.asSharedFlow()

    fun show(
        message: String,
        style: AppFeedbackStyle = AppFeedbackStyle.Info,
        duration: SnackbarDuration = SnackbarDuration.Short
    ) {
        if (message.isBlank()) return
        _messages.tryEmit(AppFeedbackMessage(message = message, style = style, duration = duration))
    }

    fun showSuccess(message: String) = show(message, style = AppFeedbackStyle.Success)

    fun showError(message: String) = show(
        message = message,
        style = AppFeedbackStyle.Error,
        duration = SnackbarDuration.Long
    )

    fun showError(
        throwable: Throwable?,
        fallback: String = "Ocurrió un problema. Inténtalo de nuevo."
    ) = showError(throwable.toUserFacingMessage(fallback))
}

@Composable
fun AppFeedbackHost(
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var currentStyle by remember { mutableStateOf(AppFeedbackStyle.Info) }

    LaunchedEffect(Unit) {
        AppFeedbackBus.messages.collect { feedback ->
            currentStyle = feedback.style
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = feedback.message,
                withDismissAction = feedback.style == AppFeedbackStyle.Error,
                duration = feedback.duration
            )
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
        snackbar = { snackbarData ->
            val (containerColor, contentColor) = when (currentStyle) {
                AppFeedbackStyle.Info -> MaterialTheme.colorScheme.inverseSurface to MaterialTheme.colorScheme.inverseOnSurface
                AppFeedbackStyle.Success -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                AppFeedbackStyle.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            }
            Snackbar(
                containerColor = containerColor,
                contentColor = contentColor
            ) {
                Text(snackbarData.visuals.message)
            }
        }
    )
}






