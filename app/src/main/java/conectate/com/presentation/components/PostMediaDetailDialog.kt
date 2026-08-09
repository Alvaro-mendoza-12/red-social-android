package conectate.com.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.data.model.Post
import conectate.com.util.DateUtils

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PostMediaDetailDialog(
    post: Post,
    imageUrls: List<String>,
    initialIndex: Int,
    reactionSummary: String,
    onUserClick: (String) -> Unit,
    onReactionSummaryClick: () -> Unit,
    onCommentClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (imageUrls.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, imageUrls.lastIndex),
        pageCount = { imageUrls.size },
    )
    val dimensionsByPage = remember(post.id, imageUrls) { mutableStateMapOf<Int, Size>() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val currentDimensions = dimensionsByPage[pagerState.currentPage] ?: Size(4f, 5f)
                val layout = calculatePostMediaDetailLayout(
                    viewportWidthDp = maxWidth.value,
                    viewportHeightDp = (maxHeight - 56.dp).value,
                    mediaWidth = currentDimensions.width,
                    mediaHeight = currentDimensions.height,
                )

                Column(Modifier.fillMaxSize()) {
                    Surface(color = Color.Black) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                            }
                            Text(
                                text = "Publicación",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (imageUrls.size > 1) {
                                Text(
                                    "${pagerState.currentPage + 1} / ${imageUrls.size}",
                                    color = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "media:${post.id}") {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(layout.mediaHeightDp.dp)
                                    .background(Color.Black),
                            ) { page ->
                                val url = imageUrls[page]
                                val context = androidx.compose.ui.platform.LocalContext.current
                                val request = remember(context, url) {
                                    MediaImageRequestFactory.fullScreen(context, url)
                                }
                                SubcomposeAsyncImage(
                                    model = request,
                                    contentDescription = "Imagen ${page + 1} de la publicación",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                    onSuccess = { state ->
                                        val drawable = state.result.drawable
                                        if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                                            dimensionsByPage[page] = Size(
                                                drawable.intrinsicWidth.toFloat(),
                                                drawable.intrinsicHeight.toFloat(),
                                            )
                                        }
                                    },
                                    loading = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(color = Color.White)
                                        }
                                    },
                                    error = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.BrokenImage,
                                                contentDescription = "No se pudo cargar la imagen",
                                                tint = Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(40.dp),
                                            )
                                        }
                                    },
                                )
                            }
                        }

                        item(key = "details:${post.id}") {
                            Surface(color = MaterialTheme.colorScheme.surface) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onDismiss()
                                                onUserClick(post.userId)
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProfileImage(
                                            imageUrl = post.userProfileImage.orEmpty(),
                                            size = 42.dp,
                                            isVerified = post.isVerified,
                                            fallbackName = post.userName,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                post.userName,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                DateUtils.formatRelativeDate(post.createdAt),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    if (post.content.isNotBlank()) {
                                        Text(post.content, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    if (reactionSummary.isNotBlank()) {
                                        Text(
                                            reactionSummary,
                                            modifier = Modifier.clickable {
                                                onDismiss()
                                                onReactionSummaryClick()
                                            },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        "${post.commentCount} comentarios",
                                        modifier = Modifier.clickable {
                                            onDismiss()
                                            onCommentClick()
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
