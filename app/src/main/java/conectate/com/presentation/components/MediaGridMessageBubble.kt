package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.Message
import conectate.com.presentation.theme.currentChatAccentColors

internal data class MessageMediaViewerSelection(
    val items: List<MediaItem>,
    val initialIndex: Int
)

internal fun buildMessageMediaViewerSelection(
    messages: List<Message>,
    selectedMessageId: String,
    reverseOrder: Boolean
): MessageMediaViewerSelection? {
    val eligibleMessages = messages.filter { message ->
        isGalleryMediaType(message.content_type) &&
            !message.media_url.isNullOrBlank() &&
            !message.deleted_for_all
    }
    val orderedMessages = if (reverseOrder) eligibleMessages.asReversed() else eligibleMessages
    val initialIndex = orderedMessages.indexOfFirst { it.id == selectedMessageId }
    if (initialIndex < 0) return null

    return MessageMediaViewerSelection(
        items = orderedMessages.map { message ->
            MediaItem(
                id = message.id,
                url = requireNotNull(message.media_url),
                type = message.content_type,
                thumbnailUrl = message.thumbnail_url,
            )
        },
        initialIndex = initialIndex
    )
}

internal fun isClusterableMediaGridMessage(message: Message): Boolean {
    val normalizedType = message.content_type.trim().lowercase()
    return isGalleryMediaType(normalizedType) &&
        normalizedType != "sticker" &&
        !message.media_url.isNullOrBlank() &&
        message.status.lowercase() !in setOf("sending", "pending", "failed") &&
        !message.deleted_for_all
}

internal fun mediaGridCaption(messages: List<Message>): String = messages
    .asSequence()
    .map { it.content.trim() }
    .firstOrNull {
        it.isNotBlank() && it !in setOf(
            "🖼️ Imagen",
            "📷 Imagen",
            "🎥 Video",
            "🌀 GIF"
        )
    }
    .orEmpty()

internal fun mediaGridOverlayCount(totalItems: Int): Int {
    return if (totalItems > 4) totalItems - 4 else 0
}

internal fun mediaGridClusterAt(
    messages: List<Message>,
    index: Int,
    maxGapMillis: Long = 60_000L
): List<Message>? {
    val message = messages.getOrNull(index) ?: return emptyList()
    if (!isClusterableMediaGridMessage(message)) return listOf(message)

    val previous = messages.getOrNull(index - 1)
    if (
        previous != null &&
        previous.sender_id == message.sender_id &&
        isClusterableMediaGridMessage(previous) &&
        kotlin.math.abs(message.timestampMillis - previous.timestampMillis) <= maxGapMillis
    ) return null

    val cluster = mutableListOf(message)
    var cursor = index + 1
    while (cursor < messages.size) {
        val next = messages[cursor]
        if (
            next.sender_id == message.sender_id &&
            isClusterableMediaGridMessage(next) &&
            kotlin.math.abs(next.timestampMillis - cluster.last().timestampMillis) <= maxGapMillis
        ) {
            cluster += next
            cursor += 1
        } else {
            break
        }
    }
    return cluster
}

