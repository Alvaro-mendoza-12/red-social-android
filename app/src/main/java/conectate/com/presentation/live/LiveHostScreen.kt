@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.withFrameNanos
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
import conectate.com.data.webrtc.LiveCameraFilter
import conectate.com.data.webrtc.LiveFilterPerformanceState
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.wallet.GiftCelebrationOverlay
import org.webrtc.RendererCommon

private data class HostLiveSettingsDraft(
    val source: LiveSource,
    val micEnabled: Boolean,
    val videoEnabled: Boolean,
    val cameraFilter: LiveCameraFilter
)

private sealed interface HostPermissionAction {
    val permissions: Set<String>

    data class Start(val requirements: LiveHostRuntimeRequirements) : HostPermissionAction {
        override val permissions: Set<String> = requirements.permissions
    }

    data class Apply(val draft: HostLiveSettingsDraft) : HostPermissionAction {
        override val permissions: Set<String> = requiredHostRuntimePermissions(
            source = draft.source,
            micEnabled = draft.micEnabled,
            videoEnabled = draft.videoEnabled
        )
    }
}

@Composable
fun LegacyLiveHostScreen(
    navController: NavController,
    liveId: String,
    viewModel: LiveSessionViewModel = hiltViewModel()
) {
    val session by viewModel.activeSession.collectAsStateWithLifecycle()
    val viewerCount by viewModel.viewerCount.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val reactionCount by viewModel.reactionCount.collectAsStateWithLifecycle()
    val giftEvents by viewModel.giftEvents.collectAsStateWithLifecycle()
    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val eglBaseContext by viewModel.eglBaseContext.collectAsStateWithLifecycle()
    val micEnabled by viewModel.isMicEnabled.collectAsStateWithLifecycle()
    val isMediaReady by viewModel.isMediaReady.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsStateWithLifecycle()
    val isMediaTransitioning by viewModel.isMediaTransitioning.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val cameraFilter by viewModel.cameraFilter.collectAsStateWithLifecycle()
    val cameraFilterPerformance by viewModel.cameraFilterPerformance.collectAsStateWithLifecycle()
    val uiError by viewModel.uiError.collectAsStateWithLifecycle()
    val hostPreparation by viewModel.hostPreparation.collectAsStateWithLifecycle()
    val moderation by viewModel.moderationState.collectAsStateWithLifecycle()
    val liveViewers by viewModel.liveViewers.collectAsStateWithLifecycle()
    val collaborationCapabilities by viewModel.collaborationCapabilities.collectAsStateWithLifecycle()
    val openAudience = liveAudienceTool(liveId, viewModel.getCurrentUserId().orEmpty(), session?.stream?.id == liveId && collaborationCapabilities.audienceToolsEnabled)
    val guestInvitations by viewModel.guestInvitations.collectAsStateWithLifecycle()
    val activeBattle by viewModel.activeBattle.collectAsStateWithLifecycle()
    val liveKitRemoteVideos by viewModel.liveKitRemoteVideos.collectAsStateWithLifecycle()
    val liveSupporters by viewModel.liveSupporters.collectAsStateWithLifecycle()
    val toolVisit = rememberLiveToolAccountVisit(liveId, viewModel)
    val toolDialogs = rememberLiveToolDialogs(toolVisit)
    val channelPoints = viewModel.channelPoints.collectAsStateWithLifecycle().value.let { viewModel.channelPointsFor(toolVisit) }
    val creatorLayoutStyle by viewModel.creatorLayoutStyle.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var hostStartRequested by remember(liveId) { mutableStateOf(false) }
    var autoStarted by remember(liveId) { mutableStateOf(false) }
    var showEndDialog by remember { mutableStateOf(false) }
    var endingLive by remember(liveId) { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showModerationDialog by remember { mutableStateOf(false) }
    var showCollaborationDialog by remember { mutableStateOf(false) }
    var showHostToolsSheet by remember { mutableStateOf(false) }
    var showCreatorLayoutSheet by remember { mutableStateOf(false) }
    var showMomentDialog by remember { mutableStateOf(false) }
    var momentTitle by remember { mutableStateOf("") }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var pendingSettingsDraft by remember { mutableStateOf<HostLiveSettingsDraft?>(null) }
    var pendingResume by remember { mutableStateOf(false) }
    var pendingInitialStart by remember { mutableStateOf(false) }
    var pendingPermissionAction by remember { mutableStateOf<HostPermissionAction?>(null) }
    var deniedPermissionAction by remember { mutableStateOf<HostPermissionAction?>(null) }
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }
    var permissionCheckEpoch by remember { mutableStateOf(0) }

    LaunchedEffect(session?.stream?.id) {
        if (session?.stream?.id == liveId) toolVisit.run {
            viewModel.refreshChannelPoints(liveId, includeRedemptions = true)
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
                    viewModel.setCameraFilter(settingsDraft.cameraFilter)
                    showSettingsDialog = false
                }
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
    }

    val applySettingsDraft: (HostLiveSettingsDraft) -> Unit = { draft ->
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
            viewModel.setCameraFilter(draft.cameraFilter)
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
            deniedPermissionAction = action
            showPermissionDeniedDialog = true
        } else {
            deniedPermissionAction = null
            when (action) {
                is HostPermissionAction.Start -> viewModel.host(action.requirements.liveId)
                is HostPermissionAction.Apply -> applySettingsDraft(action.draft)
            }
        }
    }

    val appSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (deniedPermissionAction is HostPermissionAction.Start) {
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
                    val action = HostPermissionAction.Start(requirements)
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
                is HostPermissionAction.Start -> {
                    hostStartRequested = true
                    viewModel.host(action.requirements.liveId)
                }
                is HostPermissionAction.Apply -> applySettingsDraft(action.draft)
            }
        } else {
            showPermissionDeniedDialog = true
        }
    }

    LaunchedEffect(session?.role) {
        if (session?.role == LiveRole.HOST) {
            showOverlayPermissionDialog = !Settings.canDrawOverlays(context)
        }
    }

    LaunchedEffect(
        session?.stream?.id,
        autoStarted,
        currentSource,
        isVideoEnabled,
        isMediaReady,
        isPaused,
        localVideoTrack
    ) {
        val activeSession = session ?: return@LaunchedEffect
        if (autoStarted || !isMediaReady) return@LaunchedEffect
        if (activeSession.role != LiveRole.HOST) return@LaunchedEffect
        val isReturningToPreparedSession =
            activeSession.stream.id.equals(liveId, ignoreCase = true) &&
                (isPaused || !isVideoEnabled || localVideoTrack != null)
        if (isReturningToPreparedSession) {
            // Returning from the foreground-service notification must preserve
            // pause/source state and never restart capture without consent.
            autoStarted = true
            return@LaunchedEffect
        }
        if (!isVideoEnabled) {
            autoStarted = true
            return@LaunchedEffect
        }

        when (currentSource) {
            LiveSource.CAMERA -> {
                autoStarted = viewModel.startCamera()
            }
            LiveSource.SCREEN -> {
                if (viewModel.canReuseScreenShareIntent()) {
                    autoStarted = viewModel.resumeLive()
                } else if (viewModel.consumeSetupScreenProjectionIntent()?.let(viewModel::startScreenShare) == true) {
                    // The user already approved this one-shot MediaProjection
                    // token in the private pre-live studio.
                    autoStarted = true
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
                autoStarted = viewModel.applyHostConfiguration(
                    source = LiveSource.AUDIO,
                    micEnabled = micEnabled,
                    videoEnabled = false
                )
            }
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

    LaunchedEffect(session?.stream?.id) {
        val sessionId = session?.stream?.id ?: return@LaunchedEffect
        viewModel.startCollaborationPolling(sessionId)
    }

    LaunchedEffect(session?.stream?.id, collaborationCapabilities.sfuAvailable) {
        val sessionId = session?.stream?.id ?: return@LaunchedEffect
        if (collaborationCapabilities.guestsBattlesEnabled && collaborationCapabilities.sfuAvailable) {
            viewModel.connectLiveKit(
                liveId = sessionId,
                role = "host",
                publishAudio = false,
                publishCamera = false
            ).onFailure {
                AppFeedbackBus.showError("No se pudo preparar invitados y batallas. Revisa la conexión e inténtalo otra vez.")
            }
        }
    }

    LaunchedEffect(endingLive) {
        if (!endingLive) return@LaunchedEffect
        withFrameNanos { }
        val result = viewModel.endLiveAndAwait()
        if (result.isSuccess) {
            AppFeedbackBus.show("Live finalizado")
        } else {
            AppFeedbackBus.showError("El live se cerró en este dispositivo, pero no se pudo confirmar el cierre remoto.")
        }
        navController.popBackStack()
    }

    BackHandler {
        navController.popBackStack()
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

    if (showPermissionDeniedDialog) {
        val deniedAction = deniedPermissionAction
        AlertDialog(
            onDismissRequest = {
                showPermissionDeniedDialog = false
                if (deniedAction is HostPermissionAction.Start) hostStartRequested = false
            },
            title = { Text("Permisos necesarios para el LIVE") },
            text = {
                Text(
                    "Novvin necesita los permisos de cámara y micrófono que elegiste. " +
                        "Actívalos en los ajustes del sistema y vuelve a esta pantalla."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDeniedDialog = false
                    runCatching {
                        appSettingsLauncher.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }.onFailure {
                        if (deniedAction is HostPermissionAction.Start) hostStartRequested = false
                        AppFeedbackBus.showError("No se pudieron abrir los ajustes de Novvin.")
                    }
                }) {
                    Text("Abrir ajustes")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionDeniedDialog = false
                    if (deniedAction is HostPermissionAction.Start) hostStartRequested = false
                }) {
                    Text("Ahora no")
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
                    endingLive = true
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
        var draft by remember(showSettingsDialog, currentSource, micEnabled, isVideoEnabled, cameraFilter) {
            mutableStateOf(
                HostLiveSettingsDraft(
                    source = currentSource,
                    micEnabled = micEnabled,
                    videoEnabled = isVideoEnabled,
                    cameraFilter = cameraFilter
                )
            )
        }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Configuración del live") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
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
                    LiveSettingsOption(
                        selected = draft.source == LiveSource.AUDIO,
                        title = "Solo voz (foto de perfil)",
                        onSelect = {
                            draft = draft.copy(source = LiveSource.AUDIO, videoEnabled = false)
                        }
                    )
                    LiveSettingsToggle(
                        title = "Video activo",
                        checked = draft.videoEnabled,
                        onCheckedChange = { draft = draft.copy(videoEnabled = it) },
                        enabled = draft.source != LiveSource.AUDIO
                    )
                    LiveSettingsToggle(
                        title = "Micrófono activo",
                        checked = draft.micEnabled,
                        onCheckedChange = { draft = draft.copy(micEnabled = it) }
                    )
                    if (draft.source == LiveSource.CAMERA && draft.videoEnabled) {
                        Text("Estilo de cámara", fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(LiveCameraFilter.values().toList()) { option ->
                                FilterChip(
                                    selected = draft.cameraFilter == option,
                                    onClick = { draft = draft.copy(cameraFilter = option) },
                                    label = { Text(option.hostLabel()) }
                                )
                            }
                        }
                        Text(
                            "El estilo se aplica a la señal publicada y puede pausarse automáticamente si el equipo necesita proteger la transmisión.",
                            color = Color.Gray
                        )
                        Text(
                            "Estado: ${cameraFilterPerformance.hostLabel()}",
                            color = when (cameraFilterPerformance) {
                                LiveFilterPerformanceState.TEMPORARILY_PAUSED -> Color(0xFFB3261E)
                                LiveFilterPerformanceState.REDUCED -> Color(0xFF8A5A00)
                                else -> Color.Gray
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { applySettingsDraft(draft) }) {
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

    if (showCollaborationDialog) {
        AlertDialog(
            onDismissRequest = { showCollaborationDialog = false },
            title = { Text("Invitados y batallas") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Permitir solicitudes", fontWeight = FontWeight.Bold)
                            Text("Los espectadores podrán pedir unirse por voz o cámara.", color = Color.Gray)
                        }
                        Switch(
                            checked = session?.stream?.allowGuestRequests == true,
                            onCheckedChange = { viewModel.setGuestRequestsAllowed(liveId, it) },
                            enabled = collaborationCapabilities.guestsBattlesEnabled &&
                                collaborationCapabilities.sfuAvailable
                        )
                    }
                    when {
                        !collaborationCapabilities.guestsBattlesEnabled ->
                            Text("Las invitaciones están desactivadas temporalmente.")
                        !collaborationCapabilities.sfuAvailable ->
                            Text("El transporte de invitados no está disponible en este momento.")
                        liveViewers.none { it.userId.isNotBlank() } ->
                            Text("Cuando entren espectadores podrás invitarlos a participar por voz.")
                        else -> liveViewers
                            .filter { it.userId.isNotBlank() }
                            .distinctBy { it.userId.lowercase() }
                            .forEach { viewer ->
                                val invitation = guestInvitations.firstOrNull {
                                    it.inviteeUserId.equals(viewer.userId, ignoreCase = true)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(viewer.name.ifBlank { "Usuario" }, fontWeight = FontWeight.Bold)
                                        Text(
                                            when (invitation?.status) {
                                                "pending" -> if (invitation.initiatedBy == "viewer") {
                                                    "Solicita unirse"
                                                } else {
                                                    "Invitación pendiente"
                                                }
                                                "accepted" -> "Invitado aceptado"
                                                "rejected" -> "Invitación rechazada"
                                                else -> "Espectador"
                                            },
                                            color = Color.Gray
                                        )
                                    }
                                    when {
                                        invitation?.status == "pending" && invitation.initiatedBy == "viewer" ->
                                            Row {
                                                TextButton(onClick = {
                                                    viewModel.respondToGuestInvitation(liveId, invitation.id, accept = false)
                                                }) { Text("Rechazar") }
                                                TextButton(onClick = {
                                                    viewModel.respondToGuestInvitation(liveId, invitation.id, accept = true)
                                                }) { Text("Aceptar") }
                                            }
                                        invitation?.status == "accepted" -> Column(
                                            horizontalAlignment = Alignment.End
                                        ) {
                                            if (activeBattle != null &&
                                                activeBattle?.rightUserId.equals(viewer.userId, true)
                                            ) {
                                                TextButton(onClick = { viewModel.endBattle(liveId) }) {
                                                    Text("Finalizar batalla")
                                                }
                                            } else if (activeBattle == null) {
                                                TextButton(onClick = {
                                                    viewModel.startBattle(liveId, viewer.userId)
                                                }) { Text("Batalla") }
                                            }
                                            TextButton(onClick = {
                                                viewModel.muteGuest(liveId, viewer.userId)
                                            }) { Text("Silenciar") }
                                            TextButton(onClick = {
                                                viewModel.removeGuest(liveId, viewer.userId)
                                            }) { Text("Retirar") }
                                        }
                                        invitation?.status == "pending" -> Text("Enviada")
                                        else -> TextButton(onClick = { viewModel.inviteGuest(liveId, viewer.userId) }) { Text("Invitar") }
                                    }
                                }
                            }
                    }
                    activeBattle?.let { battle ->
                        Text("Marcador: ${battle.leftScore} – ${battle.rightScore}", fontWeight = FontWeight.Black)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCollaborationDialog = false }) { Text("Cerrar") }
            }
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

    val stateOverlayText = when {
        !isMediaReady -> "Preparando transmisión…"
        currentSource == LiveSource.AUDIO -> "DIRECTO SOLO VOZ"
        !isVideoEnabled && currentSource == LiveSource.SCREEN -> "PANTALLA DESACTIVADA"
        !isVideoEnabled -> "CÁMARA DESACTIVADA"
        !isPaused && localVideoTrack == null -> "Conectando video…"
        else -> null
    }

    val initialFloatingOffset = remember {
        IntOffset(x = 24, y = 220)
    }
    var floatingChatEnabled by remember { mutableStateOf(false) }
    var floatingChatMinimized by remember { mutableStateOf(false) }
    var floatingChatOffset by remember { mutableStateOf(initialFloatingOffset) }

    if (toolDialogs.gameVisible && collaborationCapabilities.drawGuessEnabled) {
        LiveDrawGuessDialog(
            liveId = liveId,
            currentUserId = toolVisit.owner.accountId.orEmpty(),
            isHost = true,
            participantIds = guestInvitations
                .filter { it.status == "accepted" }
                .map { it.inviteeUserId }
                .filter(String::isNotBlank),
            onDismiss = toolDialogs::closeGame
        )
    }

    if (toolDialogs.pointsVisible && channelPoints.available != false) {
        LiveChannelPointsDialog(
            visit = toolVisit,
            state = channelPoints,
            isHost = true,
            onDismiss = toolDialogs::closePoints,
            onRedeem = {},
            onUpdateSettings = { enabled, interval, points, cap ->
                viewModel.updateChannelPointSettings(liveId, enabled, interval, points, cap)
            },
            onCreateReward = { title, description, cost, stock, cooldown ->
                viewModel.createChannelPointReward(liveId, title, description, cost, stock, cooldown)
            },
            onUpdateRedemption = { redemptionId, status ->
                viewModel.decideChannelPointRedemption(liveId, redemptionId, status)
            },
        )
    }

    val togglePauseAction: () -> Unit = {
        if (isPaused) {
            val needsProjection =
                currentSource == LiveSource.SCREEN &&
                    isVideoEnabled &&
                    !viewModel.canReuseScreenShareIntent()
            if (needsProjection) {
                pendingResume = true
                screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                viewModel.resumeLive()
            }
        } else {
            viewModel.pauseLive()
        }
        Unit
    }

    if (showHostToolsSheet) {
        LiveSecondaryActionsSheet(
            title = "Herramientas del live",
            onDismiss = { showHostToolsSheet = false },
            actions = buildList {
                add(
                    LiveSecondaryAction(
                        key = "pause",
                        label = if (isPaused) "Reanudar transmisión" else "Pausar transmisión",
                        description = if (isPaused) {
                            "Volver a publicar audio y video."
                        } else {
                            "Detener temporalmente la señal sin finalizar el live."
                        },
                        onClick = togglePauseAction,
                    )
                )
                add(
                    LiveSecondaryAction(
                        key = "creator_layout",
                        label = "Diseño del estudio · ${creatorLayoutStyle.displayName}",
                        description = "Cambia la distribución de tus controles sin interrumpir el directo.",
                        onClick = { showCreatorLayoutSheet = true },
                    )
                )
                add(LiveSecondaryAction("settings", "Configuración", "Fuente, calidad, filtros y estado del video.") { showSettingsDialog = true })
                add(LiveSecondaryAction("moderation", "Moderación", "Chat, moderadores, silencios y expulsiones.") { showModerationDialog = true })
                add(LiveSecondaryAction("guests", "Invitados y batallas", "Revisar solicitudes, invitar y administrar participantes.") { showCollaborationDialog = true })
                openAudience?.let { action -> add(LiveSecondaryAction("audience", "Encuestas y preguntas", "Participa con el público del directo.", onClick = action)) }
                if (collaborationCapabilities.drawGuessEnabled) {
                    add(LiveSecondaryAction("game", "Dibujo y adivinanza", "Abrir el juego contextual dentro del live.", onClick = toolDialogs::openGame))
                }
                if (channelPoints.available != false) {
                    add(
                        LiveSecondaryAction("points", "Puntos del canal", "Configurar recompensas y revisar canjes.") {
                            toolVisit.run {
                                viewModel.refreshChannelPoints(liveId, includeRedemptions = true)
                                toolDialogs.openPoints()
                            }
                        }
                    )
                }
                add(LiveSecondaryAction("moment", "Marcar momento", "Guardar un instante destacado del directo.") { showMomentDialog = true })
                add(
                    LiveSecondaryAction(
                        key = "floating_chat",
                        label = if (floatingChatEnabled) "Cerrar chat flotante" else "Abrir chat flotante",
                        description = "Mover el chat a una ventana ajustable.",
                        onClick = {
                            floatingChatEnabled = !floatingChatEnabled
                            if (floatingChatEnabled) floatingChatMinimized = false
                        },
                    )
                )
            },
        )
    }

    if (showCreatorLayoutSheet) {
        LiveCreatorStyleSheet(
            selected = creatorLayoutStyle,
            onSelect = viewModel::setCreatorLayoutStyle,
            onDismiss = { showCreatorLayoutSheet = false },
        )
    }

    LiveSessionScaffold(
        stream = session?.stream,
        viewerCount = viewerCount,
        reactionCount = reactionCount,
        videoTrack = if (endingLive || !isVideoEnabled) null else localVideoTrack,
        sharedEglContext = if (endingLive) null else eglBaseContext,
        mirrorVideo = currentSource == LiveSource.CAMERA,
        videoScalingType = if (currentSource == LiveSource.SCREEN) {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        },
        comments = comments,
        reactions = reactions,
        supporters = liveSupporters,
        joinedViewers = liveViewers,
        creatorLayoutStyle = creatorLayoutStyle,
        onSendComment = viewModel::sendComment,
        onSendReaction = viewModel::sendReaction,
        commentingEnabled = !moderation.chatPaused || moderation.canModerate,
        commentingDisabledReason = "Chat pausado por moderación",
        stateOverlayText = stateOverlayText,
        placeholderText = if (currentSource == LiveSource.AUDIO) {
            "Directo solo voz"
        } else {
            "Preparando video…"
        },
        topStatusBannerText = when {
            isPaused -> "Live pausado. Los espectadores no ven tu transmisión."
            activeBattle != null -> "BATALLA · ${activeBattle?.leftScore ?: 0} – ${activeBattle?.rightScore ?: 0}"
            else -> null
        },
        leadingTopBarAction = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color.White
                )
            }
        },
        topBarActions = {},
        floatingOverlay = {
            GiftCelebrationOverlay(events = giftEvents)
            LiveKitGuestVideoGrid(
                videos = liveKitRemoteVideos,
                onAttach = viewModel::attachLiveKitRenderer,
                onDetach = viewModel::detachLiveKitRenderer,
                maxSlots = collaborationCapabilities.maxGuests.coerceIn(1, 4),
                showEmptySlots = session?.stream?.allowGuestRequests == true,
                emptySlotLabel = if (guestInvitations.any {
                        it.status == "pending" && it.initiatedBy == "viewer"
                    }) "Solicitud pendiente" else "Invitar",
                onEmptySlotClick = { showCollaborationDialog = true },
                // Keep the guest PiP below the identity header.  The creator
                // action rail is anchored above the composer, so these two
                // layers never compete for the same vertical space.
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 128.dp, end = 12.dp)
            )
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
        },
        creatorControls = {
            LiveActionChip(
                text = if (micEnabled) "Micrófono" else "Micrófono apagado",
                onClick = { viewModel.toggleMic(!micEnabled) },
                enabled = !endingLive,
            )
            if (currentSource == LiveSource.CAMERA) {
                LiveActionChip(
                    text = if (isVideoEnabled) "Cámara" else "Cámara apagada",
                    onClick = {
                        if (isVideoEnabled) viewModel.stopVideo() else viewModel.startCamera()
                    },
                    enabled = !isMediaTransitioning && !endingLive,
                )
                if (isVideoEnabled) {
                    LiveActionChip(
                        text = "Cambiar cámara",
                        onClick = { viewModel.switchCamera() },
                        enabled = !isMediaTransitioning && !endingLive,
                    )
                }
            }
            LiveActionChip(text = "Herramientas", onClick = { showHostToolsSheet = true })
            LiveActionChip(
                text = "Finalizar live",
                onClick = { showEndDialog = true },
                containerColor = Color(0xFFE53935)
            )
        }
    )
}

private fun LiveCameraFilter.hostLabel(): String = when (this) {
    LiveCameraFilter.NONE -> "Natural"
    LiveCameraFilter.WARM -> "Cálido"
    LiveCameraFilter.COOL -> "Frío"
    LiveCameraFilter.VIVID -> "Vívido"
    LiveCameraFilter.SOFT -> "Suave"
    LiveCameraFilter.MONO -> "Mono"
}

private fun LiveFilterPerformanceState.hostLabel(): String = when (this) {
    LiveFilterPerformanceState.NATURAL -> "Natural (sin filtro)"
    LiveFilterPerformanceState.FULL -> "Completo"
    LiveFilterPerformanceState.REDUCED -> "Reducido para proteger el rendimiento"
    LiveFilterPerformanceState.TEMPORARILY_PAUSED -> "Pausado temporalmente; se reintentará"
    LiveFilterPerformanceState.NOT_APPLIED_TO_SCREEN -> "No se aplica al compartir pantalla"
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
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
private fun missingPermissions(context: android.content.Context, permissions: Collection<String>): List<String> {
    return permissions.filter {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
