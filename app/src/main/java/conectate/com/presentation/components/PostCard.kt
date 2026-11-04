package conectate.com.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.data.localfallback.UbuntuStorageUrl
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import conectate.com.data.model.Post
import conectate.com.util.DateUtils
import conectate.com.presentation.navigation.Screen

import androidx.compose.foundation.ExperimentalFoundationApi
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PostCard(
    post: Post,
    onLikeClick: () -> Unit,
    onReactionSelect: (String?) -> Unit,
    onReactionSummaryClick: () -> Unit,
    onCommentClick: () -> Unit,
    onShareClick: () -> Unit,
    onUserClick: (String) -> Unit,
    isOwner: Boolean = false,
    onDeleteClick: () -> Unit = {},
    onVisibilityClick: (String) -> Unit = {},
    onEditClick: () -> Unit = {},
    onUrlClick: ((String) -> Unit)? = null,
    onPostClick: () -> Unit = {},
    onSendToFriend: (() -> Unit)? = null,
    onWhyThisPost: (() -> Unit)? = null,
    onNotInterested: (() -> Unit)? = null,
    onSaveClick: (() -> Unit)? = null,
    onReportClick: () -> Unit = {},
    onRemixClick: ((String) -> Unit)? = null,
    onRemixPermissionClick: (Boolean, String) -> Unit = { _, _ -> },
    onOriginalPostClick: ((String) -> Unit)? = null,
    showOwnerActions: Boolean = true,
    showReportAction: Boolean = false,
    authorFollowState: conectate.com.data.model.AuthorFollowState? = null,
    followBusy: Boolean = false,
    onFollowClick: () -> Unit = {},
    authorMoment: conectate.com.data.model.Moment? = null,
    onMomentClick: (String) -> Unit = {},
    onMentionClick: ((String) -> Unit)? = null,
    onOutcomeClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val internalShare = conectate.com.presentation.messages.LocalUniversalForwardCoordinator.current
    val sendToFriend = onSendToFriend ?: internalShare?.let { coordinator -> { coordinator.openPost(post) } }
    val openUrl: (String) -> Unit = { url ->
        if (onUrlClick != null) onUrlClick(url) else {
            val uri = android.net.Uri.parse(url)
            if (uri.scheme in setOf("https", "http")) {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
                    .onFailure { conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo abrir el enlace.") }
            }
        }
    }
    val windowHeightDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp().value.roundToInt()
    }
    val postOverlayClearance = tallPostOverlayClearance(windowHeightDp)
    var fullScreenImageIndex by remember { mutableStateOf<Int?>(null) }
    var contentExpanded by rememberSaveable(post.id) { mutableStateOf(false) }
    var contentHasOverflow by remember(post.id, post.content) { mutableStateOf(false) }
    var showReactionPicker by remember(post.id) { mutableStateOf(false) }
    var showRemixDialog by remember(post.id) { mutableStateOf(false) }
    var remixComment by remember(post.id) { mutableStateOf("") }
    val reactionTotal = remember(post.reactionCounts, post.likeCount) {
        post.reactionCounts.values.sum().takeIf { it > 0 } ?: post.likeCount
    }
    val currentReaction = post.myReaction ?: if (post.likedByMe) "LIKE" else null
    val remixAvailable = post.remixAllowed && post.remixRevokedAt.isNullOrBlank()

    val images = remember(post.mediaUrls, post.mediaUrlLegacy) {
        UbuntuStorageUrl.rewriteAll(
            if (post.mediaUrls.isNotEmpty()) post.mediaUrls else listOfNotNull(post.mediaUrlLegacy)
        )
    }

    fullScreenImageIndex?.let { initialIndex ->
        FullScreenImageDialog(
            mediaItems = images.mapIndexed { index, url -> MediaItem("${post.id}:$index", url, postMediaType(url)) },
            initialIndex = initialIndex,
            bottomOverlay = { page ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProfileImage(ownerId = post.userId, 
                            imageUrl = post.userProfileImage.orEmpty(),
                            size = 38.dp,
                            isVerified = post.isVerified,
                            fallbackName = post.userName,
                            modifier = Modifier.clickable {
                                fullScreenImageIndex = null
                                onUserClick(post.userId)
                            }
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(post.userName, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                DateUtils.formatRelativeDate(post.createdAt),
                                color = Color.White.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (images.size > 1) {
                            Text(
                                "${page + 1} / ${images.size}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    if (post.content.isNotBlank()) {
                        Text(
                            post.content,
                            color = Color.White,
                            modifier = Modifier.padding(top = 10.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = postOverlayClearance),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = reactionPreview(post.reactionCounts, reactionTotal),
                            color = Color.White,
                            modifier = Modifier.weight(1f).clickable {
                                fullScreenImageIndex = null
                                onReactionSummaryClick()
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${post.commentCount} comentarios",
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.clickable {
                                fullScreenImageIndex = null
                                onCommentClick()
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            onDismiss = { fullScreenImageIndex = null }
        )
    }

    if (showRemixDialog) {
        AlertDialog(
            onDismissRequest = { showRemixDialog = false },
            title = { Text("Crear remix") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "La publicación original y su autor quedarán enlazados de forma visible.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = remixComment,
                        onValueChange = { remixComment = it.take(5000) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Añade tu comentario (opcional)") },
                        minLines = 3,
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemixClick?.invoke(remixComment.trim())
                        remixComment = ""
                        showRemixDialog = false
                    }
                ) {
                    Text("Publicar remix")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemixDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .then(if (conectate.com.BuildConfig.DEBUG) Modifier.semantics { testTagsAsResourceId = true }.testTag("feed_post_${post.id}") else Modifier)
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(0.dp) // Facebook style cards are usually flat at sides when in list
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProfileImage(ownerId = post.userId, 
                    imageUrl = post.userProfileImage ?: "",
                    size = 40.dp,
                    isVerified = post.isVerified,
                    fallbackName = post.userName,
                    momentViewed = authorMoment?.isViewed,
                    modifier = Modifier.clickable {
                        if (authorMoment != null) onMomentClick(authorMoment.id) else onUserClick(post.userId)
                    }
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = post.userName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).clickable { onUserClick(post.userId) }
                        )
                        if (post.isVerified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Verificado",
                                tint = Color(0xFF1DA1F2),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = DateUtils.formatRelativeDate(post.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (!isOwner && authorFollowState != null) {
                    TextButton(
                        onClick = {
                            if (authorFollowState == conectate.com.data.model.AuthorFollowState.NOT_FOLLOWING) onFollowClick()
                            else onUserClick(post.userId)
                        },
                        enabled = !followBusy,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        if (followBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(authorFollowState.label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                }

                if ((isOwner && showOwnerActions) || (!isOwner && showReportAction) || onOutcomeClick != null) {
                    var showMenu by remember { mutableStateOf(false) }
                    
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Opciones",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            onOutcomeClick?.let { open -> DropdownMenuItem(
                                text = { Text("Cómo terminó esta petición") },
                                onClick = { showMenu = false; open() },
                                leadingIcon = { Icon(Icons.Default.TaskAlt,null) },
                            ) }
                            if (isOwner && showOwnerActions) {
                                val visibilityText = if (post.visibility == "private") "Hacer público" else "Hacer privado"
                                val newVisibility = if (post.visibility == "private") "public" else "private"

                                DropdownMenuItem(
                                    text = { Text(visibilityText) },
                                    onClick = {
                                        onVisibilityClick(newVisibility)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Lock, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Editar") },
                                    onClick = {
                                        onEditClick()
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(if (remixAvailable) "Desactivar remixes" else "Permitir remixes")
                                    },
                                    onClick = {
                                        onRemixPermissionClick(!remixAvailable, post.remixAudience)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Repeat, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Eliminar") },
                                    onClick = {
                                        onDeleteClick()
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) },
                                    colors = MenuDefaults.itemColors(textColor = Color.Red)
                                )
                            } else if (!isOwner && showReportAction) {
                                if (onWhyThisPost != null) DropdownMenuItem(
                                    text = { Text("¿Por qué veo esto?") },
                                    onClick = {
                                        onWhyThisPost()
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Info, contentDescription = null)
                                    }
                                )
                                if (onNotInterested != null) DropdownMenuItem(
                                    text = { Text("No me interesa") },
                                    onClick = {
                                        onNotInterested()
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.ThumbDown, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reportar") },
                                    onClick = {
                                        onReportClick()
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Report,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                                if (remixAvailable && onRemixClick != null) {
                                    DropdownMenuItem(
                                        text = { Text("Crear remix") },
                                        onClick = {
                                            showRemixDialog = true
                                            showMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Repeat, contentDescription = null)
                                        }
                                    )
                                }
                            }
                            if (onSaveClick != null) DropdownMenuItem(
                                text = { Text("Guardar en colección") },
                                onClick = {
                                    onSaveClick()
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.BookmarkBorder, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            }

            post.remixSourcePostId?.takeIf { it.isNotBlank() }?.let { sourcePostId ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable {
                            if (onOriginalPostClick != null) onOriginalPostClick(sourcePostId)
                            else runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("conectate://post/${android.net.Uri.encode(sourcePostId)}"))
                                    .setPackage(context.packageName))
                            }.onFailure { conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo abrir la publicación original.") }
                        },
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Repeat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Remix de ${post.remixSourceUserName ?: "publicación original"}",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "Ver publicación original",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

            SensitiveContentGate(isSensitive = post.isSensitive) {
                Column {
                    post.surveyId?.let { surveyId ->
                        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Encuesta", style = MaterialTheme.typography.titleMedium)
                                Text("${post.surveyResponseCount} respuestas · Una por cuenta", style = MaterialTheme.typography.bodySmall)
                                Text("Las respuestas son privadas para el creador.", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("conectate://survey/$surveyId")).setPackage(context.packageName))
                                }) { Text("Ver encuesta") }
                            }
                        }
                    }
                    // Content Text
                    if (post.content.isNotBlank()) {
                        LinkText(
                            text = post.content,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            maxLines = if (contentExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { layout ->
                                if (!contentExpanded) contentHasOverflow = layout.hasVisualOverflow
                            },
                            onUrlClick = openUrl,
                            onTextClick = onPostClick,
                            onMentionClick = onMentionClick,
                        )
                        if (contentHasOverflow || contentExpanded) {
                            Text(
                                text = if (contentExpanded) "Ver menos" else "Ver más",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .clickable { contentExpanded = !contentExpanded }
                                    .padding(horizontal = 12.dp, vertical = 2.dp),
                            )
                        }
                    }

                    if (images.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        if (images.size == 1) {
                            PostMediaTile(images.first(), Modifier.fillMaxWidth().height(300.dp)) {
                                fullScreenImageIndex = 0
                            }
                        } else {
                            val pagerState = rememberPagerState(pageCount = { images.size })
                            Box(Modifier.fillMaxWidth().height(300.dp)) {
                                HorizontalPager(pagerState, modifier = Modifier.fillMaxSize()) { page ->
                                    PostMediaTile(images[page], Modifier.fillMaxSize()) { fullScreenImageIndex = page }
                                }
                                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.Center) {
                                    repeat(images.size) { index ->
                                        Box(Modifier.padding(2.dp).clip(CircleShape)
                                            .background(if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .5f))
                                            .size(6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Stats row (Likes, Comments, and Shares)
            if (reactionTotal > 0 || post.commentCount > 0 || post.shareCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (reactionTotal > 0) {
                        Text(
                            text = reactionPreview(post.reactionCounts, reactionTotal),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(onClick = onReactionSummaryClick)
                        )
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (post.commentCount > 0) {
                            Text(
                                text = "${post.commentCount} comentarios",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { onCommentClick() }
                            )
                        }
                        
                        if (post.shareCount > 0) {
                            Text(
                                text = "${post.shareCount} veces compartido",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp)

            // Interaction Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .combinedClickable(
                            onClick = onLikeClick,
                            onLongClick = { showReactionPicker = true }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (currentReaction == null) {
                            Icon(
                                imageVector = Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(reactionEmoji(currentReaction), fontSize = 20.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            currentReaction?.let(::reactionLabel) ?: "Me gusta",
                            fontSize = 13.sp,
                            maxLines = 1,
                            color = if (currentReaction != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = showReactionPicker,
                        onDismissRequest = { showReactionPicker = false }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            POST_REACTION_ORDER.forEach { reaction ->
                                IconButton(
                                    onClick = {
                                        showReactionPicker = false
                                        onReactionSelect(reaction)
                                    }
                                ) {
                                    Text(
                                        reactionEmoji(reaction),
                                        fontSize = if (currentReaction == reaction) 30.sp else 26.sp
                                    )
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = onCommentClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Comentar", fontSize = 13.sp, maxLines = 1)
                }

                var showShareSheet by remember { mutableStateOf(false) }
                val context = androidx.compose.ui.platform.LocalContext.current

                Box(modifier = Modifier.weight(1f)) {
                    TextButton(
                        onClick = { showShareSheet = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (post.sharedByMe) Color(0xFF00bcd4) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = if (post.sharedByMe) Icons.Default.RepeatOn else Icons.Outlined.Share,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (post.sharedByMe) "Compartido" else "Compartir", fontSize = 13.sp, maxLines = 1)
                    }

                    if (showShareSheet) {
                        ShareOptionsSheet(
                            onDismiss = { showShareSheet = false },
                            onSendToFriend = sendToFriend,
                            onExternalShare = {
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT,
                                        "¡Mira esta publicación en Novvin!\n${conectate.com.presentation.navigation.postShareLink(post.id)}" +
                                            post.content.take(100).takeIf(String::isNotBlank)?.let { "\n\n$it" }.orEmpty())
                                    type = "text/plain"
                                }
                                val shareIntent = android.content.Intent.createChooser(sendIntent, "Compartir publicación")
                                runCatching { context.startActivity(shareIntent) }.onFailure {
                                    conectate.com.presentation.common.AppFeedbackBus.show("No se pudo abrir Compartir. Vuelve a intentarlo.")
                                }
                            },
                            onToggleProfileShare = {
                                onShareClick() // Existing toggle logic
                            },
                            isSharedToProfile = post.sharedByMe,
                            onCopyLink = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Publicación de Novvin",
                                    conectate.com.presentation.navigation.postShareLink(post.id)))
                                conectate.com.presentation.common.AppFeedbackBus.show("Enlace copiado")
                            }
                        )
                    }
                }
            }
        }
    }
}

private val POST_REACTION_ORDER = listOf("LIKE", "LOVE", "HAHA", "WOW", "SAD", "ANGRY", "CARE", "FIRE")

private fun reactionEmoji(type: String): String = when (type.uppercase()) {
    "LOVE" -> "❤️"
    "HAHA" -> "😂"
    "WOW" -> "😮"
    "SAD" -> "😢"
    "ANGRY" -> "😡"
    "CARE" -> "🤗"
    "FIRE" -> "🔥"
    else -> "👍"
}

private fun reactionLabel(type: String): String = when (type.uppercase()) {
    "LOVE" -> "Me encanta"
    "HAHA" -> "Me divierte"
    "WOW" -> "Me asombra"
    "SAD" -> "Me entristece"
    "ANGRY" -> "Me enoja"
    "CARE" -> "Me importa"
    "FIRE" -> "Genial"
    else -> "Me gusta"
}

private fun reactionPreview(counts: Map<String, Int>, total: Int): String {
    val breakdown = POST_REACTION_ORDER
        .filter { (counts[it] ?: 0) > 0 }
        .sortedByDescending { counts[it] ?: 0 }
        .take(4)
        .joinToString(separator = "  ") { type ->
            "${reactionEmoji(type)} ${counts[type]}"
        }
        .ifBlank { "👍 $total" }
    val shownTotal = counts.values.filter { it > 0 }.sum()
    return if (shownTotal == total) breakdown else "$breakdown · $total"
}