@Composable
fun MediaGridMessageBubble(
    messages: List<Message>,
    isFromCurrentUser: Boolean,
    onClick: (Message) -> Unit,
    onLongClick: (Message) -> Unit = {},
    onResolveUrl: suspend (String) -> String? = { it }
) {
    if (messages.isEmpty()) return

    val displayMessages = messages.take(4)
    val overlayCount = mediaGridOverlayCount(messages.size)
    val chatAccentColors = currentChatAccentColors()
    val bubbleColor = if (isFromCurrentUser) {
        chatAccentColors.sentBackground
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val arrangement = if (isFromCurrentUser) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = arrangement
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(2.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (displayMessages.size) {
                    1 -> MediaGridItem(
                        message = displayMessages[0],
                        onClick = onClick,
                        onLongClick = onLongClick,
                        onResolveUrl = onResolveUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )

                    2 -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            displayMessages.forEach { message ->
                                MediaGridItem(
                                    message = message,
                                    onClick = onClick,
                        onLongClick = onLongClick,
                                    onResolveUrl = onResolveUrl,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                        }
                    }

                    3 -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            MediaGridItem(
                                message = displayMessages[0],
                                onClick = onClick,
                        onLongClick = onLongClick,
                                onResolveUrl = onResolveUrl,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                displayMessages.drop(1).forEach { message ->
                                    MediaGridItem(
                                        message = message,
                                        onClick = onClick,
                        onLongClick = onLongClick,
                                        onResolveUrl = onResolveUrl,
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            displayMessages.chunked(2).forEachIndexed { rowIndex, rowMessages ->
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    rowMessages.forEachIndexed { columnIndex, message ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        ) {
                                            MediaGridItem(
                                                message = message,
                                                onClick = onClick,
                        onLongClick = onLongClick,
                                                onResolveUrl = onResolveUrl,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            if (rowIndex == 1 && columnIndex == 1 && overlayCount > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.56f))
                                                        .clickable { onClick(message) },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "+$overlayCount",
                                                        color = Color.White,
                                                        fontSize = 24.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                mediaGridCaption(messages).takeIf(String::isNotBlank)?.let { caption ->
                    Text(
                        text = caption,
                        color = if (isFromCurrentUser) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MediaGridItem(
    message: Message,
    onClick: (Message) -> Unit,
    onLongClick: (Message) -> Unit = {},
    modifier: Modifier = Modifier,
    onResolveUrl: suspend (String) -> String? = { it }
) {
    val context = LocalContext.current
    val rawUrl = message.media_url?.trim().orEmpty()
    val resolvedUrl by produceState<String?>(initialValue = rawUrl.takeIf(String::isNotBlank), key1 = rawUrl) {
        if (rawUrl.isBlank()) {
            value = null
            return@produceState
        }
        value = runCatching { onResolveUrl(rawUrl) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: rawUrl
    }
    val displayUrl = MediaImageRequestFactory.chatPreviewSource(rawUrl, resolvedUrl)
    var requestAttempt by remember(displayUrl) { mutableIntStateOf(0) }
    var requestFailed by remember(displayUrl, requestAttempt) { mutableStateOf(false) }
    val imageRequest = remember(context, displayUrl, requestAttempt) {
        val request = if (requestAttempt == 0) MediaImageRequestFactory.preview(context, displayUrl)
            else MediaImageRequestFactory.fullScreen(context, displayUrl)
        request?.newBuilder()?.setParameter("chat_image_retry", requestAttempt)?.build()
    }
    LaunchedEffect(requestFailed, requestAttempt) {
        if (requestFailed && requestAttempt == 0) { kotlinx.coroutines.delay(350); requestAttempt++ }
    }
    val normalizedType = message.content_type.trim().lowercase()

    Box(
        modifier = modifier
            .background(Color(0xFF202020))
            .combinedClickable(enabled = rawUrl.isNotBlank(), onClick = { onClick(message) }, onLongClick = { onLongClick(message) })
    ) {
        SubcomposeAsyncImage(
            model = imageRequest,
            onError = { requestFailed = true },
            contentDescription = when {
                isVideoMediaType(normalizedType) -> "Video del chat"
                normalizedType == "gif" || normalizedType == "image/gif" -> "GIF del chat"
                else -> "Imagen del chat"
            },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
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
                Box(modifier = Modifier.fillMaxSize().clickable(onClickLabel = "Volver a cargar imagen") { requestAttempt++ }, contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = "Reintentar carga de imagen",
                        tint = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        )

        if (isVideoMediaType(normalizedType)) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = "Reproducir video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(46.dp)
            )
        } else if (normalizedType == "gif" || normalizedType == "image/gif") {
            Text(
                text = "GIF",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.66f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}
