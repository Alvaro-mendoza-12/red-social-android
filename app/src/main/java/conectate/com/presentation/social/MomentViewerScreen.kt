package conectate.com.presentation.social

import androidx.compose.runtime.Composable

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import conectate.com.data.model.Moment
import conectate.com.data.model.MomentView
import conectate.com.data.model.resolvedMusicAudioUrl
import conectate.com.data.model.requiresSeparateMusicPlayback
import conectate.com.data.model.resolvedMusicEndMs
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.rememberReliableBottomSafePadding
import conectate.com.util.MediaPlaybackDataSources
import conectate.com.util.UbuntuMedia
import conectate.com.util.VideoCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material.icons.automirrored.filled.Send

private const val IMAGE_MOMENT_DURATION_MS = 6_000
private const val MAX_VIDEO_MOMENT_DURATION_MS = 25_000L
private const val DEFAULT_VIDEO_MOMENT_DURATION_MS = 25_000L

/*
 * Legacy all-in-one implementation kept temporarily as source reference.
 * It generated a single oversized Compose method that Android rejected with
 * VerifyError on real navigation. The active implementation below is split
 * into small verifier-safe composables.
 *
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MomentViewerScreen(
    navController: NavController,
    momentId: String,
    viewModel: MomentViewModel = hiltViewModel()
) {
    val reliableBottomPadding = rememberReliableBottomSafePadding()
    android.util.Log.d("MomentDebug", "Entering MomentViewerScreen for momentId: $momentId")

    val moments by viewModel.moments.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val viewers by viewModel.viewers.collectAsState()
    val visibleMoments = remember(moments, momentId) {
        val selectedMoment = moments.firstOrNull { it.id == momentId }
        val selectedUserId = selectedMoment?.userId
        if (!selectedUserId.isNullOrBlank()) {
            moments.filter { it.userId == selectedUserId }
        } else {
            emptyList()
        }
    }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { visibleMoments.size })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    if (visibleMoments.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        Icons.Default.HistoryToggleOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(52.dp)
                    )
                    Text(
                        "Este estado ya venció o fue eliminado.",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = { navController.popBackStack() }) {
                        Text("Volver")
                    }
                }
            }
        }
        return
    }

    var showViewersSheet by remember { mutableStateOf(false) }
    var showHighlightDialog by remember { mutableStateOf(false) }
    var highlightTitle by remember { mutableStateOf("") }
    var highlightSaving by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var verticalDragTotal by remember { mutableStateOf(0f) }
    val videoDurationsMs = remember { mutableStateMapOf<String, Long>() }
    val videoReadyMomentIds = remember { mutableStateMapOf<String, Boolean>() }
    val videoProgressByMomentId = remember { mutableStateMapOf<String, Float>() }

    // Track view for the current moment
    LaunchedEffect(pagerState.currentPage, visibleMoments.size) {
        android.util.Log.d("MomentDebug", "Page Changed: ${pagerState.currentPage}, Moments: ${visibleMoments.size}")

        if (visibleMoments.isNotEmpty() && pagerState.currentPage in visibleMoments.indices) {
            val currentMoment = visibleMoments[pagerState.currentPage]
            if (currentMoment.userId == viewModel.currentUserId) {
                viewModel.loadViewers(currentMoment.id)
            } else {
                viewModel.recordView(currentMoment.id)
            }
        }
    }

    // Ensure we start at the right index once moments load
    var hasScrolledToInitial by remember { mutableStateOf(false) }
    LaunchedEffect(visibleMoments.size, momentId) {
        android.util.Log.d("MomentDebug", "Initial Scroll Check: moments=${visibleMoments.size}, hasScrolled=$hasScrolledToInitial")
        if (visibleMoments.isNotEmpty() && !hasScrolledToInitial) {

            val startIndex = visibleMoments.indexOfFirst { it.id == momentId }
            val safeIndex = if (startIndex != -1) startIndex else 0
            if (safeIndex != pagerState.currentPage) {
                pagerState.scrollToPage(safeIndex)
            }
            hasScrolledToInitial = true
        }
    }

    // Auto-advance logic using Animatable for smooth progress
    val activeMomentId = visibleMoments.getOrNull(pagerState.currentPage)?.id
    val currentProgress = remember(activeMomentId) { Animatable(0f) }
    var lastAdvancedMomentId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeMomentId) {
        lastAdvancedMomentId = null
        val activeMoment = visibleMoments.getOrNull(pagerState.currentPage)
        if (activeMoment?.isVideoMoment() == true) {
            videoReadyMomentIds[activeMoment.id] = false
        }
    }
    val advanceToNextMoment: () -> Unit = {
        val momentToAdvance = visibleMoments.getOrNull(pagerState.currentPage)?.id
        if (momentToAdvance != null && lastAdvancedMomentId != momentToAdvance) {
            lastAdvancedMomentId = momentToAdvance
            coroutineScope.launch {
                if (pagerState.currentPage < visibleMoments.size - 1) {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                } else {
                    navController.popBackStack()
                }
            }
        }
    }
    
    // Reset progress when page changes
    val activeMoment = visibleMoments.getOrNull(pagerState.currentPage)
    val activeMomentDurationMs = activeMoment
        ?.let { moment -> moment.playbackDurationMs(videoDurationsMs[moment.id]) }
        ?: IMAGE_MOMENT_DURATION_MS.toLong()
    val activeMomentReady = activeMoment
        ?.let { moment -> !moment.isVideoMoment() || videoReadyMomentIds[moment.id] == true }
        ?: false

    LaunchedEffect(
        hasScrolledToInitial,
        pagerState.currentPage,
        visibleMoments.getOrNull(pagerState.currentPage)?.id,
        paused,
        activeMomentDurationMs,
        activeMomentReady
    ) {
        if (!hasScrolledToInitial) return@LaunchedEffect
        // Video lifetime is owned by ExoPlayer. A metadata/timer race must never close a video.
        if (activeMoment?.isVideoMoment() == true) {
            currentProgress.stop()
            return@LaunchedEffect
        }
        if (!activeMomentReady) {
            currentProgress.stop()
            return@LaunchedEffect
        }
        if (!paused) {
            val currentMoment = visibleMoments.getOrNull(pagerState.currentPage)
            val currentMomentId = currentMoment?.id ?: return@LaunchedEffect
            val durationMs = currentMoment.playbackDurationMs(videoDurationsMs[currentMoment.id])
            if (currentProgress.value <= 0.001f || currentProgress.value >= 1f) {
                currentProgress.snapTo(0f)
            }
            val remainingDurationMs = (durationMs * (1f - currentProgress.value))
                .roundToInt()
                .coerceAtLeast(250)
            currentProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = remainingDurationMs, easing = LinearEasing)
            )
            if (paused || visibleMoments.getOrNull(pagerState.currentPage)?.id != currentMomentId) {
                return@LaunchedEffect
            }
            advanceToNextMoment()
        } else {
            currentProgress.stop() // Stop animation if paused
        }
    }

    // Root container replacing Scaffold for absolute control
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondBoundsPageCount = 1,
            pageSpacing = 0.dp, // Ensure no gaps
            contentPadding = PaddingValues(0.dp), // Ensure no padding
            key = { index -> visibleMoments.getOrNull(index)?.id ?: index }
        ) { pageIndex ->
            visibleMoments.getOrNull(pageIndex)?.let { moment ->
                // STRICT VISIBILITY CONTROL
                val isVisible = (pageIndex == pagerState.currentPage)
                MomentContent(
                    moment = moment,
                    isVisible = isVisible,
                    paused = paused,
                    onVideoDurationKnown = { momentId, durationMs ->
                        videoDurationsMs[momentId] = clampMomentVideoDuration(durationMs)
                    },
                    onVideoReady = { momentId ->
                        videoReadyMomentIds[momentId] = true
                    },
                    onVideoProgress = { momentId, progress ->
                        videoProgressByMomentId[momentId] = progress.coerceIn(0f, 1f)
                    },
                    onVideoEnded = { momentId ->
                        if (momentId == visibleMoments.getOrNull(pagerState.currentPage)?.id && !paused) {
                            videoProgressByMomentId[momentId] = 1f
                            advanceToNextMoment()
                        }
                    }
                )
            }
        }

        // Overlay UI
        val currentMoment = visibleMoments.getOrNull(pagerState.currentPage)
        if (currentMoment != null) {
             Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars) // Respect status bar
                    .padding(top = 8.dp) // Little extra padding
                    .padding(horizontal = 12.dp)
            ) {
                // Progress Bars
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(visibleMoments.size) { index ->
                        val progressValue = when {
                            index < pagerState.currentPage -> 1f
                            index == pagerState.currentPage && visibleMoments[index].isVideoMoment() ->
                                videoProgressByMomentId[visibleMoments[index].id] ?: 0f
                            index == pagerState.currentPage -> currentProgress.value
                            else -> 0f
                        }
                        
                        LinearProgressIndicator(
                            progress = { progressValue },
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // User Info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProfileImage(
                        imageUrl = currentMoment.userAvatarUrl,
                        size = 40.dp,
                        showBorder = false
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = currentMoment.userDisplayName ?: currentMoment.username ?: "Usuario",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = formatMomentTimestamp(currentMoment.createdAt),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // Swipe Down to close, Swipe Up to reply
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            verticalDragTotal = 0f
                        },
                        onVerticalDrag = { _, dragAmount ->
                            verticalDragTotal += dragAmount
                        },
                        onDragEnd = {
                            val moment = visibleMoments.getOrNull(pagerState.currentPage)
                            if (verticalDragTotal < -120f && moment?.userId == viewModel.currentUserId) {
                                moment?.let {
                                    paused = true
                                    viewModel.loadViewers(it.id)
                                    showViewersSheet = true
                                }
                            } else if (verticalDragTotal > 160f) {
                                navController.popBackStack()
                            }
                            verticalDragTotal = 0f
                        },
                        onDragCancel = {
                            verticalDragTotal = 0f
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val width = size.width
                            val leftZone = width * 0.25f
                            val rightZone = width * 0.75f
                            coroutineScope.launch {
                                if (offset.x < leftZone) {
                                    if (pagerState.currentPage > 0) {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                } else if (offset.x > rightZone) {
                                    if (pagerState.currentPage < visibleMoments.size - 1) {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    } else {
                                        navController.popBackStack()
                                    }
                                }
                            }
                        },
                        onLongPress = { paused = true },
                        onPress = {
                            tryAwaitRelease()
                            paused = false
                        }
                    )
                }
        )

        // Close button - rendered on top layer to ensure it works
        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 16.dp, end = 12.dp)
        ) {
            Icon(
                Icons.Default.Close, 
                contentDescription = "Cerrar",
                tint = Color.White,
                modifier = Modifier
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    .padding(4.dp)
            )
        }

        // Bottom Actions (Viewers or Reply)
        if (currentMoment != null) {
            val isMyMoment = currentMoment.userId == viewModel.currentUserId
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -reliableBottomPadding)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = reliableBottomPadding)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                 // Caption display
                if (!currentMoment.caption.isNullOrBlank()) {
                    Text(
                        text = currentMoment.caption,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                if (isMyMoment) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    paused = true
                                    showViewersSheet = true
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.White)
                            Text(
                                text = "${viewers.size} vistos",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    paused = true
                                    highlightTitle = ""
                                    showHighlightDialog = true
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.StarBorder, contentDescription = null, tint = Color.White)
                            Text(
                                text = "Destacar",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // Reply UI
                    var replyText by remember { mutableStateOf("") }
                    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                    
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        placeholder = { Text("Responder...", color = Color.White.copy(alpha = 0.6f)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    paused = true
                                } else {
                                    // Optionally resume when focus lost, or keep paused?
                                    // User said "until you send", but if they cancel (back press), 
                                    // it should probably resume. Let's resume on focus lost.
                                    paused = false
                                }
                            },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(24.dp),
                        trailingIcon = {
                            if (replyText.isNotBlank()) {
                                IconButton(onClick = { 
                                    // Send Message
                                    viewModel.sendReply(
                                        momentId = currentMoment.id, 
                                        text = replyText, 
                                        receiverId = currentMoment.userId
                                    )
                                    replyText = ""
                                    // Resume playback
                                    paused = false
                                    focusManager.clearFocus() 
                                    android.widget.Toast.makeText(context, "Respuesta enviada", android.widget.Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showHighlightDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!highlightSaving) {
                    showHighlightDialog = false
                    paused = false
                }
            },
            title = { Text("Destacar momento") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("El momento seguirá visible en tu perfil después de vencer.")
                    OutlinedTextField(
                        value = highlightTitle,
                        onValueChange = { highlightTitle = it.take(60) },
                        label = { Text("Nombre del destacado") },
                        placeholder = { Text("Viajes, amigos, música…") },
                        singleLine = true,
                        enabled = !highlightSaving
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val moment = visibleMoments.getOrNull(pagerState.currentPage)
                            ?: return@Button
                        highlightSaving = true
                        viewModel.saveMomentToHighlight(moment, highlightTitle) { result ->
                            highlightSaving = false
                            result.onSuccess {
                                showHighlightDialog = false
                                paused = false
                                android.widget.Toast.makeText(
                                    context,
                                    "Momento destacado en tu perfil",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }.onFailure { error ->
                                android.widget.Toast.makeText(
                                    context,
                                    error.message ?: "No se pudo destacar el momento",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    enabled = highlightTitle.isNotBlank() && !highlightSaving,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (highlightSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Guardar")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showHighlightDialog = false
                        paused = false
                    },
                    enabled = !highlightSaving
                ) { Text("Cancelar") }
            }
        )
    }

    if (showViewersSheet) {
        ModalBottomSheet(
            onDismissRequest = { 
                showViewersSheet = false 
                paused = false
            },
            sheetState = rememberModalBottomSheetState()
        ) {
            ViewersList(
                viewers = viewers,
                onDeleteMoment = {
                    visibleMoments.getOrNull(pagerState.currentPage)?.let { moment ->
                        viewModel.deleteMoment(moment.id) {
                            navController.popBackStack()
                        }
                    } ?: navController.popBackStack()
                },
                isOwner = true
            )
        }
    }
}
*/

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MomentViewerScreen(
    navController: NavController,
    momentId: String,
    viewModel: MomentViewModel = hiltViewModel()
) {
    val moments by viewModel.moments.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val viewers by viewModel.viewers.collectAsState()
    val visibleMoments = remember(moments, momentId) {
        val ownerId = moments.firstOrNull { it.id == momentId }?.userId
        if (ownerId.isNullOrBlank()) emptyList() else moments.filter { it.userId == ownerId }
    }

    if (visibleMoments.isEmpty()) {
        MomentUnavailableState(isRefreshing = isRefreshing, onClose = navController::popBackStack)
        return
    }

    val pagerState = rememberPagerState(pageCount = { visibleMoments.size })
    val scope = rememberCoroutineScope()
    val reliableBottomPadding = rememberReliableBottomSafePadding()
    val videoDurationsMs = remember { mutableStateMapOf<String, Long>() }
    val videoReadyMomentIds = remember { mutableStateMapOf<String, Boolean>() }
    val imageReadyMomentIds = remember { mutableStateMapOf<String, Boolean>() }
    val musicPreparedMomentIds = remember { mutableStateMapOf<String, Boolean>() }
    val musicStartedMomentIds = remember { mutableStateMapOf<String, Boolean>() }
    val videoProgressByMomentId = remember { mutableStateMapOf<String, Float>() }
    var paused by remember { mutableStateOf(false) }
    val resumed = momentPlaybackResumed()
    var automaticAdvance by rememberSaveable { mutableStateOf(true) }
    var toolsOpen by remember { mutableStateOf(false) }
    val playbackPaused = paused || toolsOpen || !resumed || pagerState.isScrollInProgress
    var showViewersSheet by remember { mutableStateOf(false) }
    var showHighlightDialog by remember { mutableStateOf(false) }
    var highlightTitle by remember { mutableStateOf("") }
    var highlightSaving by remember { mutableStateOf(false) }
    var hasScrolledToInitial by remember { mutableStateOf(false) }
    var lastAdvancedMomentId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(visibleMoments.size, momentId) {
        if (!hasScrolledToInitial) {
            pagerState.scrollToPage(visibleMoments.indexOfFirst { it.id == momentId }.coerceAtLeast(0))
            hasScrolledToInitial = true
        }
    }

    LaunchedEffect(pagerState.currentPage, visibleMoments.size) {
        visibleMoments.getOrNull(pagerState.currentPage)?.let { moment ->
            if (moment.userId == viewModel.currentUserId) viewModel.loadViewers(moment.id)
            else viewModel.recordView(moment.id)
        }
    }

    val activeMoment = visibleMoments.getOrNull(pagerState.currentPage)
    val activeMomentId = activeMoment?.id
    // This is an interval clock, not a UI animation. Compose tween/Animatable
    // honors the device animator-duration scale, so phones with animations
    // disabled could finish a six-second Moment almost immediately.
    var currentProgress by remember(activeMomentId) { mutableFloatStateOf(0f) }
    val advance: () -> Unit = {
        val currentId = visibleMoments.getOrNull(pagerState.currentPage)?.id
        if (currentId != null && lastAdvancedMomentId != currentId) {
            lastAdvancedMomentId = currentId
            scope.launch {
                if (pagerState.currentPage < visibleMoments.lastIndex) {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                } else navController.popBackStack()
            }
        }
    }

    LaunchedEffect(activeMomentId) {
        lastAdvancedMomentId = null
        // Pager preloads adjacent Moments. Preserve readiness reported during
        // that preload; resetting it here can deadlock because Media3/Coil do
        // not necessarily emit STATE_READY/onSuccess a second time.
    }

    val activeDuration = activeMoment?.playbackDurationMs(videoDurationsMs[activeMoment.id])
        ?: IMAGE_MOMENT_DURATION_MS.toLong()
    val activeVisualReady = activeMoment?.let { moment ->
        val visualReady = if (moment.isVideoMoment()) {
            videoReadyMomentIds[moment.id] == true
        } else {
            imageReadyMomentIds[moment.id] == true
        }
        visualReady
    } ?: false
    val activePrepared = activeMoment?.let { moment ->
        activeVisualReady && (
            !moment.requiresSeparateMusicPlayback || musicPreparedMomentIds[moment.id] == true
        )
    } ?: false
    val activeReady = activeMoment?.let { moment ->
        activeVisualReady && (
            !moment.requiresSeparateMusicPlayback || musicStartedMomentIds[moment.id] == true
        )
    } ?: false
    LaunchedEffect(hasScrolledToInitial, activeMomentId, playbackPaused, activeDuration, activeReady, automaticAdvance) {
        if (!hasScrolledToInitial || activeMoment == null) return@LaunchedEffect
        if (activeMoment.isVideoMoment() || !activeReady || playbackPaused || !automaticAdvance) return@LaunchedEffect

        val initialProgress = currentProgress.coerceIn(0f, 1f)
        val startedAtMs = android.os.SystemClock.elapsedRealtime()
        val remainingMs = imageMomentRemainingDurationMs(activeDuration, initialProgress)
        val deadlineMs = startedAtMs + remainingMs
        while (true) {
            val nowMs = android.os.SystemClock.elapsedRealtime()
            currentProgress = imageMomentProgressAt(
                initialProgress = initialProgress,
                startedAtMs = startedAtMs,
                nowMs = nowMs,
                durationMs = activeDuration,
            )
            if (nowMs >= deadlineMs) break
            delay((deadlineMs - nowMs).coerceIn(1L, 16L))
        }
        currentProgress = 1f
        if (!playbackPaused && visibleMoments.getOrNull(pagerState.currentPage)?.id == activeMomentId) advance()
    }

    MomentViewerCanvas(
        automaticAdvance = automaticAdvance,
        onAutomaticAdvanceChanged = { automaticAdvance = it },
        onToolsChanged = { toolsOpen = it },
        moments = visibleMoments,
        pagerState = pagerState,
        currentUserId = viewModel.currentUserId,
        viewersCount = viewers.size,
        currentProgress = currentProgress,
        videoProgressByMomentId = videoProgressByMomentId,
        paused = playbackPaused,
        activePrepared = activePrepared,
        reliableBottomPadding = reliableBottomPadding,
        onPauseChanged = { paused = it },
        onClose = navController::popBackStack,
        onPrevious = {
            scope.launch { if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        },
        onNext = {
            scope.launch {
                if (pagerState.currentPage < visibleMoments.lastIndex) pagerState.animateScrollToPage(pagerState.currentPage + 1)
                else navController.popBackStack()
            }
        },
        onOpenViewers = { moment ->
            paused = true
            viewModel.loadViewers(moment.id)
            showViewersSheet = true
        },
        onOpenHighlight = {
            paused = true
            highlightTitle = ""
            showHighlightDialog = true
        },
        onReply = { moment, text, callback -> viewModel.sendReply(moment.id, text, moment.userId, callback) },
        onToggleLike = viewModel::toggleLike,
        onVideoDurationKnown = { id, duration -> videoDurationsMs[id] = clampMomentVideoDuration(duration) },
        onVideoReady = { videoReadyMomentIds[it] = true },
        onImageReady = { imageReadyMomentIds[it] = true },
        onMusicPrepared = { musicPreparedMomentIds[it] = true },
        onMusicStarted = { musicStartedMomentIds[it] = true },
        onVideoProgress = { id, progress -> videoProgressByMomentId[id] = progress.coerceIn(0f, 1f) },
        onVideoEnded = { id ->
            if (id == visibleMoments.getOrNull(pagerState.currentPage)?.id && !playbackPaused) {
                videoProgressByMomentId[id] = 1f
                if (automaticAdvance) advance()
            }
        }
    )

    val selectedMoment = visibleMoments.getOrNull(pagerState.currentPage)
    if (showHighlightDialog && selectedMoment != null) {
        MomentHighlightDialog(
            title = highlightTitle,
            saving = highlightSaving,
            onTitleChange = { highlightTitle = it.take(60) },
            onDismiss = {
                if (!highlightSaving) {
                    showHighlightDialog = false
                    paused = false
                }
            },
            onSave = {
                highlightSaving = true
                viewModel.saveMomentToHighlight(selectedMoment, highlightTitle) { result ->
                    highlightSaving = false
                    if (result.isSuccess) {
                        showHighlightDialog = false
                        paused = false
                    }
                }
            }
        )
    }

    if (showViewersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showViewersSheet = false; paused = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            ViewersList(
                viewers = viewers,
                onDeleteMoment = {
                    selectedMoment?.let { viewModel.deleteMoment(it.id, navController::popBackStack) }
                        ?: navController.popBackStack()
                },
                isOwner = true
            )
        }
    }
}

