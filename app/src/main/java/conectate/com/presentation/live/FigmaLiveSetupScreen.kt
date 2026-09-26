@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package conectate.com.presentation.live

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.data.model.LiveQuality
import conectate.com.data.model.LiveSource
import conectate.com.data.model.LiveMusicPlaybackState
import conectate.com.data.webrtc.LiveCameraFilter
import conectate.com.data.webrtc.LiveCameraAdjustments
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.WebRtcVideoView
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.navigation.Screen
import org.webrtc.RendererCommon

private val LiveActionPink = Color(0xFFFF2D55)
private val LiveSheetBlack = Color(0xFF0B0B0C)
private val LiveSheetSurface = Color(0xFF202022)
private val LiveMutedText = Color(0xFFB8B8BC)

private enum class CreatorEntryMode { CAMERA, MOBILE_GAMES, STUDIO }
private enum class CreatorPanel { CENTER, REWARDS, MUSIC, EFFECTS, ENHANCE, SETTINGS, SERVICE }

private enum class CreatorInfoTopic {
    TIPS,
    FAN_CLUB,
    LANDSCAPE,
    CAMPAIGNS,
    ACADEMY,
    REWARD_MISSION,
    MUSIC_GIFTS,
    MUSIC_REACH,
    MUSIC_REWARDS,
    MODERATION,
    NOTIFICATIONS,
    STUDIO,
    HELP,
}

