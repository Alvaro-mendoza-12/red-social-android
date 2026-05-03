package conectate.com.presentation.live

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import conectate.com.data.model.LiveSource
import conectate.com.presentation.common.AppFeedbackBus
import org.webrtc.RendererCommon

private data class HostLiveSettingsDraft(
    val source: LiveSource,
    val micEnabled: Boolean,
    val videoEnabled: Boolean
)

@OptIn(ExperimentalPermissionsApi::class)
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
    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val eglBaseContext by viewModel.eglBaseContext.collectAsStateWithLifecycle()
    val micEnabled by viewModel.isMicEnabled.collectAsStateWithLifecycle()
    val isMediaReady by viewModel.isMediaReady.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val uiError by viewModel.uiError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val recordAudioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val requiresCameraPermission = currentSource == LiveSource.CAMERA
    val hasRequiredPermissions =
        recordAudioPermission.status.isGranted &&
            (!requiresCameraPermission || cameraPermission.status.isGranted)

    var hostConnected by remember(liveId) { mutableStateOf(false) }
    var autoStarted by remember(liveId) { mutableStateOf(false) }
    var showEndDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var pendingSettingsDraft by remember { mutableStateOf<HostLiveSettingsDraft?>(null) }
    var pendingResume by remember { mutableStateOf(false) }
    var pendingInitialStart by remember { mutableStateOf(false) }

    val projectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val projectionIntent = if (result.resultCode == Activity.RESULT_OK) result.data else null
        when {
            projectionIntent != null && pendingInitialStart -> {
                autoStarted = viewModel.startScreenShare(projectionIntent)
            }
            projectionIntent != null && pendingResume -> {
                if (viewModel.resumeLive(projectionIntent)) {
                    autoStarted = true
                }
            }
            projectionIntent != null && pendingSettingsDraft != null -> {
                val draft = pendingSettingsDraft ?: return@rememberLauncherForActivityResult
                if (viewModel.applyHostConfiguration(
                        source = draft.source,
                        micEnabled = draft.micEnabled,
                        videoEnabled = draft.videoEnabled,
                        projectionIntent = projectionIntent
                    )
                ) {
                    showSettingsDialog = false
                }
            }
        }

        pendingInitialStart = false
        pendingResume = false
        pendingSettingsDraft = null
    }

    LaunchedEffect(requiresCameraPermission) {
        if (!recordAudioPermission.status.isGranted) {
            recordAudioPermission.launchPermissionRequest()
        }
        if (requiresCameraPermission && !cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    LaunchedEffect(hasRequiredPermissions, hostConnected, liveId) {
        if (hasRequiredPermissions && !hostConnected) {
            viewModel.host(liveId)
            hostConnected = true
        }
    }

    LaunchedEffect(session?.role) {
        if (session?.role == conectate.com.data.model.LiveRole.HOST && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            showOverlayPermissionDialog = !Settings.canDrawOverlays(context)
        }
    }

    LaunchedEffect(session?.stream?.id, hasRequiredPermissions, autoStarted, currentSource, isVideoEnabled, isMediaReady) {
        val activeSession = session ?: return@LaunchedEffect
        if (!hasRequiredPermissions || autoStarted || !isMediaReady) return@LaunchedEffect
        if (activeSession.role != conectate.com.data.model.LiveRole.HOST) return@LaunchedEffect
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
                } else {
                    pendingInitialStart = true
                    screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            }
        }
    }

    LaunchedEffect(uiError) {
        val error = uiError ?: return@LaunchedEffect
        AppFeedbackBus.showError(error)
        hostConnected = false
        if (session == null) {
            navController.popBackStack()
        }
        viewModel.clearError()
    }

    BackHandler {
        navController.popBackStack()
    }

    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            title = { Text("Activa el chat flotante") },
            text = {
                Text("Para mantener el live como TikTok cuando salgas de la app, permite mostrar el chat flotante sobre otras apps.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showOverlayPermissionDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
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
                    AppFeedbackBus.show("Live finalizado")
                    viewModel.endLive()
                    navController.popBackStack()
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
                HostLiveSettingsDraft(
                    source = currentSource,
                    micEnabled = micEnabled,
                    videoEnabled = isVideoEnabled
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val needsProjection =
                        draft.source == LiveSource.SCREEN &&
                            draft.videoEnabled &&
                            (
                                currentSource != LiveSource.SCREEN ||
                                    !viewModel.canReuseScreenShareIntent()
                                )

                    if (needsProjection) {
                        pendingSettingsDraft = draft
                        screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                    } else {
                        if (viewModel.applyHostConfiguration(
                                source = draft.source,
                                micEnabled = draft.micEnabled,
                                videoEnabled = draft.videoEnabled
                            )
                        ) {
                            showSettingsDialog = false
                        }
                    }
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

    val stateOverlayText = when {
        !isMediaReady -> "Preparando transmisión…"
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

    LiveSessionScaffold(
        stream = session?.stream,
        viewerCount = viewerCount,
        videoTrack = if (isVideoEnabled) localVideoTrack else null,
        sharedEglContext = eglBaseContext,
        mirrorVideo = currentSource == LiveSource.CAMERA,
        videoScalingType = if (currentSource == LiveSource.SCREEN) {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        },
        comments = comments,
        reactions = reactions,
        onSendComment = viewModel::sendComment,
        onSendReaction = { viewModel.sendReaction("❤️") },
        stateOverlayText = stateOverlayText,
        topStatusBannerText = if (isPaused) {
            "Live pausado. Los espectadores no ven tu transmisión."
        } else {
            null
        },
        placeholderText = if (currentSource == LiveSource.SCREEN) "Preparando pantalla…" else "Preparando cámara…",
        leadingTopBarAction = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color.White
                )
            }
        },
        topBarActions = {},
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
        },
        creatorControls = {
            LiveActionChip(
                text = if (isPaused) "Reanudar" else "Pausar",
                onClick = {
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
                }
            )
            LiveActionChip(
                text = "Configuración",
                onClick = { showSettingsDialog = true }
            )
            LiveActionChip(
                text = if (floatingChatEnabled) "Cerrar chat" else "Chat flotante",
                onClick = {
                    floatingChatEnabled = !floatingChatEnabled
                    if (floatingChatEnabled) {
                        floatingChatMinimized = false
                    }
                }
            )
            LiveActionChip(
                text = "Finalizar live",
                onClick = { showEndDialog = true },
                containerColor = Color(0xFFE53935)
            )
        }
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
