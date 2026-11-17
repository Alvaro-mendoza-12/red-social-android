package conectate.com.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import conectate.com.core.result.NovvinErrors
import conectate.com.presentation.errors.PersonifiedErrorState

@Composable
fun OfflineRetryState(
    modifier: Modifier = Modifier,
    title: String = "No tienes conexión a internet",
    subtitle: String = "Revisa tu conexión e intenta de nuevo.",
    actionLabel: String = "Intentar de nuevo",
    onRetry: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    PersonifiedErrorState(
        problem = NovvinErrors.offline.copy(title = title, description = subtitle),
        modifier = modifier,
        actionLabel = actionLabel,
        onRetry = onRetry,
        secondaryLabel = secondaryActionLabel,
        onSecondary = onSecondaryAction,
    )
}
