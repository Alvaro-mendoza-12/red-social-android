package conectate.com.presentation.live

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.data.model.LiveRole
import conectate.com.data.model.LiveSource
import conectate.com.presentation.common.AppFeedbackBus
import org.webrtc.RendererCommon

private data class FigmaHostLiveSettingsDraft(
    val source: LiveSource,
    val micEnabled: Boolean,
    val videoEnabled: Boolean,
    val allowGuestRequests: Boolean
)

private data class HostLiveSummarySnapshot(
    val title: String,
    val viewerCount: Int,
    val commentCount: Int,
    val reactionCount: Int,
    val giftDiamonds: Int,
    val topDonors: List<Pair<String, Int>>,
    val startedAt: String?
)

private sealed interface FigmaHostPermissionAction {
    val permissions: Set<String>

    data class Start(val requirements: LiveHostRuntimeRequirements) : FigmaHostPermissionAction {
        override val permissions: Set<String> = requirements.permissions
    }

    data class Apply(val draft: FigmaHostLiveSettingsDraft) : FigmaHostPermissionAction {
        override val permissions: Set<String> = requiredHostRuntimePermissions(
            source = draft.source,
            micEnabled = draft.micEnabled,
            videoEnabled = draft.videoEnabled
        )
    }
}

@Composable
fun LiveHostScreen(
    navController: NavController,
    liveId: String,
    viewModel: LiveSessionViewModel = hiltViewModel()
) {
    val session by viewModel.activeSession.collectAsStateWithLifecycle()
    val viewerCount by viewModel.viewerCount.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val reactionCount by viewModel.reactionCount.collectAsStateWithLifecycle()
    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val isFrontCamera by viewModel.isFrontCamera.collectAsStateWithLifecycle()
    val eglBaseContext by viewModel.eglBaseContext.collectAsStateWithLifecycle()
    val micEnabled by viewModel.isMicEnabled.collectAsStateWithLifecycle()
    val isMediaReady by viewModel.isMediaReady.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val uiError by viewModel.uiError.collectAsStateWithLifecycle()
    val hostPreparation by viewModel.hostPreparation.collectAsStateWithLifecycle()
    val moderation by viewModel.moderationState.collectAsStateWithLifecycle()
    val liveViewers by viewModel.liveViewers.collectAsStateWithLifecycle()
    val remoteTracks by viewModel.remoteVideoTracks.collectAsStateWithLifecycle()
    val liveKitRemoteVideos by viewModel.liveKitRemoteVideos.collectAsStateWithLifecycle()
    val guestRequests by viewModel.guestRequests.collectAsStateWithLifecycle()
    val activeGuests by viewModel.activeGuests.collectAsStateWithLifecycle()
    val giftEvents by viewModel.giftEvents.collectAsStateWithLifecycle()
    val battle by viewModel.battleState.collectAsStateWithLifecycle()
    val battleCandidates by viewModel.battleCandidates.collectAsStateWithLifecycle()
    val cameraFilter by viewModel.cameraFilter.collectAsStateWithLifecycle()
    val liveMusic by viewModel.liveMusic.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var hostStartRequested by remember(liveId) { mutableStateOf(false) }
    var autoStarted by remember(liveId) { mutableStateOf(false) }
    var showEndDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showModerationDialog by remember { mutableStateOf(false) }
    var showMomentDialog by remember { mutableStateOf(false) }
    var showEffectsSheet by remember { mutableStateOf(false) }
    var momentTitle by remember { mutableStateOf("") }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var pendingSettingsDraft by remember { mutableStateOf<FigmaHostLiveSettingsDraft?>(null) }
    var pendingResume by remember { mutableStateOf(false) }
    var pendingInitialStart by remember { mutableStateOf(false) }
    var pendingPermissionAction by remember { mutableStateOf<FigmaHostPermissionAction?>(null) }
    var deniedPermissionAction by remember { mutableStateOf<FigmaHostPermissionAction?>(null) }
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }
    var permissionCheckEpoch by remember { mutableStateOf(0) }
    var liveSummary by remember(liveId) { mutableStateOf<HostLiveSummarySnapshot?>(null) }
    var showMusicPicker by remember { mutableStateOf(false) }
    var pendingToolScreenStart by remember { mutableStateOf(false) }

    LaunchedEffect(battle.battleId, battle.status, battle.opponentLiveId) {
        if (
            battle.status == conectate.com.data.model.LiveBattleStatus.ACTIVE &&
            battle.battleId.isNotBlank() && battle.opponentLiveId.isNotBlank()
        ) {
            viewModel.connectLiveKit(
                liveId = liveId,
                role = "host",
                publishAudio = micEnabled,
                publishCamera = isVideoEnabled,
            ).onFailure {
                AppFeedbackBus.showError("No se pudo conectar la sala compartida de batalla.")
            }
        }
    }

    val projectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val projectionIntent = if (result.resultCode == Activity.RESULT_OK) result.data else null
        val wasInitialStart = pendingInitialStart
        val wasResume = pendingResume
        val settingsDraft = pendingSettingsDraft
        val wasToolScreenStart = pendingToolScreenStart

        when {
            projectionIntent != null && wasInitialStart -> {
                autoStarted = viewModel.startScreenShare(projectionIntent)
            }
            projectionIntent != null && wasResume -> {
                if (viewModel.resumeLive(projectionIntent)) {
                    autoStarted = true
                }
            }
            projectionIntent != null && settingsDraft != null -> {
                if (viewModel.applyHostConfiguration(
                        source = settingsDraft.source,
                        micEnabled = settingsDraft.micEnabled,
                        videoEnabled = settingsDraft.videoEnabled,
                        projectionIntent = projectionIntent
                    )
                ) {
                    session?.stream?.id?.let { activeLiveId ->
                        if (session?.stream?.allowGuestRequests != settingsDraft.allowGuestRequests) {
                            viewModel.setGuestRequestsAllowed(activeLiveId, settingsDraft.allowGuestRequests)
                        }
                    }
                    showSettingsDialog = false
                }
            }
            projectionIntent != null && wasToolScreenStart -> {
                viewModel.startScreenShare(projectionIntent)
            }
            projectionIntent == null && wasInitialStart -> {
                viewModel.applyHostConfiguration(
                    source = LiveSource.SCREEN,
                    micEnabled = micEnabled,
                    videoEnabled = false
                )
                autoStarted = true
                AppFeedbackBus.show("Compartir pantalla cancelado. El live continúa sin video.")
            }
            projectionIntent == null && (wasResume || settingsDraft != null) -> {
                AppFeedbackBus.show("No se compartió la pantalla.")
            }
        }

        pendingInitialStart = false
        pendingResume = false
        pendingSettingsDraft = null
        pendingToolScreenStart = false
    }

    val applySettingsDraft: (FigmaHostLiveSettingsDraft) -> Unit = { draft ->
        val needsProjection =
            draft.source == LiveSource.SCREEN &&
                draft.videoEnabled &&
                (currentSource != LiveSource.SCREEN || !viewModel.canReuseScreenShareIntent())

        if (needsProjection) {
            pendingSettingsDraft = draft
            runCatching {
                screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
            }.onFailure {
                pendingSettingsDraft = null
                AppFeedbackBus.showError("No se pudo solicitar la captura de pantalla.")
            }
        } else if (
            viewModel.applyHostConfiguration(
                source = draft.source,
                micEnabled = draft.micEnabled,
                videoEnabled = draft.videoEnabled
            )
        ) {
            session?.stream?.id?.let { activeLiveId ->
                if (session?.stream?.allowGuestRequests != draft.allowGuestRequests) {
                    viewModel.setGuestRequestsAllowed(activeLiveId, draft.allowGuestRequests)
                }
            }
            showSettingsDialog = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (action == null) return@rememberLauncherForActivityResult

        if (missingPermissions(context, action.permissions).isNotEmpty()) {
            if (action is FigmaHostPermissionAction.Start) viewModel.releaseUnusedSetupPreview()
            deniedPermissionAction = action
            showPermissionDeniedDialog = true
        } else {
            deniedPermissionAction = null
            when (action) {
                is FigmaHostPermissionAction.Start -> viewModel.host(action.requirements.liveId)
                is FigmaHostPermissionAction.Apply -> applySettingsDraft(action.draft)
            }
        }
    }

    val appSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (deniedPermissionAction is FigmaHostPermissionAction.Start) {
            hostStartRequested = false
        }
        permissionCheckEpoch += 1
    }

    LaunchedEffect(liveId) {
        try {
            viewModel.prepareHost(liveId)
        } catch (e: Exception) {
            android.util.Log.e("LiveHostScreen", "Error preparing host", e)
            AppFeedbackBus.showError("Error al preparar el live: ${e.message}")
        }
    }

    LaunchedEffect(hostPreparation, session?.stream?.id, session?.role, permissionCheckEpoch) {
        when (val preparation = hostPreparation) {
            is LiveHostPreparationState.Failed -> {
                viewModel.releaseUnusedSetupPreview()
                AppFeedbackBus.showError(preparation.message)
                navController.popBackStack()
            }
            is LiveHostPreparationState.Ready -> {
                val requirements = preparation.requirements
                val alreadyConnected =
                    session?.role == LiveRole.HOST &&
                        session?.stream?.id.equals(requirements.liveId, ignoreCase = true)
                if (alreadyConnected || hostStartRequested) return@LaunchedEffect

                val missing = missingPermissions(context, requirements.permissions)
                hostStartRequested = true
                if (missing.isEmpty()) {
                    viewModel.host(requirements.liveId)
                } else {
                    val action = FigmaHostPermissionAction.Start(requirements)
                    pendingPermissionAction = action
                    runCatching { permissionLauncher.launch(missing.toTypedArray()) }
                        .onFailure {
                            pendingPermissionAction = null
                            deniedPermissionAction = action
                            showPermissionDeniedDialog = true
                        }
                }
            }
            else -> Unit
        }
    }

    LaunchedEffect(permissionCheckEpoch) {
        val action = deniedPermissionAction ?: return@LaunchedEffect
        if (missingPermissions(context, action.permissions).isEmpty()) {
            deniedPermissionAction = null
            showPermissionDeniedDialog = false
            when (action) {
                is FigmaHostPermissionAction.Start -> {
                    hostStartRequested = true
                    viewModel.host(action.requirements.liveId)
                }
                is FigmaHostPermissionAction.Apply -> applySettingsDraft(action.draft)
            }
        } else {
            showPermissionDeniedDialog = true
        }
    }

    LaunchedEffect(session?.stream?.id, autoStarted, currentSource, isVideoEnabled, isMediaReady, localVideoTrack) {
        val activeSession = session ?: return@LaunchedEffect
        if (autoStarted || !isMediaReady) return@LaunchedEffect
        if (activeSession.role != LiveRole.HOST) return@LaunchedEffect
        if (!isVideoEnabled) {
            autoStarted = true
            return@LaunchedEffect
        }

        when (currentSource) {
            LiveSource.CAMERA -> {
                // The setup preview already owns the selected camera track.
                // Reopening it here used to switch a rear-camera LIVE back to front.
                autoStarted = localVideoTrack != null || viewModel.startCamera()
            }
            LiveSource.SCREEN -> {
                if (viewModel.canReuseScreenShareIntent()) {
                    autoStarted = viewModel.resumeLive()
                } else {
                    pendingInitialStart = true
                    runCatching {
                        screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }.onFailure {
                        pendingInitialStart = false
                        viewModel.applyHostConfiguration(
                            source = LiveSource.SCREEN,
                            micEnabled = micEnabled,
                            videoEnabled = false
                        )
                        autoStarted = true
                        AppFeedbackBus.showError("No se pudo solicitar la captura de pantalla.")
                    }
                }
            }
            LiveSource.AUDIO -> {
                // Audio-only rooms intentionally publish no video track.
                autoStarted = true
            }
        }
    }

    LaunchedEffect(uiError) {
        val error = uiError ?: return@LaunchedEffect
        AppFeedbackBus.showError(error)
        if (session == null) {
            viewModel.releaseUnusedSetupPreview()
            navController.popBackStack()
        }
        viewModel.clearError()
    }

    LaunchedEffect(session?.stream?.id, session?.role) {
        val active = session ?: return@LaunchedEffect
        if (active.role == LiveRole.HOST) {
            viewModel.startCollaborationPolling(active.stream.id)
        }
    }

    BackHandler {
        showEndDialog = true
    }

    val finishedSummary = liveSummary
    if (finishedSummary != null) {
        NovvinLiveSummary(
            title = finishedSummary.title,
            viewerCount = finishedSummary.viewerCount,
            commentCount = finishedSummary.commentCount,
            reactionCount = finishedSummary.reactionCount,
            giftDiamonds = finishedSummary.giftDiamonds,
            topDonors = finishedSummary.topDonors,
            startedAt = finishedSummary.startedAt,
            onShare = {
                val shareText =
                    "Mi directo en Novvin: " + finishedSummary.title +
                        " · " + finishedSummary.viewerCount + " espectadores" +
                        " · " + finishedSummary.commentCount + " comentarios"
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, shareText)
                context.startActivity(Intent.createChooser(intent, "Compartir resumen"))
            },
            onClose = {
                liveSummary = null
                navController.popBackStack()
            }
        )
        return
    }

    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = {
                if (deniedPermissionAction is FigmaHostPermissionAction.Start) hostStartRequested = false
                showPermissionDeniedDialog = false
            },
            title = { Text("Permisos necesarios") },
            text = {
                Text(
                    "Activa los permisos de cámara y micrófono elegidos para poder iniciar o configurar el directo."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionDeniedDialog = false
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName)
                        )
                        appSettingsLauncher.launch(intent)
                    }
                ) {
                    Text("Abrir ajustes")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (deniedPermissionAction is FigmaHostPermissionAction.Start) hostStartRequested = false
                        showPermissionDeniedDialog = false
                    }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            title = { Text("Activa el chat flotante") },
            text = {
                Text("Para mantener visible el chat del LIVE cuando salgas de Novvin, permite mostrarlo sobre otras aplicaciones.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showOverlayPermissionDialog = false
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) {
                    Text("Permitir")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermissionDialog = false }) {
                    Text("Después")
                }
            }
        )
    }

    if (showEndDialog) {
        AlertDialog(
            onDismissRequest = { showEndDialog = false },
            title = { Text("Finalizar live") },
            text = { Text("¿Deseas finalizar el live?") },
            confirmButton = {
                TextButton(onClick = {
                    showEndDialog = false
                    val topDonors = giftEvents
                        .groupBy { it.senderName }
                        .mapValues { entry -> entry.value.sumOf { it.gift.priceDiamonds } }
                        .toList()
                        .sortedByDescending { it.second }
                        .take(3)
                    liveSummary = HostLiveSummarySnapshot(
                        title = session?.stream?.title.orEmpty(),
                        viewerCount = viewerCount,
                        commentCount = comments.size,
                        reactionCount = reactionCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                        giftDiamonds = giftEvents.sumOf { it.gift.priceDiamonds },
                        topDonors = topDonors,
                        startedAt = session?.stream?.startedAt ?: session?.stream?.createdAt
                    )
                    AppFeedbackBus.show("Directo finalizado")
                    viewModel.endLive()
                }) {
                    Text("Finalizar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showSettingsDialog) {
        var draft by remember(showSettingsDialog, currentSource, micEnabled, isVideoEnabled) {
            mutableStateOf(
                FigmaHostLiveSettingsDraft(
                    source = currentSource,
                    micEnabled = micEnabled,
                    videoEnabled = isVideoEnabled,
                    allowGuestRequests = session?.stream?.allowGuestRequests == true
                )
            )
        }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Configuración del live") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Fuente de video", fontWeight = FontWeight.Bold)
                    LiveSettingsOption(
                        selected = draft.source == LiveSource.CAMERA,
                        title = "Transmitir con cámara",
                        onSelect = { draft = draft.copy(source = LiveSource.CAMERA) }
                    )
                    LiveSettingsOption(
                        selected = draft.source == LiveSource.SCREEN,
                        title = "Compartir pantalla",
                        onSelect = { draft = draft.copy(source = LiveSource.SCREEN) }
                    )
                    LiveSettingsToggle(
                        title = "Video activo",
                        checked = draft.videoEnabled,
                        onCheckedChange = { draft = draft.copy(videoEnabled = it) }
                    )
                    LiveSettingsToggle(
                        title = "Micrófono activo",
                        checked = draft.micEnabled,
                        onCheckedChange = { draft = draft.copy(micEnabled = it) }
                    )
                    LiveSettingsToggle(
                        title = "Solicitudes de invitados",
                        checked = draft.allowGuestRequests,
                        onCheckedChange = { draft = draft.copy(allowGuestRequests = it) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    applySettingsDraft(draft)
                }) {
                    Text("Aplicar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showModerationDialog) {
        LiveModerationDialog(
            moderation = moderation,
            viewers = liveViewers,
            onDismiss = { showModerationDialog = false },
            onSaveSettings = viewModel::updateModerationSettings,
            onSetModerator = viewModel::setModerator,
            onKick = { viewModel.moderateViewer(it, false) },
            onBan = { viewModel.moderateViewer(it, true) }
        )
    }

    if (showMomentDialog) {
        AlertDialog(
            onDismissRequest = { showMomentDialog = false },
            title = { Text("Marcar momento") },
            text = {
                OutlinedTextField(
                    value = momentTitle,
                    onValueChange = { momentTitle = it.take(80) },
                    label = { Text("Nombre del capítulo") },
                    placeholder = { Text("Ej.: Preguntas del público") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = momentTitle.isNotBlank(),
                    onClick = {
                        viewModel.markReplayMoment(momentTitle) {
                            momentTitle = ""
                            showMomentDialog = false
                        }
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMomentDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    val initialFloatingOffset = remember {
        IntOffset(x = 24, y = 220)
    }

    if (showEffectsSheet) {
        NovvinLiveEffectsSheet(
            selected = cameraFilter,
            onSelect = viewModel::setCameraFilter,
            onDismiss = { showEffectsSheet = false }
        )
    }
    var floatingChatEnabled by remember { mutableStateOf(false) }
    var floatingChatMinimized by remember { mutableStateOf(false) }
    var floatingChatOffset by remember { mutableStateOf(initialFloatingOffset) }

    val pauseOrResume: () -> Unit = {
        if (isPaused) {
            val needsProjection =
                currentSource == LiveSource.SCREEN &&
                    isVideoEnabled &&
                    !viewModel.canReuseScreenShareIntent()
            if (needsProjection) {
                pendingResume = true
                runCatching {
                    screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                }.onFailure {
                    pendingResume = false
                    AppFeedbackBus.showError("No se pudo solicitar la captura de pantalla.")
                }
            } else {
                viewModel.resumeLive()
            }
        } else {
            viewModel.pauseLive()
        }
    }

    NovvinLiveHostExperience(
        stream = session?.stream,
        viewerCount = viewerCount,
        viewers = liveViewers,
        localTrack = if (isMediaReady) localVideoTrack else null,
        remoteTracks = remoteTracks,
        liveKitVideos = liveKitRemoteVideos,
        onAttachLiveKit = viewModel::attachLiveKitRenderer,
        onDetachLiveKit = viewModel::detachLiveKitRenderer,
        activeGuests = activeGuests,
        requests = guestRequests,
        egl = eglBaseContext,
        source = currentSource,
        paused = isPaused,
        videoEnabled = isVideoEnabled,
        isFrontCamera = isFrontCamera,
        micEnabled = micEnabled,
        musicState = liveMusic,
        comments = comments,
        reactions = reactions,
        giftsReceived = giftEvents,
        battle = battle,
        battleCandidates = battleCandidates,
        commentingEnabled = !moderation.chatPaused || moderation.canModerate,
        onComment = viewModel::sendComment,
        onRespondGuest = viewModel::respondToGuestRequest,
        onGuestRequestsAllowed = { viewModel.setGuestRequestsAllowed(liveId, it) },
        onMuteGuest = { viewModel.muteGuest(liveId, it) },
        onRemoveGuest = { viewModel.removeGuest(liveId, it) },
        onPauseResume = pauseOrResume,
        onMic = viewModel::toggleMic,
        onPickMusic = { showMusicPicker = true },
        onMusicEnabled = viewModel::setLiveMusicEnabled,
        onMusicVolume = viewModel::setLiveMusicVolume,
        onRemoveMusic = viewModel::clearLiveMusic,
        onSettings = { showSettingsDialog = true },
        onModeration = { showModerationDialog = true },
        onMoment = { showMomentDialog = true },
        onSwitchCamera = { viewModel.switchCamera() },
        onCameraToggle = {
            if (isVideoEnabled) {
                viewModel.stopVideo()
            } else if (currentSource == LiveSource.CAMERA) {
                viewModel.startCamera()
            } else {
                pendingToolScreenStart = true
                screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        },
        onEffects = { showEffectsSheet = true },
        onFloatingChat = {
            if (!Settings.canDrawOverlays(context)) {
                showOverlayPermissionDialog = true
            } else {
                floatingChatEnabled = !floatingChatEnabled
                if (floatingChatEnabled) floatingChatMinimized = false
            }
        },
        onBattle = viewModel::startBattle,
        onCrossLiveBattle = { opponentLiveId, opponentUserId ->
            viewModel.startCrossLiveBattle(liveId, opponentLiveId, opponentUserId)
        },
        onRefreshBattleCandidates = { viewModel.refreshBattleCandidates(liveId) },
        onBattleResponse = { accepted ->
            if (battle.battleId.isNotBlank()) {
                viewModel.respondToCrossLiveBattle(liveId, battle.battleId, accepted)
            }
        },
        onEnd = { showEndDialog = true },
        floatingOverlay = {
            if (floatingChatEnabled) {
                LiveFloatingChatWindow(
                    comments = comments,
                    offset = floatingChatOffset,
                    minimized = floatingChatMinimized,
                    onOffsetChange = { floatingChatOffset = it },
                    onToggleMinimize = { floatingChatMinimized = !floatingChatMinimized },
                    onClose = {
                        floatingChatEnabled = false
                        floatingChatMinimized = false
                    },
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }
    )

    LiveMusicPickerFlow(
        visible = showMusicPicker,
        onDismiss = { showMusicPicker = false },
        onPrepared = viewModel::prepareLiveMusic,
    )
}

@Composable
private fun LiveSettingsOption(
    selected: Boolean,
    title: String,
    onSelect: () -> Unit
) {
    TextButton(onClick = onSelect) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(title)
    }
}

@Composable
private fun LiveSettingsToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
private fun missingPermissions(context: android.content.Context, permissions: Collection<String>): List<String> {
    return permissions.filter {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