@Composable
private fun MomentUnavailableState(isRefreshing: Boolean, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (isRefreshing) CircularProgressIndicator(color = Color.White)
        else Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(Icons.Default.HistoryToggleOff, null, tint = Color.White, modifier = Modifier.size(52.dp))
            Text(
                "Este momento ya venció o fue eliminado.",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Button(onClick = onClose) { Text("Volver") }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MomentViewerCanvas(
    automaticAdvance: Boolean,
    onAutomaticAdvanceChanged: (Boolean) -> Unit,
    onToolsChanged: (Boolean) -> Unit,
    moments: List<Moment>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    currentUserId: String?,
    viewersCount: Int,
    currentProgress: Float,
    videoProgressByMomentId: Map<String, Float>,
    paused: Boolean,
    activePrepared: Boolean,
    reliableBottomPadding: androidx.compose.ui.unit.Dp,
    onPauseChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenViewers: (Moment) -> Unit,
    onOpenHighlight: () -> Unit,
    onReply: (Moment, String, (Result<Unit>) -> Unit) -> Unit,
    onToggleLike: (Moment, (Result<Unit>) -> Unit) -> Unit,
    onVideoDurationKnown: (String, Long) -> Unit,
    onVideoReady: (String) -> Unit,
    onImageReady: (String) -> Unit,
    onMusicPrepared: (String) -> Unit,
    onMusicStarted: (String) -> Unit,
    onVideoProgress: (String, Float) -> Unit,
    onVideoEnded: (String) -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            pageSpacing = 0.dp,
            contentPadding = PaddingValues(0.dp),
            key = { index -> moments.getOrNull(index)?.id ?: index }
        ) { pageIndex ->
            moments.getOrNull(pageIndex)?.let { moment ->
                // Stop an image soundtrack on the exact frame in which its
                // fixed six-second progress reaches completion. Navigation can
                // then animate without audio leaking into the next screen.
                val playbackActive = pageIndex == pagerState.currentPage &&
                    (moment.isVideoMoment() || currentProgress < 1f)
                MomentContent(
                    moment = moment,
                    isVisible = playbackActive,
                    paused = paused,
                    playbackClockRunning = playbackActive && activePrepared && !paused,
                    onVideoDurationKnown = onVideoDurationKnown,
                    onVideoReady = onVideoReady,
                    onImageReady = onImageReady,
                    onMusicPrepared = onMusicPrepared,
                    onMusicStarted = onMusicStarted,
                    onVideoProgress = onVideoProgress,
                    onVideoEnded = onVideoEnded
                )
            }
        }

        val currentMoment = moments.getOrNull(pagerState.currentPage)
        if (currentMoment != null) {
            MomentViewerGestureLayer(
                isOwner = currentMoment.userId == currentUserId,
                onPauseChanged = onPauseChanged,
                onPrevious = onPrevious,
                onNext = onNext,
                onOpenViewers = { onOpenViewers(currentMoment) },
                onClose = onClose
            )
            // Interactive attribution belongs above the navigation gesture layer.
            MomentViewerHeader(
                moments = moments,
                currentPage = pagerState.currentPage,
                currentProgress = currentProgress,
                videoProgressByMomentId = videoProgressByMomentId,
                currentMoment = currentMoment
            )
            MomentViewerBottomActions(
                modifier = Modifier.align(Alignment.BottomCenter),
                moment = currentMoment,
                isOwner = currentMoment.userId == currentUserId,
                viewersCount = viewersCount,
                reliableBottomPadding = reliableBottomPadding,
                onPauseChanged = onPauseChanged,
                onOpenViewers = { onOpenViewers(currentMoment) },
                onOpenHighlight = onOpenHighlight,
                onReply = { text, callback -> onReply(currentMoment, text, callback) },
                onToggleLike = { callback -> onToggleLike(currentMoment, callback) }
            )
        }

        currentMoment?.let { moment ->
            MomentViewingTools(
                moment = moment,
                automatic = automaticAdvance,
                onAutomaticChanged = onAutomaticAdvanceChanged,
                onMenuChanged = onToolsChanged,
                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 64.dp, end = 12.dp),
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp, end = 12.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Cerrar",
                tint = Color.White,
                modifier = Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(4.dp)
            )
        }
    }
}