private data class CreatorTool(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun LiveSetupScreen(
    navController: NavController,
    viewModel: LiveSetupViewModel = hiltViewModel(),
) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val micEnabled by viewModel.micEnabled.collectAsStateWithLifecycle()
    val allowGuests by viewModel.allowGuestRequests.collectAsStateWithLifecycle()
    val giftsEnabled by viewModel.giftsEnabled.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val cameraFilter by viewModel.cameraFilter.collectAsStateWithLifecycle()
    val cameraAdjustments by viewModel.cameraAdjustments.collectAsStateWithLifecycle()
    val avatarUrl by viewModel.avatarUrl.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val isStarting by viewModel.isStarting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val previewBusy by viewModel.previewBusy.collectAsStateWithLifecycle()
    val screenPermissionReady by viewModel.screenPermissionReady.collectAsStateWithLifecycle()
    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val eglBaseContext by viewModel.eglBaseContext.collectAsStateWithLifecycle()
    val liveMusic by viewModel.liveMusic.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var modeName by rememberSaveable { mutableStateOf(CreatorEntryMode.CAMERA.name) }
    val mode = CreatorEntryMode.valueOf(modeName)
    var panelName by rememberSaveable { mutableStateOf<String?>(null) }
    val panel = panelName?.let(CreatorPanel::valueOf)
    var practiceMode by rememberSaveable { mutableStateOf(false) }
    var pendingScreenStart by rememberSaveable { mutableStateOf(false) }
    var pendingPracticeScreenPreview by rememberSaveable { mutableStateOf(false) }
    var serviceBannerVisible by rememberSaveable { mutableStateOf(true) }
    var infoTopicName by rememberSaveable { mutableStateOf<String?>(null) }
    val infoTopic = infoTopicName?.let(CreatorInfoTopic::valueOf)
    var showMusicPicker by rememberSaveable { mutableStateOf(false) }
    var showCameraStartChoice by rememberSaveable { mutableStateOf(false) }

    val projectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val screenPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            if (pendingPracticeScreenPreview) {
                pendingPracticeScreenPreview = false
                viewModel.startScreenPreview(data)
            } else {
                viewModel.confirmScreenPermission(data)
            }
            if (pendingScreenStart) {
                pendingScreenStart = false
                viewModel.startLive { liveId ->
                    navController.navigate(Screen.LiveHost.createRoute(liveId)) { launchSingleTop = true }
                }
            }
        } else {
            pendingScreenStart = false
            pendingPracticeScreenPreview = false
            viewModel.reportPermissionError("Necesitas autorizar la captura para transmitir el juego.")
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startCameraPreview()
        else viewModel.reportPermissionError("Concede permiso a la cámara para ver la vista previa.")
    }

    fun openInfo(topic: CreatorInfoTopic) {
        panelName = null
        infoTopicName = topic.name
    }

    fun shareUpcomingLive() {
        val host = displayName.ifBlank { "un creador" }
        val message = "Acompáñame en el próximo LIVE de $host en Novvin."
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, message)
                    },
                    "Compartir LIVE",
                )
            )
        }.onFailure { AppFeedbackBus.showError("No hay una aplicación disponible para compartir") }
    }

    fun ensureCameraPreview() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startCameraPreview()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun selectMode(next: CreatorEntryMode) {
        modeName = next.name
        when (next) {
            CreatorEntryMode.CAMERA -> {
                viewModel.setSource(LiveSource.CAMERA)
                ensureCameraPreview()
            }
            CreatorEntryMode.MOBILE_GAMES -> viewModel.setSource(LiveSource.SCREEN)
            CreatorEntryMode.STUDIO -> viewModel.setSource(LiveSource.SCREEN)
        }
    }

    fun startPublicLive() {
        when {
            mode == CreatorEntryMode.STUDIO -> openInfo(CreatorInfoTopic.STUDIO)
            mode == CreatorEntryMode.CAMERA && localVideoTrack == null -> {
                showCameraStartChoice = true
            }
            mode == CreatorEntryMode.MOBILE_GAMES && !screenPermissionReady -> {
                pendingScreenStart = true
                screenPermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
            else -> viewModel.startLive { liveId ->
                navController.navigate(Screen.LiveHost.createRoute(liveId)) { launchSingleTop = true }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { if (!isStarting) viewModel.setSource(source) }
    }
    BackHandler(enabled = practiceMode || panel != null || infoTopic != null) {
        when {
            infoTopic != null -> infoTopicName = null
            practiceMode -> practiceMode = false
            else -> panelName = null
        }
    }

    if (practiceMode) {
        PracticeLiveScreen(
            displayName = displayName,
            avatarUrl = avatarUrl,
            localVideoTrack = localVideoTrack,
            eglBaseContext = eglBaseContext,
            filter = cameraFilter,
            micEnabled = micEnabled,
            onClose = { practiceMode = false },
            onGoLive = { practiceMode = false; startPublicLive() },
            onSwitchCamera = viewModel::switchPreviewCamera,
            onCamera = viewModel::toggleCameraPreview,
            onScreenShare = {
                pendingPracticeScreenPreview = true
                screenPermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
            },
            onEffects = { panelName = CreatorPanel.EFFECTS.name },
            onSettings = { panelName = CreatorPanel.SETTINGS.name },
            onToggleMic = { viewModel.setMicEnabled(!micEnabled) },
            onGuests = { panelName = CreatorPanel.SETTINGS.name },
            onMore = { panelName = CreatorPanel.SERVICE.name },
        )
    } else {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val compact = maxHeight < 700.dp || maxWidth < 360.dp
            val contentWidth = maxWidth.coerceAtMost(520.dp)

            CreatorBackground(
                mode = mode,
                localVideoTrack = localVideoTrack,
                eglBaseContext = eglBaseContext,
                cameraBusy = previewBusy,
                onRetryCamera = ::ensureCameraPreview,
            )

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(contentWidth)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                CreatorTopBar(
                    compact = compact,
                    onClose = navController::popBackStack,
                    onCenter = { panelName = CreatorPanel.CENTER.name },
                    onRewards = { panelName = CreatorPanel.REWARDS.name },
                )

                if (mode == CreatorEntryMode.CAMERA) {
                    PracticePrompt(
                        compact = compact,
                        onPractice = { practiceMode = true },
                    )
                }

                Spacer(Modifier.weight(1f))

                when (mode) {
                    CreatorEntryMode.CAMERA -> CameraCreatorControls(
                        compact = compact,
                        title = title,
                        avatarUrl = avatarUrl,
                        cameraEnabled = localVideoTrack != null,
                        isStarting = isStarting,
                        error = error,
                        onTitleChange = viewModel::setTitle,
                        onSwitchCamera = viewModel::switchPreviewCamera,
                        onToggleCamera = viewModel::toggleCameraPreview,
                        onEnhance = { panelName = CreatorPanel.ENHANCE.name },
                        onEffects = { panelName = CreatorPanel.EFFECTS.name },
                        onSettings = { panelName = CreatorPanel.SETTINGS.name },
                        onService = { panelName = CreatorPanel.SERVICE.name },
                        onStart = ::startPublicLive,
                    )
                    CreatorEntryMode.MOBILE_GAMES -> MobileGamesCreatorControls(
                        compact = compact,
                        title = title,
                        category = category,
                        isStarting = isStarting,
                        screenPermissionReady = screenPermissionReady,
                        error = error,
                        onTitleChange = viewModel::setTitle,
                        onCategory = viewModel::setCategory,
                        onSettings = { panelName = CreatorPanel.SETTINGS.name },
                        onTips = { openInfo(CreatorInfoTopic.TIPS) },
                        onFanClub = { openInfo(CreatorInfoTopic.FAN_CLUB) },
                        onLandscape = { openInfo(CreatorInfoTopic.LANDSCAPE) },
                        onCamera = { selectMode(CreatorEntryMode.CAMERA) },
                        onShare = ::shareUpcomingLive,
                        onStart = ::startPublicLive,
                    )
                    CreatorEntryMode.STUDIO -> StudioCreatorControls(
                        compact = compact,
                        onAccess = { openInfo(CreatorInfoTopic.STUDIO) },
                        onMoreInfo = { openInfo(CreatorInfoTopic.STUDIO) },
                    )
                }

                CreatorSourceTabs(mode = mode, onSelect = ::selectMode)
                CreatorBottomModeBar(
                    onPublishVideo = { navController.navigate(Screen.VideoUpload.route) },
                    onCreatePost = { navController.navigate(Screen.CreatePost.route) },
                )
            }

            if (serviceBannerVisible && mode == CreatorEntryMode.CAMERA && !compact) {
                MusicServiceBanner(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        // Keep the announcement below the practice prompt. At
                        // 360dp wide the former 142dp offset covered its text.
                        .padding(top = 220.dp, start = 20.dp, end = 20.dp)
                        .widthIn(max = 460.dp),
                    onOpen = { panelName = CreatorPanel.MUSIC.name },
                    onClose = { serviceBannerVisible = false },
                )
            }
        }
    }

    if (panel != null) {
        CreatorPanelSheet(
            panel = panel,
            quality = quality,
            micEnabled = micEnabled,
            allowGuests = allowGuests,
            giftsEnabled = giftsEnabled,
            cameraFilter = cameraFilter,
            cameraAdjustments = cameraAdjustments,
            onQuality = viewModel::setQuality,
            onMic = viewModel::setMicEnabled,
            onAllowGuests = viewModel::setAllowGuestRequests,
            onGiftsEnabled = viewModel::setGiftsEnabled,
            onFilter = viewModel::setCameraFilter,
            onCameraAdjustments = viewModel::setCameraAdjustments,
            onDismiss = { panelName = null },
            onPractice = { panelName = null; practiceMode = true },
            onStart = { panelName = null; startPublicLive() },
            onInfo = ::openInfo,
            musicState = liveMusic,
            onPickSong = { showMusicPicker = true },
            onShareLive = ::shareUpcomingLive,
            onMusicEnabled = viewModel::setLiveMusicEnabled,
            onMusicVolume = viewModel::setLiveMusicVolume,
            onRemoveMusic = viewModel::clearLiveMusic,
            onOpenMusic = { panelName = CreatorPanel.MUSIC.name },
            onOpenRewards = { panelName = CreatorPanel.REWARDS.name },
            onOpenStudio = {
                panelName = null
                selectMode(CreatorEntryMode.STUDIO)
            },
        )
    }

    if (infoTopic != null) {
        CreatorInfoSheet(
            topic = infoTopic,
            onDismiss = { infoTopicName = null },
        )
    }

    if (showCameraStartChoice) {
        AlertDialog(
            onDismissRequest = { showCameraStartChoice = false },
            title = { Text("Cámara desactivada") },
            text = { Text("Puedes activar la cámara antes de emitir o comenzar solo con audio.") },
            confirmButton = {
                TextButton(onClick = {
                    showCameraStartChoice = false
                    ensureCameraPreview()
                }) { Text("Activar cámara") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCameraStartChoice = false
                    viewModel.startLive { liveId ->
                        navController.navigate(Screen.LiveHost.createRoute(liveId)) { launchSingleTop = true }
                    }
                }) { Text("Solo audio") }
            },
        )
    }

    LiveMusicPickerFlow(
        visible = showMusicPicker,
        onDismiss = { showMusicPicker = false },
        onPrepared = viewModel::prepareLiveMusic,
    )
}

