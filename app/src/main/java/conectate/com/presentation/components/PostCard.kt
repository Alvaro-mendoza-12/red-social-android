package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import conectate.com.data.model.Post
import conectate.com.util.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostCard(
    post: Post,
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    onShareClick: () -> Unit,
    onUserClick: (String) -> Unit,
    isOwner: Boolean = false,
    onDeleteClick: () -> Unit = {},
    onVisibilityClick: (String) -> Unit = {},
    onEditClick: () -> Unit = {},
    onUrlClick: (String) -> Unit = {},
    onPostClick: () -> Unit = {},
    onReactionSelected: (ReactionType) -> Unit = { onLikeClick() },
    onReactionRemoved: () -> Unit = onLikeClick,
    onSaveClick: () -> Unit = {},
    onSendToFriend: () -> Unit = {}
) {
    val context = LocalContext.current
    var showFullScreenImage by remember { mutableStateOf<String?>(null) }
    var showReactionBreakdown by remember { mutableStateOf(false) }

    // Performance: Pre-calculate relative date to avoid overhead during recomposition
    val relativeDate = remember(post.createdAt) {
        DateUtils.formatRelativeDate(post.createdAt)
    }
    val currentReaction = remember(post.myReaction, post.likedByMe) {
        reactionTypeFromWire(post.myReaction) ?: if (post.likedByMe) ReactionType.LIKE else null
    }
    val reactionCounts = remember(post.reactionCounts, post.likeCount) {
        val parsedCounts = post.reactionCounts
            .mapNotNull { (reaction, count) ->
                reactionTypeFromWire(reaction)
                    ?.takeIf { count > 0 }
                    ?.let { it to count }
            }
            .toMap()

        parsedCounts.takeIf { it.isNotEmpty() }
            ?: if (post.likeCount > 0) mapOf(ReactionType.LIKE to post.likeCount) else emptyMap()
    }
    val totalReactions = remember(reactionCounts) { reactionCounts.values.sum() }
    val topReactions = remember(reactionCounts) {
        reactionCounts.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    if (showFullScreenImage != null) {
        FullScreenImageDialog(
            imageUrl = showFullScreenImage!!,
            onDismiss = { showFullScreenImage = null }
        )
    }
    if (showReactionBreakdown) {
        ReactionBreakdownDialog(
            reactionCounts = reactionCounts,
            onDismiss = { showReactionBreakdown = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProfileImage(
                imageUrl = post.userProfileImage ?: "",
                size = 36.dp,
                isVerified = post.isVerified,
                modifier = Modifier.clickable { onUserClick(post.userId) }
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = post.userName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.sp
                        ),
                        modifier = Modifier.clickable { onUserClick(post.userId) }
                    )
                    if (post.isVerified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Verificado",
                            tint = Color(0xFF0095F6),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = relativeDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = { /* More actions */ }) {
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Content Text
        if (post.content.isNotBlank()) {
            LinkText(
                text = post.content,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { onPostClick() },
                onUrlClick = onUrlClick
            )
        }

        // Content Media
        val images = remember(post.mediaUrls, post.mediaUrlLegacy) {
            if (post.mediaUrls.isNotEmpty()) post.mediaUrls else listOfNotNull(post.mediaUrlLegacy)
        }

        if (images.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            if (images.size == 1) {
                val imageRequest = remember(images[0]) {
                    ImageRequest.Builder(context)
                        .data(images[0])
                        .crossfade(true)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .clickable { showFullScreenImage = images.first() },
                    contentScale = ContentScale.FillWidth
                )
            } else {
                val pagerState = rememberPagerState(pageCount = { images.size })
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val pagerImageRequest = remember(images[page]) {
                            ImageRequest.Builder(context)
                                .data(images[page])
                                .crossfade(true)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()
                        }
                        AsyncImage(
                            model = pagerImageRequest,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clickable { showFullScreenImage = images[page] },
                            contentScale = ContentScale.Crop
                        )
                    }

                    if (images.size > 1) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${pagerState.currentPage + 1}/${images.size}",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        if (totalReactions > 0 || post.commentCount > 0 || post.shareCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReactionSummaryBar(
                    reactionCounts = reactionCounts,
                    totalCount = totalReactions,
                    topReactions = topReactions,
                    onClick = { showReactionBreakdown = true }
                )

                Spacer(modifier = Modifier.weight(1f))

                val activitySummary = buildList {
                    if (post.commentCount > 0) add("${post.commentCount} comentarios")
                    if (post.shareCount > 0) add("${post.shareCount} compartidos")
                }.joinToString(" · ")
                if (activitySummary.isNotBlank()) {
                    Text(
                        text = activitySummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Interaction Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReactionCarouselButton(
                currentReaction = currentReaction,
                onReactionSelected = onReactionSelected,
                onReactionRemoved = onReactionRemoved,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp)
            )

            IconButton(onClick = onCommentClick) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = "Comentar",
                    modifier = Modifier.size(26.dp)
                )
            }

            IconButton(onClick = onSendToFriend) {
                Icon(
                    imageVector = Icons.Outlined.Send,
                    contentDescription = "Enviar por mensaje",
                    modifier = Modifier.size(26.dp)
                )
            }

            IconButton(onClick = onShareClick) {
                Icon(
                    imageVector = Icons.Default.Repeat,
                    contentDescription = if (post.sharedByMe) "Quitar de mi perfil" else "Compartir en mi perfil",
                    tint = if (post.sharedByMe) Color(0xFF00B894) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onSaveClick) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkBorder,
                    contentDescription = "Guardar",
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Stats & Caption
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            if (totalReactions > 0) {
                Text(
                    text = "$totalReactions reacciones",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            if (post.content.isNotBlank()) {
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = post.userName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = post.content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (post.commentCount > 0) {
                Text(
                    text = "Ver los ${post.commentCount} comentarios",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { onCommentClick() }
                )
            }
        }
    }
}

private fun reactionTypeFromWire(value: String?): ReactionType? {
    val normalized = value
        ?.trim()
        ?.uppercase()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return ReactionType.entries.firstOrNull { it.name == normalized }
}
