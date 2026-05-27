package conectate.com.presentation.social

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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import conectate.com.data.model.Moment
import conectate.com.data.model.MomentView
import conectate.com.presentation.components.CircularProfileImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MomentViewerScreen(
    navController: NavController,
    momentId: String,
    viewModel: MomentViewModel = hiltViewModel()
) {
    android.util.Log.d("MomentDebug", "Entering MomentViewerScreen for momentId: $momentId")

    val moments by viewModel.moments.collectAsState()
    val viewers by viewModel.viewers.collectAsState()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { moments.size })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    if (moments.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var showViewersSheet by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    // Track view for the current moment
    LaunchedEffect(pagerState.currentPage, moments.size) {
        android.util.Log.d("MomentDebug", "Page Changed: ${pagerState.currentPage}, Moments: ${moments.size}")

        if (moments.isNotEmpty() && pagerState.currentPage in moments.indices) {
            val currentMoment = moments[pagerState.currentPage]
            viewModel.recordView(currentMoment.id)
            if (currentMoment.userId != null && currentMoment.userId == viewModel.currentUserId) {
                viewModel.loadViewers(currentMoment.id)
            }
        }
    }

    // Ensure we start at the right index once moments load
    var hasScrolledToInitial by remember { mutableStateOf(false) }
    LaunchedEffect(moments.size, momentId) {
        android.util.Log.d("MomentDebug", "Initial Scroll Check: moments=${moments.size}, hasScrolled=$hasScrolledToInitial")
        if (moments.isNotEmpty() && !hasScrolledToInitial) {

            val startIndex = moments.indexOfFirst { it.id == momentId }
            val safeIndex = if (startIndex != -1) startIndex else 0
            if (safeIndex != pagerState.currentPage) {
                pagerState.scrollToPage(safeIndex)
            }
            hasScrolledToInitial = true
        }
    }

    // Auto-advance logic using Animatable for smooth progress
    val currentProgress = remember(pagerState.currentPage) { Animatable(0f) }
    
    // Reset progress when page changes
    LaunchedEffect(pagerState.currentPage, paused) {
        if (!paused) {
            // Animate to 1f over 15 seconds (moment duration)
            currentProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 15000, easing = LinearEasing)
            )
            // If animation finishes and we haven't paused/changed page, move next
            if (pagerState.currentPage < moments.size - 1) {
                // Fix: Launch scroll in parent scope so it doesn't get cancelled when currentPage changes
                coroutineScope.launch {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                }
            } else {
                navController.popBackStack()
            }
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
            key = { index -> moments.getOrNull(index)?.id ?: index }
        ) { pageIndex ->
            moments.getOrNull(pageIndex)?.let { moment ->
                // STRICT VISIBILITY CONTROL
                val isVisible = (pageIndex == pagerState.currentPage)
                MomentContent(
                    moment = moment,
                    isVisible = isVisible
                )
            }
        }

        // Overlay UI
        val currentMoment = moments.getOrNull(pagerState.currentPage)
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
                    repeat(moments.size) { index ->
                        val progressValue = when {
                            index < pagerState.currentPage -> 1f
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
                            text = "Hace poco", // In a real app, format moment.createdAt
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
                        onVerticalDrag = { _, _ ->
                            // Optional: add visual feedback
                        },
                        onDragEnd = {
                            // Threshold for swipe
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val width = size.width
                            coroutineScope.launch {
                                if (offset.x < width / 3) {
                                    if (pagerState.currentPage > 0) {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                } else {
                                    if (pagerState.currentPage < moments.size - 1) {
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
                    .padding(bottom = 24.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars) // Respect nav bar
                    .navigationBarsPadding() // thorough padding
                    .imePadding() // CRITICAL FIX: Push up when keyboard opens
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
                    viewModel.deleteMoment(moments[pagerState.currentPage].id) {
                        navController.popBackStack()
                    }
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
    isVisible: Boolean
) {
    val context = LocalContext.current
    // Only log if visibile to reduce noise
    if (isVisible) {
        android.util.Log.d("MomentDebug", "Rendering visible content: ${moment.id}")
    }

    // Force clipping to prevent next slide from overlapping
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        if (moment.mediaType == "video") {
            var isVideoReady by remember { mutableStateOf(false) }

            val exoPlayer = remember(moment.mediaUrl) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(moment.mediaUrl))
                    repeatMode = Player.REPEAT_MODE_ONE
                    prepare()
                    
                    // Limit playback to 15 seconds loop
                    addListener(object : Player.Listener {
                         override fun onPlaybackStateChanged(playbackState: Int) {
                             // Check explicitly during playback
                         }
                         override fun onRenderedFirstFrame() {
                             isVideoReady = true
                         }
                    })
                }
            }

            // Monitor duration loop
            LaunchedEffect(isVisible, exoPlayer) {
                if (isVisible) {
                    while (true) {
                        if (exoPlayer.currentPosition >= 15000) {
                            exoPlayer.seekTo(0)
                        }
                        delay(100)
                    }
                }
            }

            // Sync play state with visibility
            LaunchedEffect(isVisible) {
                exoPlayer.playWhenReady = isVisible
                if (!isVisible) exoPlayer.pause()
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
                        contentScale = ContentScale.Crop
                    )
                }
            }
        } else {
            AsyncImage(
                model = moment.mediaUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
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
