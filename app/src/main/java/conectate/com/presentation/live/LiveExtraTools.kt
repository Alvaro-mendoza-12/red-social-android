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
    val audience: (() -> Unit)?,
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
    val visit = rememberLiveToolAccountVisit(liveId, viewModel)
    val dialogs = rememberLiveToolDialogs(visit)
    val points = viewModel.channelPoints.collectAsStateWithLifecycle().value.let { viewModel.channelPointsFor(visit) }
    val moderation by viewModel.moderationState.collectAsStateWithLifecycle()
    val joined = visit.isCurrent && session?.stream?.id == liveId
    val audience = liveAudienceTool(liveId, viewModel.getCurrentUserId().orEmpty(), joined && capabilities.audienceToolsEnabled)
    LaunchedEffect(joined, moderation.canModerate) {
        if (!joined) dialogs.closeAll()
        if (!joined || !moderation.canModerate) dialogs.clearComment()
    }

    LifecycleResumeEffect(Triple(liveId, joined, isHost)) {
        if (joined && !isHost) visit.run {
            viewModel.setChannelPointsForeground(true)
            viewModel.startChannelPoints(liveId)
        }
        onPauseOrDispose {
            if (!isHost) {
                visit.run { viewModel.setChannelPointsForeground(false) }
                viewModel.stopChannelPoints(visit)
            }
        }
    }

    if (dialogs.gameVisible && joined && capabilities.drawGuessEnabled) LiveDrawGuessDialog(
        liveId = liveId, currentUserId = visit.owner.accountId.orEmpty(), isHost = isHost,
        participantIds = participantIds, onDismiss = dialogs::closeGame,
    )
    if (dialogs.pointsVisible && joined && points.available != false) LiveChannelPointsDialog(
        visit = visit, state = points, isHost = isHost, onDismiss = dialogs::closePoints,
        onRedeem = { viewModel.redeemChannelPointReward(liveId, it) },
        onUpdateSettings = { enabled, interval, value, cap -> viewModel.updateChannelPointSettings(liveId, enabled, interval, value, cap) },
        onCreateReward = { title, description, cost, stock, cooldown -> viewModel.createChannelPointReward(liveId, title, description, cost, stock, cooldown) },
        onUpdateRedemption = { id, status -> viewModel.decideChannelPointRedemption(liveId, id, status) },
    )
    dialogs.selectedComment?.takeIf { joined && moderation.canModerate }?.let { comment ->
        val protectedUser = comment.userId == viewModel.getCurrentUserId() || comment.userId == session?.stream?.hostUserId
        AlertDialog(onDismissRequest = dialogs::clearComment, title = { Text(comment.userName.ifBlank { "Comentario" }) },
            text = { Text("Puedes expulsar a esta persona del directo o bloquear su acceso a la transmisión.") },
            confirmButton = { TextButton(enabled = !protectedUser, onClick = {
                visit.run { viewModel.moderateViewer(comment.userId, ban = true); dialogs.clearComment() }
            }) { Text("Bloquear") } },
            dismissButton = {
                TextButton(enabled = !protectedUser, onClick = {
                    visit.run { viewModel.moderateViewer(comment.userId, ban = false); dialogs.clearComment() }
                }) { Text("Expulsar") }
                TextButton(onClick = dialogs::clearComment) { Text("Cancelar") }
            })
    }
    return LiveExtraToolActions(
        audience = audience,
        drawGuess = if (joined && capabilities.drawGuessEnabled) (dialogs::openGame) else null,
        channelPoints = if (joined && points.available != false) ({
            visit.run { viewModel.refreshChannelPoints(liveId, includeRedemptions = isHost); dialogs.openPoints() }
        }) else null,
        commentOptions = if (joined && moderation.canModerate) (dialogs::selectComment) else null,
    )
}
