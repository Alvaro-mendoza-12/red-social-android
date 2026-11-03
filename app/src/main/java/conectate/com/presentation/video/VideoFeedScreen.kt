package conectate.com.presentation.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import conectate.com.presentation.components.AdminBadge
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import conectate.com.data.model.Video
import conectate.com.data.model.videoWebLink
import conectate.com.data.model.Post
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.repository.VideoRepository
import conectate.com.util.UbuntuMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.*
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.common.ConnectivityViewModel
import conectate.com.presentation.common.FeedRecoveryPolicy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.input.pointer.pointerInput
import conectate.com.presentation.videos.VideoReportDialog
import androidx.compose.material.icons.filled.PlayArrow as PlayArrowIcon
import conectate.com.util.VideoPerformanceProfiles
import conectate.com.presentation.videos.VideoDescriptionText

private enum class VideoSidePanel {
    VIDEO,
    COMMENTS,
    PROFILE
}

data class VideoSoundSelection(
    val soundId: String,
    val sourceVideoId: String,
    val sourceVideoUrl: String,
    val title: String,
    val artist: String,
)

const val PENDING_VIDEO_SOUND_URL = "pending_video_sound_url"
const val PENDING_VIDEO_SOUND_TITLE = "pending_video_sound_title"
const val PENDING_VIDEO_SOUND_ARTIST = "pending_video_sound_artist"
const val PENDING_VIDEO_SOUND_SOURCE_ID = "pending_video_sound_source_id"
const val PENDING_VIDEO_SOUND_ID = "pending_video_sound_id"

