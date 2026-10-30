package conectate.com.presentation.live

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.model.LiveComment

internal data class LiveExtraToolActions(
    val drawGuess: (() -> Unit)?,
    val channelPoints: (() -> Unit)?,
    val commentOptions: ((LiveComment) -> Unit)?,
)

/** Active screens reuse the existing server-backed tools and their capability gates. */
@Composable
internal fun liveExtraTools(
    liveId: String,
    isHost: Boolean,
    participantIds: List<String>,
    viewModel: LiveSessionViewModel,
): LiveExtraToolActions {
    val session by viewModel.activeSession.collectAsStateWithLifecycle()
    val capabilities by viewModel.collaborationCapabilities.collectAsStateWithLifecycle()
    val points by viewModel.channelPoints.collectAsStateWithLifecycle()
    val moderation by viewModel.moderationState.collectAsStateWithLifecycle()
    val joined = session?.stream?.id == liveId
    var showGame by remember(liveId) { mutableStateOf(false) }
    var showPoints by remember(liveId) { mutableStateOf(false) }
    var selectedComment by remember(liveId) { mutableStateOf<LiveComment?>(null) }
    LaunchedEffect(joined, moderation.canModerate) {
        if (!joined) { showGame = false; showPoints = false }
        if (!joined || !moderation.canModerate) selectedComment = null
    }

    LifecycleResumeEffect(Triple(liveId, joined, isHost)) {
        if (joined && !isHost) {
            viewModel.setChannelPointsForeground(true)
            viewModel.startChannelPoints(liveId)
        }
        onPauseOrDispose {
            if (!isHost) {
                viewModel.setChannelPointsForeground(false)
                viewModel.stopChannelPoints()
            }
        }
    }

    if (showGame && joined && capabilities.drawGuessEnabled) LiveDrawGuessDialog(
        liveId = liveId, currentUserId = viewModel.getCurrentUserId().orEmpty(), isHost = isHost,
        participantIds = participantIds, onDismiss = { showGame = false },
    )
    if (showPoints && joined && points.available != false) LiveChannelPointsDialog(
        state = points, isHost = isHost, onDismiss = { showPoints = false },
        onRedeem = { viewModel.redeemChannelPointReward(liveId, it) },
        onUpdateSettings = { enabled, interval, value, cap -> viewModel.updateChannelPointSettings(liveId, enabled, interval, value, cap) },
        onCreateReward = { title, description, cost, stock, cooldown -> viewModel.createChannelPointReward(liveId, title, description, cost, stock, cooldown) },
        onUpdateRedemption = { id, status -> viewModel.decideChannelPointRedemption(liveId, id, status) },
    )
    selectedComment?.takeIf { joined && moderation.canModerate }?.let { comment ->
        val protectedUser = comment.userId == viewModel.getCurrentUserId() || comment.userId == session?.stream?.hostUserId
        AlertDialog(onDismissRequest = { selectedComment = null }, title = { Text(comment.userName.ifBlank { "Comentario" }) },
            text = { Text("Puedes expulsar a esta persona del directo o bloquear su acceso a la transmisión.") },
            confirmButton = { TextButton(enabled = !protectedUser, onClick = {
                viewModel.moderateViewer(comment.userId, ban = true); selectedComment = null
            }) { Text("Bloquear") } },
            dismissButton = {
                TextButton(enabled = !protectedUser, onClick = {
                    viewModel.moderateViewer(comment.userId, ban = false); selectedComment = null
                }) { Text("Expulsar") }
                TextButton(onClick = { selectedComment = null }) { Text("Cancelar") }
            })
    }
    return LiveExtraToolActions(
        drawGuess = if (joined && capabilities.drawGuessEnabled) ({ showGame = true }) else null,
        channelPoints = if (joined && points.available != false) ({
            viewModel.refreshChannelPoints(liveId, includeRedemptions = isHost); showPoints = true
        }) else null,
        commentOptions = if (joined && moderation.canModerate) ({ selectedComment = it }) else null,
    )
}
