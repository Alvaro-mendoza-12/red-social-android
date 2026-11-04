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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    val accountId = viewModel.currentUserId
    var showOutcome by remember(postId,accountId) { mutableStateOf(false) }
    if(showOutcome) conectate.com.presentation.purpose.PostPurposeDialog(postId,navController) { showOutcome = false }
    val commentPosting by viewModel.commentPosting.collectAsStateWithLifecycle()
    var commentText by rememberSaveable(postId, accountId) { mutableStateOf("") }
    val draftRevision = remember(postId, accountId) { PostCommentDraftRevision() }
    var visibleLimit by rememberSaveable(postId, accountId) { mutableIntStateOf(POST_COMMENT_PAGE_SIZE) }
    var showReactions by remember(postId, accountId) { mutableStateOf(false) }
    var reactionFilter by remember(postId, accountId) { mutableStateOf<String?>(null) }
    val reactionState by viewModel.reactionUsersState.collectAsStateWithLifecycle()
    LaunchedEffect(postId, showReactions, reactionFilter) {
        if (showReactions) viewModel.loadPostReactions(postId, reactionFilter) else viewModel.clearPostReactions()
    }
    if (showReactions) uiState.post?.let { post ->
        PostReactionsSheet(post, reactionState, reactionFilter, { reactionFilter = it },
            { viewModel.loadPostReactions(postId, reactionFilter) },
            { userId -> showReactions = false; viewModel.clearPostReactions()
                navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(userId)) },
            { showReactions = false; viewModel.clearPostReactions() })
    }
    var replyingTo by remember(postId, accountId) { mutableStateOf<conectate.com.data.model.Comment?>(null) }
    var commentToEdit by remember(postId, accountId) { mutableStateOf<conectate.com.data.model.Comment?>(null) }
    var postToReport by remember(postId, accountId) { mutableStateOf<conectate.com.data.model.Post?>(null) }
    var commentToReport by remember(postId, accountId) { mutableStateOf<conectate.com.data.model.Comment?>(null) }
    var showStickerPicker by remember(postId, accountId) { mutableStateOf(false) }
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsStateWithLifecycle()
    val commentEditing by viewModel.commentEditing.collectAsStateWithLifecycle()
    
    val commentsWindow = remember(uiState.comments, visibleLimit, focusCommentId) {
        postCommentWindow(uiState.comments, visibleLimit, focusCommentId)
    }
    val threadedComments = commentsWindow.rows
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val summaryItemVisible = uiState.isSummarizingThread ||
        uiState.threadSummary != null ||
        uiState.threadSummaryError != null
    var focusResolved by remember(postId, accountId, focusCommentId) { mutableStateOf(focusCommentId.isNullOrBlank()) }

    LaunchedEffect(postId) {
        viewModel.loadPost(postId)
    }

    LaunchedEffect(postId, accountId, threadedComments, focusCommentId, summaryItemVisible,
        uiState.isLoadingComments, uiState.commentsError, uiState.error) {
        if (focusResolved || focusCommentId.isNullOrBlank()) return@LaunchedEffect
        val idx = threadedComments.indexOfFirst { (comment, _) ->
            comment.id.equals(focusCommentId, ignoreCase = true)
        }
        if (idx >= 0) {
            // 0 = post, 1 = comments header, 2 = optional summary, then comments.
            listState.scrollToItem(idx + 2 + if (summaryItemVisible) 1 else 0)
            focusResolved = true
        } else if (!uiState.isLoadingComments && uiState.commentsError == null && uiState.error == null) {
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
                                onClick = { draftRevision.changed(); replyingTo = null },
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
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (mentionSuggestions.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                shape = RoundedCornerShape(12.dp),
                                tonalElevation = 2.dp
                            ) {
                                Column(
                                    modifier = Modifier.heightIn(max = 180.dp)
                                        .verticalScroll(rememberScrollState()).padding(vertical = 6.dp)
                                ) {
                                    mentionSuggestions.forEach { user ->
                                        val username = conectate.com.data.model.profilePublicHandle(user)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                draftRevision.changed()
                                                commentText = replaceActiveMention(commentText, username)
                                                viewModel.clearMentionSuggestions()
                                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            conectate.com.presentation.components.CircularProfileImage(
                                                ownerId = user.id,
                                                imageUrl = user.avatar_url ?: "",
                                                size = 28.dp,
                                                fallbackName = user.display_name ?: user.full_name ?: user.username ?: user.email
                                            )
                                            Spacer(Modifier.width(8.dp))
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
                                    draftRevision.changed()
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
                                    contentDescription = "Emojis y stickers",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    val content = commentText.trim()
                                    if (content.isNotEmpty()) {
                                        val replyId = replyingTo?.id
                                        val submittedRevision = draftRevision.snapshot()
                                        viewModel.addComment(postId, content, replyId) {
                                            if (draftRevision.acknowledge(submittedRevision)) {
                                                commentText = ""
                                                replyingTo = null
                                                viewModel.clearMentionSuggestions()
                                            }
                                        }
                                    }
                                },
                                enabled = commentText.isNotBlank() && !commentPosting
                            ) {
                                if (commentPosting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar comentario")
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
                            onLikeClick = { viewModel.toggleLike(state.post.id) },
                            onReactionSelect = { viewModel.setReaction(state.post.id, it) },
                            onReactionSummaryClick = { reactionFilter = null; showReactions = true },
                            onOutcomeClick = { showOutcome = true },
                            onCommentClick = { /* Already here */ },
                            onShareClick = { viewModel.toggleShare(state.post.id) },
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

                    if (state.commentsError != null) item(key = "comments-error") {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.commentsError, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = viewModel::retryComments, enabled = !state.isLoadingComments) { Text("Reintentar") }
                        }
                    }
                    if (state.isLoadingComments && state.comments.isEmpty()) {
                        item(key = "loading_comments") {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    } else if (state.comments.isEmpty() && state.commentsError == null) {
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
                                    draftRevision.changed()
                                    replyingTo = comment
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
                        if (commentsWindow.hasMore) item(key = "more-comments") {
                            TextButton(onClick = { visibleLimit = commentsWindow.nextLimit }, modifier = Modifier.fillMaxWidth()) {
                                Text("Ver más comentarios")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStickerPicker) {
        conectate.com.presentation.messages.StickerPickerDialog(
            onDismiss = { showStickerPicker = false },
            onEmojiSelected = { emoji ->
                draftRevision.changed()
                commentText += emoji
                viewModel.onCommentDraftChanged(commentText)
                showStickerPicker = false
            },
            onStickerSelected = { sticker ->
                val url = sticker.media_url ?: sticker.thumbnail_url
                if (!url.isNullOrBlank()) {
                    val submittedRevision = draftRevision.snapshot()
                    viewModel.addStickerComment(postId, url, replyingTo?.id) {
                        if (draftRevision.acknowledge(submittedRevision)) replyingTo = null
                    }
                }
                showStickerPicker = false
            }
        )
    }

    postToReport?.let { reportPost ->
        conectate.com.presentation.components.ContentReportDialog(
            title = "Reportar publicación",
            reportKey = "post:${reportPost.id}",
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
            }
        )
    }

    commentToReport?.let { reportComment ->
        conectate.com.presentation.components.ContentReportDialog(
            title = "Reportar comentario",
            reportKey = "comment:${reportComment.id}",
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
            }
        )
    }

    commentToEdit?.let { comment ->
        conectate.com.presentation.components.CommentEditDialog(
            comment, commentEditing, onDismiss = { commentToEdit = null },
            onSave = { text -> viewModel.editComment(comment.id, text) { commentToEdit = null } },
        )
    }

}

private fun replaceActiveMention(currentText: String, username: String): String {
    val atIndex = currentText.lastIndexOf('@')
    if (atIndex < 0) return "$currentText @$username "

    val suffix = currentText.substring(atIndex + 1)
    if (suffix.any { it.isWhitespace() }) return "$currentText@$username "

    return currentText.substring(0, atIndex + 1) + username + " "
}