@Composable
private fun MomentViewerHeader(
    moments: List<Moment>,
    currentPage: Int,
    currentProgress: Float,
    videoProgressByMomentId: Map<String, Float>,
    currentMoment: Moment
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp, start = 12.dp, end = 56.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            moments.forEachIndexed { index, moment ->
                val progress = when {
                    index < currentPage -> 1f
                    index == currentPage && moment.isVideoMoment() -> videoProgressByMomentId[moment.id] ?: 0f
                    index == currentPage -> currentProgress
                    else -> 0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        MomentAuthorHeader(
            moment = currentMoment,
            timestamp = momentTimestampLabel(currentMoment.createdAt),
            onOpenMusicAttribution = { target ->
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(target)))
                }
            }
        )
    }
}

@Composable
private fun MomentViewerGestureLayer(
    isOwner: Boolean,
    onPauseChanged: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenViewers: () -> Unit,
    onClose: () -> Unit
) {
    var dragTotal by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(isOwner) {
                detectVerticalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onVerticalDrag = { _, amount -> dragTotal += amount },
                    onDragEnd = {
                        if (dragTotal < -120f && isOwner) onOpenViewers()
                        else if (dragTotal > 160f) onClose()
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (it.x < size.width * 0.25f) onPrevious() else if (it.x > size.width * 0.75f) onNext() },
                    onLongPress = { onPauseChanged(true) },
                    onPress = { tryAwaitRelease(); onPauseChanged(false) }
                )
            }
    )
}