@Composable
private fun BoxScope.CreatorBackground(
    mode: CreatorEntryMode,
    localVideoTrack: org.webrtc.VideoTrack?,
    eglBaseContext: org.webrtc.EglBase.Context?,
    cameraBusy: Boolean,
    onRetryCamera: () -> Unit,
) {
    if (mode == CreatorEntryMode.CAMERA && localVideoTrack != null && eglBaseContext != null) {
        WebRtcVideoView(
            videoTrack = localVideoTrack,
            sharedEglContext = eglBaseContext,
            mirror = true,
            scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.36f),
                        0.36f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    )
                )
        )
    } else if (mode == CreatorEntryMode.CAMERA) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF18242D), Color(0xFF2B2028), Color(0xFF07110F))
                    )
                )
                .clickable(onClick = onRetryCamera),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (cameraBusy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Default.CameraAlt, "Activar cámara", tint = Color.White, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    if (cameraBusy) "Preparando cámara…" else "Toca para activar la cámara",
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        if (mode == CreatorEntryMode.MOBILE_GAMES) {
                            listOf(Color(0xFF102940), Color(0xFF1C252B), Color(0xFF002C20))
                        } else {
                            listOf(Color(0xFF0C2440), Color(0xFF17202C), Color(0xFF003426))
                        }
                    )
                )
        )
    }
}

@Composable
private fun CreatorTopBar(
    compact: Boolean,
    onClose: () -> Unit,
    onCenter: () -> Unit,
    onRewards: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlayIconButton(Icons.Default.Close, "Cerrar", onClose)
        Spacer(Modifier.weight(1f))
        OverlayPill("Recompensas", Icons.Default.Paid, onRewards)
        Spacer(Modifier.width(8.dp))
        OverlayPill("Centro LIVE", Icons.Default.Videocam, onCenter)
    }
}

@Composable
private fun PracticePrompt(compact: Boolean, onPractice: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 28.dp, vertical = if (compact) 4.dp else 14.dp)
    ) {
        Text(
            "Prepárate para tu LIVE:\nPractica en privado antes de iniciar LIVE",
            color = Color.White,
            fontSize = if (compact) 18.sp else 21.sp,
            lineHeight = if (compact) 21.sp else 24.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, Color.White.copy(alpha = 0.82f), RoundedCornerShape(999.dp))
                .clickable(onClick = onPractice)
                .padding(horizontal = 20.dp, vertical = 9.dp)
        ) {
            Text("Practicar", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CameraCreatorControls(
    compact: Boolean,
    title: String,
    avatarUrl: String?,
    cameraEnabled: Boolean,
    isStarting: Boolean,
    error: String?,
    onTitleChange: (String) -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleCamera: () -> Unit,
    onEnhance: () -> Unit,
    onEffects: () -> Unit,
    onSettings: () -> Unit,
    onService: () -> Unit,
    onStart: () -> Unit,
) {
    Text(
        "Desliza hacia arriba para ver más",
        color = Color.White.copy(alpha = 0.86f),
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    CreatorToolRow(
        compact = compact,
        tools = listOf(
            CreatorTool("Girar", Icons.Default.Cameraswitch, onSwitchCamera),
            CreatorTool(
                if (cameraEnabled) "Apagar" else "Activar",
                if (cameraEnabled) Icons.Default.VideocamOff else Icons.Default.Videocam,
                onToggleCamera,
            ),
            CreatorTool("Mejorar", Icons.Default.AutoAwesome, onEnhance),
            CreatorTool("Efectos", Icons.Default.FilterVintage, onEffects),
            CreatorTool("Ajustes", Icons.Default.Settings, onSettings),
            CreatorTool("Servicio+", Icons.Default.Campaign, onService),
        )
    )
    CreatorStartCard(
        title = title,
        avatarUrl = avatarUrl,
        isStarting = isStarting,
        error = error,
        onTitleChange = onTitleChange,
        onStart = onStart,
    )
}

@Composable
private fun MobileGamesCreatorControls(
    compact: Boolean,
    title: String,
    category: String,
    isStarting: Boolean,
    screenPermissionReady: Boolean,
    error: String?,
    onTitleChange: (String) -> Unit,
    onCategory: (String) -> Unit,
    onSettings: () -> Unit,
    onTips: () -> Unit,
    onFanClub: () -> Unit,
    onLandscape: () -> Unit,
    onCamera: () -> Unit,
    onShare: () -> Unit,
    onStart: () -> Unit,
) {
    val tools = listOf(
        CreatorTool("Consejos", Icons.Default.Lightbulb, onTips),
        CreatorTool("Club de fans", Icons.Default.FavoriteBorder, onFanClub),
        CreatorTool("Paisaje", Icons.AutoMirrored.Filled.ScreenShare, onLandscape),
        CreatorTool("Cámara", Icons.Default.AddAPhoto, onCamera),
        CreatorTool("Compartir", Icons.Default.Share, onShare),
        CreatorTool("Ajustes", Icons.Default.Settings, onSettings),
    )
    CreatorToolRow(compact = compact, tools = tools)
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.32f))
            .padding(14.dp)
    ) {
        CreatorTitleField(title = title, onTitleChange = onTitleChange, leading = null)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LIVE_CATEGORIES) { item ->
                val selected = item == category
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Color.White else Color.White.copy(alpha = 0.10f))
                        .clickable { onCategory(item) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(item, color = if (selected) Color.Black else Color.White, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (screenPermissionReady) "Captura autorizada" else "Android pedirá permiso para compartir la pantalla",
            color = if (screenPermissionReady) Color(0xFF83F1C4) else LiveMutedText,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        LivePrimaryButton("Emitir LIVE", isStarting, onStart)
        if (!error.isNullOrBlank()) CreatorInlineError(error)
    }
}

@Composable
private fun StudioCreatorControls(
    compact: Boolean,
    onAccess: () -> Unit,
    onMoreInfo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Videocam, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
            Text("Novvin", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                "LIVE Studio",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(LiveActionPink)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(if (compact) 10.dp else 22.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 150.dp else 230.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF171A20))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
        ) {
            Icon(Icons.Default.Computer, null, tint = Color.White.copy(alpha = 0.18f), modifier = Modifier.size(118.dp).align(Alignment.Center))
            Column(Modifier.align(Alignment.CenterEnd).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Icons.Default.SportsEsports, Icons.Default.Campaign, Icons.Default.AutoAwesome).forEach { icon ->
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF343942)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Crea escenas, combina fuentes, controla el audio y revisa el chat desde Novvin LIVE Studio.",
            color = LiveMutedText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onMoreInfo)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Link, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Más información", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        LivePrimaryButton("Obtener acceso", false, onAccess)
    }
}

