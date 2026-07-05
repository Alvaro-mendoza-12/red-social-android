package conectate.com.presentation.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import conectate.com.presentation.components.AdminBadge
import conectate.com.presentation.components.isUserAdmin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import conectate.com.data.model.Video
import conectate.com.data.repository.VideoRepository
import conectate.com.util.UbuntuMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import javax.inject.Inject
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.*
import conectate.com.presentation.navigation.Screen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.input.pointer.pointerInput
import conectate.com.presentation.videos.VideoReportDialog
import androidx.compose.material.icons.filled.PlayArrow as PlayArrowIcon
import conectate.com.util.VideoPerformanceProfiles

private enum class VideoSidePanel {
    VIDEO,
    COMMENTS,
    PROFILE
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun VideoFeedScreen(
    navController: NavController,
    viewModel: VideoFeedViewModel = hiltViewModel(),
    onNavigateToUpload: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onNavigateToChat: (String) -> Unit
) {
    val forYouVideos by viewModel.forYouVideos.collectAsState()
    val followingVideos by viewModel.followingVideos.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    // Comments
    val commentsSheetOpen by viewModel.commentsSheetOpen.collectAsState()
    val selectedVideoForComments by viewModel.selectedVideoForComments.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsState()
    val reportedVideoIds by viewModel.reportedVideoIds.collectAsState()
    val reportActionMessage by viewModel.reportActionMessage.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val followedUsers by viewModel.followedUsers.collectAsState()
    val isDraftPlaying by viewModel.isDraftPlaying.collectAsState()
    val draftProgress by viewModel.draftProgress.collectAsState()
    val draftDuration by viewModel.draftDuration.collectAsState()
    val currentPlayingCommentId by viewModel.currentPlayingCommentId.collectAsState()
    val audioProgress by viewModel.audioProgress.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    
    // Search State
    val isSearchMode by viewModel.isSearchMode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    
    val currentUserId = viewModel.currentUserId

    val navScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    
    var showInternalShareDialog by remember { mutableStateOf(false) }
    var selectedVideoForShare by remember { mutableStateOf<conectate.com.data.model.Video?>(null) }
    var selectedVideoForReport by remember { mutableStateOf<conectate.com.data.model.Video?>(null) }
    var pendingNavigationRoute by remember { mutableStateOf<String?>(null) }
    var isSystemBusy by remember { mutableStateOf(false) } // Safety: Disable heavy bits during Nav
    val sidePanels = remember { listOf(VideoSidePanel.VIDEO, VideoSidePanel.COMMENTS, VideoSidePanel.PROFILE) }
    var sidePanelIndex by rememberSaveable { mutableStateOf(0) }
    val sidePanelState = sidePanels.getOrElse(sidePanelIndex) { VideoSidePanel.VIDEO }
    val performanceProfile = remember(context) { VideoPerformanceProfiles.detect(context) }

    val currentVideos = if (isSearchMode) searchResults 
                         else (if (selectedTab == 0) forYouVideos else followingVideos)
    val pagerState = rememberPagerState(pageCount = { currentVideos.size })

    fun setSidePanelIndex(index: Int) {
        sidePanelIndex = index.coerceIn(0, sidePanels.lastIndex)
    }

    fun switchToVideoPanel() {
        setSidePanelIndex(0)
        if (commentsSheetOpen) {
            viewModel.closeComments()
        }
    }

    fun switchToCommentsPanel(video: Video) {
        setSidePanelIndex(1)
        if (!commentsSheetOpen || selectedVideoForComments?.id != video.id) {
            viewModel.openComments(video)
        }
    }

    fun switchToProfilePanel(video: Video) {
        setSidePanelIndex(2)
        if (commentsSheetOpen) {
            viewModel.closeComments()
        }
        onNavigateToProfile(video.userId)
    }

    // Forward = swipe right-to-left (TikTok): video -> comments -> profile -> video (loop)
    // Backward = swipe left-to-right: profile -> comments -> video
    fun onHorizontalSwipe(video: Video, forward: Boolean) {
        val nextIndex = if (forward) {
            if (sidePanelIndex < sidePanels.lastIndex) sidePanelIndex + 1 else 0
        } else {
            if (sidePanelIndex > 0) sidePanelIndex - 1 else 0
        }
        if (nextIndex == sidePanelIndex) return
        when (sidePanels[nextIndex]) {
            VideoSidePanel.VIDEO -> switchToVideoPanel()
            VideoSidePanel.COMMENTS -> switchToCommentsPanel(video)
            VideoSidePanel.PROFILE -> switchToProfilePanel(video)
        }
    }

    LaunchedEffect(reportActionMessage) {
        val message = reportActionMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.consumeReportActionMessage()
    }

    // Auto-scroll to top on new search results
    LaunchedEffect(searchResults) {
        if (isSearchMode && searchResults.isNotEmpty()) {
            pagerState.scrollToPage(0)
        }
    }
    
    // ════════════════════════════════════════════════════════
    // 1️⃣ SINGLE EXO PLAYER INSTANCE
    // ════════════════════════════════════════════════════════
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestCommentsSheetOpen by rememberUpdatedState(commentsSheetOpen)
    val latestShareDialogOpen by rememberUpdatedState(showInternalShareDialog)
    
    // We remember the player across recompositions. 
    val exoPlayer = remember {
        // Optimized LoadControl for INSTANT PLAYBACK (TikTok-style)
        // Aggressive buffer reduction for immediate start
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                performanceProfile.minBufferMs,
                performanceProfile.maxBufferMs,
                performanceProfile.playBufferMs,
                performanceProfile.rebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
            
        // Use Cached DataSource
        val cacheDataSourceFactory = conectate.com.util.VideoCache.getCacheDataSourceFactory(context)

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheDataSourceFactory)
            )
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                // Handle Audio Focus automatically
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
            }
    }
    
    // Keep the current video looping until the user swipes.
    DisposableEffect(exoPlayer, pagerState.currentPage) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                    exoPlayer.play()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // ════════════════════════════════════════════════════════
    // 2️⃣ LIFECYCLE MANAGEMENT (CRITICAL BUG FIX)
    // ════════════════════════════════════════════════════════
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || 
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // Refresh follow status when returning to screen
                android.util.Log.d("VideoFeedScreen", "Screen resumed, refreshing follow status")
                viewModel.refreshFollowStatus()
                
                // Only auto-resume if NOT in comments mode
                if (!latestCommentsSheetOpen && !latestShareDialogOpen) {
                    exoPlayer.play()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release() // STRICT RELEASE
        }
    }
    
    // 🚀 RESTORED & STABLE: Synchronize Playlist when videos change
    val playlistSignature = remember(currentVideos) {
        currentVideos.map { video -> video.id to video.videoUrl }
    }

    LaunchedEffect(playlistSignature) {
        if (currentVideos.isNotEmpty()) {
            val resolvedVideoUrls = currentVideos.map { UbuntuMedia.resolveUrl(it.videoUrl) ?: it.videoUrl }
            val mediaItems = resolvedVideoUrls.map { UbuntuMedia.mediaItem(it) }

            val existingUrls = (0 until exoPlayer.mediaItemCount).map { index ->
                exoPlayer.getMediaItemAt(index).localConfiguration?.uri?.toString().orEmpty()
            }
            val shouldUpdate = existingUrls != resolvedVideoUrls

            if (shouldUpdate) {
                android.util.Log.d("VideoFeedScreen", "Updating media items (stable)...")
                val targetPage = pagerState.settledPage.coerceIn(0, mediaItems.lastIndex)
                exoPlayer.setMediaItems(mediaItems, targetPage, 0L)
                exoPlayer.prepare()
            }
        } else {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    // Count views and paginate only after a swipe has settled on one video.
    LaunchedEffect(pagerState.settledPage, playlistSignature) {
        if (sidePanelState != VideoSidePanel.VIDEO) {
            switchToVideoPanel()
        }
        if (currentVideos.isNotEmpty() && pagerState.settledPage < currentVideos.size) {
            val video = currentVideos[pagerState.settledPage]
            viewModel.incrementVideoViews(video.id)
            if (pagerState.settledPage >= currentVideos.size - 3) {
                viewModel.loadMoreVideos()
            }
        }
    }

    // Exactly one settled page owns playback. Pause while swiping or while an
    // overlay is open, then resume automatically when it closes.
    LaunchedEffect(
        pagerState.settledPage,
        pagerState.isScrollInProgress,
        commentsSheetOpen,
        showInternalShareDialog,
        sidePanelState,
        playlistSignature
    ) {
        val page = pagerState.settledPage
        val canPlay = currentVideos.isNotEmpty() &&
            page in currentVideos.indices &&
            !pagerState.isScrollInProgress &&
            !commentsSheetOpen &&
            !showInternalShareDialog &&
            sidePanelState == VideoSidePanel.VIDEO

        if (!canPlay) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.currentMediaItemIndex != page) {
                exoPlayer.seekTo(page, 0L)
            }
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    // 🚀 NEW: State-Driven Navigation Logic (The "Freeze" Fix)
    // This effect handles the navigation ONLY after the dialog is dismissed and the UI has settled.
    LaunchedEffect(showInternalShareDialog, pendingNavigationRoute) {
        android.util.Log.d("VideoFeedScreen", "Nav Effect: showDialog=$showInternalShareDialog, pendingRoute=${pendingNavigationRoute != null}")
        
        if (!showInternalShareDialog && pendingNavigationRoute != null) {
            val route = pendingNavigationRoute!!
            android.util.Log.d("VideoFeedScreen", "🚀 STARTING NAVIGATION SEQUENCE to: $route")
            
            // 1. Mark system as busy (hides heavy UI bits)
            isSystemBusy = true
            
            // 2. Explicitly pause player
            try {
                exoPlayer.pause()
                android.util.Log.d("VideoFeedScreen", "Player pause() called")
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedScreen", "Player pause failed", e)
            }
            
            // 3. Buffer delay for UI cleanup
            kotlinx.coroutines.delay(250)
            android.util.Log.d("VideoFeedScreen", "Transition delay completed")
            
            // 4. Perform navigation
            try {
                pendingNavigationRoute = null
                onNavigateToChat(route)
                android.util.Log.d("VideoFeedScreen", "✅ onNavigateToChat callback called successfully")
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedScreen", "Navigation FAILED", e)
                isSystemBusy = false // Reset on failure
            }
            
            // Reset busy state after a while (when we might come back)
            navScope.launch {
                kotlinx.coroutines.delay(1000)
                isSystemBusy = false
            }
        }
    }

    // Comments Sheet
    val videoForComments = selectedVideoForComments
    if (commentsSheetOpen && videoForComments != null) {
        val videoIdForComments = videoForComments.id  // ✅ FIXED: Extract ID first
        conectate.com.presentation.videos.CommentSheet(
            comments = comments,
            onAddComment = { content ->
                // ✅ FIXED: No more !!
                viewModel.addComment(videoIdForComments, content)
            },
            onDismiss = { 
                viewModel.closeComments()
                setSidePanelIndex(0)
                // Do not auto-play here.
            },
            mentionSuggestions = mentionSuggestions,
            onCommentDraftChanged = { viewModel.onCommentDraftChanged(it) },
            onClearMentionSuggestions = { viewModel.clearMentionSuggestions() },
            voiceState = voiceState,
            recordingDuration = recordingDuration,
            onStartRecording = { viewModel.startRecording() },
            onPauseRecording = { viewModel.pauseRecording() },
            onResumeRecording = { viewModel.resumeRecording() },
            onStopRecording = { shouldSend -> viewModel.stopRecording(videoIdForComments, shouldSend) },
            onCancelRecording = { viewModel.cancelRecording() },
            isDraftPlaying = isDraftPlaying,
            draftProgress = draftProgress,
            draftDuration = draftDuration,
            onPlayDraft = { viewModel.playDraft() },
            onPauseDraft = { viewModel.pauseDraft() },
            onSeekDraft = { viewModel.seekDraft(it) },
            onPlayAudio = { viewModel.playCommentAudio(it) },
            currentPlayingCommentId = currentPlayingCommentId,
            audioProgress = audioProgress,
            currentUserId = currentUserId,
            onDeleteComment = { comment -> viewModel.deleteComment(videoIdForComments, comment) },
            onProfileClick = { userId ->
                setSidePanelIndex(2)
                onNavigateToProfile(userId)
            },
            onSwipeForward = { onHorizontalSwipe(videoForComments, forward = true) },
            onSwipeBackward = { onHorizontalSwipe(videoForComments, forward = false) },
            videoCreatorId = videoForComments.userId,
            replyingTo = viewModel.replyingTo.collectAsState().value,
            onReplyClick = { comment -> viewModel.onReplyClick(comment) },
            cancelReply = { viewModel.cancelReply() }
        )
    }

    if (showInternalShareDialog && selectedVideoForShare != null) {
        conectate.com.presentation.components.InternalShareDialog(
            userRepository = viewModel.userRepository,
            onDismiss = { 
                showInternalShareDialog = false
                selectedVideoForShare = null
            },
            onShareWithUser = { user ->
                android.util.Log.d("VideoFeedScreen", "onShareWithUser: Start. User=${user.id}")
                
                // 1. Prepare the route
                val video = selectedVideoForShare
                val videoLink = "https://conectate.com/video/${video?.id}"
                val route = Screen.Chat.createRoute(
                    user.id,
                    initialMessage = "¡Mira este video!\n$videoLink\n\n${video?.title ?: ""}"
                )
                
                android.util.Log.d("VideoFeedScreen", "Route prepared: $route")

                // 2. Close dialog and hide keyboard
                focusManager.clearFocus()
                android.util.Log.d("VideoFeedScreen", "Focus cleared")
                
                showInternalShareDialog = false
                android.util.Log.d("VideoFeedScreen", "showInternalShareDialog set to false")
                
                // 3. Set pending route
                pendingNavigationRoute = route
                
                // Reset selected video
                selectedVideoForShare = null
            }
        )
    }

    if (selectedVideoForReport != null) {
        val targetVideo = selectedVideoForReport!!
        VideoReportDialog(
            alreadyReported = reportedVideoIds.contains(targetVideo.id),
            onDismiss = { selectedVideoForReport = null },
            onSubmit = { reason, details ->
                viewModel.submitVideoReport(targetVideo.id, reason, details)
                selectedVideoForReport = null
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            
            if (isLoading && currentVideos.isEmpty()) {
                 Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else if (currentVideos.isEmpty()) {
                 // Empty State
                 Box(
                     modifier = Modifier.fillMaxSize(),
                     contentAlignment = Alignment.Center
                 ) {
                     Column(horizontalAlignment = Alignment.CenterHorizontally) {
                          Text(
                              text = if (isSearchMode) "No se encontraron videos para \"$searchQuery\""
                                     else if (selectedTab == 0) "Sé el primero en subir un video" 
                                     else "Sigue personas para ver su contenido",
                              color = Color.White,
                              style = MaterialTheme.typography.titleMedium,
                              fontWeight = FontWeight.Bold,
                              textAlign = TextAlign.Center,
                              modifier = Modifier.padding(horizontal = 32.dp)
                          )
                         if (selectedTab == 1) {
                             Spacer(modifier = Modifier.height(16.dp))
                             Button(
                                 onClick = { onNavigateToSearch() },
                                 colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                             ) {
                                 Text("Buscar personas")
                             }
                         }
                     }
                 }
            } else {
                // 3️⃣ VERTICAL PAGER
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondBoundsPageCount = performanceProfile.feedBeyondBoundsPageCount,
                    key = { page -> 
                        if (page < currentVideos.size) currentVideos[page].id else page
                    }
                ) { page ->
                    if (page < currentVideos.size) {
                        VideoItem(
                            video = currentVideos[page],
                            exoPlayer = exoPlayer, // Pass the SINGLE player
                            isSelected = pagerState.settledPage == page && !isSystemBusy,
                            onLikeClick = { viewModel.toggleLike(currentVideos[page]) },
                            onCommentClick = { 
                                switchToCommentsPanel(currentVideos[page])
                            },
                            onProfileClick = { userId ->
                                setSidePanelIndex(2)
                                onNavigateToProfile(userId)
                            },
                            onSwipeRight = {
                                onHorizontalSwipe(currentVideos[page], forward = false)
                            },
                            onSwipeLeft = {
                                onHorizontalSwipe(currentVideos[page], forward = true)
                            },
                            currentUserId = currentUserId,
                            followedUsers = followedUsers,
                            getStatsFlow = { viewModel.getVideoStats(it) },
                            onShareInternalClick = { video ->
                                selectedVideoForShare = video
                                showInternalShareDialog = true
                            },
                            onToggleProfileShare = { viewModel.toggleShare(currentVideos[page]) },
                            isAlreadyReported = reportedVideoIds.contains(currentVideos[page].id),
                            onReportClick = { video ->
                                viewModel.ensureVideoReportStatus(video.id)
                                selectedVideoForReport = video
                            }
                        )
                    }
                }
            }
            
            // Top Overlay (Search / Amigos / Recomendado)
            if (!isSearchMode) {
                // NORMAL MODE: Tabs and Search Icon
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .align(Alignment.TopCenter)
                ) {
                    // Search Switcher
                    IconButton(
                        onClick = { viewModel.toggleSearchMode(true) },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Buscar",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Amigos",
                            color = if (selectedTab == 1) Color.White else Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .clickable { viewModel.onTabSelected(1) }
                        )
                        Text(
                            text = "|",
                            color = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        Text(
                            text = "Recomendado",
                            color = if (selectedTab == 0) Color.White else Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .clickable { viewModel.onTabSelected(0) }
                        )
                    }
                }
            } else {
                // SEARCH MODE: Search Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 8.dp, end = 8.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.toggleSearchMode(false) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.White
                        )
                    }
                    
                    TextField(
                        value = searchQuery,
                        onValueChange = { viewModel.onSearchQueryChanged(it) },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp)),
                        placeholder = { Text("¿Qué quieres ver?", color = Color.White.copy(alpha = 0.5f)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.1f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.1f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        trailingIcon = {
                            if (isSearching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    )
                }
            }
            
            // Upload Button
            FloatingActionButton(
                onClick = { 
                    exoPlayer.pause() // Pause before navigating away
                    onNavigateToUpload()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                 Icon(
                     imageVector = Icons.Default.Add,
                     contentDescription = "Subir Video",
                     modifier = Modifier.size(24.dp)
                 )
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun VideoItem(
    video: Video, 
    exoPlayer: ExoPlayer, 
    isSelected: Boolean, 
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    onProfileClick: (String) -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    currentUserId: String?,
    followedUsers: Set<String>,
    getStatsFlow: (String) -> kotlinx.coroutines.flow.Flow<conectate.com.data.local.entity.VideoStatsEntity?>,
    onShareInternalClick: (Video) -> Unit,
    onToggleProfileShare: () -> Unit,
    isAlreadyReported: Boolean,
    onReportClick: (Video) -> Unit
) {
    // Live Stats Observation (Optimized: only collect when selected)
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentStats by remember { mutableStateOf<conectate.com.data.local.entity.VideoStatsEntity?>(null) }
    
    LaunchedEffect(isSelected, video.id) {
        if (isSelected) {
            getStatsFlow(video.id).collect {
                currentStats = it
            }
        } else {
            currentStats = null // Clear when not focused to save memory
        }
    }
    
    
    // 🚀 HYBRID LIKE COUNTER (TikTok-style):
    // 1. Initial State: use video.likeCount (now fetched accurately from Repository)
    // 2. Real-time updates: currentStats provides the latest truth from the server
    // 3. User Interaction: ViewModel updates video.likeCount optimistically
    // We use the local object when user is interacting, otherwise trust the server.
    val likeCount = if (video.likedByMe) {
        // If likedByMe is true, ensure we show AT LEAST currentStats value or video value
        maxOf(video.likeCount, currentStats?.likeCount ?: 0)
    } else {
        // If not liked, we can just trust the server's count
        currentStats?.likeCount ?: video.likeCount
    }
    
    val commentCount = maxOf(video.commentCount, currentStats?.commentCount ?: 0)
    val viewCount = maxOf(video.views, currentStats?.viewCount ?: 0)
    val shareCount = maxOf(video.shareCount, currentStats?.shareCount ?: 0)

    // Only attach player if selected. Otherwise show thumbnail.
    // This effectively manages the player view binding.
    var accumulatedHorizontalDrag by remember { mutableStateOf(0f) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(video.id) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        accumulatedHorizontalDrag += dragAmount
                    },
                    onDragEnd = {
                        when {
                            accumulatedHorizontalDrag > 80f -> onSwipeRight()
                            accumulatedHorizontalDrag < -80f -> onSwipeLeft()
                        }
                        accumulatedHorizontalDrag = 0f
                    },
                    onDragCancel = {
                        accumulatedHorizontalDrag = 0f
                    }
                )
            }
            .clickable { 
                // TAP TO PAUSE/PLAY
                if (isSelected) {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                }
            }
    ) {
        // Removed duplicate AndroidView - keeping only the one below with proper thumbnail management
        
        // PAUSE ICON OVERLAY
        // We only observe isPlaying state if this item is selected
        var isPlaying by remember { mutableStateOf(true) }
        var playbackProgress by remember(video.id) { mutableFloatStateOf(0f) }
        var playbackDurationMs by remember(video.id) { mutableLongStateOf(0L) }
        var isUserSeeking by remember(video.id) { mutableStateOf(false) }
        
        // 🚀 SMART TRANSITION STATE
        // Keep thumbnail visible until the FIRST FRAME is actually rendered by the player.
        // This eliminates the black flash completely.
        var isFirstFrameRendered by remember { mutableStateOf(false) }
        
        // Reset state when video changes
        LaunchedEffect(video.id) {
            isFirstFrameRendered = false
        }
        
        LaunchedEffect(isSelected, exoPlayer) {
            if (isSelected) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlayingParam: Boolean) {
                        isPlaying = isPlayingParam
                    }
                    
                    override fun onRenderedFirstFrame() {
                        super.onRenderedFirstFrame()
                        isFirstFrameRendered = true
                    }
                    
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            // Backup: sometimes onRenderedFirstFrame might be missed or delayed?
                            // Usually onRenderedFirstFrame is better for visual sync.
                        }
                    }
                }
                exoPlayer.addListener(listener)
                // Initial state
                isPlaying = exoPlayer.isPlaying
                
                try {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        val duration = exoPlayer.duration.takeIf { it > 0L } ?: 0L
                        playbackDurationMs = duration
                        if (!isUserSeeking && duration > 0L) {
                            playbackProgress = (exoPlayer.currentPosition.toFloat() / duration.toFloat())
                                .coerceIn(0f, 1f)
                        }
                        delay(200)
                    }
                } finally {
                    exoPlayer.removeListener(listener)
                }
            }
        }
        
        if (isSelected) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setKeepContentOnPlayerReset(true)
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        // Important: Keep screen on while playing
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } 
        
        // 🚀 THUMBNAIL OVERLAY with smooth fade transition
        // Visible if:
        // 1. Not selected (standard list behavior)
        // 2. Selected but frame NOT YET rendered (hides the loading black screen)
        AnimatedVisibility(
            visible = !isSelected || !isFirstFrameRendered,
            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(50))
        ) {
            val thumbnailRequest = remember(video.thumbnailUrl) {
                ImageRequest.Builder(context)
                    .data(UbuntuMedia.resolveUrl(video.thumbnailUrl) ?: video.thumbnailUrl)
                    .memoryCacheKey(conectate.com.data.localfallback.UbuntuStorageUrl.stableCacheKey(video.thumbnailUrl))
                    .diskCacheKey(conectate.com.data.localfallback.UbuntuStorageUrl.stableCacheKey(video.thumbnailUrl))
                    .size(720, 1280)
                    .crossfade(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
            AsyncImage(
                model = thumbnailRequest,
                contentDescription = null,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        if (isSelected && !isPlaying) {
             Icon(
                 imageVector = Icons.Filled.PlayArrow,
                 contentDescription = "Play",
                 tint = Color.White.copy(alpha = 0.7f),
                 modifier = Modifier.align(Alignment.Center).size(64.dp)
             )
        }

        if (isSelected && playbackDurationMs > 0L) {
            Slider(
                value = playbackProgress,
                onValueChange = { value ->
                    isUserSeeking = true
                    playbackProgress = value.coerceIn(0f, 1f)
                    exoPlayer.seekTo((playbackDurationMs * playbackProgress).toLong())
                },
                onValueChangeFinished = { isUserSeeking = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(22.dp)
                    .padding(horizontal = 2.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                )
            )
        }

        // Right Sidebar (Interactions)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 20.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Profile Pic with Follow Button
            val isOwnVideo = video.userId == currentUserId
            val isFollowing = followedUsers.contains(video.userId)
            val showFollowButton = !isOwnVideo && !isFollowing

            Box(contentAlignment = Alignment.BottomCenter) {
                conectate.com.presentation.components.CircularProfileImage(
                    modifier = Modifier.clickable { onProfileClick(video.userId) },
                    imageUrl = if (video.userProfileImage.isNotEmpty()) video.userProfileImage else null,
                    size = 50.dp,
                    isVerified = video.isVerified,
                    mediaType = video.avatarMediaType,
                    muted = video.avatarVideoMuted,
                    allowSound = !video.avatarVideoMuted,
                    fallbackName = video.userName,
                    borderColor = Color.White,
                    borderWidth = 1.dp
                )
                
                // Follow button (+) - only show if not following and not own video
                if (showFollowButton) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Abrir perfil",
                        tint = Color.White,
                        modifier = Modifier
                            .offset(y = 10.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                onProfileClick(video.userId)
                            }
                            .padding(2.dp)
                    )
                }
            }

            // Like
            InteractionButton(
                icon = if (video.likedByMe) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                text = likeCount.toString(),
                tint = if (video.likedByMe) Color.Red else Color.White,
                onClick = onLikeClick
            )

            // Comment
            InteractionButton(
                icon = Icons.Filled.Message,
                text = commentCount.toString(),
                onClick = onCommentClick
            )

            // Share
            var showShareMenu by remember { mutableStateOf(false) }
            Box {
                InteractionButton(
                    icon = Icons.Filled.Share,
                    text = shareCount.toString(),
                    onClick = { showShareMenu = true }
                )
                
                DropdownMenu(
                    expanded = showShareMenu,
                    onDismissRequest = { showShareMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Enviar a amigo") },
                        onClick = {
                            // 1. Pause player to release resources before transition
                            exoPlayer.pause()
                            
                            // 2. Clear state and navigate immediately (matches SocialScreen behavior)
                            showShareMenu = false
                            onShareInternalClick(video)
                        },
                        leadingIcon = { Icon(Icons.Default.Send, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Compartir enlace externo") },
                        onClick = {
                            showShareMenu = false
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, "Mira este video en Conéctate: https://conectate.com/video/${video.id}\n\nO abre directamente en la app:\nconectate://video/${video.id}\n\n${video.title}")
                                type = "text/plain"
                            }
                            val shareIntent = android.content.Intent.createChooser(sendIntent, "Compartir video con...")
                            context.startActivity(shareIntent)
                        },
                        leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp)) }
                    )
                    // New "Add to Profile" Option
                    DropdownMenuItem(
                        text = { Text(if (video.sharedByMe) "Quitar de mi perfil" else "Añadir a mi perfil") },
                        onClick = {
                            showShareMenu = false
                            onToggleProfileShare()
                        },
                        leadingIcon = { 
                            Icon(
                                imageVector = if (video.sharedByMe) androidx.compose.material.icons.Icons.Default.RepeatOn else androidx.compose.material.icons.Icons.Default.Repeat,
                                contentDescription = null, 
                                modifier = Modifier.size(18.dp),
                                tint = if (video.sharedByMe) Color(0xFF00bcd4) else MaterialTheme.colorScheme.onSurface
                            ) 
                        }
                    )
                    if (currentUserId == null || currentUserId != video.userId) {
                        DropdownMenuItem(
                            text = { Text(if (isAlreadyReported) "Reportado" else "Reportar video") },
                            onClick = {
                                showShareMenu = false
                                if (!isAlreadyReported) {
                                    onReportClick(video)
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Flag,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isAlreadyReported) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                    }
                }
            }
            
             // Music Disk Animation
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.DarkGray)
                    .padding(8.dp)
            )
        }

        // Bottom Info - Adjusted position (moved LOWER, closer to bottom edge)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 20.dp, start = 16.dp, end = 80.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "@${video.userName}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (video.isVerified) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Verificado",
                        tint = Color(0xFF1DA1F2),
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (isUserAdmin(video.userName)) {
                    AdminBadge(showVerifiedLine = false)
                }
                Spacer(modifier = Modifier.width(8.dp))
                // View Count Indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = viewCount.toString(),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = video.description,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Sonido original - ${video.userName}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun InteractionButton(
    icon: ImageVector,
    text: String,
    tint: Color = Color.White,
    onClick: () -> Unit = {}
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(32.dp),
                // Add shadow for better visibility on light backgrounds
                // modifier = Modifier.shadow(4.dp, shape = CircleShape) 
            )
        }
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall, // Smaller font
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}
