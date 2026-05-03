package conectate.com.presentation.posts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.presentation.components.PostCard
import conectate.com.presentation.components.CommentItem
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(
    postId: String,
    navController: NavController,
    focusCommentId: String? = null,
    viewModel: PostDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var commentText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<conectate.com.data.model.Comment?>(null) }
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsStateWithLifecycle()
    
    // Voice Manager States
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val recordingDuration by viewModel.recordingDuration.collectAsStateWithLifecycle()
    val voicePlaybackProgress by viewModel.voicePlaybackProgress.collectAsStateWithLifecycle()
    val voicePlaybackDurationLabel by viewModel.voicePlaybackDurationLabel.collectAsStateWithLifecycle()
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val recordPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        }
    }

    val threadedComments = remember(uiState.comments) {
        organizeThreaded(uiState.comments)
    }
    val listState = rememberLazyListState()
    var focusResolved by remember(focusCommentId) { mutableStateOf(focusCommentId.isNullOrBlank()) }

    LaunchedEffect(postId) {
        viewModel.loadPost(postId)
    }

    LaunchedEffect(threadedComments, focusCommentId) {
        if (focusResolved || focusCommentId.isNullOrBlank()) return@LaunchedEffect
        val idx = threadedComments.indexOfFirst { (comment, _) ->
            comment.id.equals(focusCommentId, ignoreCase = true)
        }
        if (idx >= 0) {
            // 0 = post, 1 = comments header, 2+ = comments
            listState.scrollToItem(idx + 2)
            focusResolved = true
        }
    }

    // Root Cause Fix: Removed manual "isNavigating" checks that caused listener leaks.
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Publicación", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                if (replyingTo != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Respondiendo a ${replyingTo!!.userName}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { replyingTo = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    "Cancelar",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Surface(tonalElevation = 2.dp) {
                    if (voiceState != conectate.com.presentation.messages.VoiceState.IDLE) {
                        // Voice Recording UI
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .height(64.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .padding(horizontal = 8.dp)
                        ) {
                            IconButton(onClick = { viewModel.cancelRecording() }) {
                                Icon(Icons.Default.Delete, "Cancelar", tint = MaterialTheme.colorScheme.error)
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                when (voiceState) {
                                    conectate.com.presentation.messages.VoiceState.RECORDING -> {
                                        val minutes = recordingDuration / 1000 / 60
                                        val seconds = (recordingDuration / 1000) % 60
                                        val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "Grabando... $timeString", 
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    conectate.com.presentation.messages.VoiceState.PAUSED -> {
                                        Text(
                                            "Pausado", 
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    conectate.com.presentation.messages.VoiceState.REVIEWING -> {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            IconButton(onClick = { 
                                                if (viewModel.voiceMessageManagerIsPreviewPlaying()) viewModel.pauseDraftPreview() 
                                                else viewModel.playDraftPreview()
                                            }) {
                                                Icon(
                                                    if (viewModel.voiceMessageManagerIsPreviewPlaying()) Icons.Default.Pause else Icons.Default.PlayArrow, 
                                                    "Reproducir",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            
                                            Slider(
                                                value = voicePlaybackProgress,
                                                onValueChange = { viewModel.seekDraftPreview(it) },
                                                modifier = Modifier.weight(1f)
                                            )
                                            
                                            Text(
                                                voicePlaybackDurationLabel,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    }
                                    else -> {}
                                }
                            }
                            
                            when (voiceState) {
                                conectate.com.presentation.messages.VoiceState.RECORDING -> {
                                    IconButton(onClick = { viewModel.pauseRecording() }) {
                                        Icon(Icons.Default.Pause, "Pausar", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { viewModel.stopRecording(true) }) {
                                        Icon(Icons.Default.Send, "Enviar", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                conectate.com.presentation.messages.VoiceState.PAUSED -> {
                                    IconButton(onClick = { viewModel.resumeRecording() }) {
                                        Icon(Icons.Default.Mic, "Reanudar", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { viewModel.stopRecording(false) }) {
                                        Icon(Icons.Default.Stop, "Revisar", tint = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                                conectate.com.presentation.messages.VoiceState.REVIEWING -> {
                                    IconButton(onClick = { viewModel.resumeRecordingFromReview() }) {
                                        Icon(Icons.Default.Mic, "Reanudar", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { viewModel.sendCurrentRecording() }) {
                                        Icon(Icons.Default.Send, "Enviar", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                else -> {}
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            if (mentionSuggestions.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                    tonalElevation = 2.dp
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                        mentionSuggestions.forEach { user ->
                                            val username = user.username ?: return@forEach
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        commentText = replaceActiveMention(commentText, username)
                                                        viewModel.clearMentionSuggestions()
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                conectate.com.presentation.components.CircularProfileImage(
                                                    imageUrl = user.avatar_url ?: "",
                                                    size = 28.dp
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("@$username", fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = commentText,
                                    onValueChange = {
                                        commentText = it
                                        viewModel.onCommentDraftChanged(it)
                                    },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Añadir un comentario...") },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    maxLines = 4
                                )
                                
                                if (commentText.isEmpty()) {
                                    IconButton(
                                        onClick = {
                                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                viewModel.startRecording()
                                            } else {
                                                recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = "Grabar",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            if (commentText.isNotBlank()) {
                                                viewModel.addComment(postId, commentText, replyingTo?.id)
                                                commentText = ""
                                                replyingTo = null
                                                viewModel.clearMentionSuggestions()
                                            }
                                        },
                                        enabled = commentText.isNotBlank()
                                    ) {
                                        Icon(
                                            Icons.Default.Send,
                                            contentDescription = "Enviar",
                                            tint = if (commentText.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val state = uiState
            
            if (state.isLoadingPost) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.error != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error, color = Color.Red)
                }
            } else if (state.post != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState
                ) {
                    item(key = "post_${state.post.id}") {
                        PostCard(
                            post = state.post,
                            onLikeClick = { viewModel.toggleLike(postId) },
                            onCommentClick = { /* Already here */ },
                            onShareClick = { viewModel.toggleShare(postId) },
                            onUserClick = { userId ->
                                navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(userId))
                            },
                            onPostClick = {}
                        )
                    }
                    
                    item(key = "header_comments") {
                        Text(
                            text = "Comentarios",
                            modifier = Modifier.padding(16.dp),
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                    }

                    if (state.isLoadingComments && state.comments.isEmpty()) {
                        item(key = "loading_comments") {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    } else if (state.comments.isEmpty()) {
                        item(key = "empty_comments") {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("Sé el primero en comentar", color = Color.Gray)
                            }
                        }
                    } else {
                        items(threadedComments, key = { it.first.id }) { (comment, level) ->
                            CommentItem(
                                comment = comment,
                                level = level,
                                isPostOwner = state.post.userId == viewModel.currentUserId,
                                onProfileClick = {
                                    navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(comment.userId))
                                },
                                onReplyClick = {
                                    replyingTo = comment
                                    commentText = "@${comment.userName} "
                                },
                                onDeleteClick = {
                                    viewModel.deleteComment(comment.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

fun organizeThreaded(comments: List<conectate.com.data.model.Comment>): List<Pair<conectate.com.data.model.Comment, Int>> {
    val result = mutableListOf<Pair<conectate.com.data.model.Comment, Int>>()
    val roots = comments.filter { it.parentId == null }
    val childrenMap = comments.filter { it.parentId != null }.groupBy { it.parentId }

    fun addChildren(parentId: String, level: Int) {
        childrenMap[parentId]?.forEach { child ->
            result.add(child to level)
            addChildren(child.id, level + 1)
        }
    }

    roots.forEach { root ->
        result.add(root to 0)
        addChildren(root.id, 1)
    }
    return result
}

private fun replaceActiveMention(currentText: String, username: String): String {
    val atIndex = currentText.lastIndexOf('@')
    if (atIndex < 0) return "$currentText @$username "

    val suffix = currentText.substring(atIndex + 1)
    if (suffix.any { it.isWhitespace() }) return "$currentText@$username "

    return currentText.substring(0, atIndex + 1) + username + " "
}
