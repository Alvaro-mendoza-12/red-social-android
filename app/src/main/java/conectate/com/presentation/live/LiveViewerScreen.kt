package conectate.com.presentation.live

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.presentation.common.AppFeedbackBus
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.RendererCommon

internal class LiveViewerExitGate {
    private val started = AtomicBoolean(false)

    fun runOnce(cleanup: () -> Unit): Boolean {
        if (!started.compareAndSet(false, true)) return false
        cleanup()
        return true
    }
}

@Composable
fun LiveViewerScreen(
    navController: NavController,
    liveId: String,
    viewModel: LiveSessionViewModel = hiltViewModel()
) {
    val viewerCount by viewModel.viewerCount.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val remoteTracks by viewModel.remoteVideoTracks.collectAsStateWithLifecycle()
    val eglBaseContext by viewModel.eglBaseContext.collectAsStateWithLifecycle()
    val session by viewModel.activeSession.collectAsStateWithLifecycle()
    val isFollowingHost by viewModel.isFollowingHost.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val uiError by viewModel.uiError.collectAsStateWithLifecycle()
    val moderation by viewModel.moderationState.collectAsStateWithLifecycle()
    val liveViewers by viewModel.liveViewers.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val hostTrack = remoteTracks.values.firstOrNull()
    val stream = session?.stream
    var wasConnectedToLive by remember(liveId) { mutableStateOf(false) }
    var manualLeaveRequested by remember(liveId) { mutableStateOf(false) }
    var showModerationDialog by remember(liveId) { mutableStateOf(false) }
    val exitGate = remember(liveId) { LiveViewerExitGate() }

    fun leaveViewer() {
        manualLeaveRequested = true
        exitGate.runOnce(viewModel::leaveLive)
        navController.popBackStack()
    }

    DisposableEffect(liveId) {
        onDispose {
            // Idempotent cleanup also covers navigation gestures and route
            // replacement. The gate prevents a second native WebRTC teardown
            // after the explicit "Salir" action already removed this route.
            exitGate.runOnce(viewModel::leaveLive)
        }
    }

    LaunchedEffect(liveId) {
        try {
            viewModel.joinAsViewer(liveId)
        } catch (e: Exception) {
            android.util.Log.e("LiveViewerScreen", "Error joining live", e)
            AppFeedbackBus.showError("Error al unirse al live: ${e.message}")
            navController.popBackStack()
        }
    }

    LaunchedEffect(session?.stream?.id) {
        if (session?.stream?.id == liveId) {
            wasConnectedToLive = true
        }
    }

    LaunchedEffect(session, wasConnectedToLive, manualLeaveRequested) {
        if (wasConnectedToLive && session == null && !manualLeaveRequested) {
            AppFeedbackBus.show("Live finalizado")
            navController.popBackStack()
        }
    }

    LaunchedEffect(stream?.hostUserId) {
        val hostId = stream?.hostUserId?.trim().orEmpty()
        if (hostId.isNotBlank()) {
            viewModel.refreshFollowing(hostId)
        }
    }

    LaunchedEffect(uiError) {
        val error = uiError ?: return@LaunchedEffect
        AppFeedbackBus.showError(error)
        if (session == null) {
            navController.popBackStack()
        }
        viewModel.clearError()
    }

    BackHandler {
        leaveViewer()
    }

    if (showModerationDialog && moderation.canModerate) {
        LiveModerationDialog(
            moderation = moderation,
            viewers = liveViewers,
            onDismiss = { showModerationDialog = false },
            onSaveSettings = { chatPaused, _ -> viewModel.updateModerationPause(chatPaused) },
            onSetModerator = { _, _ -> },
            onKick = { viewModel.moderateViewer(it, false) },
            onBan = { viewModel.moderateViewer(it, true) }
        )
    }

    val stateOverlayText = when {
        isPaused -> "LIVE EN PAUSA"
        !isVideoEnabled && currentSource == conectate.com.data.model.LiveSource.SCREEN -> "PANTALLA DESACTIVADA"
        !isVideoEnabled -> "CÁMARA DESACTIVADA"
        else -> null
    }

    LiveSessionScaffold(
        stream = stream,
        viewerCount = viewerCount,
        videoTrack = if (isPaused || !isVideoEnabled) null else hostTrack,
        sharedEglContext = eglBaseContext,
        mirrorVideo = false,
        videoScalingType = if (currentSource == conectate.com.data.model.LiveSource.SCREEN) {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        },
        comments = comments,
        reactions = reactions,
        onSendComment = viewModel::sendComment,
        onSendReaction = { viewModel.sendReaction("❤️") },
        commentingEnabled = !moderation.chatPaused || moderation.canModerate,
        commentingDisabledReason = "Chat pausado por moderación",
        stateOverlayText = stateOverlayText,
        placeholderText = if (currentSource == conectate.com.data.model.LiveSource.SCREEN) {
            "Esperando pantalla del creador…"
        } else {
            "Conectando directo…"
        },
        leadingTopBarAction = {
            IconButton(onClick = ::leaveViewer) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color.White
                )
            }
        },
        topBarActions = {
            val hostUserId = stream?.hostUserId?.trim().orEmpty()
            if (hostUserId.isNotBlank()) {
                LiveActionChip(
                    text = if (isFollowingHost == true) "Siguiendo" else "Seguir",
                    onClick = { viewModel.toggleFollow(hostUserId) }
                )
            }
            LiveActionChip(
                text = "Compartir",
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(
                            Intent.EXTRA_TEXT,
                            buildString {
                                append("Mira este live en Novvin")
                                if (!stream?.hostName.isNullOrBlank()) append(": ${stream?.hostName}")
                            }
                        )
                    context.startActivity(Intent.createChooser(shareIntent, "Compartir live"))
                }
            )
            LiveActionChip(
                text = "Salir",
                onClick = ::leaveViewer
            )
        },
        creatorControls = {
            if (moderation.canModerate) {
                LiveActionChip(
                    text = "Moderar",
                    onClick = { showModerationDialog = true }
                )
            }
        }
    )
}
