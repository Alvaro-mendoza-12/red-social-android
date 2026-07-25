package conectate.com.presentation.videos

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import conectate.com.data.model.Video
import conectate.com.presentation.common.ConnectivityViewModel
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.OfflineNoticeBar
import conectate.com.presentation.navigation.Screen
import java.text.SimpleDateFormat
import java.util.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(
    navController: NavController,
    viewModel: VideosViewModel = hiltViewModel(),
    connectivityViewModel: ConnectivityViewModel = hiltViewModel()
) {
    val videosState by viewModel.videosState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isConnected by connectivityViewModel.isConnected.collectAsState()
    
    // ✅ FIX: Add comment sheet state
    val selectedVideoForComments by viewModel.selectedVideoForComments.collectAsState()
    val commentsState by viewModel.commentsState.collectAsState()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsState()
    val reportedVideoIds by viewModel.reportedVideoIds.collectAsState()
    val reportActionMessage by viewModel.reportActionMessage.collectAsState()
    
    var videoToShare by remember { mutableStateOf<conectate.com.data.model.Video?>(null) }
    var videoToReport by remember { mutableStateOf<conectate.com.data.model.Video?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(reportActionMessage) {
        val message = reportActionMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.consumeReportActionMessage()
    }
    
    // Show CommentSheet when a video is selected for comments
    if (selectedVideoForComments != null) {
        val voiceState by viewModel.voiceState.collectAsState()
        val recordingDuration by viewModel.recordingDuration.collectAsState()
        val isDraftPlaying by viewModel.isDraftPlaying.collectAsState()
        val draftProgress by viewModel.draftProgress.collectAsState()
        val draftDuration by viewModel.draftDuration.collectAsState()
        val currentPlayingCommentId by viewModel.currentPlayingCommentId.collectAsState()
        val audioProgress by viewModel.audioProgress.collectAsState()
        val currentVideo = (videosState as? VideosUiState.Success)?.videos?.find { it.id == selectedVideoForComments }

        CommentSheet(
            comments = commentsState,
            onAddComment = { content -> viewModel.addComment(content) },
            onDismiss = { viewModel.closeComments() },
            mentionSuggestions = mentionSuggestions,
            onCommentDraftChanged = { viewModel.onCommentDraftChanged(it) },
            onClearMentionSuggestions = { viewModel.clearMentionSuggestions() },
            voiceState = voiceState,
            recordingDuration = recordingDuration,
            onStartRecording = { viewModel.startRecording() },
            onPauseRecording = { viewModel.pauseRecording() },
            onResumeRecording = { viewModel.resumeRecording() },
            onStopRecording = { shouldSend -> viewModel.stopRecording(shouldSend) },
            onCancelRecording = { viewModel.cancelRecording() },
            isDraftPlaying = isDraftPlaying,
            draftProgress = draftProgress,
            draftDuration = draftDuration,
            onPlayDraft = { viewModel.playDraft() },
            onPauseDraft = { viewModel.pauseDraft() },
            onSeekDraft = { viewModel.seekDraft(it) },
            onPlayAudio = { comment -> viewModel.playAudio(comment) },
            currentPlayingCommentId = currentPlayingCommentId,
            audioProgress = audioProgress,
            currentUserId = viewModel.currentUserId,
            onDeleteComment = { comment -> viewModel.deleteComment(comment) },
            onAddSticker = { url, label -> viewModel.addStickerComment(url, label) },
            onReactComment = { comment, reaction -> viewModel.reactToComment(comment, reaction) },
            onEditComment = { comment, content -> viewModel.editComment(comment, content) },
            onReportComment = { comment -> viewModel.reportComment(comment) },
            videoCreatorId = currentVideo?.userId,
            replyingTo = viewModel.replyingTo.collectAsState().value,
            onReplyClick = { viewModel.onReplyClick(it) },
            cancelReply = { viewModel.cancelReply() }
        )
    }

    var videoForOptions by remember { mutableStateOf<conectate.com.data.model.Video?>(null) }
    
    // Show Share Options Sheet
    if (videoForOptions != null) {
        val video = videoForOptions!!
        conectate.com.presentation.components.ShareOptionsSheet(
            onDismiss = { videoForOptions = null },
            onSendToFriend = {
                videoToShare = video
                videoForOptions = null
            },
            onExternalShare = {
                val sendIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_TEXT, "¡Mira este video en Novvin!\nhttps://conectate.com/video/${video.id}\n\n${video.title}")
                    type = "text/plain"
                }
                val shareIntent = android.content.Intent.createChooser(sendIntent, "Compartir video")
                context.startActivity(shareIntent)
                videoForOptions = null
            },
            onToggleProfileShare = {
                viewModel.toggleVideoShare(video)
                videoForOptions = null
            },
            isSharedToProfile = video.sharedByMe,
            onReportVideo = {
                viewModel.ensureVideoReportStatus(video.id)
                videoToReport = video
            },
            isReported = reportedVideoIds.contains(video.id)
        )
    }

    if (videoToReport != null) {
        val video = videoToReport!!
        VideoReportDialog(
            alreadyReported = reportedVideoIds.contains(video.id),
            onDismiss = { videoToReport = null },
            onSubmit = { reason, details ->
                viewModel.submitVideoReport(video.id, reason, details)
                videoToReport = null
            }
        )
    }

    if (videoToShare != null) {
        conectate.com.presentation.components.InternalShareDialog(
            userRepository = viewModel.userRepository,
            onDismiss = { videoToShare = null },
            onShareWithUser = { user ->
                val video = videoToShare!!
                videoToShare = null
                val videoLink = "https://conectate.com/video/${video.id}"
                val route = conectate.com.presentation.navigation.Screen.Chat.createRoute(
                    user.id, 
                    initialMessage = "Compartió un video:\n$videoLink\n\n${video.title}"
                )
                navController.navigate(route)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Videos",
                        fontWeight = FontWeight.Bold
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { 
                    navController.navigate(Screen.VideoUpload.route)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.VideoCall,
                    contentDescription = "Subir video",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!isConnected) {
                OfflineNoticeBar(
                    message = "Sin conexión. Mostrando videos en caché.",
                    onRetry = {
                        connectivityViewModel.retrySync("videos_retry")
                        viewModel.refreshVideos()
                    }
                )
            }
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Buscar videos") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar"
                    )
                },
                singleLine = true,
                shape = CircleShape
            )

            // Videos Grid
            when (val state = videosState) {
                is VideosUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                is VideosUiState.Success -> {
                    if (state.videos.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.VideoLibrary else Icons.Default.WifiOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                                )
                                Text(
                                    text = when {
                                        !isConnected -> "No se pudieron cargar los videos. Verifica tu conexión a internet."
                                        searchQuery.isEmpty() -> "No hay videos"
                                        else -> "No se encontraron videos"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                )
                                if (!isConnected) {
                                    Button(onClick = {
                                        connectivityViewModel.retrySync("videos_empty_retry")
                                        viewModel.refreshVideos()
                                    }) { Text("Reintentar") }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(1),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = state.videos,
                                key = { it.id }
                            ) { video ->
                                VideoCard(
                                    video = video,
                                    onClick = {
                                        navController.navigate(Screen.VideoPlayer.createRoute(video.id))
                                    },
                                    onLikeClick = {
                                        viewModel.likeVideo(video.id, video.likedByMe)
                                    },
                                    onCommentClick = {
                                        viewModel.openComments(video.id)
                                    },
                                    onFavoriteClick = { viewModel.toggleFavorite(video) },
                                    onShareClick = {
                                        videoForOptions = video
                                    },
                                    navController = navController
                                )
                            }
                        }
                    }
                }
                
                is VideosUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Error al cargar videos",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCard(
    video: Video,
    onClick: () -> Unit,
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onShareClick: () -> Unit,
    navController: androidx.navigation.NavController // Pass navController
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val thumbnailRequest = remember(video.thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(video.thumbnailUrl)
            .size(720, 1280)
            .crossfade(false)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .build()
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Thumbnail
            // Thumbnail
            AsyncImage(
                model = thumbnailRequest,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            // Gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            ),
                            startY = 100f
                        )
                    )
            )
            
            // Duration badge
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = formatDuration(video.duration.toInt()),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
            
            // Video info at bottom
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                 // Author Info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .clickable { navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(video.userId)) }
                ) {
                    CircularProfileImage(
                        imageUrl = video.userProfileImage,
                        size = 20.dp,
                        mediaType = video.avatarMediaType,
                        muted = video.avatarVideoMuted,
                        allowSound = !video.avatarVideoMuted,
                        fallbackName = video.userName
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = video.userName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Views
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = formatNumber(video.views),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                    
                    // Likes
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onLikeClick)
                    ) {
                        Icon(
                            imageVector = if (video.likedByMe) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = null,
                            tint = if (video.likedByMe) Color.Red else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = formatNumber(video.likeCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }

                    // Comments
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onCommentClick)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Comment,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = formatNumber(video.commentCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }

                    // Share
                    IconButton(onClick = onFavoriteClick, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = if (video.favoritedByMe) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorito",
                            tint = if (video.favoritedByMe) Color(0xFFFFC107) else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Share
                    IconButton(onClick = onShareClick, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = if (video.sharedByMe) Icons.Default.RepeatOn else Icons.Default.Share,
                            contentDescription = "Compartir",
                            tint = if (video.sharedByMe) Color(0xFF00bcd4) else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
}

private fun formatNumber(number: Int): String {
    return when {
        number >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", number / 1_000_000.0)
        number >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", number / 1_000.0)
        else -> number.toString()
    }
}