@Composable
private fun CreatorToolRow(compact: Boolean, tools: List<CreatorTool>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items(tools) { tool ->
            Column(
                modifier = Modifier
                    .width(if (compact) 58.dp else 64.dp)
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = tool.onClick)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tool.icon, tool.label, tint = Color.White, modifier = Modifier.size(if (compact) 23.dp else 26.dp))
                Spacer(Modifier.height(4.dp))
                Text(tool.label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CreatorStartCard(
    title: String,
    avatarUrl: String?,
    isStarting: Boolean,
    error: String?,
    onTitleChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.34f))
            .padding(14.dp)
    ) {
        CreatorTitleField(title, onTitleChange) {
            CircularProfileImage(
                imageUrl = avatarUrl,
                size = 34.dp,
                showBorder = false,
                fallbackName = "LIVE",
            )
        }
        Spacer(Modifier.height(10.dp))
        LivePrimaryButton("Emitir LIVE", isStarting, onStart)
        if (!error.isNullOrBlank()) CreatorInlineError(error)
    }
}

@Composable
private fun CreatorTitleField(
    title: String,
    onTitleChange: (String) -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.width(10.dp))
        TextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Agrega un título", color = Color.White.copy(alpha = 0.52f), fontWeight = FontWeight.Bold) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun LivePrimaryButton(text: String, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LiveActionPink, disabledContainerColor = LiveActionPink.copy(alpha = 0.62f)),
    ) {
        if (loading) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        else Text(text, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    }
}

@Composable
private fun CreatorInlineError(error: String) {
    Text(
        error,
        color = Color(0xFFFFB4AB),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun CreatorSourceTabs(mode: CreatorEntryMode, onSelect: (CreatorEntryMode) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { SourceTab("Cámara del dispositivo", Icons.Default.Videocam, mode == CreatorEntryMode.CAMERA) { onSelect(CreatorEntryMode.CAMERA) } }
        item { SourceTab("Juegos para dispositivos móviles", Icons.Default.Smartphone, mode == CreatorEntryMode.MOBILE_GAMES) { onSelect(CreatorEntryMode.MOBILE_GAMES) } }
        item { SourceTab("LIVE Studio", Icons.Default.Computer, mode == CreatorEntryMode.STUDIO) { onSelect(CreatorEntryMode.STUDIO) } }
    }
}

@Composable
private fun SourceTab(text: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Color.White else Color.White.copy(alpha = 0.48f), modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (selected) Color.White else Color.White.copy(alpha = 0.48f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
    }
}

@Composable
private fun CreatorBottomModeBar(
    onPublishVideo: () -> Unit,
    onCreatePost: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(Color.Black),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "PUBLICAR",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onPublishVideo).padding(horizontal = 12.dp, vertical = 12.dp),
        )
        Text(
            "CREAR",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onCreatePost).padding(horizontal = 12.dp, vertical = 12.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LIVE", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(5.dp))
            Box(Modifier.width(28.dp).height(2.dp).background(Color.White))
        }
    }
}

@Composable
private fun OverlayIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun OverlayPill(text: String, icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.46f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun MusicServiceBanner(modifier: Modifier, onOpen: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF04E0D3), Color(0xFF8B5CF6)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Canciones de LIVE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Explora regalos musicales y promociones", color = Color.White.copy(alpha = 0.68f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Cerrar aviso", tint = Color.White) }
    }
}

