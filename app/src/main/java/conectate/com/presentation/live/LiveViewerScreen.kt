package conectate.com.presentation.live

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.wallet.GiftCelebrationOverlay
import conectate.com.presentation.wallet.GiftSelectionDialog
import conectate.com.presentation.wallet.GooglePlayRechargeDialog
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
    val reactionCount by viewModel.reactionCount.collectAsStateWithLifecycle()
    val giftEvents by viewModel.giftEvents.collectAsStateWithLifecycle()
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
    val collaborationCapabilities by viewModel.collaborationCapabilities.collectAsStateWithLifecycle()
    val guestInvitations by viewModel.guestInvitations.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val hostTrack = remoteTracks.values.firstOrNull()
    val stream = session?.stream
    var wasConnectedToLive by remember(liveId) { mutableStateOf(false) }
    var manualLeaveRequested by remember(liveId) { mutableStateOf(false) }
    var exitRequested by remember(liveId) { mutableStateOf(false) }
    var showModerationDialog by remember(liveId) { mutableStateOf(false) }
    var showGiftSheet by remember(liveId) { mutableStateOf(false) }
    var showRechargeDialog by remember(liveId) { mutableStateOf(false) }
    var invitationAwaitingPermission by remember(liveId) { mutableStateOf<String?>(null) }
    val exitGate = remember(liveId) { LiveViewerExitGate() }
    val currentUserId = viewModel.getCurrentUserId().orEmpty()
    val pendingInvitation = guestInvitations.firstOrNull {
        it.status == "pending" && it.inviteeUserId.equals(currentUserId, ignoreCase = true)
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val invitationId = invitationAwaitingPermission
        invitationAwaitingPermission = null
        if (granted && invitationId != null) {
            viewModel.respondToGuestInvitation(liveId, invitationId, accept = true)
        } else if (!granted) {
            AppFeedbackBus.showError("El micrófono es necesario para participar como invitado.")
        }
    }

    fun leaveViewer() {
        manualLeaveRequested = true
        exitGate.runOnce { exitRequested = true }
    }

    LaunchedEffect(exitRequested) {
        if (!exitRequested) return@LaunchedEffect
        // Remove the renderer from the composition before native tracks are
        // released. This avoids SurfaceViewRenderer callbacks racing teardown.
        withFrameNanos { }
        viewModel.leaveLiveAndAwait().onFailure { error ->
            android.util.Log.w("LiveViewerScreen", "Viewer cleanup did not finish cleanly", error)
        }
        if (!navController.popBackStack()) {
            navController.navigate(Screen.LiveDiscovery.route) { launchSingleTop = true }
        }
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
        val sessionId = session?.stream?.id ?: return@LaunchedEffect
        viewModel.startCollaborationPolling(sessionId)
    }

    LaunchedEffect(
        session?.stream?.id,
        collaborationCapabilities.guestsBattlesEnabled,
        collaborationCapabilities.sfuAvailable
    ) {
        val sessionId = session?.stream?.id ?: return@LaunchedEffect
        if (collaborationCapabilities.guestsBattlesEnabled && collaborationCapabilities.sfuAvailable) {
            viewModel.connectLiveKit(
                liveId = sessionId,
                role = "viewer",
                publishAudio = false,
                publishCamera = false
            ).onFailure {
                // El directo principal continúa por el transporte existente;
                // únicamente se pierde el audio opcional de los invitados.
                AppFeedbackBus.showError("No se pudo conectar el audio de los invitados.")
            }
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
            exitGate.runOnce { }
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
            exitGate.runOnce { }
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

    if (showGiftSheet && stream != null) {
        GiftSelectionDialog(
            receiverId = stream.hostUserId,
            receiverName = stream.hostName.ifBlank { "creador" },
            liveStreamId = stream.id,
            onDismiss = { showGiftSheet = false },
            onRecharge = { showRechargeDialog = true },
            onGiftSent = { receipt ->
                viewModel.announceCommittedGift(receipt)
                AppFeedbackBus.show("Regalo enviado")
            }
        )
    }

    if (showRechargeDialog) {
        GooglePlayRechargeDialog(
            accountId = viewModel.getCurrentUserId().orEmpty(),
            onDismiss = { showRechargeDialog = false }
        )
    }

    if (pendingInvitation != null && collaborationCapabilities.guestsBattlesEnabled) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Invitación al live") },
            text = { Text("El creador te invita a participar por voz. Puedes aceptar o rechazar.") },
            confirmButton = {
                TextButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.respondToGuestInvitation(liveId, pendingInvitation.id, accept = true)
                    } else {
                        invitationAwaitingPermission = pendingInvitation.id
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.respondToGuestInvitation(liveId, pendingInvitation.id, accept = false)
                }) { Text("Rechazar") }
            }
        )
    }

    val stateOverlayText = when {
        isPaused -> "LIVE EN PAUSA"
        currentSource == conectate.com.data.model.LiveSource.AUDIO -> "DIRECTO SOLO VOZ"
        !isVideoEnabled && currentSource == conectate.com.data.model.LiveSource.SCREEN -> "PANTALLA DESACTIVADA"
        !isVideoEnabled -> "CÁMARA DESACTIVADA"
        else -> null
    }

    LiveSessionScaffold(
        stream = stream,
        viewerCount = viewerCount,
        reactionCount = reactionCount,
        videoTrack = if (exitRequested || isPaused || !isVideoEnabled) null else hostTrack,
        sharedEglContext = if (exitRequested) null else eglBaseContext,
        mirrorVideo = false,
        videoScalingType = if (currentSource == conectate.com.data.model.LiveSource.SCREEN) {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        },
        comments = comments,
        reactions = reactions,
        onSendComment = viewModel::sendComment,
        onSendReaction = viewModel::sendReaction,
        commentingEnabled = !moderation.chatPaused || moderation.canModerate,
        commentingDisabledReason = "Chat pausado por moderación",
        stateOverlayText = stateOverlayText,
        placeholderText = when (currentSource) {
            conectate.com.data.model.LiveSource.SCREEN -> "Esperando pantalla del creador…"
            conectate.com.data.model.LiveSource.AUDIO -> "Escuchando al creador…"
            conectate.com.data.model.LiveSource.CAMERA -> "Conectando directo…"
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
                text = "Regalar",
                onClick = { showGiftSheet = true }
            )
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
        floatingOverlay = {
            GiftCelebrationOverlay(events = giftEvents)
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
