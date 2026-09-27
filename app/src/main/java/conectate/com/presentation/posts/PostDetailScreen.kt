package conectate.com.presentation.posts

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import conectate.com.presentation.common.AppFeedbackBus
import kotlinx.coroutines.launch
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
    var commentToEdit by remember { mutableStateOf<conectate.com.data.model.Comment?>(null) }
    var postToReport by remember { mutableStateOf<conectate.com.data.model.Post?>(null) }
    var commentToReport by remember { mutableStateOf<conectate.com.data.model.Comment?>(null) }
    var showStickerPicker by remember { mutableStateOf(false) }
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
    val coroutineScope = rememberCoroutineScope()
    val summaryItemVisible = uiState.isSummarizingThread ||
        uiState.threadSummary != null ||
        uiState.threadSummaryError != null
    var focusResolved by remember(focusCommentId) { mutableStateOf(focusCommentId.isNullOrBlank()) }

    LaunchedEffect(postId) {
        viewModel.loadPost(postId)
    }

    LaunchedEffect(threadedComments, focusCommentId, summaryItemVisible) {
        if (focusResolved || focusCommentId.isNullOrBlank()) return@LaunchedEffect
        val idx = threadedComments.indexOfFirst { (comment, _) ->
            comment.id.equals(focusCommentId, ignoreCase = true)
        }
        if (idx >= 0) {
            // 0 = post, 1 = comments header, 2 = optional summary, then comments.
            listState.scrollToItem(idx + 2 + if (summaryItemVisible) 1 else 0)
            focusResolved = true
        } else if (!uiState.isLoadingComments) {
            focusResolved = true
            AppFeedbackBus.show("Ese comentario fue eliminado o ya no está disponible.")
        }
    }

    // Root Cause Fix: Removed manual "isNavigating" checks that caused listener leaks.
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Publicación", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
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
                                        val timeString = String.format(
                                            androidx.compose.ui.platform.LocalLocale.current.platformLocale,
                                            "%02d:%02d",
                                            minutes,
                                            seconds,
                                        )
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
                                        Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = MaterialTheme.colorScheme.primary)
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
                                        Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = MaterialTheme.colorScheme.primary)
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
                                    Column(modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                                        mentionSuggestions.forEach { user ->
                                            val username = conectate.com.data.model.profilePublicHandle(user)
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
                                                    size = 28.dp,
                                                    fallbackName = user.display_name ?: user.full_name ?: user.username ?: user.email
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
                                    maxLines = 4,
                                    visualTransformation = conectate.com.presentation.components.mentionInputTransformation,
                                )
                                IconButton(onClick = { showStickerPicker = true }) {
                                    Icon(
                                        Icons.Default.EmojiEmotions,
                                        contentDescription = "Enviar sticker",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                
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
                                            Icons.AutoMirrored.Filled.Send,
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.HideSource,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.error,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { navController.popBackStack() }) {
                        Text("Volver")
                    }
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
                            onPostClick = {},
                            isOwner = state.post.userId == viewModel.currentUserId,
                            showOwnerActions = false,
                            onReportClick = { postToReport = state.post },
                            showReportAction = true
                        )
                    }
                    
                    item(key = "header_comments") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Comentarios",
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            TextButton(
                                onClick = { viewModel.requestThreadSummary(postId) },
                                enabled = !state.isSummarizingThread &&
                                    state.comments.count { it.content.trim().length >= 3 } >= 3
                            ) {
                                if (state.isSummarizingThread) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (state.threadSummary == null) "Resumir hilo" else "Actualizar resumen")
                            }
                        }
                    }

                    if (summaryItemVisible) {
                        item(key = "thread_summary") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Resumen con Novvin IA",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    when {
                                        state.isSummarizingThread -> Text(
                                            "Leyendo únicamente los comentarios visibles de esta publicación…",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        state.threadSummaryError != null -> Text(
                                            state.threadSummaryError,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        state.threadSummary != null -> {
                                            state.threadSummary.bullets.forEach { bullet ->
                                                Text(
                                                    text = "• ${bullet.text}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.padding(top = 8.dp)
                                                )
                                                bullet.sources.forEach { source ->
                                                    TextButton(
                                                        onClick = {
                                                            val index = threadedComments.indexOfFirst { (comment, _) ->
                                                                comment.id.equals(source.commentId, ignoreCase = true)
                                                            }
                                                            if (index >= 0) {
                                                                coroutineScope.launch {
                                                                    listState.animateScrollToItem(index + 3)
                                                                }
                                                            }
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 0.dp)
                                                    ) {
                                                        Text("Ver fuente · ${source.authorName}")
                                                    }
                                                }
                                            }
                                            HorizontalDivider(
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.18f)
                                            )
                                            Text(
                                                state.threadSummary.notice,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
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
                        val isCurrentUserPostOwner = state.post.userId
                            .trim()
                            .equals(viewModel.currentUserId?.trim(), ignoreCase = true)
                        items(threadedComments, key = { it.first.id }) { (comment, level) ->
                            CommentItem(
                                comment = comment,
                                level = level,
                                isPostOwner = isCurrentUserPostOwner,
                                onProfileClick = {
                                    navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(comment.userId))
                                },
                                onMentionClick = { handle ->
                                    viewModel.resolvePublicMention(handle) { userId ->
                                        if (userId != null) {
                                            navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(userId))
                                        } else AppFeedbackBus.showError("No se pudo identificar esta mención.")
                                    }
                                },
                                onReplyClick = {
                                    replyingTo = comment
                                    commentText = ""
                                },
                                onLikeClick = { viewModel.toggleCommentReaction(comment, "LIKE") },
                                onDislikeClick = { viewModel.toggleCommentReaction(comment, "DISLIKE") },
                                onDeleteClick = {
                                    viewModel.deleteComment(comment.id)
                                },
                                onEditClick = {
                                    commentToEdit = comment
                                },
                                onReportClick = {
                                    commentToReport = comment
                                },
                                showReportAction = true
                            )
                        }
                    }
                }
            }
        }
    }

    if (showStickerPicker) {
        conectate.com.presentation.messages.StickerPickerDialog(
            onDismiss = { showStickerPicker = false },
            onStickerSelected = { sticker ->
                val url = sticker.media_url ?: sticker.thumbnail_url
                if (!url.isNullOrBlank()) {
                    viewModel.addStickerComment(postId, url, replyingTo?.id)
                    replyingTo = null
                }
                showStickerPicker = false
            }
        )
    }

    postToReport?.let { reportPost ->
        conectate.com.presentation.components.ContentReportDialog(
            title = "Reportar publicación",
            subjectText = reportPost.userName,
            reasonOptions = listOf(
                "Spam",
                "Acoso",
                "Violencia",
                "Desinformación",
                "Contenido sexual",
                "Otro"
            ),
            attachEvidenceEnabled = true,
            onDismiss = { postToReport = null },
            onSubmit = { reason, details, evidenceUri ->
                viewModel.reportPost(reportPost, reason, details, evidenceUri)
                postToReport = null
            }
        )
    }

    commentToReport?.let { reportComment ->
        conectate.com.presentation.components.ContentReportDialog(
            title = "Reportar comentario",
            subjectText = reportComment.userName ?: "Usuario",
            reasonOptions = listOf(
                "Spam",
                "Acoso",
                "Violencia",
                "Desinformación",
                "Contenido sexual",
                "Otro"
            ),
            attachEvidenceEnabled = true,
            onDismiss = { commentToReport = null },
            onSubmit = { reason, details, evidenceUri ->
                viewModel.reportComment(reportComment, reason, details, evidenceUri)
                commentToReport = null
            }
        )
    }

    if (commentToEdit != null) {
        var editContent by remember(commentToEdit) { mutableStateOf(commentToEdit?.content ?: "") }
        AlertDialog(
            onDismissRequest = { commentToEdit = null },
            title = { Text("Editar Comentario") },
            text = {
                OutlinedTextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        commentToEdit?.let {
                            viewModel.editComment(it.id, editContent)
                        }
                        commentToEdit = null
                    },
                    enabled = editContent.isNotBlank() && editContent != commentToEdit?.content
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { commentToEdit = null }) {
                    Text("Cancelar")
                }
            }
        )
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
