package conectate.com.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.Message
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageMediaClusterGrid(
    messages: List<Message>,
    selectedMessageIds: Set<String>,
    onResolveUrl: suspend (String) -> String?,
    onMessageClick: (Message, String?) -> Unit,
    onMessageLongClick: (Message) -> Unit,
    modifier: Modifier = Modifier,
    onOpenMedia: ((messages: List<Message>, initialIndex: Int) -> Unit)? = null
) {
    if (messages.isEmpty()) return

    val visibleMessages = messages.take(4)
    val hiddenCount = (messages.size - visibleMessages.size).coerceAtLeast(0)
    val openMedia: (Message, String?) -> Unit = { message, resolvedUrl ->
        val initialIndex = messages.indexOfFirst { it.id == message.id }
        if (onOpenMedia != null && initialIndex >= 0) {
            onOpenMedia(messages, initialIndex)
        } else {
            onMessageClick(message, resolvedUrl)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (visibleMessages.size) {
            1 -> {
                MessageMediaClusterTile(
                    message = visibleMessages.first(),
                    isSelected = selectedMessageIds.contains(visibleMessages.first().id),
                    onResolveUrl = onResolveUrl,
                    onMessageClick = openMedia,
                    onMessageLongClick = onMessageLongClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
            }

            2 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    visibleMessages.forEach { mediaMessage ->
                        MessageMediaClusterTile(
                            message = mediaMessage,
                            isSelected = selectedMessageIds.contains(mediaMessage.id),
                            onResolveUrl = onResolveUrl,
                            onMessageClick = openMedia,
                            onMessageLongClick = onMessageLongClick,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    }
                }
            }

            3 -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(232.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MessageMediaClusterTile(
                        message = visibleMessages[0],
                        isSelected = selectedMessageIds.contains(visibleMessages[0].id),
                        onResolveUrl = onResolveUrl,
                        onMessageClick = openMedia,
                        onMessageLongClick = onMessageLongClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        visibleMessages.drop(1).forEach { mediaMessage ->
                            MessageMediaClusterTile(
                                message = mediaMessage,
                                isSelected = selectedMessageIds.contains(mediaMessage.id),
                                onResolveUrl = onResolveUrl,
                                onMessageClick = openMedia,
                                onMessageLongClick = onMessageLongClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    visibleMessages.chunked(2).forEachIndexed { rowIndex, rowMessages ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowMessages.forEachIndexed { columnIndex, mediaMessage ->
                                val moreOverlay = if (rowIndex == 1 && columnIndex == 1) hiddenCount else 0
                                MessageMediaClusterTile(
                                    message = mediaMessage,
                                    isSelected = selectedMessageIds.contains(mediaMessage.id),
                                    onResolveUrl = onResolveUrl,
                                    onMessageClick = openMedia,
                                    onMessageLongClick = onMessageLongClick,
                                    moreOverlay = moreOverlay,
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                )
                            }
                            if (rowMessages.size == 1) {
                                Spacer(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageMediaClusterTile(
    message: Message,
    isSelected: Boolean,
    onResolveUrl: suspend (String) -> String?,
    onMessageClick: (Message, String?) -> Unit,
    onMessageLongClick: (Message) -> Unit,
    modifier: Modifier = Modifier,
    moreOverlay: Int = 0
) {
    val context = LocalContext.current
    val resolvedUrl by produceState<String?>(initialValue = message.media_url, key1 = message.media_url) {
        val rawUrl = message.media_url?.trim().orEmpty()
        if (rawUrl.isBlank()) {
            value = null
            return@produceState
        }

        value = onResolveUrl(rawUrl) ?: rawUrl
    }
    val imageUrl = UbuntuStorageUrl.rewrite(resolvedUrl ?: message.media_url)
    val imageRequest = androidx.compose.runtime.remember(context, imageUrl) {
        MediaImageRequestFactory.preview(context, imageUrl)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { onMessageClick(message, resolvedUrl ?: message.media_url) },
                onLongClick = { onMessageLongClick(message) }
            )
    ) {
        SubcomposeAsyncImage(
            model = imageRequest,
            contentDescription = "Imagen del chat",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            loading = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF151515)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
            },
            error = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF202020)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = "Error",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        )

        val normalizedType = message.content_type.trim().lowercase()
        if (normalizedType == "gif" || normalizedType == "image/gif") {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "GIF",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (isVideoMediaType(normalizedType)) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = "Reproducir video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
            )
        }

        if (moreOverlay > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.52f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+$moreOverlay",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
            )
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Seleccionado",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(18.dp)
            )
        }
    }
}