@Composable
private fun PracticeLiveScreen(
    displayName: String,
    avatarUrl: String?,
    localVideoTrack: org.webrtc.VideoTrack?,
    eglBaseContext: org.webrtc.EglBase.Context?,
    filter: LiveCameraFilter,
    micEnabled: Boolean,
    onClose: () -> Unit,
    onGoLive: () -> Unit,
    onSwitchCamera: () -> Unit,
    onCamera: () -> Unit,
    onScreenShare: () -> Unit,
    onEffects: () -> Unit,
    onSettings: () -> Unit,
    onToggleMic: () -> Unit,
    onGuests: () -> Unit,
    onMore: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (localVideoTrack != null && eglBaseContext != null) {
            WebRtcVideoView(
                videoTrack = localVideoTrack,
                sharedEglContext = eglBaseContext,
                mirror = true,
                scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProfileImage(
                    imageUrl = avatarUrl,
                    size = 92.dp,
                    showBorder = false,
                    fallbackName = displayName,
                )
                Text("CÁMARA DESACTIVADA", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.24f), Color.Transparent, Color.Black.copy(alpha = 0.82f)))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProfileImage(avatarUrl, size = 38.dp, showBorder = false, fallbackName = displayName)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(displayName, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("0 me gusta · Modo práctica", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFF68152C)).clickable(onClick = onGoLive).padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Campaign, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Emitir LIVE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                OverlayIconButton(Icons.Default.Close, "Salir de práctica", onClose)
            }
            Spacer(Modifier.weight(1f))
            PracticeSystemMessage("¡Te damos la bienvenida al modo de práctica! Explora las funciones LIVE antes de iniciar una transmisión.")
            PracticeSystemMessage("Los ajustes del modo práctica se conservarán cuando decidas emitir.")
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                item { PracticeControl(if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff, "Micrófono", onToggleMic) }
                item { PracticeControl(Icons.Default.Groups, "Invitados", onGuests) }
                item { PracticeControl(Icons.Default.Cameraswitch, "Girar", onSwitchCamera) }
                item {
                    PracticeControl(
                        if (localVideoTrack == null) Icons.Default.Videocam else Icons.Default.Close,
                        if (localVideoTrack == null) "Activar" else "Apagar",
                        onCamera,
                    )
                }
                item { PracticeControl(Icons.Default.PhoneAndroid, "Pantalla", onScreenShare) }
                item { PracticeControl(Icons.Default.FilterVintage, filter.wireValue, onEffects) }
                item { PracticeControl(Icons.Default.Settings, "Ajustes", onSettings) }
                item { PracticeControl(Icons.Default.MoreHoriz, "Más", onMore) }
            }
        }
    }
}

@Composable
private fun PracticeSystemMessage(text: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF343438)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PracticeControl(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF2B2B2E)),
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(23.dp)) }
}

@Composable
private fun CreatorPanelSheet(
    panel: CreatorPanel,
    quality: LiveQuality,
    micEnabled: Boolean,
    allowGuests: Boolean,
    giftsEnabled: Boolean,
    cameraFilter: LiveCameraFilter,
    cameraAdjustments: LiveCameraAdjustments,
    onQuality: (LiveQuality) -> Unit,
    onMic: (Boolean) -> Unit,
    onAllowGuests: (Boolean) -> Unit,
    onGiftsEnabled: (Boolean) -> Unit,
    onFilter: (LiveCameraFilter) -> Unit,
    onCameraAdjustments: (LiveCameraAdjustments) -> Unit,
    onDismiss: () -> Unit,
    onPractice: () -> Unit,
    onStart: () -> Unit,
    onInfo: (CreatorInfoTopic) -> Unit,
    musicState: LiveMusicPlaybackState,
    onPickSong: () -> Unit,
    onShareLive: () -> Unit,
    onMusicEnabled: (Boolean) -> Unit,
    onMusicVolume: (Float) -> Unit,
    onRemoveMusic: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenRewards: () -> Unit,
    onOpenStudio: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LiveSheetBlack,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.58f),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { Box(Modifier.padding(vertical = 9.dp).width(36.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f))) },
    ) {
        Box(Modifier.fillMaxWidth().widthIn(max = 560.dp).align(Alignment.CenterHorizontally)) {
            when (panel) {
                CreatorPanel.CENTER -> LiveCenterPanel(onPractice, onStart, onInfo)
                CreatorPanel.REWARDS -> LiveRewardsPanel(onInfo)
                CreatorPanel.MUSIC -> LiveMusicPanel(
                    musicState,
                    onPickSong,
                    onMusicEnabled,
                    onMusicVolume,
                    onRemoveMusic,
                    onOpenRewards,
                )
                CreatorPanel.EFFECTS -> LiveEffectsPanel(cameraFilter, onFilter)
                CreatorPanel.ENHANCE -> LiveEnhancePanel(cameraAdjustments, onCameraAdjustments)
                CreatorPanel.SETTINGS -> LiveSettingsPanel(
                    quality, micEnabled, allowGuests, giftsEnabled,
                    onQuality, onMic, onAllowGuests, onGiftsEnabled, onInfo,
                )
                CreatorPanel.SERVICE -> LiveServicePanel(onShareLive, onOpenMusic, onOpenStudio, onInfo)
            }
        }
    }
}