fun savePendingVideoSound(navController: NavController, sound: VideoSoundSelection?) {
    val handle = navController.currentBackStackEntry?.savedStateHandle ?: return
    if (sound == null) {
        handle.remove<String>(PENDING_VIDEO_SOUND_URL)
        handle.remove<String>(PENDING_VIDEO_SOUND_TITLE)
        handle.remove<String>(PENDING_VIDEO_SOUND_ARTIST)
        handle.remove<String>(PENDING_VIDEO_SOUND_SOURCE_ID)
        handle.remove<String>(PENDING_VIDEO_SOUND_ID)
    } else {
        handle[PENDING_VIDEO_SOUND_URL] = sound.sourceVideoUrl
        handle[PENDING_VIDEO_SOUND_TITLE] = sound.title
        handle[PENDING_VIDEO_SOUND_ARTIST] = sound.artist
        handle[PENDING_VIDEO_SOUND_SOURCE_ID] = sound.sourceVideoId
        handle[PENDING_VIDEO_SOUND_ID] = sound.soundId
    }
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun VideoFeedScreen(
    navController: NavController,
    viewModel: VideoFeedViewModel = hiltViewModel(),
    connectivityViewModel: ConnectivityViewModel = hiltViewModel(),
    initialVideoId: String? = null,
    initialPositionMs: Long = 0L,
    initialClipEndMs: Long = 0L,
    focusCommentId: String? = null,
    showBackButton: Boolean = false,
    feedRefreshToken: Long = 0L,
    onNavigateToUpload: (VideoSoundSelection?) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onNavigateToChat: (String) -> Unit,
    onNavigateToLiveSetup: () -> Unit = {},
    onNavigateToLiveDiscovery: () -> Unit = {},
    onNavigateToLiveViewer: (String) -> Unit = {},
    onFollowingFeedVisible: (List<conectate.com.data.model.Video>) -> Unit = {},
) {
    val watchLaterViewModel: VideoWatchLaterViewModel = hiltViewModel()
    val historyViewModel: VideoHistoryViewModel = hiltViewModel()
    val collectionsViewModel: VideoCollectionsViewModel = hiltViewModel()
    LaunchedEffect(feedRefreshToken) {
        if (feedRefreshToken > 0L && !viewModel.isProfileFeed) {
            viewModel.refreshFeed()
        }
    }

    val forYouFeed by viewModel.forYouFeed.collectAsState()
    val forYouVideos = forYouFeed.videos
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()
    val followingVideos by viewModel.followingVideos.collectAsState()
    val profileVideos by viewModel.profileVideos.collectAsState()
    val socialHighlights by viewModel.socialHighlights.collectAsState()
    val recommendedPeople by viewModel.recommendedPeople.collectAsState()
    val socializationCards by viewModel.socializationCards.collectAsState()
    val activeStreams by viewModel.activeStreams.collectAsStateWithLifecycle()
    val showLiveRecommendations by viewModel.showLiveRecommendations.collectAsState()
    val dataSaverEnabled by viewModel.dataSaverEnabled.collectAsStateWithLifecycle()
    val videoAutoplayOnMobile by viewModel.videoAutoplayOnMobile.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val recommendationError by viewModel.feedError.collectAsState()
    val noMatchingRecommendations by viewModel.noMatchingRecommendations.collectAsState()
    val authoritativeFeedAccepted by viewModel.authoritativeFeedAccepted.collectAsState()
    val authoritativeContinuationPending by viewModel.authoritativeContinuationPending.collectAsState()
    val recommendedFeedExhausted by viewModel.recommendedFeedExhausted.collectAsState()
    val followingError by viewModel.followingFeedError.collectAsState()
    val feedError = if (selectedTab == 1) followingError else recommendationError
    val currentFollowingCallback by rememberUpdatedState(onFollowingFeedVisible)
    val feedLifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(selectedTab, isLoading, isRefreshing, feedError, followingVideos, feedLifecycleState) {
        if (feedLifecycleState == androidx.lifecycle.Lifecycle.State.RESUMED &&
            selectedTab == 1 && !isLoading && !isRefreshing && feedError == null) {
            currentFollowingCallback(followingVideos)
        }
    }
    val isConnected by connectivityViewModel.isConnected.collectAsState()
    val isCellular by connectivityViewModel.isCellular.collectAsState()
    val cellularAutoplayBlocked = isCellular && !videoAutoplayOnMobile
    
    // Comments
    val commentsSheetOpen by viewModel.commentsSheetOpen.collectAsState()
    val selectedVideoForComments by viewModel.selectedVideoForComments.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val commentPosting by viewModel.commentPosting.collectAsState()
    val focusedVideoError by viewModel.focusedVideoError.collectAsState()
    val profileFeedError by viewModel.profileFeedError.collectAsState()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsState()
    val reportedVideoIds by viewModel.reportedVideoIds.collectAsState()
    val reportActionMessage by viewModel.reportActionMessage.collectAsState()
    val algorithmActionMessage by viewModel.algorithmActionMessage.collectAsState()
    val algorithmUndoOffer by viewModel.algorithmUndoOffer.collectAsState()
    val followedUsers by viewModel.followedUsers.collectAsState()
    val pendingFollowUsers by viewModel.pendingFollowUsers.collectAsState()
    val reduceMotionEnabled by viewModel.reduceMotionEnabled.collectAsState()
    val videoDownloadsInProgress by viewModel.videoDownloadsInProgress.collectAsState()
    val currentPlayingCommentId by viewModel.currentPlayingCommentId.collectAsState()
    val audioProgress by viewModel.audioProgress.collectAsState()
    val soundVideos by viewModel.soundVideos.collectAsState()
    val isSoundVideosLoading by viewModel.isSoundVideosLoading.collectAsState()
    val soundVideosError by viewModel.soundVideosError.collectAsState()
    
    // Search State
    val isSearchMode by viewModel.isSearchMode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    val searchTopics by viewModel.searchTopics.collectAsState()
    
    val currentUserId by viewModel.currentUserId.collectAsState()
    val isProfileFeed = viewModel.isProfileFeed
    val profilePrependCount by viewModel.profilePrependCount.collectAsState()

    val navScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val algorithmSnackbarHostState = remember { SnackbarHostState() }
    var pendingLegacyDownload by remember { mutableStateOf<Video?>(null) }
    val legacyStoragePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingLegacyDownload
        pendingLegacyDownload = null
        if (granted && pending != null) {
            viewModel.downloadVideo(pending)
        } else if (!granted) {
            conectate.com.presentation.common.AppFeedbackBus.showError(
                "Permite guardar archivos para descargar el video."
            )
        }
    }
    val requestVideoDownload: (Video) -> Unit = { selectedVideo ->
        if (
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingLegacyDownload = selectedVideo
            legacyStoragePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.downloadVideo(selectedVideo)
        }
    }
    
    val videoShareCoordinator = conectate.com.presentation.messages.LocalUniversalForwardCoordinator.current
    var showInternalShareDialog by remember { mutableStateOf(false) }
    var selectedVideoForShare by remember { mutableStateOf<conectate.com.data.model.Video?>(null) }
    var selectedVideoForReport by remember { mutableStateOf<conectate.com.data.model.Video?>(null) }
    var videoPendingDeletion by remember { mutableStateOf<Video?>(null) }
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
        mutableFloatStateOf(playbackPreferences.getFloat("playback_speed", 1f)
            .takeIf { it in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f) } ?: 1f)
    }
    var scrollingLocked by rememberSaveable { mutableStateOf(false) }
    var showPlaybackOptions by remember { mutableStateOf(false) }
    var showWatchLater by remember { mutableStateOf(false) }
    var showVideoHistory by remember { mutableStateOf(false) }
    var showVideoCollections by remember { mutableStateOf(false) }
    var videoToAddToCollection by remember { mutableStateOf<String?>(null) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var selectedSound by remember { mutableStateOf<Video?>(null) }
    val markerStore = remember(context, currentUserId) { VideoPersonalMarkerStore(context, currentUserId) }
    var reviewLoop by remember(currentUserId) { mutableStateOf<VideoReviewLoop?>(null) }
    var sessionMinutes by remember {
        mutableIntStateOf(playbackPreferences.getInt("session_minutes", 0).takeIf { it in listOf(0, 5, 15, 30) } ?: 0)
    }
    val watchBudget = remember(currentUserId) { VideoWatchBudget() }
    var sessionLimitReached by remember(currentUserId) { mutableStateOf(false) }
    var optionsPositionMs by remember { mutableLongStateOf(0L) }
    var optionsDurationMs by remember { mutableLongStateOf(0L) }
    var personalMarkers by remember(currentUserId) { mutableStateOf<List<VideoPersonalMarker>>(emptyList()) }
    val playbackOverlayOpen = (videoShareCoordinator?.videoRequest != null) || commentsSheetOpen || showInternalShareDialog || showPlaybackOptions || showWatchLater || showVideoHistory || showVideoCollections ||
        showCreateMenu || selectedVideoForReport != null || videoPendingDeletion != null ||
        explanationVideo != null || notInterestedVideo != null || topicVideo != null ||
        selectedSound != null || sessionLimitReached || isSearchMode

    LaunchedEffect(algorithmActionMessage) {
        algorithmActionMessage?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeAlgorithmActionMessage()
        }
    }

    LaunchedEffect(algorithmUndoOffer?.id) {
        val offer = algorithmUndoOffer ?: return@LaunchedEffect
        val remainingMs = offer.expiresAtElapsedMs - android.os.SystemClock.elapsedRealtime()
        if (remainingMs > 0L) {
            val result = kotlinx.coroutines.withTimeoutOrNull(remainingMs) {
                algorithmSnackbarHostState.showSnackbar(
                    message = offer.message,
                    actionLabel = "Deshacer",
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == SnackbarResult.ActionPerformed) viewModel.undoAlgorithmPreference(offer.id)
        }
        viewModel.consumeAlgorithmUndoOffer(offer.id)
    }

    val currentVideos = when {
        isProfileFeed -> profileVideos
        isSearchMode -> searchResults
        selectedTab == 0 -> forYouVideos
        else -> followingVideos
    }
    var automaticFeedRecoveryAttempts by remember { mutableIntStateOf(0) }

    LaunchedEffect(forYouVideos.isNotEmpty(), isConnected) {
        if (forYouVideos.isNotEmpty() || !isConnected) automaticFeedRecoveryAttempts = 0
    }

    LaunchedEffect(
        isConnected,
        isProfileFeed,
        selectedTab,
        forYouVideos.isEmpty(),
        isLoading,
        feedError,
        authoritativeFeedAccepted,
    ) {
        if (
            !isProfileFeed &&
            !authoritativeFeedAccepted &&
            selectedTab == 0 &&
            FeedRecoveryPolicy.shouldRetry(
                isConnected = isConnected,
                hasContent = forYouVideos.isNotEmpty(),
                isLoading = isLoading,
                hasError = feedError != null,
                attempts = automaticFeedRecoveryAttempts,
            )
        ) {
            val attempt = automaticFeedRecoveryAttempts
            delay(FeedRecoveryPolicy.delayMillis(attempt))
            automaticFeedRecoveryAttempts = attempt + 1
            viewModel.recoverFeedAfterAppUpdate()
        }
    }
    // A signed discovery update can rotate the public backend while video
    // metadata remains byte-for-byte identical. Media URLs must follow it.
    val runtimeBackendBaseUrl by RuntimeBackendEndpoint.baseUrlFlow.collectAsState()
    val initialEmptyGraceActive by produceState(
        initialValue = false,
        currentVideos.isEmpty(),
        isLoading,
        isProfileFeed,
        isSearchMode,
        selectedTab,
    ) {
        val shouldWaitForRecovery = currentVideos.isEmpty() &&
            !isLoading &&
            !isProfileFeed &&
            !isSearchMode &&
            selectedTab == 0
        if (shouldWaitForRecovery) {
            value = true
            delay(INITIAL_EMPTY_STATE_GRACE_MS)
            value = false
        } else {
            value = false
        }
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
            includeTail = !isProfileFeed && !isSearchMode && selectedTab == 0,
        )
    }
    val pagerState = rememberPagerState(pageCount = { feedEntries.size })
    MaintainVideoFeedTailAppendAnchor(
        pagerState, currentVideos, feedEntries, forYouFeed.sessionId,
        enabled = !isProfileFeed && !isSearchMode && selectedTab == 0,
    )
    ContinueRecommendedFeedAtTail(pagerState, feedEntries, forYouFeed.sessionId, isLoading, feedError,
        enabled = !isProfileFeed && !isSearchMode && selectedTab == 0 && currentVideos.isNotEmpty(),
        onContinue = viewModel::continueRecommendedFeed)
    var initialVideoPositionApplied by rememberSaveable(initialVideoId, currentUserId) { mutableStateOf(false) }
    var entryCommentOpened by rememberSaveable(initialVideoId, focusCommentId, currentUserId) { mutableStateOf(false) }
    var entrySeekApplied by rememberSaveable(initialVideoId, initialPositionMs, initialClipEndMs, currentUserId) { mutableStateOf(false) }

    LaunchedEffect(initialVideoId, isProfileFeed, currentUserId) {
        if (!isProfileFeed) {
            if (initialVideoId != null || showBackButton) viewModel.ensureVideoInFeed(initialVideoId.orEmpty())
        }
    }

    PositionInitialVideoPage(initialVideoId, feedEntries, pagerState, initialVideoPositionApplied) {
        initialVideoPositionApplied = true
    }

    LaunchedEffect(profilePrependCount, feedEntries.size) {
        if (profilePrependCount == 0 || feedEntries.isEmpty()) return@LaunchedEffect
        pagerState.scrollToPage(
            (pagerState.currentPage + profilePrependCount).coerceIn(0, feedEntries.lastIndex)
        )
        viewModel.consumeProfilePrepend()
    }

    // Reset with the published page, not the request: key retention can otherwise
    // move the previous first video into the middle of the newly ordered list.
    LaunchedEffect(forYouFeed.sessionId, selectedTab) {
        if (!isProfileFeed && initialVideoId.isNullOrBlank() &&
            (selectedTab == 1 || forYouFeed.sessionId != null)
        ) {
            pagerState.requestScrollToPage(0)
        }
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

    LaunchedEffect(initialVideoPositionApplied, focusCommentId) {
        if (!initialVideoPositionApplied || entryCommentOpened || focusCommentId.isNullOrBlank()) return@LaunchedEffect
        val video = currentVideos.firstOrNull { it.id.equals(initialVideoId, ignoreCase = true) } ?: return@LaunchedEffect
        entryCommentOpened = true
        switchToCommentsPanel(video)
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
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        )
    }
    val settledVideo = (feedEntries.getOrNull(pagerState.settledPage) as? VideoFeedVideoEntry)?.video
    val settledVideoId = settledVideo?.id
    var playbackRequested by remember(currentUserId, settledVideoId) {
        mutableStateOf(settledVideoId != null && !cellularAutoplayBlocked)
    }
    var manuallyStartedVideoId by remember(currentUserId, settledVideoId) { mutableStateOf<String?>(null) }
    LaunchedEffect(settledVideoId, forYouFeed.sessionId, selectedTab, isProfileFeed, feedLifecycleState) {
        if (feedLifecycleState == androidx.lifecycle.Lifecycle.State.RESUMED && !isProfileFeed && selectedTab == 0) viewModel.onVideoVisible(settledVideoId, forYouFeed.sessionId)
    }
    val settledVideoIndex = remember(currentVideos, settledVideoId) {
        currentVideos.indexOfFirst { it.id == settledVideoId }
    }

    // Keep every feed fluid without holding extra players/decoders. The main
    // recommendation feed warms only the next item; profile feeds also warm the
    // previous one because people commonly move in both directions there.
    val videoSourceSignature = remember(currentVideos, runtimeBackendBaseUrl) {
        currentVideos.map { video -> video.id to video.videoUrl }
    }
    // A new video may autoplay on Wi-Fi; on mobile data it needs its own explicit tap
    // when the account preference disables autoplay. Reopening an overlay does not
    // discard a manual play request for the current video.
    LaunchedEffect(settledVideoId, cellularAutoplayBlocked) {
        reviewLoop = null
        if (cellularAutoplayBlocked && manuallyStartedVideoId != settledVideoId) {
            playbackRequested = false
        }
    }

    fun toggleVisiblePlayback() {
        if (cellularAutoplayBlocked && manuallyStartedVideoId != settledVideoId) {
            manuallyStartedVideoId = settledVideoId
            playbackRequested = true
        } else {
            playbackRequested = !playbackRequested
            if (!playbackRequested) manuallyStartedVideoId = null
        }
    }
    
    // We remember the player across recompositions. 
    val exoPlayer = remember {
        // Keep the device-specific startup and rebuffer protection.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                performanceProfile.minBufferMs,
                performanceProfile.maxBufferMs,
                performanceProfile.playBufferMs,
                performanceProfile.rebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
            
        val cacheDataSourceFactory = conectate.com.util.VideoCache.feedDataSourceFactory(context)

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

    val automaticPlaybackAttempts = remember { mutableMapOf<String, Int>() }
    var exhaustedPlaybackVideoId by remember { mutableStateOf<String?>(null) }
    val latestVideos = rememberUpdatedState(currentVideos)
    val latestRecoveryTarget = rememberUpdatedState(currentUserId to settledVideoId)
    val recoveryRunner = remember(navScope) { VideoPlaybackRecoveryRunner(navScope) }
    DisposableEffect(recoveryRunner, currentUserId, settledVideoId) {
        onDispose { recoveryRunner.cancel() }
    }
    val selectedWantsPlayback = shouldPlayFeedVideo(
        playbackRequested, settledVideoId, isCellular, videoAutoplayOnMobile, manuallyStartedVideoId,
    )
    val playbackAllowed = selectedWantsPlayback &&
        (!showBackButton || initialVideoPositionApplied) &&
        settledVideoIndex >= 0 &&
        !pagerState.isScrollInProgress &&
        !playbackOverlayOpen &&
        sidePanelState == VideoSidePanel.VIDEO &&
        lifecycleResumed &&
        !isSystemBusy
    val latestPlaybackAllowed = rememberUpdatedState(playbackAllowed)

    // Warm the next item only after the visible video owns the player and has
    // enough data buffered to survive a competing request. The former fixed
    // one-second delay started preloading at the exact moment low/balanced
    // devices had consumed their small startup buffer, which looked like an
    // automatic pause despite a healthy connection.
    val neighbourUrls = remember(currentVideos, settledVideoIndex, isProfileFeed) {
        if (settledVideoIndex !in currentVideos.indices) emptyList() else {
            val indexes = if (isProfileFeed) listOf(settledVideoIndex + 1, settledVideoIndex - 1)
                else listOf(settledVideoIndex + 1)
            indexes.mapNotNull { index -> currentVideos.getOrNull(index)?.videoUrl?.takeIf(String::isNotBlank) }
                .distinct()
        }
    }
    val prefetchAllowed = playbackAllowed && shouldPrefetchFeedVideo(
        isConnected, dataSaverEnabled || cellularAutoplayBlocked, lifecycleResumed, playbackOverlayOpen,
    )
    LaunchedEffect(prefetchAllowed, currentUserId, settledVideoId, neighbourUrls, runtimeBackendBaseUrl, exoPlayer) {
        if (!prefetchAllowed || settledVideoId == null || neighbourUrls.isEmpty()) return@LaunchedEffect

        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val visibleVideoProtected = hasProtectedVisibleVideoBuffer(
                currentMediaId = exoPlayer.currentMediaItem?.mediaId,
                selectedVideoId = settledVideoId,
                isReady = exoPlayer.playbackState == Player.STATE_READY,
                isPlaying = exoPlayer.isPlaying,
                durationMs = exoPlayer.duration,
                bufferedPositionMs = exoPlayer.bufferedPosition,
                currentPositionMs = exoPlayer.currentPosition,
            )
            if (visibleVideoProtected) break
            delay(250L)
        }

        if (
            pagerState.isScrollInProgress ||
            exoPlayer.currentMediaItem?.mediaId != settledVideoId ||
            !exoPlayer.isPlaying
        ) return@LaunchedEffect

        val byteBudget = when (performanceProfile.tier) {
            conectate.com.util.VideoDeviceTier.LOW -> 384L * 1024L
            conectate.com.util.VideoDeviceTier.BALANCED -> 768L * 1024L
            conectate.com.util.VideoDeviceTier.HIGH -> 1L * 1024L * 1024L
        }
        neighbourUrls.forEach { url ->
            runCatching {
                conectate.com.util.VideoCache.preloadStart(
                    context = context.applicationContext,
                    url = url,
                    byteCount = byteBudget,
                    timeoutMs = 1_500L,
                )
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    fun schedulePlaybackRecovery(
        videoId: String,
        delayMs: Long,
        refreshEndpoint: Boolean,
        evictCache: Boolean = false,
    ) {
        exhaustedPlaybackVideoId = null
        val target = latestRecoveryTarget.value
        val failedUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
        recoveryRunner.schedule(
            delayMs = delayMs,
            isCurrent = {
                latestRecoveryTarget.value == target && target.second == videoId &&
                    exoPlayer.currentMediaItem?.mediaId == videoId &&
                    latestVideos.value.any { it.id == videoId }
            },
            resolve = {
                if (evictCache) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        conectate.com.util.VideoCache.removeResource(context.applicationContext, failedUrl)
                    }
                }
                if (refreshEndpoint) RuntimeBackendEndpoint.refreshAfterEndpointFailure(failedUrl)
                val refreshed = if (refreshEndpoint) viewModel.refreshVideoForPlayback(videoId) else null
                refreshed ?: latestVideos.value.firstOrNull { it.id == videoId }
            },
            apply = { video ->
                // Resolve the index and position after the suspended work:
                // pagination may have changed the playlist in the meantime.
                val targetIndex = exoPlayer.currentMediaItemIndex
                val resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val resolvedUrl = UbuntuMedia.resolveUrl(video.videoUrl) ?: video.videoUrl
                viewModel.updateVideoPlaybackSource(videoId, resolvedUrl)
                exoPlayer.replaceMediaItem(targetIndex, buildVideoMediaItem(videoId, resolvedUrl))
                exoPlayer.seekTo(targetIndex, resumePositionMs)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = latestPlaybackAllowed.value
                if (latestPlaybackAllowed.value) exoPlayer.play()
            },
            onFailure = { exhaustedPlaybackVideoId = videoId },
        )
    }
    
    // Keep the pager synchronized when ExoPlayer advances to the next item and
    // recover bounded transport failures without retrying corrupt codecs forever.
    var renderedVideoId by remember(exoPlayer) { mutableStateOf<String?>(null) }
    val latestFeedEntries by rememberUpdatedState(feedEntries)
    val latestReviewLoop by rememberUpdatedState(reviewLoop)
    DisposableEffect(exoPlayer, autoAdvanceEnabled, scrollingLocked) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                renderedVideoId = null
                exhaustedPlaybackVideoId = null
                latestReviewLoop?.takeIf {
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && it.videoId == mediaItem?.mediaId
                }?.let { exoPlayer.seekTo(it.startMs) }
                val nextPage = latestFeedEntries.indexOfFirst { entry ->
                    entry is VideoFeedVideoEntry && entry.video.id == mediaItem?.mediaId
                }
                if (
                    autoAdvanceEnabled && !scrollingLocked &&
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    nextPage >= 0 &&
                    nextPage != pagerState.currentPage
                ) {
                    navScope.launch { pagerState.animateScrollToPage(nextPage) }
                }
            }

            override fun onRenderedFirstFrame() {
                renderedVideoId = exoPlayer.currentMediaItem?.mediaId
                if (conectate.com.BuildConfig.DEBUG) android.util.Log.d("FeedPlaybackAudit", "first_frame:$renderedVideoId")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !entrySeekApplied &&
                    exoPlayer.currentMediaItem?.mediaId.equals(initialVideoId, ignoreCase = true) && exoPlayer.duration > 0L) {
                    val entryVideo = latestVideos.value.firstOrNull { it.id.equals(initialVideoId, ignoreCase = true) }
                    if (entryVideo != null) {
                        val entry = resolveVideoEntryPlayback(entryVideo, initialPositionMs, initialClipEndMs, exoPlayer.duration)
                        entrySeekApplied = true
                        reviewLoop = entry.loop
                        exoPlayer.seekTo(entry.positionMs)
                        if (entry.rejectedClip) conectate.com.presentation.common.AppFeedbackBus.show(
                            "Este fragmento ya no está permitido o quedó fuera del video.")
                    }
                }
                if (conectate.com.BuildConfig.DEBUG) android.util.Log.d("FeedPlaybackAudit", "state=$playbackState requested=${exoPlayer.playWhenReady} playing=${exoPlayer.isPlaying} suppression=${exoPlayer.playbackSuppressionReason}")
                if (playbackState == Player.STATE_READY) {
                    exhaustedPlaybackVideoId = null
                    // READY can last only a frame before the source fails
                    // again. Do not replenish its budget on this callback.
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (conectate.com.BuildConfig.DEBUG) android.util.Log.d("FeedPlaybackAudit", "error_code=${error.errorCode}")
                val videoId = exoPlayer.currentMediaItem?.mediaId
                    ?.takeIf { it.isNotBlank() }
                    ?: return
                val httpStatusCode = error.causeSequence()
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                    ?.responseCode
                val decision = decideVideoPlaybackRecovery(
                    errorCode = error.errorCode,
                    httpStatusCode = httpStatusCode,
                    previousAttempts = automaticPlaybackAttempts[videoId] ?: 0,
                )
                when (decision) {
                    is VideoPlaybackRecoveryDecision.Retry -> {
                        automaticPlaybackAttempts[videoId] = decision.attempt
                        schedulePlaybackRecovery(
                            videoId = videoId,
                            delayMs = decision.delayMs,
                            refreshEndpoint = decision.refreshEndpoint,
                            evictCache = decision.evictCache,
                        )
                    }
                    VideoPlaybackRecoveryDecision.ShowError -> {
                        exhaustedPlaybackVideoId = videoId
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(autoAdvanceEnabled, playbackSpeed, reviewLoop, scrollingLocked, cellularAutoplayBlocked) {
        exoPlayer.repeatMode = if (autoAdvanceEnabled && !cellularAutoplayBlocked && !scrollingLocked && reviewLoop == null)
            Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
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
                viewModel.onFeedResumed()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.onFeedBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onFeedBackgrounded()
            exoPlayer.pause()
        }
    }

    LaunchedEffect(viewModel) { viewModel.onFeedResumed() }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    
    // Incremented whenever the player playlist has been reconciled. Playback
    // observes this state so it cannot race the first playlist installation.
    var playerPlaylistGeneration by remember { mutableIntStateOf(0) }

    // 🚀 RESTORED & STABLE: Synchronize Playlist when videos change
    LaunchedEffect(videoSourceSignature, runtimeBackendBaseUrl) {
        automaticPlaybackAttempts.keys.retainAll(videoSourceSignature.mapTo(mutableSetOf()) { it.first })
        if (videoSourceSignature.isNotEmpty()) {
            // URL rewriting and stable-key normalization scale with the feed.
            // Keep that work off the UI thread when pagination adds videos.
            val desiredPlaylist = withContext(Dispatchers.Default) {
                videoSourceSignature.map { (videoId, sourceUrl) ->
                    val resolvedUrl = UbuntuMedia.resolveUrl(sourceUrl) ?: sourceUrl
                    val stableUri = conectate.com.data.localfallback.UbuntuStorageUrl.stableCacheKey(resolvedUrl)
                        ?: resolvedUrl
                    VideoPlaylistEntry(videoId, stableUri, resolvedUrl)
                }
            }

            val existingPlaylist = (0 until exoPlayer.mediaItemCount).map { index ->
                val item = exoPlayer.getMediaItemAt(index)
                val config = item.localConfiguration
                val uri = config?.uri?.toString().orEmpty()
                val stableUri = config?.customCacheKey ?: uri
                VideoPlaylistEntry(item.mediaId, stableUri, uri)
            }
            val selectedVideoId = (feedEntries.getOrNull(pagerState.settledPage) as? VideoFeedVideoEntry)
                ?.video
                ?.id
            when (
                val update = reconcileVideoPlaylist(
                    existing = existingPlaylist,
                    desired = desiredPlaylist,
                    settledVideoId = selectedVideoId,
                    currentMediaId = exoPlayer.currentMediaItem?.mediaId,
                    currentPositionMs = exoPlayer.currentPosition,
                )
            ) {
                VideoPlaylistUpdate.None -> Unit
                is VideoPlaylistUpdate.Append -> {
                    // Pagination must not detach the currently rendered frame
                    // or restart its decoder. Media3 can append in place.
                    exoPlayer.addMediaItems(desiredPlaylist.drop(update.fromIndex).map { entry ->
                        buildVideoMediaItem(entry.mediaId, entry.playbackUri)
                    })
                    if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
                    playerPlaylistGeneration += 1
                }
                is VideoPlaylistUpdate.ReplaceTail -> {
                    // Replace only upcoming items. The active source, decoder,
                    // buffered bytes and rendered frame remain attached.
                    exoPlayer.replaceMediaItems(update.fromIndex, exoPlayer.mediaItemCount,
                        desiredPlaylist.drop(update.fromIndex).map { buildVideoMediaItem(it.mediaId, it.playbackUri) })
                    playerPlaylistGeneration += 1
                }
                is VideoPlaylistUpdate.Replace -> {
                    exoPlayer.setMediaItems(
                        desiredPlaylist.map { entry ->
                            buildVideoMediaItem(entry.mediaId, entry.playbackUri)
                        },
                        update.targetIndex,
                        update.startPositionMs,
                    )
                    val targetId = desiredPlaylist[update.targetIndex].mediaId
                    exoPlayer.playWhenReady = latestPlaybackAllowed.value &&
                        targetId.equals(latestRecoveryTarget.value.second, ignoreCase = true)
                    exoPlayer.prepare()
                    playerPlaylistGeneration += 1
                }
            }
        } else {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            playerPlaylistGeneration += 1
        }
    }

    // A page transition resets side panels, but list/stat refreshes do not.
    LaunchedEffect(pagerState.settledPage, settledVideoId) {
        if (sidePanelState != VideoSidePanel.VIDEO) {
            switchToVideoPanel()
        }
    }

    LaunchedEffect(settledVideoId, currentVideos.size, isProfileFeed, isLoading, feedError) {
        // A two-item provisional page can settle while the initial request is
        // still loading. Retry admission once that request completes, but never
        // spin on a failed network page.
        if (settledVideoId == null || settledVideoIndex < 0 || isLoading || feedError != null) return@LaunchedEffect

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
    LaunchedEffect(playbackAllowed, settledVideoId, settledVideoIndex, playerPlaylistGeneration) {
        val selectedPlaylistId = settledVideoIndex
            .takeIf { it in 0 until exoPlayer.mediaItemCount }
            ?.let { index -> exoPlayer.getMediaItemAt(index).mediaId }
        val playlistMatchesSelection = selectedPlaylistId?.equals(
            settledVideoId,
            ignoreCase = true,
        ) == true

        if (conectate.com.BuildConfig.DEBUG) android.util.Log.d("FeedPlaybackAudit", "gate allowed=$playbackAllowed playlist=$playlistMatchesSelection count=${exoPlayer.mediaItemCount} selected=$settledVideoIndex overlay=$playbackOverlayOpen resumed=$lifecycleResumed requested=$playbackRequested")

        // Reconciliation and playback run in independent coroutines. Never
        // seek by an index that still belongs to the previous playlist.
        if (!playbackAllowed || !playlistMatchesSelection) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.currentMediaItemIndex != settledVideoIndex) {
                exoPlayer.seekTo(settledVideoIndex, 0L)
            }
            exoPlayer.play()
        }
    }

    val currentSessionMinutes by rememberUpdatedState(sessionMinutes)
    LaunchedEffect(settledVideoId, settledVideoIndex, exoPlayer, watchBudget) {
        var lastHistorySampleAtMs = 0L
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val isActuallyPlaying = settledVideoId != null &&
                    settledVideoIndex >= 0 &&
                    exoPlayer.currentMediaItemIndex == settledVideoIndex &&
                    exoPlayer.currentMediaItem?.mediaId == settledVideoId &&
                    latestPlaybackAllowed.value &&
                    exoPlayer.isPlaying
                val watchedMs = watchBudget.sample(android.os.SystemClock.elapsedRealtime(), isActuallyPlaying)
                if (isActuallyPlaying && currentSessionMinutes > 0 && watchedMs >= currentSessionMinutes * 60_000L) {
                    playbackRequested = false
                    sessionLimitReached = true
                    exoPlayer.pause()
                }
                latestReviewLoop?.takeIf { it.videoId == settledVideoId && isActuallyPlaying }?.let { loop ->
                    if (exoPlayer.currentPosition >= loop.endMs || exoPlayer.currentPosition < loop.startMs) {
                        exoPlayer.seekTo(loop.startMs)
                    }
                }
                viewModel.onVideoPlaybackSample(
                    videoId = settledVideoId,
                    isActuallyPlaying = isActuallyPlaying,
                    elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                )
                val sampleAtMs = android.os.SystemClock.elapsedRealtime()
                if (isActuallyPlaying && sampleAtMs - lastHistorySampleAtMs >= 5_000L) {
                    historyViewModel.recordProgress(
                        settledVideoId, exoPlayer.currentPosition, exoPlayer.duration)
                    lastHistorySampleAtMs = sampleAtMs
                }
                delay(250L)
            }
        } finally {
            if (exoPlayer.currentMediaItem?.mediaId == settledVideoId) historyViewModel.recordProgress(
                settledVideoId, exoPlayer.currentPosition, exoPlayer.duration)
            viewModel.onVideoPlaybackSample(
                videoId = null,
                isActuallyPlaying = false,
                elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
            )
        }
    }

    LaunchedEffect(showPlaybackOptions, settledVideoId, markerStore) {
        if (showPlaybackOptions) {
            optionsPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            optionsDurationMs = exoPlayer.duration.coerceAtLeast(0L)
            personalMarkers = settledVideoId?.let(markerStore::readAll).orEmpty()
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
            isPosting = commentPosting,
            focusCommentId = focusCommentId.takeIf { videoForComments.id.equals(initialVideoId, ignoreCase = true) },
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
            onMentionClick = { handle ->
                navScope.launch {
                    val user = viewModel.userRepository.resolvePublicMention(handle)
                    if (user != null) {
                        setSidePanelIndex(2)
                        onNavigateToProfile(user.id)
                    } else conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo identificar esta mención.")
                }
            },
            onSwipeForward = { onHorizontalSwipe(videoForComments, forward = true) },
            onSwipeBackward = { onHorizontalSwipe(videoForComments, forward = false) },
            videoCreatorId = videoForComments.userId,
            replyingTo = viewModel.replyingTo.collectAsState().value,
            onReplyClick = { comment -> viewModel.onReplyClick(comment) },
            cancelReply = { viewModel.cancelReply() }
        )
    }

    LaunchedEffect(showInternalShareDialog, selectedVideoForShare?.id) {
        if (showInternalShareDialog) {
            selectedVideoForShare?.let { videoShareCoordinator?.openVideo(it) }
            showInternalShareDialog = false
            selectedVideoForShare = null
        }
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
                        videoTopicText = selected.tags.firstNotNullOfOrNull {
                            VideoAlgorithmFeedbackPolicy.topicKey(it)
                        }.orEmpty()
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
            videoId = targetVideo.id,
            alreadyReported = reportedVideoIds.contains(targetVideo.id),
            onDismiss = { selectedVideoForReport = null },
            onSubmit = { reason, details ->
                viewModel.submitVideoReport(targetVideo.id, reason, details)
            }
        )
    }

    videoPendingDeletion?.let { targetVideo ->
        AlertDialog(
            onDismissRequest = { videoPendingDeletion = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("¿Eliminar este video?") },
            text = { Text("Se quitará de tu perfil y de todos los feeds. Esta acción no se puede deshacer desde la aplicación.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        videoPendingDeletion = null
                        viewModel.deleteVideo(targetVideo)
                    },
                ) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { videoPendingDeletion = null }) { Text("Cancelar") }
            },
        )
    }

    selectedSound?.let { soundVideo ->
        val soundSources = remember(soundVideo.soundUrl, soundVideo.videoUrl) {
            listOfNotNull(soundVideo.soundUrl?.takeIf { it.isNotBlank() },
                soundVideo.videoUrl.takeIf { it.isNotBlank() })
                .map { UbuntuMedia.resolveUrl(it) ?: it }
                .filter { it.startsWith("https://") }
                .distinct()
        }
        val resolvedSource = soundSources.firstOrNull().orEmpty()
        var selectedSource by remember(soundSources) { mutableStateOf(resolvedSource) }
        LaunchedEffect(soundVideo.soundId ?: soundVideo.id) {
            viewModel.loadSoundVideos(soundVideo)
        }
        ModalBottomSheet(
            onDismissRequest = {
                selectedSound = null
                viewModel.clearSoundVideos()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Información del sonido", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(58.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(28.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            soundVideo.soundTitle
                                ?: "Sonido original — ${soundVideo.userName.ifBlank { "Creador de Novvin" }}",
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            soundVideo.soundArtist ?: soundVideo.userName.ifBlank { "Creador de Novvin" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    SoundPreviewButton(soundSources, onPlayingSource = { selectedSource = it })
                }
                if (soundSources.isEmpty()) {
                    Text("Este sonido no está disponible para escucharlo o reutilizarlo ahora.",
                        color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "El editor extraerá la pista de audio del video y conservará la referencia al creador original.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Videos con este sonido (${soundVideos.size})",
                    fontWeight = FontWeight.SemiBold,
                )
                if (isSoundVideosLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    soundVideosError?.let { message ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { viewModel.loadSoundVideos(soundVideo) }) { Text("Reintentar") }
                        }
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 4.dp),
                    ) {
                        lazyItems(soundVideos, key = { it.id }) { relatedVideo ->
                            Column(
                                modifier = Modifier
                                    .width(112.dp)
                                    .clickable {
                                        exoPlayer.pause()
                                        selectedSound = null
                                        viewModel.clearSoundVideos()
                                        navController.navigate(Screen.VideoPlayer.createRoute(relatedVideo.id))
                                    },
                            ) {
                                AsyncImage(
                                    model = relatedVideo.thumbnailUrl.ifBlank { relatedVideo.videoUrl },
                                    contentDescription = relatedVideo.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(154.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Text(
                                    relatedVideo.title.ifBlank { "Video de Novvin" },
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        val selection = VideoSoundSelection(
                            soundId = soundVideo.soundId ?: soundVideo.id,
                            sourceVideoId = soundVideo.soundSourceVideoId ?: soundVideo.id,
                            sourceVideoUrl = selectedSource,
                            title = soundVideo.soundTitle
                                ?: "Sonido original — ${soundVideo.userName.ifBlank { "Novvin" }}",
                            artist = soundVideo.soundArtist ?: soundVideo.userName.ifBlank { "Novvin" },
                        )
                        exoPlayer.pause()
                        selectedSound = null
                        viewModel.clearSoundVideos()
                        onNavigateToUpload(selection)
                    },
                    enabled = selectedSource.startsWith("https://"),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.VideoCall, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Usar sonido")
                }
            }
        }
    }

    if (sessionLimitReached) {
        AlertDialog(
            onDismissRequest = {
                watchBudget.reset()
                sessionLimitReached = false
                playbackRequested = false
            },
            title = { Text("Llegaste a tu tiempo de video") },
            text = { Text("Has visto $sessionMinutes minutos. Tu video queda en pausa; puedes descansar o iniciar otro bloque.") },
            confirmButton = {
                TextButton(onClick = {
                    watchBudget.reset()
                    sessionLimitReached = false
                    playbackRequested = true
                    manuallyStartedVideoId = settledVideoId
                }) { Text("Otro bloque") }
            },
            dismissButton = {
                TextButton(onClick = {
                    watchBudget.reset()
                    sessionLimitReached = false
                    playbackRequested = false
                }) { Text("Quedar en pausa") }
            }
        )
    }

    if (showWatchLater) {
        VideoWatchLaterSheet(
            viewModel = watchLaterViewModel,
            onDismiss = { showWatchLater = false },
            onOpenVideo = { videoId ->
                showWatchLater = false
                exoPlayer.pause()
                navController.navigate(Screen.VideoPlayer.createRoute(videoId))
            },
        )
    }

    if (showVideoHistory) {
        VideoHistorySheet(
            viewModel = historyViewModel,
            onDismiss = { showVideoHistory = false },
            onContinue = { videoId, positionMs ->
                showVideoHistory = false
                exoPlayer.pause()
                navController.navigate(Screen.VideoPlayer.createRoute(videoId, resumeMs = positionMs))
            },
        )
    }

    if (showVideoCollections) {
        VideoCollectionsSheet(
            viewModel = collectionsViewModel,
            videoToAdd = videoToAddToCollection,
            onDismiss = {
                showVideoCollections = false
                videoToAddToCollection = null
            },
            onOpenVideo = { videoId ->
                showVideoCollections = false
                videoToAddToCollection = null
                exoPlayer.pause()
                navController.navigate(Screen.VideoPlayer.createRoute(videoId))
            },
        )
    }

    if (showPlaybackOptions) {
        ModalBottomSheet(onDismissRequest = { showPlaybackOptions = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Reproducción", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Velocidad", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    maxItemsInEachRow = 3,
                ) {
                    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    speeds.forEach { speed ->
                        FilterChip(
                            selected = playbackSpeed == speed,
                            onClick = {
                                playbackSpeed = speed
                                playbackPreferences.edit().putFloat("playback_speed", speed).apply()
                            },
                            label = { Text("${speed}x") },
                            modifier = Modifier.widthIn(min = 84.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Bloquear deslizamiento vertical", modifier = Modifier.weight(1f))
                    Switch(checked = scrollingLocked, onCheckedChange = { scrollingLocked = it })
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
                VideoLearningTools(
                    accountId = currentUserId,
                    videoId = settledVideoId,
                    positionMs = optionsPositionMs,
                    durationMs = optionsDurationMs,
                    canSaveMarker = !currentUserId.isNullOrBlank(),
                    markers = personalMarkers,
                    reviewLoop = reviewLoop,
                    sessionMinutes = sessionMinutes,
                    onSaveMarker = { note ->
                        settledVideoId?.let { id ->
                            if (markerStore.save(id, optionsPositionMs, note) != null) {
                                personalMarkers = markerStore.readAll(id)
                                conectate.com.presentation.common.AppFeedbackBus.showSuccess("Punto privado guardado")
                            } else {
                                conectate.com.presentation.common.AppFeedbackBus.showError(
                                    "No se pudo guardar el punto. Borra uno si llegaste al límite."
                                )
                            }
                        }
                    },
                    onReturnToMarker = { markerPositionMs ->
                        if (exoPlayer.currentMediaItem?.mediaId == settledVideoId) {
                            reviewLoop = null
                            exoPlayer.seekTo(markerPositionMs.coerceIn(0L, (optionsDurationMs - 1L).coerceAtLeast(0L)))
                            playbackRequested = true
                            manuallyStartedVideoId = settledVideoId
                            showPlaybackOptions = false
                        }
                    },
                    onUpdateMarker = { markerPositionMs, note ->
                        settledVideoId?.let { id ->
                            if (markerStore.updateNote(id, markerPositionMs, note)) {
                                personalMarkers = markerStore.readAll(id)
                            }
                        }
                    },
                    onRemoveMarker = { markerPositionMs ->
                        settledVideoId?.let { id ->
                            if (markerStore.remove(id, markerPositionMs)) {
                                personalMarkers = markerStore.readAll(id)
                            }
                        }
                    },
                    onToggleLoop = {
                        reviewLoop = if (reviewLoop != null) null else settledVideoId?.let { id ->
                            VideoReviewLoop.at(id, optionsPositionMs, optionsDurationMs)
                        }
                        playbackRequested = true
                        manuallyStartedVideoId = settledVideoId
                        showPlaybackOptions = false
                    },
                    onSessionMinutesChange = { minutes ->
                        sessionMinutes = minutes
                        watchBudget.reset()
                        playbackPreferences.edit().putInt("session_minutes", minutes).apply()
                    }
                )
                val recommendationReasons = settledVideo?.let(viewModel::explainVideo).orEmpty()
                if (recommendationReasons.isNotEmpty()) {
                    HorizontalDivider()
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
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(algorithmSnackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().pullToRefresh(
            isRefreshing = isRefreshing,
            state = pullRefreshState,
            enabled = !isProfileFeed && !isSearchMode,
            onRefresh = { if (!isRefreshing) viewModel.refreshFeed() },
        )) {
            
            if (showBackButton && !initialVideoPositionApplied) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (focusedVideoError == null) {
                        CircularProgressIndicator(color = Color.White)
                        Text("Abriendo video…", color = Color.White)
                    } else {
                        Text(focusedVideoError.orEmpty(), color = Color.White, textAlign = TextAlign.Center)
                        Button(onClick = { initialVideoId?.let(viewModel::ensureVideoInFeed) }) { Text("Reintentar") }
                    }
                }
            } else if (isProfileFeed && currentVideos.isEmpty() && profileFeedError != null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(profileFeedError.orEmpty(), color = Color.White)
                    Button(onClick = viewModel::refreshFeed) { Text("Reintentar") }
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
            } else if (isSearchMode && searchQuery.trim().length < 2) {
                Column(Modifier.fillMaxSize().padding(top = 76.dp, bottom = 80.dp).verticalScroll(rememberScrollState())) {
                    Text("Descubre temas", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                    Text("Basados en videos públicos de este mes de tu feed", color = Color.White.copy(alpha = .7f), modifier = Modifier.padding(horizontal = 16.dp))
                    searchTopics.forEach { topic ->
                        TextButton(onClick = { viewModel.onSearchQueryChanged(topic.query) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
                            Text(topic.query, color = Color.White, modifier = Modifier.weight(1f).padding(12.dp))
                            Text(topic.label, color = Color.White.copy(alpha = .6f))
                        }
                    }
                    if (searchTopics.isEmpty()) Text("Busca por tema, etiqueta o comentario", color = Color.White, modifier = Modifier.padding(16.dp))
                }
            } else if ((isLoading || initialEmptyGraceActive || isSearching) && currentVideos.isEmpty()) {
                 Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else if (currentVideos.isEmpty() && !isProfileFeed && !isSearchMode && selectedTab == 0 && noMatchingRecommendations) {
                VideoFeedNoMatchesPage(
                    onReviewPreferences = { navController.navigate(Screen.SettingsCategory.createRoute("algorithm")) },
                    onRefresh = viewModel::refreshFeed,
                )
            } else if (currentVideos.isEmpty() && !isProfileFeed && !isSearchMode && selectedTab == 0 && authoritativeFeedAccepted) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = 32.dp),
                    ) {
                        Text(
                            text = feedError ?: if (authoritativeContinuationPending)
                                "Podemos seguir buscando videos para ti."
                            else "Todavía no hay videos disponibles para ti. Actualiza para comprobar novedades.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        if (authoritativeContinuationPending || feedError != null) {
                            Button(onClick = { viewModel.loadMoreVideos() }, enabled = !isLoading) {
                                Text(if (feedError != null) "Reintentar" else "Continuar")
                            }
                        }
                    }
                }
            } else if (feedEntries.isEmpty() && feedError != null) {
                 Box(
                     modifier = Modifier.fillMaxSize(),
                     contentAlignment = Alignment.Center
                 ) {
                     Column(
                         horizontalAlignment = Alignment.CenterHorizontally,
                         verticalArrangement = Arrangement.spacedBy(12.dp),
                         modifier = Modifier.padding(horizontal = 32.dp)
                     ) {
                         Text(
                             feedError ?: "No pudimos actualizar tus videos. Intenta nuevamente.",
                             color = Color.White,
                             textAlign = TextAlign.Center,
                             fontWeight = FontWeight.Bold
                         )
                         Button(onClick = {
                             automaticFeedRecoveryAttempts = 0
                             connectivityViewModel.retrySync("video_feed_error_retry")
                             viewModel.refreshFeed()
                         }) {
                             Icon(Icons.Default.Refresh, contentDescription = null)
                             Spacer(Modifier.width(8.dp))
                             Text("Reintentar")
                         }
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
                                     else if (isSearchMode) searchError ?: "No se encontraron videos para \"$searchQuery\""
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
                if (!isProfileFeed && !isSearchMode && selectedTab == 0 && authoritativeFeedAccepted && feedError != null) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f).padding(bottom = 92.dp, start = 16.dp, end = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                            Text("No pudimos cargar más videos", modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.loadMoreVideos() }, enabled = !isLoading) { Text("Reintentar") }
                        }
                    }
                }
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
                    userScrollEnabled = !scrollingLocked,
                    beyondViewportPageCount = if (dataSaverEnabled) 0 else performanceProfile.feedBeyondBoundsPageCount,
                    key = { page -> feedEntries.getOrNull(page)?.stableKey ?: "feed:$page" }
                ) { page ->
                    when (val entry = feedEntries.getOrNull(page)) {
                        is VideoFeedVideoEntry -> {
                            val video = entry.video
                            val pageVisible by remember(pagerState, page) {
                                derivedStateOf { pagerState.layoutInfo.visiblePagesInfo.any { it.index == page } }
                            }
                            VideoItem(
                            video = video,
                            exoPlayer = exoPlayer, // Pass the SINGLE player
                            renderedVideoId = renderedVideoId,
                            isSelected = pagerState.settledPage == page && !isSystemBusy,
                            showThumbnail = pageVisible || pagerState.settledPage == page,
                            playbackRequested = selectedWantsPlayback,
                            showCellularPlayHint = cellularAutoplayBlocked && manuallyStartedVideoId != video.id,
                            onTogglePlayback = {
                                if (pagerState.settledPage == page) {
                                    toggleVisiblePlayback()
                                }
                            },
                            onLikeClick = { viewModel.toggleLike(video) },
                            onCommentClick = { 
                                switchToCommentsPanel(video)
                            },
                            onFavoriteClick = { viewModel.toggleFavorite(video) },
                            onWatchLaterSave = { watchLaterViewModel.save(video.id) },
                            onAddToCollection = {
                                exoPlayer.pause()
                                videoToAddToCollection = video.id
                                showVideoCollections = true
                            },
                            onLongPress = { showPlaybackOptions = true },
                            onProfileClick = { userId ->
                                setSidePanelIndex(2)
                                onNavigateToProfile(userId)
                            },
                            onMentionClick = { handle ->
                                navScope.launch {
                                    val user = viewModel.userRepository.resolvePublicMention(handle)
                                    if (user != null) onNavigateToProfile(user.id)
                                    else conectate.com.presentation.common.AppFeedbackBus.showError(
                                        "Este perfil ya no está disponible."
                                    )
                                }
                            },
                            onSwipeRight = {
                                onHorizontalSwipe(video, forward = false)
                            },
                            onSwipeLeft = {
                                onHorizontalSwipe(video, forward = true)
                            },
                            currentUserId = currentUserId,
                            followedUsers = followedUsers,
                            pendingFollowUsers = pendingFollowUsers,
                            reduceMotion = reduceMotionEnabled,
                            isDownloading = videoDownloadsInProgress.contains(video.id.trim().lowercase()),
                            getStatsFlow = { viewModel.getVideoStats(it) },
                            onShareInternalClick = { videoToShare ->
                                selectedVideoForShare = videoToShare
                                showInternalShareDialog = true
                            },
                            onToggleProfileShare = { viewModel.toggleShare(video) },
                            onFollowClick = viewModel::followUser,
                            onDownloadClick = requestVideoDownload,
                            onDeleteVideo = { videoToDelete -> videoPendingDeletion = videoToDelete },
                            isAlreadyReported = reportedVideoIds.contains(video.id),
                            onWhyThisVideo = { explainedVideo -> explanationVideo = explainedVideo },
                            onNotInterested = { dismissedVideo -> notInterestedVideo = dismissedVideo },
                            onReportClick = { reportedVideo ->
                                viewModel.ensureVideoReportStatus(reportedVideo.id)
                                selectedVideoForReport = reportedVideo
                            },
                            onSoundClick = { soundVideo -> selectedSound = soundVideo },
                            onRelatedSearch = { query ->
                                navController.navigate(Screen.SearchUsers.createRoute(query)) { launchSingleTop = true }
                            },
                            hasPlaybackError = exhaustedPlaybackVideoId == video.id,
                            onRetryPlayback = {
                                automaticPlaybackAttempts[video.id] = 0
                                schedulePlaybackRecovery(
                                    videoId = video.id,
                                    delayMs = 0L,
                                    refreshEndpoint = true,
                                )
                            },
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
                        VideoFeedTailEntry -> VideoFeedTailPage(
                            exhausted = recommendedFeedExhausted,
                            loading = isLoading,
                            error = feedError,
                            onContinue = { viewModel.continueRecommendedFeed() },
                            onRefresh = { viewModel.continueRecommendedFeed() },
                            onSearch = { viewModel.toggleSearchMode(true) },
                        )
                        null -> Unit
                    }
                }
                }
            }
            
            if (!isProfileFeed && !isSearchMode) {
                PullToRefreshDefaults.Indicator(
                    state = pullRefreshState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
                )
            }

            // Top Overlay (Search / Amigos / Recomendado)
            if (isProfileFeed || showBackButton) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = if (isProfileFeed) "Videos del perfil" else "Video",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            } else if (!isSearchMode) {
                // LIVE, feed tabs, search and create share a single responsive toolbar.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(start = 8.dp, end = 72.dp),
                ) {
                    val compactVideoToolbar = maxWidth < 300.dp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    IconButton(
                        onClick = {
                            exoPlayer.pause()
                            onNavigateToLiveDiscovery()
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.LiveTv,
                            contentDescription = "Ver directos",
                            tint = Color(0xFFFF365D),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Amigos",
                            color = if (selectedTab == 1) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clickable { viewModel.onTabSelected(1) }
                        )
                        Text(
                            text = "|",
                            color = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        Text(
                            text = if (compactVideoToolbar) "Para ti" else "Recomendado",
                            color = if (selectedTab == 0) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clickable { viewModel.onTabSelected(0) }
                        )
                    }
                    IconButton(onClick = { viewModel.toggleSearchMode(true) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Search, contentDescription = "Buscar videos", tint = Color.White)
                    }
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
                            .heightIn(min = 52.dp)
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
            
            // Keep the create action on the same toolbar row as LIVE, tabs and search.
            if (!isSearchMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp, top = 0.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
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
                            contentDescription = "Crear o abrir biblioteca de Videos",
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
                            onNavigateToUpload(null)
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
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Mi biblioteca",
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    DropdownMenuItem(
                        text = { Text("Ver después") },
                        leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                        onClick = {
                            showCreateMenu = false
                            exoPlayer.pause()
                            showWatchLater = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Historial de videos") },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = {
                            showCreateMenu = false
                            exoPlayer.pause()
                            showVideoHistory = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Mis colecciones") },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        onClick = {
                            showCreateMenu = false
                            videoToAddToCollection = null
                            exoPlayer.pause()
                            showVideoCollections = true
                        },
                    )
                    }
                }
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
                conectate.com.presentation.components.CircularProfileImage(ownerId = stream.hostUserId, 
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
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
fun VideoItem(
    video: Video, 
    exoPlayer: ExoPlayer, 
    isSelected: Boolean,
    showThumbnail: Boolean,
    renderedVideoId: String?,
    playbackRequested: Boolean,
    showCellularPlayHint: Boolean,
    onTogglePlayback: () -> Unit,
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onWatchLaterSave: () -> Unit,
    onAddToCollection: () -> Unit,
    onLongPress: () -> Unit,
    onProfileClick: (String) -> Unit,
    onMentionClick: (String) -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    currentUserId: String?,
    followedUsers: Set<String>,
    pendingFollowUsers: Set<String> = emptySet(),
    reduceMotion: Boolean = false,
    isDownloading: Boolean,
    getStatsFlow: (String) -> kotlinx.coroutines.flow.Flow<conectate.com.data.local.entity.VideoStatsEntity?>,
    onShareInternalClick: (Video) -> Unit,
    onToggleProfileShare: () -> Unit,
    onFollowClick: (String) -> Unit,
    onDownloadClick: (Video) -> Unit,
    onDeleteVideo: (Video) -> Unit,
    isAlreadyReported: Boolean,
    onWhyThisVideo: (Video) -> Unit,
    onNotInterested: (Video) -> Unit,
    onReportClick: (Video) -> Unit,
    onSoundClick: (Video) -> Unit,
    hasPlaybackError: Boolean,
    onRetryPlayback: () -> Unit,
    onRelatedSearch: (String) -> Unit = {},
) {
    // Live Stats Observation (Optimized: only collect when selected)
    val context = androidx.compose.ui.platform.LocalContext.current
    val sensitiveContentMode = conectate.com.presentation.theme.LocalSensitiveContentMode.current
    var sensitiveContentRevealed by remember(video.id, sensitiveContentMode) { mutableStateOf(false) }
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
    // The ViewModel owns the optimistic like count. A cached stats snapshot can
    // arrive later and must not hide the just-tapped result (including my videos).
    // We use the local object when user is interacting, otherwise trust the server.
    // The feed model includes the latest optimistic mutation. A cached stats
    // row can lag behind it, especially for a creator liking their own video.
    val likeCount = video.likeCount
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
            .then(if (conectate.com.BuildConfig.DEBUG) Modifier.semantics { testTagsAsResourceId = true }.testTag("feed_video_${video.id}") else Modifier)
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
        
        // 🚀 SMART TRANSITION STATE
        // Keep thumbnail visible until the FIRST FRAME is actually rendered by the player.
        // This eliminates the black flash completely.
        var isFirstFrameRendered by remember(video.id) { mutableStateOf(renderedVideoId == video.id) }
        
        // Reset the transition guard both for a new item and when an item is
        // selected again after another page owned the shared player. Without
        // this, a stale first frame can remain visible while ExoPlayer is
        // preparing the newly selected source.
        LaunchedEffect(video.id, isSelected, renderedVideoId) {
            if (!isSelected) isFirstFrameRendered = false
            else if (renderedVideoId == video.id) isFirstFrameRendered = true
        }
        
        DisposableEffect(video.id, isSelected, exoPlayer) {
            if (isSelected) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlayingParam: Boolean) {
                        isPlaying = isPlayingParam
                    }
                    
                    override fun onRenderedFirstFrame() {
                        super.onRenderedFirstFrame()
                        if (exoPlayer.currentMediaItem?.mediaId == video.id) {
                            isFirstFrameRendered = true
                        }
                    }
                    
                }
                exoPlayer.addListener(listener)
                // Initial state
                isPlaying = exoPlayer.isPlaying
                onDispose { exoPlayer.removeListener(listener) }
            } else {
                isPlaying = false
                onDispose { }
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
                        // Retaining the old surface makes a refresh look like
                        // two videos are layered for roughly one second.
                        setKeepContentOnPlayerReset(false)
                         setShutterBackgroundColor(android.graphics.Color.BLACK)
                         setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        // Preserve the original aspect ratio and keep short or
                        // letterboxed videos centered inside the usable page.
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame)?.let { frame ->
                            (frame.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
                                params.gravity = android.view.Gravity.CENTER
                                frame.layoutParams = params
                            }
                        }
                        // Important: Keep screen on while playing
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    if (view.player !== exoPlayer) view.player = exoPlayer
                },
                onRelease = { view ->
                    // VerticalPager can keep adjacent pages composed while a
                    // new PlayerView is attached. Explicitly detach the old
                    // surface so it cannot retain a stale frame or compete
                    // with the selected view during the hand-off.
                    if (view.player === exoPlayer) view.player = null
                },
            )
        } 
        
        // The pager keeps neighbours composed. Request their artwork only
        // when they enter the viewport, while keeping the selected cover until its first frame.
        AnimatedVisibility(
            visible = showThumbnail && (!isSelected || !isFirstFrameRendered),
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
                alignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
        
        if (isSelected && playbackRequested && !isFirstFrameRendered && !hasPlaybackError) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(32.dp)
                    .semantics { contentDescription = "Cargando video" },
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }

        if (isSelected && !playbackRequested && !isPlaying && !hasPlaybackError) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(onClick = onTogglePlayback, modifier = Modifier.size(80.dp)) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Reproducir video",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(64.dp),
                    )
                }
                if (showCellularPlayHint) {
                    Text(
                        "Toca para reproducir con datos móviles",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }

        if (isSelected && hasPlaybackError) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "No se pudo continuar este video",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onRetryPlayback) {
                        Text("Reintentar")
                    }
                }
            }
        }

        if (isSelected) {
            FeedVideoSeekBar(
                videoId = video.id,
                player = exoPlayer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 2.dp, end = 2.dp, bottom = 78.dp)
                    .height(20.dp),
            )
        }

        // Right Sidebar (Interactions)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 82.dp, end = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Profile Pic with Follow Button
            val normalizedCurrentUserId = currentUserId?.trim()?.lowercase()
            val normalizedVideoUserId = video.userId.trim().lowercase()
            val isOwnVideo = video.ownedByMe ||
                (normalizedCurrentUserId != null && normalizedCurrentUserId == normalizedVideoUserId)
            val isFollowing = followedUsers.any { it.trim().lowercase() == normalizedVideoUserId }
            val isFollowPending = normalizedVideoUserId in pendingFollowUsers

            Box(modifier = Modifier.size(width = 54.dp, height = 65.dp)) {
                conectate.com.presentation.components.CircularProfileImage(ownerId = video.userId, 
                    modifier = Modifier.align(Alignment.TopCenter)
                        .clickable { onProfileClick(video.userId) },
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
                
                if (!isOwnVideo) {
                    VideoFollowButton(userId = video.userId, name = video.userName,
                        following = isFollowing, pending = isFollowPending, reduceMotion = reduceMotion,
                        modifier = Modifier.align(Alignment.BottomCenter), onFollow = { onFollowClick(video.userId) })
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
                icon = Icons.AutoMirrored.Filled.Message,
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
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Compartir enlace externo") },
                        onClick = {
                            showShareMenu = false
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, "Mira este video en Novvin: ${videoWebLink(video.id)}\n\nO abre directamente en la app:\nconectate://video/${video.id}\n\n${video.title}")
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
                        text = { Text("Herramientas de reproducción") },
                        onClick = {
                            showAlgorithmMenu = false
                            onLongPress()
                        },
                        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Guardar para ver después") },
                        onClick = {
                            showAlgorithmMenu = false
                            onWatchLaterSave()
                        },
                        leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir a colección") },
                        onClick = {
                            showAlgorithmMenu = false
                            onAddToCollection()
                        },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isDownloading) "Preparando descarga…" else "Descargar video") },
                        enabled = !isDownloading,
                        onClick = {
                            showAlgorithmMenu = false
                            onDownloadClick(video)
                        },
                        leadingIcon = {
                            if (isDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Download, contentDescription = null)
                            }
                        },
                    )
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
                contentDescription = "Abrir información del sonido",
                tint = Color.White,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.DarkGray)
                    .clickable { onSoundClick(video) }
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
                    text = video.userName.ifBlank { "Usuario" },
                    modifier = Modifier.weight(1f, fill = false).clickable { onProfileClick(video.userId) },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                if (video.isAdmin) {
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
            VideoDescriptionText(
                text = video.description,
                onMentionClick = onMentionClick,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
            remember(video.id, video.title, video.description, video.tags, video.isPublic, video.isSensitive) {
                videoRelatedTopic(video)
            }?.let { topic ->
                Row(Modifier.fillMaxWidth().clickable { onRelatedSearch(topic) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text("Buscar: $topic", color = Color.White, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 6.dp))
                }
            }
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
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }

        if (
            video.isSensitive &&
            sensitiveContentMode != conectate.com.data.local.prefs.SensitiveContentMode.SHOW &&
            !sensitiveContentRevealed
        ) {
            conectate.com.presentation.components.SensitiveContentCover(
                mode = sensitiveContentMode,
                onReveal = { sensitiveContentRevealed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val INITIAL_EMPTY_STATE_GRACE_MS = 2_000L

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun buildVideoMediaItem(videoId: String, resolvedUrl: String): MediaItem =
    MediaItem.Builder()
        // MediaItem defaults mediaId to an empty string. Using the video id is
        // essential: state emissions must not look like a brand-new playlist.
        .setMediaId(videoId)
        .setUri(resolvedUrl)
        .setCustomCacheKey(
            conectate.com.data.localfallback.UbuntuStorageUrl.stableCacheKey(resolvedUrl)
        )
        .build()

private fun Throwable.causeSequence(): Sequence<Throwable> =
    generateSequence(this as Throwable?) { throwable -> throwable.cause }

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
                                    conectate.com.presentation.components.CircularProfileImage(
                                        imageUrl = avatar, fallbackName = user.displayName,
                                        size = 82.dp, mediaType = user.avatar_media_type,
                                        openMomentOnClick = false, showMomentRing = false,
                                        modifier = Modifier.fillMaxSize(),
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
        java.time.format.DateTimeFormatter.ofPattern(
            "MMMM yyyy",
            java.util.Locale.forLanguageTag("es")
        )
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
    Column(
        modifier = Modifier
            .width(48.dp)
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = if (text.isBlank()) "Más opciones del video" else "Acción del video, $text"
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(31.dp),
        )
        if (text.isNotBlank()) {
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                lineHeight = 12.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
