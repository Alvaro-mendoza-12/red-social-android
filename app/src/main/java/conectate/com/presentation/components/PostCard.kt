package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
    onSendToFriend: () -> Unit = {},
    onReportClick: () -> Unit = {},
    showOwnerActions: Boolean = true,
    showReportAction: Boolean = false
) {
    val context = LocalContext.current
    var fullScreenImageIndex by remember { mutableStateOf<Int?>(null) }

    val images = remember(post.mediaUrls, post.mediaUrlLegacy) {
        UbuntuStorageUrl.rewriteAll(
            if (post.mediaUrls.isNotEmpty()) post.mediaUrls else listOfNotNull(post.mediaUrlLegacy)
        )
    }

    fullScreenImageIndex?.let { initialIndex ->
        FullScreenImageDialog(
            mediaItems = images.mapIndexed { index, url -> MediaItem("${post.id}:$index", url, "image") },
            initialIndex = initialIndex,
            onDismiss = { fullScreenImageIndex = null }
        )
    }

    Card(
        modifier = Modifier
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
                CircularProfileImage(
                    imageUrl = post.userProfileImage ?: "",
                    size = 40.dp,
                    isVerified = post.isVerified,
                    fallbackName = post.userName,
                    modifier = Modifier.clickable { onUserClick(post.userId) }
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = post.userName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onUserClick(post.userId) }
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
                
                Spacer(modifier = Modifier.weight(1f))
                
                if ((isOwner && showOwnerActions) || (!isOwner && showReportAction)) {
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
                            if (isOwner) {
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
                                    text = { Text("Eliminar") },
                                    onClick = {
                                        onDeleteClick()
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) },
                                    colors = MenuDefaults.itemColors(textColor = Color.Red)
                                )
                            } else {
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
                            }
                        }
                    }
                }
            }

            // Content Text
            if (post.content.isNotBlank()) {
                LinkText(
                    text = post.content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).clickable { onPostClick() },
                    onUrlClick = onUrlClick
                )
            }

            // Content Image(s)
            if (images.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                if (images.size == 1) {
                    val image = images.first()
                    val request = remember(image) {
                        MediaImageRequestFactory.preview(context, image)
                    }
                    SubcomposeAsyncImage(
                        model = request,
                        contentDescription = "Imagen de publicación",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 420.dp)
                            .clickable { fullScreenImageIndex = 0 },
                        contentScale = ContentScale.Crop,
                        loading = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        },
                        error = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BrokenImage,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                } else {
                    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { images.size })
                    Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                        androidx.compose.foundation.pager.HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            val image = images[page]
                            val request = remember(image) {
                                MediaImageRequestFactory.preview(context, image)
                            }
                            SubcomposeAsyncImage(
                                model = request,
                                contentDescription = "Imagen de publicación",
                                modifier = Modifier.fillMaxSize().clickable { fullScreenImageIndex = page },
                                contentScale = ContentScale.Crop,
                                loading = {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                },
                                error = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BrokenImage,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            )
                        }
                        
                        // Page Indicator
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            repeat(images.size) { iteration ->
                                val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f)
                                Box(
                                    modifier = Modifier
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .size(6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Stats row (Likes, Comments, and Shares)
            if (post.likeCount > 0 || post.commentCount > 0 || post.shareCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (post.likeCount > 0) {
                        Icon(
                            imageVector = Icons.Default.ThumbUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = post.likeCount.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

            Divider(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp)

            // Interaction Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(
                    onClick = onLikeClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (post.likedByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = if (post.likedByMe) Icons.Default.ThumbUp else Icons.Outlined.FavoriteBorder, // FavoriteBorder because ThumbUp looks heavy if not filled
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Me gusta", fontSize = 13.sp, maxLines = 1)
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
                            onSendToFriend = {
                                onSendToFriend()
                            },
                            onExternalShare = {
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, "¡Mira esta publicación en Novvin!\nhttps://conectate.com/post/${post.id}\n\nO abre directamente en la app:\nconectate://post/${post.id}\n\n${post.content.take(100)}...")
                                    type = "text/plain"
                                }
                                val shareIntent = android.content.Intent.createChooser(sendIntent, "Compartir publicación")
                                context.startActivity(shareIntent)
                            },
                            onToggleProfileShare = {
                                onShareClick() // Existing toggle logic
                            },
                            isSharedToProfile = post.sharedByMe
                        )
                    }
                }
            }
        }
    }
}