@Composable
private fun LiveCenterPanel(
    onPractice: () -> Unit,
    onStart: () -> Unit,
    onInfo: (CreatorInfoTopic) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 690.dp).navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PanelTitle("Centro LIVE", "Tu espacio para preparar y hacer crecer cada transmisión") }
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LiveSheetSurface)
            ) {
                Box(
                    Modifier.fillMaxWidth().height(210.dp).background(Brush.horizontalGradient(listOf(Color(0xFF5F213E), Color(0xFFCF6C47), Color(0xFF233F66))))
                ) {
                    Icon(Icons.Default.Campaign, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(88.dp).align(Alignment.Center))
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("¡Hola!", "¿Cómo estás?", "✨").forEach { text ->
                            Text(text, color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFF1D5)).padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                }
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Deja brillar tu verdadero yo", color = Color.White, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(7.dp))
                    Text("Habla, reacciona y genera conversaciones en tiempo real con tus espectadores.", color = LiveMutedText, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    LivePrimaryButton("Inicia LIVE", false, onStart)
                }
            }
        }
        item {
            PanelCardRow(Icons.Default.AutoAwesome, "Practica antes de emitir", "Prueba cámara, audio, efectos e invitados en privado.", onPractice)
        }
        item { PanelCardRow(Icons.Default.Campaign, "Campañas", "Retos y oportunidades activas para creadores.") { onInfo(CreatorInfoTopic.CAMPAIGNS) } }
        item { PanelCardRow(Icons.Default.TipsAndUpdates, "Academia LIVE", "Consejos de calidad, seguridad y crecimiento.") { onInfo(CreatorInfoTopic.ACADEMY) } }
    }
}

@Composable
private fun LiveRewardsPanel(onInfo: (CreatorInfoTopic) -> Unit) {
    var weekly by rememberSaveable { mutableStateOf(true) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 690.dp).navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PanelTitle("Recompensas LIVE", "Consulta diamantes, misiones y progreso") }
        item {
            Row(Modifier.fillMaxWidth()) {
                listOf(false to "Este LIVE", true to "Esta semana").forEach { (value, label) ->
                    Column(Modifier.weight(1f).clickable { weekly = value }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = if (weekly == value) Color.White else LiveMutedText, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (weekly == value) Color.White else Color.Transparent))
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LiveSheetSurface).padding(18.dp)) {
                Text("Recompensas de la misión semanal", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text("Diamantes totales: 💎 0", color = LiveMutedText, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { 0.13f }, modifier = Modifier.size(124.dp), color = LiveActionPink, trackColor = Color(0xFF555558), strokeWidth = 12.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("USD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("0.00", color = Color.White, fontWeight = FontWeight.Black, fontSize = 34.sp)
                    }
                }
                Text("Sugerencia: cuantos más Regalos y comentarios destacados reciba tu LIVE, más Diamantes podrás conseguir.", color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF3B3B3E)).padding(12.dp))
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LiveSheetSurface).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Misiones", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text("0%", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                PanelCardRow(Icons.Default.Videocam, "Días válidos de emisión LIVE", "Emite al menos 25 minutos para que el día cuente.") { onInfo(CreatorInfoTopic.REWARD_MISSION) }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = LiveActionPink, trackColor = Color(0xFF555558))
            }
        }
    }
}

@Composable
private fun LiveMusicPanel(
    state: LiveMusicPlaybackState,
    onPickSong: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onRemove: () -> Unit,
    onOpenRewards: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 690.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
    ) {
        PanelTitle("Que el mundo te escuche", "Promociona música y crea momentos compartibles")
        Box(
            Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(18.dp)).background(Brush.radialGradient(listOf(Color(0xFF143A67), Color(0xFF090A16), Color.Black))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Headphones, null, tint = Color(0xFF9CE8FF), modifier = Modifier.size(92.dp))
            Icon(Icons.Default.MusicNote, null, tint = LiveActionPink, modifier = Modifier.align(Alignment.CenterStart).padding(start = 54.dp).size(32.dp))
            Icon(Icons.Default.Paid, null, tint = Color(0xFFC8FF55), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 54.dp).size(36.dp))
        }
        Spacer(Modifier.height(14.dp))
        PanelCardRow(
            Icons.Default.MusicNote,
            "Canción del directo",
            state.displayName ?: "Elige un audio autorizado de tu dispositivo.",
            onPickSong,
        )
        if (state.preparing) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(color = LiveActionPink, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text("Preparando fragmento…", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        if (state.isPrepared) {
            TogglePanelRow(
                "Música en el LIVE",
                if (state.enabled) "Se mezcla con tu micrófono y se repite" else "Pausada para los espectadores",
                state.enabled,
                onEnabled,
            )
            Text("Volumen de la música", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = LiveMutedText, modifier = Modifier.size(20.dp))
                Slider(value = state.volume, onValueChange = onVolume, modifier = Modifier.weight(1f))
                Text("${(state.volume * 100).toInt()}%", color = LiveMutedText, fontSize = 12.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
            }
            TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.End)) {
                Text("Quitar canción", color = Color(0xFFFF8A9D), fontWeight = FontWeight.Bold)
            }
        }
        state.error?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        PanelCardRow(Icons.Default.AccountBalanceWallet, "Más recompensas", "Programas exclusivos para impulsar tu música.", onOpenRewards)
        Spacer(Modifier.height(18.dp))
        LivePrimaryButton(if (!state.isPrepared) "Elegir canción" else "Cambiar canción", state.preparing, onPickSong)
    }
}

