package conectate.com.presentation.components

import conectate.com.presentation.common.toUserFacingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class ReportSubmissionState(
    val isSubmitting: Boolean = false,
    val confirmed: Boolean = false,
    val error: String? = null,
)

/** A button click starts one request; only its actual ACK can confirm the form. */
internal class ReportSubmission(
    private val scope: CoroutineScope,
    private val isOwnerCurrent: () -> Boolean,
    private val timeoutMillis: Long = 90_000,
) {
    private val mutableState = MutableStateFlow(ReportSubmissionState())
    val state = mutableState.asStateFlow()

    fun submit(operation: suspend () -> Result<Unit>) {
        if (state.value.isSubmitting || state.value.confirmed || !isOwnerCurrent()) return
        // Set before scheduling: two taps in the same frame must not send twice.
        mutableState.value = ReportSubmissionState(isSubmitting = true)
        scope.launch {
            try {
                val result = withTimeoutOrNull(timeoutMillis) { operation() }
                currentCoroutineContext().ensureActive()
                if (!isOwnerCurrent()) return@launch
                val error = result?.exceptionOrNull()
                if (error is CancellationException) throw error
                mutableState.value = when {
                    result == null -> ReportSubmissionState(error = "No pudimos confirmar el envío a tiempo. Inténtalo de nuevo.")
                    result.isSuccess -> ReportSubmissionState(confirmed = true)
                    else -> ReportSubmissionState(error = error.toUserFacingMessage("No se pudo enviar el reporte. Inténtalo de nuevo."))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isOwnerCurrent()) mutableState.value = ReportSubmissionState(
                    error = error.toUserFacingMessage("No se pudo enviar el reporte. Inténtalo de nuevo."),
                )
            } finally {
                if (state.value.isSubmitting) mutableState.value = ReportSubmissionState()
            }
        }
    }
}
