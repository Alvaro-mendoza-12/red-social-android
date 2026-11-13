package conectate.com.presentation.live

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
fun LegacyLiveViewerScreen(
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
    val liveKitRemoteVideos by viewModel.liveKitRemoteVideos.collectAsStateWithLifecycle()
    val liveKitState by viewModel.liveKitState.collectAsStateWithLifecycle()
    val activeBattle by viewModel.activeBattle.collectAsStateWithLifecycle()
    val liveSupporters by viewModel.liveSupporters.collectAsStateWithLifecycle()
    val channelPoints by viewModel.channelPoints.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val hostTrack = remoteTracks.values.firstOrNull()
    val stream = session?.stream
    var wasConnectedToLive by remember(liveId) { mutableStateOf(false) }
    var manualLeaveRequested by remember(liveId) { mutableStateOf(false) }
    var exitRequested by remember(liveId) { mutableStateOf(false) }
    var showModerationDialog by remember(liveId) { mutableStateOf(false) }
    var showGiftSheet by remember(liveId) { mutableStateOf(false) }
    var showViewerToolsSheet by remember(liveId) { mutableStateOf(false) }
    var showRechargeDialog by remember(liveId) { mutableStateOf(false) }
    var rechargeMinimumCoins by remember(liveId) { mutableIntStateOf(0) }
    var permissionAction by remember(liveId) { mutableStateOf<String?>(null) }
    var showJoinRequestDialog by remember(liveId) { mutableStateOf(false) }
    var showDrawGuessDialog by remember(liveId) { mutableStateOf(false) }
    var showChannelPointsDialog by remember(liveId) { mutableStateOf(false) }
    var requestedCamera by remember(liveId) { mutableStateOf(false) }
    val exitGate = remember(liveId) { LiveViewerExitGate() }
    val currentUserId = viewModel.getCurrentUserId().orEmpty()
    val pendingInvitation = guestInvitations.firstOrNull {
        it.liveId.equals(liveId, ignoreCase = true) &&
            it.status == "pending" && it.initiatedBy == "host" &&
            it.inviteeUserId.equals(currentUserId, ignoreCase = true)
    }
    val ownJoinRequest = guestInvitations.firstOrNull {
        it.liveId.equals(liveId, ignoreCase = true) && it.initiatedBy == "viewer" &&
            it.inviteeUserId.equals(currentUserId, ignoreCase = true)
    }
    val guestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = permissionAction
        permissionAction = null
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cameraRequired = action?.endsWith(":camera") == true
        val cameraGranted = !cameraRequired || grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!audioGranted || !cameraGranted) {
            AppFeedbackBus.showError("Concede los permisos necesarios para participar en el live.")
            return@rememberLauncherForActivityResult
        }
        when {
            action?.startsWith("accept:") == true -> {
                val invitationId = action.substringAfter("accept:").substringBefore(":")
                requestedCamera = cameraRequired
                viewModel.respondToGuestInvitation(
                    liveId,
                    invitationId,
                    accept = true,
                    publishCamera = cameraRequired,
                    connectMediaAfterAccept = false
                )
            }
            action?.startsWith("request:") == true -> {
                requestedCamera = cameraRequired
                showJoinRequestDialog = false
                viewModel.requestToJoin(liveId)
            }
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, liveId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> viewModel.setChannelPointsForeground(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> viewModel.setChannelPointsForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.startChannelPoints(liveId)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopChannelPoints()
        }
    }

    LaunchedEffect(session?.stream?.id) {
        val sessionId = session?.stream?.id ?: return@LaunchedEffect
        viewModel.startCollaborationPolling(sessionId)
    }

    val collaborationPlan = liveViewerCollaborationPlan(
        liveId = session?.stream?.id?.takeIf { it.equals(liveId, ignoreCase = true) && !exitRequested },
        currentUserId = currentUserId,
        invitations = guestInvitations,
        guestsBattlesEnabled = collaborationCapabilities.guestsBattlesEnabled,
        sfuAvailable = collaborationCapabilities.sfuAvailable,
        requestedCamera = requestedCamera
    )
    LiveViewerCollaborationConnectionOwner(
        plan = collaborationPlan,
        transportState = liveKitState,
        leaving = exitRequested || manualLeaveRequested,
        disconnect = { viewModel.disconnectLiveKit() },
        connect = { plan ->
            viewModel.connectLiveKit(plan.liveId, plan.role, plan.publishAudio, plan.publishCamera)
        },
        onConnected = { plan ->
            if (plan.role == "guest") {
                AppFeedbackBus.show(if (plan.publishCamera) "Te uniste con cámara" else "Te uniste por voz")
            }
        }
    )

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
            onRecharge = { minimumCoins ->
                rechargeMinimumCoins = minimumCoins
                showRechargeDialog = true
            },
            onGiftSent = { receipt ->
                viewModel.announceCommittedGift(receipt)
                AppFeedbackBus.show("Regalo enviado")
            }
        )
    }

    if (showRechargeDialog) {
        GooglePlayRechargeDialog(
            accountId = viewModel.getCurrentUserId().orEmpty(),
            minimumCoins = rechargeMinimumCoins,
            onDismiss = {
                showRechargeDialog = false
                rechargeMinimumCoins = 0
            }
        )
    }

    if (pendingInvitation != null && collaborationCapabilities.guestsBattlesEnabled) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Invitación al live") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("El creador te invita a participar. Elige si quieres activar tu cámara o entrar solo por voz.")
                    TextButton(onClick = {
                        permissionAction = "accept:${pendingInvitation.id}:camera"
                        guestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                    }) { Text("Unirme con cámara") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        requestedCamera = false
                        viewModel.respondToGuestInvitation(
                            liveId, pendingInvitation.id, accept = true,
                            publishCamera = false, connectMediaAfterAccept = false
                        )
                    } else {
                        permissionAction = "accept:${pendingInvitation.id}:voice"
                        guestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                }) { Text("Solo voz") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.respondToGuestInvitation(liveId, pendingInvitation.id, accept = false)
                }) { Text("Rechazar") }
            }
        )
    }

    if (showJoinRequestDialog) {
        AlertDialog(
            onDismissRequest = { showJoinRequestDialog = false },
            title = { Text("Solicitar unirme") },
            text = { Text("El anfitrión debe aprobar tu solicitud. Podrás salir o apagar cámara y micrófono después.") },
            confirmButton = {
                TextButton(onClick = {
                    permissionAction = "request:camera"
                    guestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                }) { Text("Con cámara") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        permissionAction = "request:voice"
                        guestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }) { Text("Solo voz") }
                    TextButton(onClick = { showJoinRequestDialog = false }) { Text("Cancelar") }
                }
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

    if (showDrawGuessDialog && collaborationCapabilities.drawGuessEnabled) {
        LiveDrawGuessDialog(
            liveId = liveId,
            currentUserId = currentUserId,
            isHost = false,
            participantIds = emptyList(),
            onDismiss = { showDrawGuessDialog = false }
        )
    }

    if (showChannelPointsDialog && channelPoints.available != false) {
        LiveChannelPointsDialog(
            state = channelPoints,
            isHost = false,
            onDismiss = { showChannelPointsDialog = false },
            onRedeem = { viewModel.redeemChannelPointReward(liveId, it) },
            onUpdateSettings = { _, _, _, _ -> },
            onCreateReward = { _, _, _, _, _ -> },
            onUpdateRedemption = { _, _ -> },
        )
    }

    val canRequestJoin =
        collaborationCapabilities.guestsBattlesEnabled &&
            collaborationCapabilities.sfuAvailable &&
            stream?.allowGuestRequests == true
    val hasViewerSecondaryActions =
        collaborationCapabilities.drawGuessEnabled ||
            channelPoints.available != false ||
            canRequestJoin ||
            moderation.canModerate

    if (showViewerToolsSheet) {
        LiveSecondaryActionsSheet(
            title = "Más opciones",
            onDismiss = { showViewerToolsSheet = false },
            actions = buildList {
                if (canRequestJoin) {
                    val requestStatus = ownJoinRequest?.status
                    add(
                        LiveSecondaryAction(
                            key = "join",
                            label = when (requestStatus) {
                                "pending" -> "Solicitud enviada"
                                "accepted" -> "Participando en el live"
                                else -> "Solicitar unirme"
                            },
                            description = when (requestStatus) {
                                "pending" -> "El anfitrión todavía debe aceptar o rechazar tu solicitud."
                                "accepted" -> "Tu participación ya fue aceptada."
                                else -> "Pide participar por voz o con cámara; el anfitrión decide."
                            },
                            enabled = requestStatus !in setOf("pending", "accepted"),
                            onClick = { showJoinRequestDialog = true },
                        )
                    )
                }
                if (collaborationCapabilities.drawGuessEnabled) {
                    add(LiveSecondaryAction("game", "Jugar", "Abrir dibujo y adivinanza dentro del live.") { showDrawGuessDialog = true })
                }
                if (channelPoints.available != false) {
                    add(
                        LiveSecondaryAction("points", "Puntos del canal · ${channelPoints.balance}", "Consultar saldo, recompensas y canjes gratuitos.") {
                            viewModel.refreshChannelPoints(liveId, includeRedemptions = false)
                            showChannelPointsDialog = true
                        }
                    )
                }
                if (moderation.canModerate) {
                    add(LiveSecondaryAction("moderation", "Moderación", "Administrar comentarios y espectadores autorizados.") { showModerationDialog = true })
                }
            },
        )
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
        supporters = liveSupporters,
        joinedViewers = liveViewers,
        onSendComment = viewModel::sendComment,
        onSendReaction = viewModel::sendReaction,
        onOpenGift = { showGiftSheet = true },
        onRequestJoin = if (canRequestJoin) {
            {
                when (ownJoinRequest?.status) {
                    "pending" -> AppFeedbackBus.show("Tu solicitud sigue pendiente de aprobación.")
                    "accepted" -> AppFeedbackBus.show("Ya estás participando en este live.")
                    else -> showJoinRequestDialog = true
                }
            }
        } else {
            null
        },
        joinRequestLabel = when (ownJoinRequest?.status) {
            "pending" -> "Solicitud enviada"
            "accepted" -> "Participando"
            else -> "Solicitar unirme"
        },
        commentingEnabled = !moderation.chatPaused || moderation.canModerate,
        commentingDisabledReason = "Chat pausado por moderación",
        stateOverlayText = stateOverlayText,
        placeholderText = when (currentSource) {
            conectate.com.data.model.LiveSource.SCREEN -> "Esperando pantalla del creador…"
            conectate.com.data.model.LiveSource.AUDIO -> "Escuchando al creador…"
            conectate.com.data.model.LiveSource.CAMERA -> "Conectando directo…"
        },
        topStatusBannerText = when (liveKitState) {
            conectate.com.data.livekit.LiveKitRoomBridge.State.RECONNECTING -> "Reconectando…   |   Calidad media"
            conectate.com.data.livekit.LiveKitRoomBridge.State.CONNECTING -> "Conectando…   |   Calidad media"
            else -> activeBattle?.let { "BATALLA · ${it.leftScore} – ${it.rightScore}" }
        },
        leadingTopBarAction = {
            IconButton(onClick = ::leaveViewer) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar live",
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
                                runCatching { conectate.com.presentation.navigation.liveShareLink(liveId) }
                                    .getOrNull()
                                    ?.let { link -> append("\n$link") }
                            }
                        )
                    context.startActivity(Intent.createChooser(shareIntent, "Compartir live"))
                }
            )
            if (hasViewerSecondaryActions) {
                LiveActionChip(text = "Más", onClick = { showViewerToolsSheet = true })
            }
        },
        floatingOverlay = {
            GiftCelebrationOverlay(events = giftEvents)
            LiveKitGuestVideoGrid(
                videos = liveKitRemoteVideos,
                onAttach = viewModel::attachLiveKitRenderer,
                onDetach = viewModel::detachLiveKitRenderer,
                maxSlots = collaborationCapabilities.maxGuests.coerceIn(1, 4),
                showEmptySlots = stream?.allowGuestRequests == true,
                emptySlotLabel = when (ownJoinRequest?.status) {
                    "pending" -> "Solicitud enviada"
                    "accepted" -> "Participando"
                    else -> "Solicitar unirme"
                },
                onEmptySlotClick = {
                    if (ownJoinRequest == null || ownJoinRequest.status in setOf("rejected", "expired", "canceled")) {
                        showJoinRequestDialog = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 118.dp, end = 12.dp)
            )
        },
        creatorControls = {},
        isViewerLayout = true
    )
}