@Composable
private fun LiveEffectsPanel(selected: LiveCameraFilter, onSelect: (LiveCameraFilter) -> Unit) {
    val categories = listOf("Popular", "Belleza", "Retrato", "Divertido", "Color")
    var selectedCategory by rememberSaveable { mutableStateOf("Popular") }
    val filters = when (selectedCategory) {
        "Belleza" -> listOf(LiveCameraFilter.SOFT, LiveCameraFilter.WARM)
        "Retrato" -> listOf(LiveCameraFilter.NONE, LiveCameraFilter.SOFT, LiveCameraFilter.WARM)
        "Divertido" -> listOf(LiveCameraFilter.VIVID, LiveCameraFilter.MONO)
        "Color" -> listOf(LiveCameraFilter.WARM, LiveCameraFilter.COOL, LiveCameraFilter.VIVID, LiveCameraFilter.MONO)
        else -> LiveCameraFilter.entries
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).navigationBarsPadding().padding(horizontal = 16.dp)) {
        PanelTitle("Efectos", "El efecto seleccionado se aplica a la cámara en tiempo real")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            items(categories) { category ->
                val active = category == selectedCategory
                Text(
                    category,
                    color = if (active) Color.White else LiveMutedText,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Normal,
                    modifier = Modifier.clickable { selectedCategory = category }.padding(vertical = 10.dp),
                )
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().height(360.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(filters) { filter ->
                val active = filter == selected
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onSelect(filter) }) {
                    Box(
                        Modifier.size(76.dp).clip(RoundedCornerShape(18.dp)).background(filterPreviewBrush(filter)).then(if (active) Modifier.border(3.dp, Color.White, RoundedCornerShape(18.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (filter == LiveCameraFilter.NONE) Icons.Default.Close else Icons.Default.AutoAwesome, filterLabel(filter), tint = Color.White, modifier = Modifier.size(30.dp))
                        if (active) Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(LiveActionPink), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(filterLabel(filter), color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
internal fun NovvinLiveEffectsSheet(
    selected: LiveCameraFilter,
    onSelect: (LiveCameraFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LiveSheetBlack,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.58f),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        LiveEffectsPanel(selected = selected, onSelect = onSelect)
    }
}

@Composable
private fun LiveEnhancePanel(
    adjustments: LiveCameraAdjustments,
    onAdjustments: (LiveCameraAdjustments) -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp)) {
        PanelTitle("Mejorar", "Ajustes sutiles para mantener una imagen natural")
        TogglePanelRow(
            "Mejora automática",
            "Equilibra luz y color",
            adjustments.enabled,
        ) { onAdjustments(adjustments.copy(enabled = it)) }
        EnhanceSlider("Suavizado", adjustments.smooth) {
            onAdjustments(adjustments.copy(smooth = it))
        }
        EnhanceSlider("Brillo", adjustments.brightness) {
            onAdjustments(adjustments.copy(brightness = it))
        }
        EnhanceSlider("Calidez", adjustments.warmth) {
            onAdjustments(adjustments.copy(warmth = it))
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun EnhanceSlider(label: String, value: Float, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.width(92.dp), fontSize = 13.sp)
        Slider(value = value, onValueChange = onValue, modifier = Modifier.weight(1f))
        Text("${(value * 100).toInt()}", color = LiveMutedText, modifier = Modifier.width(34.dp), textAlign = TextAlign.End, fontSize = 12.sp)
    }
}

@Composable
private fun LiveSettingsPanel(
    quality: LiveQuality,
    micEnabled: Boolean,
    allowGuests: Boolean,
    giftsEnabled: Boolean,
    onQuality: (LiveQuality) -> Unit,
    onMic: (Boolean) -> Unit,
    onAllowGuests: (Boolean) -> Unit,
    onGiftsEnabled: (Boolean) -> Unit,
    onInfo: (CreatorInfoTopic) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        PanelTitle("Ajustes de LIVE", "Configura la emisión antes de comenzar")
        TogglePanelRow("Micrófono", "Puedes cambiarlo durante el LIVE", micEnabled, onMic)
        TogglePanelRow("Solicitudes de invitados", "Permite que espectadores pidan unirse", allowGuests, onAllowGuests)
        TogglePanelRow(
            "Regalos animados",
            if (giftsEnabled) "Los espectadores pueden enviar regalos" else "Los regalos no aparecerán en este directo",
            giftsEnabled,
            onGiftsEnabled,
        )
        Text("Calidad", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            LiveQuality.entries.forEach { item ->
                val selected = item == quality
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Color.White else LiveSheetSurface).clickable { onQuality(item) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text(item.label, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(12.dp))
        PanelCardRow(Icons.Default.Tune, "Moderación", "Comentarios, palabras bloqueadas y mayores de 18.") { onInfo(CreatorInfoTopic.MODERATION) }
        PanelCardRow(Icons.Default.Notifications, "Notificaciones", "Avisos de regalos, invitados y partidas.") { onInfo(CreatorInfoTopic.NOTIFICATIONS) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun LiveServicePanel(
    onShareLive: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenStudio: () -> Unit,
    onInfo: (CreatorInfoTopic) -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp)) {
        PanelTitle("Servicio+", "Herramientas adicionales para creadores LIVE")
        PanelCardRow(Icons.Default.Campaign, "Promocionar LIVE", "Invita a seguidores y comparte el enlace.", onShareLive)
        PanelCardRow(Icons.Default.Lyrics, "Canciones de LIVE", "Carga una canción y revisa elegibilidad.", onOpenMusic)
        PanelCardRow(Icons.Default.Computer, "Novvin LIVE Studio", "Escenas, fuentes y chat desde escritorio.", onOpenStudio)
        PanelCardRow(Icons.AutoMirrored.Filled.HelpOutline, "Ayuda y seguridad", "Normas, conexión y solución de problemas.") { onInfo(CreatorInfoTopic.HELP) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CreatorInfoSheet(
    topic: CreatorInfoTopic,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LiveSheetBlack,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.58f),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        val content = creatorInfoContent(topic)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .align(Alignment.CenterHorizontally)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF2A2A2D)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(creatorInfoIcon(topic), null, tint = Color.White, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(content.first, color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp)
            Spacer(Modifier.height(6.dp))
            Text(content.second, color = LiveMutedText, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            ) {
                Text("Entendido", color = Color.Black, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

private fun creatorInfoContent(topic: CreatorInfoTopic): Pair<String, String> = when (topic) {
    CreatorInfoTopic.TIPS -> "Consejos para transmitir juegos" to
        "Activa No molestar, cierra información privada antes de compartir pantalla y usa audífonos para evitar eco. La transmisión conserva el audio aunque Android reduzca temporalmente el video."
    CreatorInfoTopic.FAN_CLUB -> "Club de fans" to
        "Los seguidores frecuentes pueden recibir reconocimiento durante tus LIVE. Las recompensas y niveles solo aparecen cuando la cuenta cumple los requisitos del programa de creadores."
    CreatorInfoTopic.LANDSCAPE -> "Modo paisaje" to
        "Gira el teléfono antes de autorizar la captura. Novvin detecta la orientación compartida y adapta el video sin estirar ni recortar la interfaz del juego."
    CreatorInfoTopic.CAMPAIGNS -> "Campañas LIVE" to
        "Aquí aparecerán los retos activos de Novvin cuando estén disponibles para tu región y tu cuenta. No se inscribe a una campaña sin tu confirmación."
    CreatorInfoTopic.ACADEMY -> "Academia LIVE" to
        "Mantén una conexión estable, ilumina el rostro de frente, modera el chat y avisa a tus invitados antes de activar su cámara o micrófono."
    CreatorInfoTopic.REWARD_MISSION -> "Días válidos de emisión" to
        "Un día cuenta cuando alcanzas al menos 25 minutos de emisión pública. El modo práctica, las reconexiones duplicadas y los LIVE eliminados no suman progreso."
    CreatorInfoTopic.MUSIC_GIFTS -> "Regalos de canciones" to
        "Selecciona un archivo de audio autorizado para prepararlo. La publicación o monetización depende de derechos, región y elegibilidad de la cuenta."
    CreatorInfoTopic.MUSIC_REACH -> "Compartir música" to
        "Promociona el LIVE con el selector de compartir. Novvin nunca publica en otra aplicación sin que elijas el destino y confirmes el envío."
    CreatorInfoTopic.MUSIC_REWARDS -> "Recompensas musicales" to
        "Las campañas musicales aparecerán en Recompensas cuando exista una convocatoria activa y tu cuenta cumpla sus requisitos."
    CreatorInfoTopic.MODERATION -> "Moderación" to
        "Durante el LIVE puedes silenciar, expulsar, bloquear o denunciar espectadores desde el menú de cada mensaje. Las solicitudes de invitados se pueden desactivar antes de emitir."
    CreatorInfoTopic.NOTIFICATIONS -> "Notificaciones de LIVE" to
        "Los avisos de regalos, invitados y batallas respetan la configuración general de notificaciones de Novvin y el modo No molestar de Android."
    CreatorInfoTopic.STUDIO -> "Novvin LIVE Studio" to
        "LIVE Studio permite preparar escenas y fuentes desde escritorio. El acceso y la clave de transmisión se habilitan únicamente para cuentas autorizadas."
    CreatorInfoTopic.HELP -> "Ayuda y seguridad" to
        "No compartas datos personales en pantalla, revisa invitados antes de aceptarlos y finaliza el LIVE si detectas acoso o contenido peligroso. Usa las herramientas de denuncia y bloqueo del chat."
}

private fun creatorInfoIcon(topic: CreatorInfoTopic): ImageVector = when (topic) {
    CreatorInfoTopic.TIPS,
    CreatorInfoTopic.ACADEMY -> Icons.Default.TipsAndUpdates
    CreatorInfoTopic.FAN_CLUB -> Icons.Default.FavoriteBorder
    CreatorInfoTopic.LANDSCAPE -> Icons.AutoMirrored.Filled.ScreenShare
    CreatorInfoTopic.CAMPAIGNS -> Icons.Default.Campaign
    CreatorInfoTopic.REWARD_MISSION,
    CreatorInfoTopic.MUSIC_REWARDS -> Icons.Default.AccountBalanceWallet
    CreatorInfoTopic.MUSIC_GIFTS,
    CreatorInfoTopic.MUSIC_REACH -> Icons.Default.MusicNote
    CreatorInfoTopic.MODERATION -> Icons.Default.Tune
    CreatorInfoTopic.NOTIFICATIONS -> Icons.Default.Notifications
    CreatorInfoTopic.STUDIO -> Icons.Default.Computer
    CreatorInfoTopic.HELP -> Icons.AutoMirrored.Filled.HelpOutline
}

@Composable
private fun PanelTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = LiveMutedText, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun PanelCardRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF2A2A2D)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = LiveMutedText, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = LiveMutedText, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TogglePanelRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = LiveMutedText, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedTrackColor = LiveActionPink, checkedThumbColor = Color.White),
        )
    }
}

private fun filterLabel(filter: LiveCameraFilter): String = when (filter) {
    LiveCameraFilter.NONE -> "Natural"
    LiveCameraFilter.WARM -> "Cálido"
    LiveCameraFilter.COOL -> "Frío"
    LiveCameraFilter.VIVID -> "Vívido"
    LiveCameraFilter.SOFT -> "Suave"
    LiveCameraFilter.MONO -> "Mono"
}

private fun filterPreviewBrush(filter: LiveCameraFilter): Brush = when (filter) {
    LiveCameraFilter.NONE -> Brush.linearGradient(listOf(Color(0xFF3C4147), Color(0xFF151719)))
    LiveCameraFilter.WARM -> Brush.linearGradient(listOf(Color(0xFFFF9A62), Color(0xFF8B3C45)))
    LiveCameraFilter.COOL -> Brush.linearGradient(listOf(Color(0xFF6FD7FF), Color(0xFF344A92)))
    LiveCameraFilter.VIVID -> Brush.linearGradient(listOf(Color(0xFFFF376D), Color(0xFF3BE0B4)))
    LiveCameraFilter.SOFT -> Brush.linearGradient(listOf(Color(0xFFFFD7E5), Color(0xFF9D8FC7)))
    LiveCameraFilter.MONO -> Brush.linearGradient(listOf(Color.White, Color.Black))
}