@Composable
private fun MomentViewerBottomActions(
    modifier: Modifier = Modifier,
    moment: Moment,
    isOwner: Boolean,
    viewersCount: Int,
    reliableBottomPadding: androidx.compose.ui.unit.Dp,
    onPauseChanged: (Boolean) -> Unit,
    onOpenViewers: () -> Unit,
    onOpenHighlight: () -> Unit,
    onReply: (String, (Result<Unit>) -> Unit) -> Unit,
    onToggleLike: ((Result<Unit>) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var replyText by remember(moment.id) { mutableStateOf("") }
    var replySending by remember(moment.id) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = reliableBottomPadding + 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!moment.caption.isNullOrBlank()) {
            Text(
                moment.caption,
                color = Color.White,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                    .background(Color.Black.copy(alpha = 0.38f), RoundedCornerShape(10.dp)).padding(9.dp),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        if (isOwner) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                MomentOwnerAction(Icons.Default.KeyboardArrowUp, "$viewersCount vistos", onOpenViewers)
                MomentOwnerAction(Icons.Default.StarBorder, "Destacar", onOpenHighlight)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (moment.allowReplies) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it.take(500) },
                        enabled = !replySending,
                        placeholder = { Text("Responder…", color = Color.White.copy(alpha = 0.65f)) },
                        modifier = Modifier.weight(1f).onFocusChanged { onPauseChanged(it.isFocused) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(22.dp),
                        singleLine = true,
                        trailingIcon = {
                            if (replyText.isNotBlank()) IconButton(
                                enabled = !replySending,
                                onClick = {
                                    replySending = true
                                    onReply(replyText) { result ->
                                        replySending = false
                                        result.onSuccess {
                                            replyText = ""
                                            focusManager.clearFocus()
                                            onPauseChanged(false)
                                            android.widget.Toast.makeText(context, "Respuesta enviada", android.widget.Toast.LENGTH_SHORT).show()
                                        }.onFailure {
                                            android.widget.Toast.makeText(context, it.message ?: "No se pudo responder", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            ) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White) }
                        }
                    )
                } else {
                    Text("Respuestas desactivadas", color = Color.White.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { onToggleLike {} }) {
                        Icon(
                            if (moment.isLikedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Me gusta",
                            tint = if (moment.isLikedByMe) Color(0xFFFF3B5C) else Color.White
                        )
                    }
                    if (moment.reactionCount > 0) Text(moment.reactionCount.toString(), color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun MomentOwnerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, null, tint = Color.White)
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MomentHighlightDialog(
    title: String,
    saving: Boolean,
    onTitleChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Destacar momento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("El destacado estará visible hasta que venza el momento.")
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("Nombre del destacado") },
                    placeholder = { Text("Viajes, amigos, música…") },
                    singleLine = true,
                    enabled = !saving
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = title.isNotBlank() && !saving,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancelar") } }
    )
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun MomentContent(
    moment: Moment,
    isVisible: Boolean,
    paused: Boolean,
    playbackClockRunning: Boolean = isVisible && !paused,
    onVideoDurationKnown: (String, Long) -> Unit,
    onVideoReady: (String) -> Unit,
    onImageReady: (String) -> Unit = {},
    onMusicPrepared: (String) -> Unit = {},
    onMusicStarted: (String) -> Unit = {},
    onVideoProgress: (String, Float) -> Unit,
    onVideoEnded: (String) -> Unit,
    allowImageZoom: Boolean = false,
) {
    val context = LocalContext.current
    var videoPlaying by remember(moment.id, moment.mediaUrl) { mutableStateOf(false) }
    // Force clipping to prevent next slide from overlapping
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        if (moment.isVideoMoment()) {
            var isVideoReady by remember(moment.id, moment.mediaUrl) { mutableStateOf(false) }
            val latestOnVideoDurationKnown = rememberUpdatedState(onVideoDurationKnown)
            val latestOnVideoReady = rememberUpdatedState(onVideoReady)
            val latestOnVideoProgress = rememberUpdatedState(onVideoProgress)
            val latestOnVideoEnded = rememberUpdatedState(onVideoEnded)

            LaunchedEffect(moment.id, moment.displayDurationMs) {
                moment.displayDurationMs?.takeIf { it > 0L }?.let {
                    onVideoDurationKnown(moment.id, it)
                }
            }

            val exoPlayer = remember(moment.id, moment.mediaUrl) {
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                            VideoCache.getCacheDataSourceFactory(context)
                        )
                    )
                     .build()
                     .apply {
                    setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        // When a licensed soundtrack is present, that player owns
                        // audio focus and this player remains audible as the
                        // controllable original-audio layer instead of fighting it.
                        !moment.requiresSeparateMusicPlayback,
                    )
                    val resolvedUrl = UbuntuMedia.resolveUrl(moment.mediaUrl) ?: moment.mediaUrl
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(resolvedUrl)
                            .setMimeType(MimeTypes.VIDEO_MP4)
                            .build()
                    )
                    repeatMode = Player.REPEAT_MODE_OFF
                    volume = if (moment.musicEmbedded) {
                        // The Transformer output already contains the exact original/music mix.
                        // Applying editor gains here would attenuate it a second time.
                        1f
                    } else {
                        (moment.originalAudioVolume * if (moment.duckOriginalAudio && moment.requiresSeparateMusicPlayback) 0.35f else 1f)
                            .coerceIn(0f, 1f)
                    }
                    prepare()
                    
                    addListener(object : Player.Listener {
                         override fun onPlaybackStateChanged(playbackState: Int) {
                             if (playbackState == Player.STATE_READY) {
                                  val knownDuration = duration
                                  if (knownDuration != C.TIME_UNSET && knownDuration > 0L) {
                                      latestOnVideoDurationKnown.value(moment.id, knownDuration)
                                  } else {
                                      moment.displayDurationMs?.takeIf { it > 0L }?.let {
                                          latestOnVideoDurationKnown.value(moment.id, it)
                                      }
                                  }
                                  latestOnVideoReady.value(moment.id)
                              }
                              if (playbackState == Player.STATE_ENDED) {
                                  val knownDuration = duration
                                  val hasKnownDuration = knownDuration != C.TIME_UNSET && knownDuration > 0L
                                  val endThreshold = (knownDuration - 750L).coerceAtLeast(0L)
                                  val reachedRealEnd = hasKnownDuration && currentPosition >= endThreshold
                                  if (reachedRealEnd) {
                                      latestOnVideoProgress.value(moment.id, 1f)
                                      latestOnVideoEnded.value(moment.id)
                                  } else {
                                      android.util.Log.w(
                                          "MomentDebug",
                                          "Ignoring premature STATE_ENDED for ${moment.id}: " +
                                              "position=$currentPosition duration=$knownDuration"
                                      )
                                  }
                              }
                          }
                          override fun onRenderedFirstFrame() {
                              isVideoReady = true
                              latestOnVideoReady.value(moment.id)
                          }
                          override fun onIsPlayingChanged(isPlaying: Boolean) {
                              videoPlaying = isPlaying
                          }
                    })
                }
            }

            // Sync play state with visibility
            LaunchedEffect(isVisible, paused, playbackClockRunning, moment.id) {
                val shouldPlay = isVisible && !paused && playbackClockRunning
                if (shouldPlay && exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                }
                exoPlayer.playWhenReady = shouldPlay
                if (!shouldPlay) exoPlayer.pause()
            }

            LaunchedEffect(isVisible, exoPlayer, moment.id) {
                 if (isVisible) {
                     exoPlayer.seekTo(0)
                     latestOnVideoProgress.value(moment.id, 0f)
                     while (true) {
                         val knownDuration = exoPlayer.duration
                         if (knownDuration != C.TIME_UNSET && knownDuration > 0L) {
                             latestOnVideoDurationKnown.value(moment.id, knownDuration)
                             latestOnVideoProgress.value(
                                 moment.id,
                                 exoPlayer.currentPosition.toFloat() / knownDuration.toFloat()
                             )
                         }
                         delay(100)
                     }
                }
            }

            DisposableEffect(exoPlayer) {
                onDispose {
                    exoPlayer.release()
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            // Use FIT to show the whole video without cropping, resolving the "too zoomed in" issue
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    },
                    update = { view -> if (view.player !== exoPlayer) view.player = exoPlayer },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier.fillMaxSize()
                )

                // Thumbnail Overlay to prevent black screen while loading
                if (!isVideoReady) {
                    AsyncImage(
                        model = moment.thumbnailUrl ?: moment.mediaUrl, 
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                ZoomableMomentImage(
                    mediaUrl = moment.mediaUrl,
                    thumbnailUrl = moment.thumbnailUrl,
                    momentId = moment.id,
                    zoomEnabled = allowImageZoom,
                    onImageSettled = { onImageReady(moment.id) },
                )
                moment.overlayText?.takeIf { it.isNotBlank() }?.let { overlay ->
                    val overlayX = (maxWidth * moment.overlayX.coerceIn(0f, 1f) - 76.dp)
                        .coerceIn(8.dp, (maxWidth - 152.dp).coerceAtLeast(8.dp))
                    val overlayY = (maxHeight * moment.overlayY.coerceIn(0f, 1f) - 26.dp)
                        .coerceIn(12.dp, (maxHeight - 96.dp).coerceAtLeast(12.dp))
                    Text(
                        text = overlay,
                        color = viewerOverlayColor(moment.overlayColor),
                        fontFamily = viewerOverlayFont(moment.overlayFont),
                        fontWeight = FontWeight.Bold,
                        fontSize = (24f * moment.overlayScale.coerceIn(0.7f, 2.1f)).sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(
                                x = overlayX,
                                y = overlayY,
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.34f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
        MomentBackgroundMusic(
            moment = moment,
            isVisible = isVisible,
            paused = paused,
            playbackClockRunning = momentSoundtrackClockRunning(playbackClockRunning, moment.isVideoMoment(), videoPlaying),
            onPlaybackPrepared = onMusicPrepared,
            onPlaybackStarted = onMusicStarted,
        )
    }
}

@Composable
internal fun ZoomableMomentImage(
    mediaUrl: String,
    thumbnailUrl: String?,
    momentId: String,
    zoomEnabled: Boolean,
    onImageSettled: () -> Unit,
) {
    var scale by remember(momentId) { mutableFloatStateOf(1f) }
    var offset by remember(momentId) { mutableStateOf(Offset.Zero) }
    var imageState by remember(momentId, mediaUrl) { mutableStateOf(MomentImageLoadState.LOADING) }
    var retry by remember(momentId, mediaUrl) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val gestureModifier = if (!zoomEnabled) {
        Modifier
    } else {
        Modifier
            .pointerInput(momentId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var keepGoing: Boolean
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val multiTouch = pressed.size >= 2
                        if (multiTouch || scale > 1f) {
                            val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            val maxX = size.width * (nextScale - 1f) / 2f
                            val maxY = size.height * (nextScale - 1f) / 2f
                            val pan = event.calculatePan()
                            scale = nextScale
                            offset = if (nextScale <= 1.01f) {
                                Offset.Zero
                            } else {
                                Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                            event.changes.forEach { it.consume() }
                        }
                        keepGoing = event.changes.any { it.pressed }
                    } while (keepGoing)
                }
            }
            .pointerInput(momentId) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2f
                        }
                    }
                )
            }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF15171B))) {
        val decodeSize = momentImageDecodeSize(constraints.maxWidth, constraints.maxHeight)
        val imageUrl = momentImagePreviewUrl(mediaUrl, decodeSize.first)
        val thumbnail = thumbnailUrl?.let { UbuntuMedia.resolveUrl(it) ?: it }?.takeIf { it != imageUrl }
        if (imageState != MomentImageLoadState.READY && thumbnail != null) {
            AsyncImage(model = ImageRequest.Builder(context).data(thumbnail)
                .size(decodeSize.first, decodeSize.second).build(), contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        key(momentId, imageUrl, retry) {
        AsyncImage(
        model = ImageRequest.Builder(context).data(imageUrl)
            .size(decodeSize.first, decodeSize.second).build(),
        contentDescription = "Imagen del momento; pellizca o toca dos veces para ampliar",
        onSuccess = { imageState = MomentImageLoadState.READY; onImageSettled() },
        // A broken object must not trap the viewer forever. The error state is
        // still shown for the complete six-second image window.
        onError = { imageState = MomentImageLoadState.FAILED; onImageSettled() },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            )
            .then(gestureModifier),
        contentScale = ContentScale.Fit,
    )
        }
        if (imageState == MomentImageLoadState.LOADING) {
            Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(12.dp))
                .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(8.dp))
                Text("Cargando imagen…", color = Color.White)
            }
        } else if (imageState == MomentImageLoadState.FAILED) {
            Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .75f), RoundedCornerShape(12.dp))
                .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No se pudo cargar la imagen", color = Color.White)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { imageState = MomentImageLoadState.LOADING; retry++ }) { Text("Reintentar") }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun MomentBackgroundMusic(
    moment: Moment,
    isVisible: Boolean,
    paused: Boolean,
    playbackClockRunning: Boolean,
    onPlaybackPrepared: (String) -> Unit,
    onPlaybackStarted: (String) -> Unit,
) {
    if (!moment.requiresSeparateMusicPlayback) return
    val source = moment.resolvedMusicAudioUrl ?: return
    val context = LocalContext.current
    val latestOnPlaybackPrepared = rememberUpdatedState(onPlaybackPrepared)
    val latestOnPlaybackStarted = rememberUpdatedState(onPlaybackStarted)
    val latestShouldPlay = rememberUpdatedState(isVisible && !paused && playbackClockRunning)
    val exoPlayer = remember(moment.id, source) {
        val retries = java.util.concurrent.atomic.AtomicInteger(0)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    MediaPlaybackDataSources.streamingFactory(context),
                ),
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            volume = moment.musicVolume.coerceIn(0f, 1f)
            setMediaItem(
                MediaItem.Builder()
                    .setMediaId(moment.musicId.orEmpty())
                    .setUri(UbuntuMedia.resolveUrl(source) ?: source)
                    .setCustomCacheKey(
                        UbuntuStorageUrl.stableCacheKey(UbuntuMedia.resolveUrl(source) ?: source)
                            ?.let { "moment-music:$it" },
                    )
                    .build(),
            )
            val player = this
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        retries.set(0)
                        player.volume = moment.musicVolume.coerceIn(0f, 1f)
                        player.playWhenReady = latestShouldPlay.value
                        latestOnPlaybackPrepared.value(moment.id)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) latestOnPlaybackStarted.value(moment.id)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val attempt = retries.incrementAndGet()
                    android.util.Log.w("MomentMusic", "No se pudo preparar ${moment.id}; intento $attempt", error)
                    if (attempt <= 2) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            runCatching {
                                player.prepare()
                                player.playWhenReady = latestShouldPlay.value
                            }
                        }, 450L * attempt)
                    } else {
                        // Do not leave the image frozen forever when the provider
                        // is genuinely unavailable; the UI can continue after the
                        // bounded retry window.
                        latestOnPlaybackPrepared.value(moment.id)
                        latestOnPlaybackStarted.value(moment.id)
                    }
                }
            })
            prepare()
        }
    }
    val startMs = moment.musicStartMs.coerceAtLeast(0L)
    val segmentDurationMs = (moment.resolvedMusicEndMs - startMs).coerceIn(1_000L, 60_000L)
    var hasStarted by remember(moment.id, source) { mutableStateOf(false) }
    LaunchedEffect(moment.musicVolume, exoPlayer) {
        exoPlayer.volume = moment.musicVolume.coerceIn(0f, 1f)
    }
    LaunchedEffect(isVisible, paused, playbackClockRunning, startMs, segmentDurationMs, exoPlayer) {
        val shouldPlay = isVisible && !paused && playbackClockRunning
        if (shouldPlay) {
            if (!hasStarted || exoPlayer.playbackState == Player.STATE_ENDED ||
                exoPlayer.currentPosition !in startMs until (startMs + segmentDurationMs)) {
                exoPlayer.seekTo(startMs)
                hasStarted = true
            }
            // The visual six-second clock and audio are enabled by the same
            // state transition. Preparation alone never starts the soundtrack.
            exoPlayer.playWhenReady = true
            while (true) {
                if (exoPlayer.currentPosition >= startMs + segmentDurationMs || exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(startMs)
                    exoPlayer.playWhenReady = true
                }
                val relativeMs = (exoPlayer.currentPosition - startMs).coerceIn(0L, segmentDurationMs)
                val fadeInGain = if (moment.musicFadeInMs > 0L) {
                    (relativeMs.toFloat() / moment.musicFadeInMs.toFloat()).coerceIn(0f, 1f)
                } else 1f
                val remainingMs = (segmentDurationMs - relativeMs).coerceAtLeast(0L)
                val fadeOutGain = if (moment.musicFadeOutMs > 0L) {
                    (remainingMs.toFloat() / moment.musicFadeOutMs.toFloat()).coerceIn(0f, 1f)
                } else 1f
                exoPlayer.volume = (moment.musicVolume * minOf(fadeInGain, fadeOutGain)).coerceIn(0f, 1f)
                delay(50)
            }
        } else {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
            if (!isVisible) hasStarted = false
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
}

private fun viewerOverlayColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color.White)

