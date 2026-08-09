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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
import conectate.com.data.model.Post
import conectate.com.data.repository.VideoRepository
import conectate.com.util.UbuntuMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.*
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.common.ConnectivityViewModel
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VideoSearchPreview(
    video: Video,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val resolvedThumbnail = remember(video.thumbnailUrl) {
        UbuntuMedia.resolveUrl(video.thumbnailUrl) ?: video.thumbnailUrl
    }
    val request = remember(context, resolvedThumbnail) {
        ImageRequest.Builder(context)
            .data(resolvedThumbnail)
            .size(540, 960)
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171717))
    ) {
        Box(Modifier.fillMaxSize()) {
            if (resolvedThumbnail.isNotBlank()) {
                AsyncImage(
                    model = request,
                    contentDescription = "Vista previa de ${video.title}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                        )
                    )
            )
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Reproducir",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(8.dp)
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text(
                    video.title.ifBlank { "Video" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    "${video.userName} · ${video.views} vistas",
                    color = Color.White.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun VideoFeedScreen(
    navController: NavController,
    viewModel: VideoFeedViewModel = hiltViewModel(),
    connectivityViewModel: ConnectivityViewModel = hiltViewModel(),
    initialVideoId: String? = null,
    onNavigateToUpload: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onNavigateToChat: (String) -> Unit,
    onNavigateToLiveSetup: () -> Unit = {},
    onNavigateToLiveDiscovery: () -> Unit = {},
    onNavigateToLiveViewer: (String) -> Unit = {}
) {
    val forYouVideos by viewModel.forYouVideos.collectAsState()
    val followingVideos by viewModel.followingVideos.collectAsState()
    val profileVideos by viewModel.profileVideos.collectAsState()
    val socialHighlights by viewModel.socialHighlights.collectAsState()
    val recommendedPeople by viewModel.recommendedPeople.collectAsState()
    val socializationCards by viewModel.socializationCards.collectAsState()
    val activeStreams by viewModel.activeStreams.collectAsState()
    val showLiveRecommendations by viewModel.showLiveRecommendations.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isConnected by connectivityViewModel.isConnected.collectAsState()
    
    // Comments
    val commentsSheetOpen by viewModel.commentsSheetOpen.collectAsState()
    val selectedVideoForComments by viewModel.selectedVideoForComments.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsState()
    val reportedVideoIds by viewModel.reportedVideoIds.collectAsState()
    val reportActionMessage by viewModel.reportActionMessage.collectAsState()
    val algorithmActionMessage by viewModel.algorithmActionMessage.collectAsState()
    val offlineVideos by viewModel.offlineVideos.collectAsState()
    val offlineDownloadProgress by viewModel.offlineDownloadProgress.collectAsState()
    val downloadActionMessage by viewModel.downloadActionMessage.collectAsState()
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
    val isProfileFeed = viewModel.isProfileFeed
    val profilePrependCount by viewModel.profilePrependCount.collectAsState()

    val navScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    
    var showInternalShareDialog by remember { mutableStateOf(false) }
    var selectedVideoForShare by remember { mutableStateOf<conectate.com.data.model.Video?>(null) }
    var selectedVideoForReport by remember { mutableStateOf<conectate.com.data.model.Video?>(null) }
    var explanationVideo by remember { mutableStateOf<Video?>(null) }
    var notInterestedVideo by remember { mutableStateOf<Video?>(null) }
    var topicVideo by remember { mutableStateOf<Video?>(null) }
    var videoTopicText by remember { mutableStateOf("") }
    var pendingNavigationRoute by remember { mutableStateOf<String?>(null) }
    var isSystemBusy by remember { mutableStateOf(false) } // Safety: Disable heavy bits during Nav
    val sidePanels = remember { listOf(VideoSidePanel.VIDEO, VideoSidePanel.COMMENTS, VideoSidePanel.PROFILE) }
    var sidePanelIndex by rememberSaveable { mutableStateOf(0) }
    val sidePanelState = sidePanels.getOrElse(sidePanelIndex) { VideoSidePanel.VIDEO }
    val performanceProfile = remember(context) { VideoPerformanceProfiles.detect(context) }
    val playbackPreferences = remember(context) {
        context.getSharedPreferences("video_playback_preferences", android.content.Context.MODE_PRIVATE)
    }
    var autoAdvanceEnabled by remember {
        mutableStateOf(playbackPreferences.getBoolean("auto_advance", false))
    }
    var playbackSpeed by remember {
        mutableFloatStateOf(playbackPreferences.getFloat("playback_speed", 1f))
    }
    var showPlaybackOptions by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }

    LaunchedEffect(algorithmActionMessage) {
        algorithmActionMessage?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeAlgorithmActionMessage()
        }
    }

    LaunchedEffect(isConnected, isProfileFeed) {
        if (!isProfileFeed && isConnected && forYouVideos.isEmpty() && followingVideos.isEmpty()) {
            viewModel.recoverFeedAfterAppUpdate()
        }
    }

    val currentVideos = when {
        isProfileFeed -> profileVideos
        isSearchMode -> searchResults
        selectedTab == 0 -> forYouVideos
        else -> followingVideos
    }
    val downloadedVideoIds = remember(offlineVideos) {
        offlineVideos.map { it.video.id.trim().lowercase() }.toSet()
    }
    val feedEntries = remember(
        currentVideos,
        activeStreams,
        isSearchMode,
        selectedTab,
        showLiveRecommendations,
        socialHighlights,
        recommendedPeople,
        socializationCards,
    ) {
        mixedVideoFeed(
            videos = currentVideos,
            streams = activeStreams,
            includeLives = !isProfileFeed && showLiveRecommendations && !isSearchMode && selectedTab == 0,
            socialPosts = if (!isProfileFeed && !isSearchMode && selectedTab == 0) socialHighlights else emptyList(),
            recommendedPeople = if (!isProfileFeed && !isSearchMode && selectedTab == 0) recommendedPeople else emptyList(),
            socializationCards = if (!isProfileFeed && !isSearchMode && selectedTab == 0) socializationCards else emptyList(),
        )
    }
    val pagerState = rememberPagerState(pageCount = { feedEntries.size })
    var initialVideoPositionApplied by rememberSaveable(initialVideoId) { mutableStateOf(false) }

    LaunchedEffect(initialVideoId, isProfileFeed) {
        if (!isProfileFeed) {
            initialVideoId?.trim()?.takeIf { it.isNotBlank() }?.let(viewModel::ensureVideoInFeed)
        }
    }

    LaunchedEffect(initialVideoId, feedEntries, initialVideoPositionApplied) {
        if (initialVideoPositionApplied) return@LaunchedEffect
        val targetId = initialVideoId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val targetPage = feedEntries.indexOfFirst { entry ->
            entry is VideoFeedVideoEntry && entry.video.id == targetId
        }
        if (targetPage >= 0) {
            pagerState.scrollToPage(targetPage)
            initialVideoPositionApplied = true
        }
    }

    LaunchedEffect(profilePrependCount, feedEntries.size) {
        if (profilePrependCount == 0 || feedEntries.isEmpty()) return@LaunchedEffect
        pagerState.scrollToPage(
            (pagerState.currentPage + profilePrependCount).coerceIn(0, feedEntries.lastIndex)
        )
        viewModel.consumeProfilePrepend()
    }

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

    LaunchedEffect(downloadActionMessage) {
        val message = downloadActionMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.consumeDownloadActionMessage()
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
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        )
    }
    var playbackRequested by remember { mutableStateOf(true) }
    val settledVideo = (feedEntries.getOrNull(pagerState.settledPage) as? VideoFeedVideoEntry)?.video
    val settledVideoId = settledVideo?.id
    val settledVideoIndex = currentVideos.indexOfFirst { it.id == settledVideoId }

    // Keep profile feeds fluid without holding extra players/decoders. Only the
    // first bytes of the immediate neighbours are cached, with a smaller budget
    // on modest devices; the visible video remains the sole ExoPlayer instance.
    LaunchedEffect(isProfileFeed, isConnected, settledVideoIndex, currentVideos) {
        if (!isProfileFeed || !isConnected || settledVideoIndex !in currentVideos.indices) {
            return@LaunchedEffect
        }
        val byteBudget = when (performanceProfile.tier) {
            conectate.com.util.VideoDeviceTier.LOW -> 512L * 1024L
            conectate.com.util.VideoDeviceTier.BALANCED -> 1L * 1024L * 1024L
            conectate.com.util.VideoDeviceTier.HIGH -> 2L * 1024L * 1024L
        }
        val neighbourUrls = listOf(settledVideoIndex + 1, settledVideoIndex - 1)
            .filter { it in currentVideos.indices }
            .mapNotNull { index -> currentVideos[index].videoUrl.takeIf(String::isNotBlank) }
            .distinct()
        withContext(Dispatchers.IO) {
            neighbourUrls.forEach { url ->
                runCatching {
                    conectate.com.util.VideoCache.preloadStart(context.applicationContext, url, byteBudget)
                }
            }
        }
    }

    // Only reset playbackRequested when navigating to a *different* video (non-null -> non-null).
    // If the user manually paused (playbackRequested = false), we must NOT reset it to true
    // just because the current settled video changed. We only auto-play when a genuinely new
    // video settles.
    var prevSettledVideoId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settledVideoId) {
        val prev = prevSettledVideoId
        val curr = settledVideoId
        if (curr != null && curr != prev) {
            // Moved to a new video — auto-play it
            playbackRequested = true
        } else if (curr == null) {
            // No video to play
            playbackRequested = false
        }
        prevSettledVideoId = curr
    }
    
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
                playWhenReady = false
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
    
    // Keep the pager synchronized when ExoPlayer advances to the next item.
    DisposableEffect(exoPlayer, pagerState.currentPage, autoAdvanceEnabled, feedEntries) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val video = currentVideos.getOrNull(exoPlayer.currentMediaItemIndex)
                val nextPage = video?.let { target ->
                    feedEntries.indexOfFirst { entry ->
                        entry is VideoFeedVideoEntry && entry.video.id == target.id
                    }
                } ?: -1
                if (
                    autoAdvanceEnabled &&
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    nextPage >= 0 &&
                    nextPage != pagerState.currentPage
                ) {
                    navScope.launch { pagerState.animateScrollToPage(nextPage) }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(autoAdvanceEnabled, playbackSpeed) {
        exoPlayer.repeatMode = if (autoAdvanceEnabled) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        exoPlayer.setPlaybackSpeed(playbackSpeed)
    }

    // ════════════════════════════════════════════════════════
    // 2️⃣ LIFECYCLE MANAGEMENT (CRITICAL BUG FIX)
    // ════════════════════════════════════════════════════════
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            lifecycleResumed = lifecycleOwner.lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (!lifecycleResumed) {
                exoPlayer.pause()
            }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // Refresh follow status when returning to screen
                android.util.Log.d("VideoFeedScreen", "Screen resumed, refreshing follow status")
                viewModel.refreshFollowStatus()
                viewModel.refreshLives()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.pause()
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    
    // 🚀 RESTORED & STABLE: Synchronize Playlist when videos change
    val playlistSignature = remember(currentVideos) {
        currentVideos.map { video -> video.id to video.videoUrl }
    }

    LaunchedEffect(playlistSignature, feedEntries) {
        if (currentVideos.isNotEmpty()) {
            val resolvedVideoUrls = currentVideos.map { UbuntuMedia.resolveUrl(it.videoUrl) ?: it.videoUrl }
            val mediaItems = resolvedVideoUrls.map { UbuntuMedia.mediaItem(it) }

            val existingUrls = (0 until exoPlayer.mediaItemCount).map { index ->
                exoPlayer.getMediaItemAt(index).localConfiguration?.uri?.toString().orEmpty()
            }
            val shouldUpdate = existingUrls != resolvedVideoUrls

            if (shouldUpdate) {
                android.util.Log.d("VideoFeedScreen", "Updating media items (stable)...")
                val selectedVideoId = (feedEntries.getOrNull(pagerState.settledPage) as? VideoFeedVideoEntry)
                    ?.video
                    ?.id
                val targetVideoIndex = currentVideos.indexOfFirst { it.id == selectedVideoId }
                    .takeIf { it >= 0 }
                    ?: 0
                exoPlayer.setMediaItems(mediaItems, targetVideoIndex, 0L)
                exoPlayer.prepare()
            }
        } else {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    // A page transition resets side panels, but list/stat refreshes do not.
    LaunchedEffect(pagerState.settledPage, settledVideoId) {
        if (sidePanelState != VideoSidePanel.VIDEO) {
            switchToVideoPanel()
        }
    }

    LaunchedEffect(settledVideoId, currentVideos.size, isProfileFeed) {
        if (settledVideoId == null || settledVideoIndex < 0) return@LaunchedEffect

        if (isProfileFeed) {
            if (settledVideoIndex >= currentVideos.size - 3) {
                viewModel.loadMoreProfileVideos()
            }
            if (settledVideoIndex <= 2) {
                viewModel.loadPreviousProfileVideos()
            }
        } else if (settledVideoIndex >= currentVideos.size - 3) {
                viewModel.loadMoreVideos()
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
        lifecycleResumed,
        playbackRequested,
        isSystemBusy,
        settledVideoId,
        settledVideoIndex,
        playlistSignature
    ) {
        val canPlay = settledVideoId != null &&
            settledVideoIndex >= 0 &&
            !pagerState.isScrollInProgress &&
            !commentsSheetOpen &&
            !showInternalShareDialog &&
            sidePanelState == VideoSidePanel.VIDEO &&
            lifecycleResumed &&
            playbackRequested &&
            !isSystemBusy

        if (!canPlay) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.currentMediaItemIndex != settledVideoIndex) {
                exoPlayer.seekTo(settledVideoIndex, 0L)
            }
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    LaunchedEffect(settledVideoId, settledVideoIndex, exoPlayer) {
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val isActuallyPlaying = settledVideoId != null &&
                    settledVideoIndex >= 0 &&
                    exoPlayer.currentMediaItemIndex == settledVideoIndex &&
                    exoPlayer.isPlaying
                viewModel.onVideoPlaybackSample(
                    videoId = settledVideoId,
                    isActuallyPlaying = isActuallyPlaying,
                    elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                )
                delay(250L)
            }
        } finally {
            viewModel.onVideoPlaybackSample(
                videoId = null,
                isActuallyPlaying = false,
                elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
            )
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
            onAddSticker = { url, label -> viewModel.addStickerComment(videoIdForComments, url, label) },
            onReactComment = { comment, reaction -> viewModel.reactToComment(videoIdForComments, comment, reaction) },
            onEditComment = { comment, content -> viewModel.editComment(videoIdForComments, comment, content) },
            onReportComment = { comment -> viewModel.reportComment(comment) },
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

    explanationVideo?.let { selected ->
        val reasons = viewModel.explainVideo(selected)
        ModalBottomSheet(onDismissRequest = { explanationVideo = null }) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("¿Por qué ves este video?", style = MaterialTheme.typography.titleLarge)
                if (reasons.isEmpty()) {
                    Text("Este video forma parte del feed actual de Novvin.")
                } else {
                    reasons.forEach { Text("• $it") }
                }
                FilledTonalButton(
                    onClick = {
                        notInterestedVideo = selected
                        explanationVideo = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("No me interesa") }
            }
        }
    }

    notInterestedVideo?.let { selected ->
        ModalBottomSheet(onDismissRequest = { notInterestedVideo = null }) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("¿Qué no te interesa?", style = MaterialTheme.typography.titleLarge)
                Text("La elección se guarda en Mi algoritmo para Videos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ListItem(
                    headlineContent = { Text("Solo este video") },
                    modifier = Modifier.clickable {
                        viewModel.excludeVideoFromAlgorithm(selected)
                        notInterestedVideo = null
                    },
                )
                ListItem(
                    headlineContent = { Text("Este tema") },
                    supportingContent = { Text("Elige el tema exacto") },
                    modifier = Modifier.clickable {
                        topicVideo = selected
                        videoTopicText = selected.tags.firstOrNull().orEmpty()
                        notInterestedVideo = null
                    },
                )
                ListItem(
                    headlineContent = { Text("Este creador") },
                    modifier = Modifier.clickable {
                        viewModel.excludeVideoCreatorFromAlgorithm(selected)
                        notInterestedVideo = null
                    },
                )
                ListItem(
                    headlineContent = { Text("Este tipo de video") },
                    modifier = Modifier.clickable {
                        viewModel.excludeVideoFormatFromAlgorithm(selected)
                        notInterestedVideo = null
                    },
                )
                TextButton(onClick = { notInterestedVideo = null }, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancelar")
                }
            }
        }
    }

    topicVideo?.let { selected ->
        AlertDialog(
            onDismissRequest = { topicVideo = null },
            title = { Text("No recomendar este tema") },
            text = {
                OutlinedTextField(
                    value = videoTopicText,
                    onValueChange = { videoTopicText = it.take(60) },
                    label = { Text("Tema") },
                    supportingText = { Text("Se aplicará únicamente al feed de Videos.") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = videoTopicText.trim().length >= 2,
                    onClick = {
                        viewModel.excludeVideoTopicFromAlgorithm(selected, videoTopicText)
                        topicVideo = null
                        videoTopicText = ""
                    },
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { topicVideo = null }) { Text("Cancelar") } },
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

    if (showPlaybackOptions) {
        ModalBottomSheet(onDismissRequest = { showPlaybackOptions = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Reproducción", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Velocidad", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val speeds = listOf(0.5f, 1f, 1.5f, 2f)
                    speeds.forEach { speed ->
                        FilterChip(
                            selected = playbackSpeed == speed,
                            onClick = {
                                playbackSpeed = speed
                                playbackPreferences.edit().putFloat("playback_speed", speed).apply()
                            },
                            label = { Text("${speed}x") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Pasar al siguiente automáticamente", modifier = Modifier.weight(1f))
                    Switch(
                        checked = autoAdvanceEnabled,
                        onCheckedChange = { enabled ->
                            autoAdvanceEnabled = enabled
                            playbackPreferences.edit().putBoolean("auto_advance", enabled).apply()
                        }
                    )
                }
                val recommendationReasons = settledVideo?.let(viewModel::explainVideo).orEmpty()
                if (recommendationReasons.isNotEmpty()) {
                    Divider()
                    Text(
                        "Por qué este video",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    recommendationReasons.forEach { reason ->
                        Text(
                            text = "• $reason",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
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
            } else if (!isConnected && feedEntries.isEmpty()) {
                 Box(
                     modifier = Modifier.fillMaxSize(),
                     contentAlignment = Alignment.Center
                 ) {
                     Column(
                         horizontalAlignment = Alignment.CenterHorizontally,
                         verticalArrangement = Arrangement.spacedBy(12.dp),
                         modifier = Modifier.padding(horizontal = 32.dp)
                     ) {
                         Icon(Icons.Default.WifiOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                         Text(
                             "No se pudieron cargar los videos porque no hay conexión a internet.",
                             color = Color.White,
                             textAlign = TextAlign.Center,
                             fontWeight = FontWeight.Bold
                         )
                         Text(
                             "Verifica tu conexión y vuelve a intentarlo.",
                             color = Color.White.copy(alpha = 0.72f),
                             textAlign = TextAlign.Center
                         )
                         Button(onClick = {
                             connectivityViewModel.retrySync("video_feed_retry")
                             viewModel.refreshFeed()
                         }) { Text("Reintentar") }
                     }
                 }
            } else if (feedEntries.isEmpty()) {
                 // Empty State
                 Box(
                     modifier = Modifier.fillMaxSize(),
                     contentAlignment = Alignment.Center
                 ) {
                     Column(horizontalAlignment = Alignment.CenterHorizontally) {
                          Text(
                              text = if (isProfileFeed) "Este perfil todavía no tiene videos publicados"
                                     else if (isSearchMode) "No se encontraron videos para \"$searchQuery\""
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
                if (isSearchMode) {
                    LaunchedEffect(Unit) {
                        exoPlayer.pause()
                        playbackRequested = false
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 68.dp,
                            bottom = 88.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(currentVideos, key = { it.id }) { video ->
                            VideoSearchPreview(
                                video = video,
                                onClick = {
                                    navController.navigate(Screen.VideoPlayer.createRoute(video.id))
                                }
                            )
                        }
                    }
                } else {
                // 3️⃣ VERTICAL PAGER
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondBoundsPageCount = performanceProfile.feedBeyondBoundsPageCount,
                    key = { page -> feedEntries.getOrNull(page)?.stableKey ?: "feed:$page" }
                ) { page ->
                    when (val entry = feedEntries.getOrNull(page)) {
                        is VideoFeedVideoEntry -> {
                            val video = entry.video
                            VideoItem(
                            video = video,
                            exoPlayer = exoPlayer, // Pass the SINGLE player
                            isSelected = pagerState.settledPage == page && !isSystemBusy,
                            onTogglePlayback = {
                                if (pagerState.settledPage == page) {
                                    playbackRequested = !playbackRequested
                                }
                            },
                            onLikeClick = { viewModel.toggleLike(video) },
                            onCommentClick = { 
                                switchToCommentsPanel(video)
                            },
                            onFavoriteClick = { viewModel.toggleFavorite(video) },
                            onLongPress = { showPlaybackOptions = true },
                            onProfileClick = { userId ->
                                setSidePanelIndex(2)
                                onNavigateToProfile(userId)
                            },
                            onSwipeRight = {
                                onHorizontalSwipe(video, forward = false)
                            },
                            onSwipeLeft = {
                                onHorizontalSwipe(video, forward = true)
                            },
                            currentUserId = currentUserId,
                            followedUsers = followedUsers,
                            getStatsFlow = { viewModel.getVideoStats(it) },
                            onShareInternalClick = { video ->
                                selectedVideoForShare = video
                                showInternalShareDialog = true
                            },
                            onToggleProfileShare = { viewModel.toggleShare(video) },
                            isAvailableOffline = downloadedVideoIds.contains(video.id.trim().lowercase()),
                            downloadProgress = offlineDownloadProgress[video.id],
                            onDownloadClick = { viewModel.downloadForOffline(video) },
                            onDeleteVideo = { video -> viewModel.deleteVideo(video) },
                            isAlreadyReported = reportedVideoIds.contains(video.id),
                            onWhyThisVideo = { video -> explanationVideo = video },
                            onNotInterested = { video -> notInterestedVideo = video },
                            onReportClick = { video ->
                                viewModel.ensureVideoReportStatus(video.id)
                                selectedVideoForReport = video
                            }
                        )
                        }
                        is VideoFeedLiveEntry -> {
                            LiveFeedRecommendation(
                                stream = entry.stream,
                                onOpenLive = {
                                    onNavigateToLiveViewer(entry.stream.id)
                                },
                                onOpenProfile = {
                                    onNavigateToProfile(entry.stream.hostUserId)
                                }
                            )
                        }
                        is VideoFeedSocialEntry -> {
                            SocialHighlightsPage(
                                posts = entry.posts,
                                onPostClick = { post ->
                                    navController.navigate(Screen.PostDetail.createRoute(post.id))
                                }
                            )
                        }
                        is VideoFeedPeopleEntry -> {
                            VideoPeopleRecommendationPage(
                                users = entry.users,
                                onOpenProfile = onNavigateToProfile,
                                onFollow = viewModel::followRecommendedUser
                            )
                        }
                        is VideoFeedSocializationEntry -> {
                            VideoSocializationRecommendationPage(
                                cards = entry.cards,
                                onOpen = { card ->
                                    when (card.action.type) {
                                        "OPEN_PROFILE" -> onNavigateToProfile(card.action.targetId)
                                        "OPEN_COMMUNITY" -> navController.navigate(Screen.CommunityDetail.createRoute(card.action.targetId))
                                        "OPEN_LIVE" -> onNavigateToLiveViewer(card.action.targetId)
                                    }
                                },
                                onDismiss = { viewModel.dismissSocializationCard(it.id) },
                            )
                        }
                        null -> Unit
                    }
                }
                }
            }
            
            // Top Overlay (Search / Amigos / Recomendado)
            if (isProfileFeed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Volver al perfil",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Videos del perfil",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            } else if (!isSearchMode) {
                // NORMAL MODE: Tabs and Search Icon
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        exoPlayer.pause()
                        showCreateMenu = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Crear en Videos",
                        modifier = Modifier.size(24.dp)
                    )
                }
                DropdownMenu(
                    expanded = showCreateMenu,
                    onDismissRequest = { showCreateMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Subir video") },
                        leadingIcon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                        onClick = {
                            showCreateMenu = false
                            onNavigateToUpload()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Crear directo") },
                        leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                        onClick = {
                            showCreateMenu = false
                            onNavigateToLiveSetup()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Ver directos") },
                        leadingIcon = { Icon(Icons.Default.LiveTv, contentDescription = null) },
                        onClick = {
                            showCreateMenu = false
                            onNavigateToLiveDiscovery()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveFeedRecommendation(
    stream: conectate.com.data.model.LiveStream,
    onOpenLive: () -> Unit,
    onOpenProfile: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AsyncImage(
            model = UbuntuMedia.resolveUrl(stream.replayThumbnailUrl ?: stream.hostAvatarUrl)
                ?: stream.replayThumbnailUrl
                ?: stream.hostAvatarUrl,
            contentDescription = "Vista previa del directo de ${stream.hostName}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onOpenLive)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.32f),
                        0.48f to Color.Black.copy(alpha = 0.48f),
                        1f to Color.Black.copy(alpha = 0.92f)
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 72.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xFFE53935),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "EN DIRECTO",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stream.viewerCount.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = if (stream.source.equals("screen", ignoreCase = true)) {
                        "PANTALLA"
                    } else {
                        "CÁMARA"
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onOpenProfile),
                verticalAlignment = Alignment.CenterVertically
            ) {
                conectate.com.presentation.components.CircularProfileImage(
                    imageUrl = stream.hostAvatarUrl,
                    size = 44.dp,
                    showBorder = true
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stream.hostName.ifBlank { "Creador de Novvin" },
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Transmitiendo ahora · toca para ver su perfil",
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                text = stream.title.ifBlank { stream.hostName },
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onOpenLive,
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Entrar al directo", fontWeight = FontWeight.Bold)
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
    onTogglePlayback: () -> Unit,
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onLongPress: () -> Unit,
    onProfileClick: (String) -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    currentUserId: String?,
    followedUsers: Set<String>,
    getStatsFlow: (String) -> kotlinx.coroutines.flow.Flow<conectate.com.data.local.entity.VideoStatsEntity?>,
    onShareInternalClick: (Video) -> Unit,
    onToggleProfileShare: () -> Unit,
    isAvailableOffline: Boolean,
    downloadProgress: Float?,
    onDownloadClick: () -> Unit,
    onDeleteVideo: (Video) -> Unit,
    isAlreadyReported: Boolean,
    onWhyThisVideo: (Video) -> Unit,
    onNotInterested: (Video) -> Unit,
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
    val likeCount = currentStats?.likeCount ?: video.likeCount
    val commentCount = currentStats?.commentCount ?: video.commentCount
    val viewCount = maxOf(video.views, currentStats?.viewCount ?: 0)
    val shareCount = currentStats?.shareCount ?: video.shareCount

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
            .pointerInput(video.id, isSelected) {
                detectTapGestures(
                    onTap = {
                        if (isSelected) {
                            onTogglePlayback()
                        }
                    },
                    onLongPress = { if (isSelected) onLongPress() }
                )
            }
    ) {
        // Removed duplicate AndroidView - keeping only the one below with proper thumbnail management
        
        // PAUSE ICON OVERLAY
        // We only observe isPlaying state if this item is selected
        var isPlaying by remember { mutableStateOf(false) }
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
                        setBackgroundColor(android.graphics.Color.BLACK)
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
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
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
                    .padding(start = 2.dp, end = 2.dp, bottom = 78.dp)
                    .height(22.dp),
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
                .padding(bottom = 94.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Profile Pic with Follow Button
            val normalizedCurrentUserId = currentUserId?.trim()?.lowercase()
            val normalizedVideoUserId = video.userId.trim().lowercase()
            val isOwnVideo = normalizedCurrentUserId != null && normalizedCurrentUserId == normalizedVideoUserId
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

            InteractionButton(
                icon = if (video.favoritedByMe) Icons.Filled.Star else Icons.Filled.StarBorder,
                text = video.favoriteCount.toString(),
                tint = if (video.favoritedByMe) Color(0xFFFFC107) else Color.White,
                onClick = onFavoriteClick
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
                                putExtra(android.content.Intent.EXTRA_TEXT, "Mira este video en Novvin: https://conectate.com/video/${video.id}\n\nO abre directamente en la app:\nconectate://video/${video.id}\n\n${video.title}")
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
                    if (!isOwnVideo) {
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
                    if (isOwnVideo) {
                        DropdownMenuItem(
                            text = { Text("Eliminar video") },
                            onClick = {
                                showShareMenu = false
                                exoPlayer.pause()
                                onDeleteVideo(video)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.Red
                                )
                            },
                            colors = MenuDefaults.itemColors(textColor = Color.Red)
                        )
                    }
                }
            }

            var showAlgorithmMenu by remember(video.id) { mutableStateOf(false) }
            Box {
                InteractionButton(
                    icon = Icons.Default.MoreVert,
                    text = "",
                    onClick = { showAlgorithmMenu = true },
                )
                DropdownMenu(
                    expanded = showAlgorithmMenu,
                    onDismissRequest = { showAlgorithmMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("¿Por qué veo esto?") },
                        onClick = {
                            showAlgorithmMenu = false
                            onWhyThisVideo(video)
                        },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("No me interesa") },
                        onClick = {
                            showAlgorithmMenu = false
                            onNotInterested(video)
                        },
                        leadingIcon = { Icon(Icons.Default.ThumbDown, contentDescription = null) },
                    )
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
                .padding(bottom = 94.dp, start = 16.dp, end = 80.dp)
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
            Text(
                text = formatVideoMonthYear(video.created_at),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
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

private sealed interface VideoFeedEntry {
    val stableKey: String
}

private data class VideoFeedVideoEntry(val video: Video) : VideoFeedEntry {
    override val stableKey: String = "video:${video.id}"
}

private data class VideoFeedLiveEntry(
    val stream: conectate.com.data.model.LiveStream
) : VideoFeedEntry {
    override val stableKey: String = "live:${stream.id}"
}

private data class VideoFeedSocialEntry(
    val posts: List<Post>
) : VideoFeedEntry {
    override val stableKey: String = "social:${posts.joinToString(",") { it.id }}"
}

private data class VideoFeedPeopleEntry(
    val users: List<conectate.com.data.model.User>
) : VideoFeedEntry {
    override val stableKey: String = "people:${users.joinToString(",") { it.id }}"
}

private data class VideoFeedSocializationEntry(
    val cards: List<conectate.com.data.model.SocializationCard>,
) : VideoFeedEntry {
    override val stableKey: String = "socialize:${cards.joinToString(",") { it.id }}"
}

private fun mixedVideoFeed(
    videos: List<Video>,
    streams: List<conectate.com.data.model.LiveStream>,
    includeLives: Boolean,
    socialPosts: List<Post> = emptyList(),
    recommendedPeople: List<conectate.com.data.model.User> = emptyList(),
    socializationCards: List<conectate.com.data.model.SocializationCard> = emptyList(),
): List<VideoFeedEntry> {
    if (videos.isEmpty()) {
        val liveEntries = if (includeLives) streams.take(6).map(::VideoFeedLiveEntry) else emptyList()
        val socialEntry = socialPosts.takeIf { it.isNotEmpty() }?.let(::VideoFeedSocialEntry)
        val peopleEntry = recommendedPeople.takeIf { it.isNotEmpty() }?.let(::VideoFeedPeopleEntry)
        val socializationEntry = socializationCards.takeIf { it.isNotEmpty() }?.let(::VideoFeedSocializationEntry)
        return liveEntries + listOfNotNull(socialEntry, peopleEntry, socializationEntry)
    }

    val result = ArrayList<VideoFeedEntry>(videos.size + (videos.size / 10) + 2)
    var nextLiveAfter = 12
    var liveIndex = 0
    var socialInserted = false
    var peopleInserted = false
    var socializationInserted = false
    videos.forEachIndexed { index, video ->
        result += VideoFeedVideoEntry(video)
        val videoCount = index + 1
        if (includeLives && streams.isNotEmpty() && videoCount == nextLiveAfter) {
            result += VideoFeedLiveEntry(streams[liveIndex % streams.size])
            liveIndex += 1
            nextLiveAfter += when (liveIndex % 3) {
                0 -> 10
                1 -> 12
                else -> 15
            }
        }
        if (!socialInserted && socialPosts.isNotEmpty() && videoCount == 8) {
            result += VideoFeedSocialEntry(socialPosts.take(8))
            socialInserted = true
        }
        if (!peopleInserted && recommendedPeople.isNotEmpty() && videoCount == 15) {
            result += VideoFeedPeopleEntry(recommendedPeople.take(10))
            peopleInserted = true
        }
        if (!socializationInserted && socializationCards.isNotEmpty() && videoCount == 12) {
            result += VideoFeedSocializationEntry(socializationCards.take(4))
            socializationInserted = true
        }
    }
    if (!peopleInserted && recommendedPeople.isNotEmpty()) {
        result += VideoFeedPeopleEntry(recommendedPeople.take(10))
    }
    if (!socialInserted && socialPosts.isNotEmpty()) {
        result += VideoFeedSocialEntry(socialPosts.take(8))
    }
    if (!socializationInserted && socializationCards.isNotEmpty()) {
        result += VideoFeedSocializationEntry(socializationCards.take(4))
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SocialHighlightsPage(
    posts: List<Post>,
    onPostClick: (Post) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1220)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16233A)),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier.padding(vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.padding(horizontal = 18.dp)) {
                    Text(
                        "También puede interesarte",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Publicaciones de Social seleccionadas para ti",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    lazyItems(posts, key = { it.id }) { post ->
                        val media = post.mediaUrls.firstOrNull() ?: post.mediaUrlLegacy
                        Card(
                            onClick = { onPostClick(post) },
                            modifier = Modifier.width(190.dp).height(250.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF243756)),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Column {
                                if (!media.isNullOrBlank()) {
                                    AsyncImage(
                                        model = UbuntuMedia.resolveUrl(media) ?: media,
                                        contentDescription = "Publicación de ${post.userName}",
                                        modifier = Modifier.fillMaxWidth().height(132.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(132.dp)
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(Color(0xFF5B4BDB), Color(0xFF1DB6A5))
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Social", color = Color.White, fontWeight = FontWeight.Black)
                                    }
                                }
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        post.userName.ifBlank { "Usuario" },
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        post.content.ifBlank { "Ver publicación" },
                                        color = Color.White.copy(alpha = 0.82f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 3,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${post.likeCount} me gusta · ${post.commentCount} comentarios",
                                        color = Color.White.copy(alpha = 0.64f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "Desliza horizontalmente para ver más",
                    color = Color.White.copy(alpha = 0.64f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoPeopleRecommendationPage(
    users: List<conectate.com.data.model.User>,
    onOpenProfile: (String) -> Unit,
    onFollow: (conectate.com.data.model.User) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B1220)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "Personas que quizá conozcas",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Perfiles sugeridos según tu actividad",
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                lazyItems(users, key = { it.id }) { user ->
                    Card(
                        onClick = { onOpenProfile(user.id) },
                        modifier = Modifier.width(176.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF17243A)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(82.dp).clip(CircleShape).background(Color(0xFF283B5A)),
                                contentAlignment = Alignment.Center
                            ) {
                                val avatar = UbuntuMedia.resolveUrl(user.safeProfileImage)
                                if (!avatar.isNullOrBlank()) {
                                    AsyncImage(
                                        model = avatar,
                                        contentDescription = "Foto de ${user.displayName}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(
                                        user.displayName.take(1).uppercase(),
                                        color = Color.White,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                            Text(
                                user.displayName,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                "${user.followers_count ?: 0} seguidores",
                                color = Color.White.copy(alpha = 0.68f),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Button(onClick = { onFollow(user) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Seguir")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoSocializationRecommendationPage(
    cards: List<conectate.com.data.model.SocializationCard>,
    onOpen: (conectate.com.data.model.SocializationCard) -> Unit,
    onDismiss: (conectate.com.data.model.SocializationCard) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B1220)),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Socializa con seguridad", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text("Opciones públicas que autorizaste; nunca te conectan automáticamente.", color = Color.White.copy(alpha = 0.72f))
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                lazyItems(cards, key = { it.id }) { card ->
                    Card(
                        onClick = { onOpen(card) },
                        modifier = Modifier.width(190.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF17243A)),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier.size(68.dp).clip(CircleShape).background(Color(0xFF283B5A)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val image = UbuntuMedia.resolveUrl(card.imageUrl) ?: card.imageUrl
                                    if (!image.isNullOrBlank()) {
                                        AsyncImage(model = image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Icon(Icons.Default.People, contentDescription = null, tint = Color.White)
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                if (card.dismissible) {
                                    IconButton(onClick = { onDismiss(card) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Ocultar recomendación", tint = Color.White)
                                    }
                                }
                            }
                            Text(card.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            card.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                            Text(
                                when (card.type) {
                                    "person" -> "Ver perfil"
                                    "community" -> "Ver comunidad"
                                    "live" -> "Entrar al directo"
                                    else -> "Abrir"
                                },
                                color = Color(0xFF6FD6FF),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatVideoMonthYear(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        val instant = java.time.Instant.parse(value)
        java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale("es"))
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
            .replaceFirstChar { it.uppercase() }
    }.getOrDefault(value.take(7))
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
