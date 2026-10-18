package conectate.com.presentation.live

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import conectate.com.data.livekit.LiveKitRoomBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The desired plan and an explicit retry own connection; transport events only report failures. */
@Composable
internal fun LiveViewerCollaborationConnectionOwner(
    plan: LiveViewerCollaborationPlan?,
    transportState: LiveKitRoomBridge.State,
    leaving: Boolean,
    connect: suspend (LiveViewerCollaborationPlan) -> Result<Unit>,
    disconnect: () -> Unit = {},
    onConnected: (LiveViewerCollaborationPlan) -> Unit = {}
) {
    var retryNonce by remember(plan) { mutableIntStateOf(0) }
    var retryState by remember(plan) { mutableStateOf(LiveCollaborationRetryState()) }
    var retryRequested by remember(plan) { mutableStateOf(false) }
    val latestConnect by rememberUpdatedState(connect)
    val latestDisconnect by rememberUpdatedState(disconnect)
    val latestOnConnected by rememberUpdatedState(onConnected)

    LaunchedEffect(plan, retryNonce, leaving) {
        if (plan == null || leaving) {
            latestDisconnect()
            return@LaunchedEffect
        }
        val desired = plan
        retryRequested = false
        retryState = retryState.beginAttempt()
        val result = try {
            latestConnect(desired)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        currentCoroutineContext().ensureActive()
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        retryState = retryState.completeAttempt(result.isSuccess)
        if (result.isSuccess) {
            latestOnConnected(desired)
        } else {
            retryRequested = true
        }
    }

    // Observing a terminal event offers a button; it never starts or cancels a
    // connection attempt. SDK reconnection remains responsible for transient loss.
    LaunchedEffect(plan, transportState, retryState, leaving) {
        if (plan != null && !leaving && transportState == LiveKitRoomBridge.State.DISCONNECTED &&
            retryState.shouldOfferAfterDisconnect()
        ) {
            retryState = retryState.consumeDisconnect()
            retryRequested = true
        }
    }

    val transportBusy = transportState == LiveKitRoomBridge.State.CONNECTING ||
        transportState == LiveKitRoomBridge.State.RECONNECTING
    if (canShowLiveCollaborationRetry(
            requested = retryRequested,
            hasPlan = plan != null,
            leaving = leaving,
            attempting = retryState.attempting,
            transportBusy = transportBusy
        )
    ) {
        AlertDialog(
            onDismissRequest = { retryRequested = false },
            title = { Text("Conexión del directo interrumpida") },
            text = {
                Text("No se pudo conectar el audio y video del directo. Puedes reintentar sin salir de la transmisión.")
            },
            confirmButton = {
                TextButton(onClick = {
                    retryRequested = false
                    retryNonce++
                }) { Text("Reintentar") }
            },
            dismissButton = {
                TextButton(onClick = { retryRequested = false }) { Text("Continuar viendo") }
            }
        )
    }
}