private fun viewerOverlayFont(font: String): FontFamily = when (font) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

@Composable
fun ViewersList(
    viewers: List<MomentView>,
    onDeleteMoment: () -> Unit,
    isOwner: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Personas que vieron esto",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (isOwner) {
                IconButton(onClick = onDeleteMoment) {
                    Icon(Icons.Default.Delete, null, tint = Color.Red)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (viewers.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                Text("Aún no hay visualizaciones", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            viewers.forEach { viewer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProfileImage(
                        imageUrl = viewer.userAvatarUrl,
                        size = 48.dp,
                        showBorder = false
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = viewer.userDisplayName ?: viewer.username ?: "Usuario",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

internal fun Moment.isVideoMoment(): Boolean {
    val type = mediaType.trim().lowercase()
    val url = mediaUrl.substringBefore('?').lowercase()
    return type == "video" ||
        type.startsWith("video/") ||
        url.endsWith(".mp4") ||
        url.endsWith(".mov") ||
        url.endsWith(".m4v") ||
        url.endsWith(".webm")
}

private fun Moment.playbackDurationMs(playerDurationMs: Long?): Long {
    return if (isVideoMoment()) {
        (playerDurationMs ?: displayDurationMs ?: DEFAULT_VIDEO_MOMENT_DURATION_MS)
            .coerceIn(1_000L, MAX_VIDEO_MOMENT_DURATION_MS)
    } else {
        val requestedDuration = displayDurationMs?.takeIf { it > 0L }
        val selectedMusicDuration = resolvedMusicAudioUrl?.let {
            (resolvedMusicEndMs - musicStartMs.coerceAtLeast(0L))
                .coerceIn(MIN_IMAGE_MOMENT_PLAYBACK_MS, MAX_IMAGE_MOMENT_PLAYBACK_MS)
        }
        resolvedImageMomentPlaybackDurationMs(requestedDuration, selectedMusicDuration)
    }
}

private fun clampMomentVideoDuration(durationMs: Long): Long {
    return when {
        durationMs <= 0L || durationMs == C.TIME_UNSET -> DEFAULT_VIDEO_MOMENT_DURATION_MS
        else -> durationMs.coerceIn(1_000L, MAX_VIDEO_MOMENT_DURATION_MS)
    }
}
