package conectate.com.presentation.social

import androidx.compose.runtime.Composable

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import conectate.com.data.model.Moment
import conectate.com.data.model.MomentView
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.rememberReliableBottomSafePadding
import conectate.com.util.UbuntuMedia
import conectate.com.util.VideoCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val IMAGE_MOMENT_DURATION_MS = 6_000
private const val MAX_VIDEO_MOMENT_DURATION_MS = 25_000L
private const val DEFAULT_VIDEO_MOMENT_DURATION_MS = 25_000L

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
                            progress = progressValue,
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { 
                                paused = true
                                showViewersSheet = true 
                            }
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.White)
                        Text(
                            text = "${viewers.size} vistos",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
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
                                    Icon(Icons.Default.Send, null, tint = Color.White)
                                }
                            }
                        }
                    )
                }
            }
        }
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

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun MomentContent(
    moment: Moment,
    isVisible: Boolean,
    paused: Boolean,
    onVideoDurationKnown: (String, Long) -> Unit,
    onVideoReady: (String) -> Unit,
    onVideoProgress: (String, Float) -> Unit,
    onVideoEnded: (String) -> Unit
) {
    val context = LocalContext.current
    // Only log if visibile to reduce noise
    if (isVisible) {
        android.util.Log.d("MomentDebug", "Rendering visible content: ${moment.id}")
    }

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
                    val resolvedUrl = UbuntuMedia.resolveUrl(moment.mediaUrl) ?: moment.mediaUrl
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(resolvedUrl)
                            .setMimeType(MimeTypes.VIDEO_MP4)
                            .build()
                    )
                    repeatMode = Player.REPEAT_MODE_OFF
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
                                  val reachedRealEnd = knownDuration != C.TIME_UNSET &&
                                      knownDuration > 0L &&
                                      currentPosition >= (knownDuration - 750L).coerceAtLeast(0L)
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
                    })
                }
            }

            // Sync play state with visibility
            LaunchedEffect(isVisible, paused, moment.id) {
                if (isVisible && exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                }
                exoPlayer.playWhenReady = isVisible && !paused
                if (!isVisible || paused) exoPlayer.pause()
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
            AsyncImage(
                model = moment.mediaUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
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

private fun Moment.isVideoMoment(): Boolean {
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
        IMAGE_MOMENT_DURATION_MS.toLong()
    }
}

private fun clampMomentVideoDuration(durationMs: Long): Long {
    return when {
        durationMs <= 0L || durationMs == C.TIME_UNSET -> DEFAULT_VIDEO_MOMENT_DURATION_MS
        else -> durationMs.coerceIn(1_000L, MAX_VIDEO_MOMENT_DURATION_MS)
    }
}

private fun formatMomentTimestamp(createdAt: String?): String {
    val instant = runCatching {
        createdAt?.let { Instant.parse(it) }
    }.getOrNull() ?: return "Ahora"

    val now = Instant.now()
    val elapsedSeconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
    val elapsed = when {
        elapsedSeconds < 60 -> "hace ${elapsedSeconds}s"
        elapsedSeconds < 3_600 -> "hace ${elapsedSeconds / 60} min"
        elapsedSeconds < 86_400 -> "hace ${elapsedSeconds / 3_600} h"
        else -> "hace ${elapsedSeconds / 86_400} d"
    }
    val localTime = instant
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
    return "$localTime · $elapsed"
}
