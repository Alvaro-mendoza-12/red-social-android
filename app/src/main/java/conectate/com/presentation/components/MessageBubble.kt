package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import conectate.com.data.model.LinkPreview
import conectate.com.data.model.Message
import conectate.com.presentation.theme.*
import conectate.com.util.Constants
import conectate.com.util.ChatContactPayload
import java.util.*

@Composable
@androidx.compose.foundation.ExperimentalFoundationApi
fun MessageBubble(
    message: Message,
    isFromCurrentUser: Boolean,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: (String?) -> Unit = {},
    onPlayAudio: (Message) -> Unit = {},
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    progress: Float = 0f,
    duration: String = "0:00",
    onResolveUrl: suspend (String) -> String? = { it },
    senderName: String? = null,
    senderEmail: String? = null,
    isVerified: Boolean = false,
    userBadges: List<conectate.com.data.model.Badge> = emptyList(),
    onUrlClick: (String) -> Unit = {},
    onSwipeReply: (Message) -> Unit = {},
    onSenderClick: (String) -> Unit = {},
    currentUserId: String? = null,
    onReactionClick: (String) -> Unit = {},
    linkPreviews: Map<String, LinkPreview> = emptyMap(),
    onRequestLinkPreview: (String) -> Unit = {},
    onOpenContactChat: (ChatContactPayload) -> Unit = {},
    onAddToDeviceContacts: (ChatContactPayload) -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()
    val isSticker = message.content_type == "sticker"
    
    val bubbleColor = if (isFromCurrentUser) {
        if (isDark) MessageSentBackgroundDark else MessageSentBackground
    } else {
        if (isDark) MessageReceivedBackgroundDark else MessageReceivedBackground
    }
    
    val contentColor = if (isFromCurrentUser) {
        MessageSentTextColor
    } else {
        if (isDark) MessageReceivedTextColorDark else MessageReceivedTextColorLight
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (isFromCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isFromCurrentUser) Alignment.End else Alignment.Start
        ) {
            if (!isFromCurrentUser && senderName != null && !isSticker) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }

            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (isFromCurrentUser) 18.dp else 4.dp,
                            bottomEnd = if (isFromCurrentUser) 4.dp else 18.dp
                        )
                    )
                    .background(if (isSticker) Color.Transparent else bubbleColor)
                    .combinedClickable(
                        onClick = { onClick(message.media_url) },
                        onLongClick = onLongClick
                    )
                    .padding(
                        if (isSticker) {
                            PaddingValues(0.dp)
                        } else {
                            PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        }
                    )
            ) {
                Column {
                    if (message.reply_to != null) {
                        ReplyPreview(message.reply_to, isFromCurrentUser)
                    }

                    when (message.content_type) {
                        "text" -> {
                            val firstUrl = remember(message.content) { extractFirstUrl(message.content) }
                            val normalizedUrl = remember(firstUrl) { firstUrl?.let(::normalizePreviewUrl) }
                            LaunchedEffect(message.id, normalizedUrl) {
                                if (!normalizedUrl.isNullOrBlank() && !linkPreviews.containsKey(normalizedUrl)) {
                                    onRequestLinkPreview(normalizedUrl)
                                }
                            }
                            LinkText(
                                text = message.content,
                                color = contentColor,
                                fontSize = 15.sp,
                                onUrlClick = onUrlClick
                            )
                            val preview = normalizedUrl?.let { linkPreviews[it] }
                            if (normalizedUrl != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                if (preview != null) {
                                    LinkPreviewCardCompact(
                                        preview = preview,
                                        onClick = { onUrlClick(preview.url) }
                                    )
                                } else {
                                    BasicLinkPreviewCard(
                                        url = normalizedUrl,
                                        onClick = { onUrlClick(normalizedUrl) }
                                    )
                                }
                            }
                        }
                        "image", "gif" -> {
                            ImageContent(message.media_url)
                        }
                        "video" -> {
                            VideoContent(message.media_url)
                        }
                        "audio" -> {
                            AudioContent(isPlaying, progress, duration, contentColor, onPlayAudio, message)
                        }
                        "sticker" -> {
                            StickerContent(message.media_url, message.content)
                        }
                        else -> {
                            Text(text = message.content, color = contentColor)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatIsoTimestamp(message.created_at ?: ""),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = contentColor.copy(alpha = 0.6f)
                        )
                        if (isFromCurrentUser) {
                            Spacer(modifier = Modifier.width(4.dp))
                            StatusIcon(message.status, contentColor.copy(alpha = 0.6f))
                        }
                    }
                }
            }
            
            if (message.reactions.isNotEmpty()) {
                ReactionsRow(message.reactions, onReactionClick)
            }
        }
    }
}

@Composable
private fun BasicLinkPreviewCard(
    url: String,
    onClick: () -> Unit
) {
    val host = remember(url) {
        runCatching {
            java.net.URL(url).host.removePrefix("www.")
        }.getOrDefault(url)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun extractFirstUrl(text: String): String? {
    return URL_REGEX.find(text)?.value?.let(::normalizePreviewUrl)
}

private fun normalizePreviewUrl(rawUrl: String): String {
    val clean = rawUrl.trim().trimEnd('.', ',', ';', ')', ']')
    return when {
        clean.startsWith("http://", ignoreCase = true) ||
            clean.startsWith("https://", ignoreCase = true) -> clean
        clean.startsWith("www.", ignoreCase = true) -> "https://$clean"
        else -> "https://$clean"
    }
}

private val URL_REGEX = Regex(
    pattern = """(?i)\b((?:https?://|www\.)[^\s<>()]+|(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s<>()]*)?)"""
)

@Composable
private fun ReplyPreview(replyTo: Message, isFromCurrentUser: Boolean) {
    Row(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.05f))
            .height(IntrinsicSize.Min)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = "Respuesta",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = replyTo.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (isFromCurrentUser) MessageSentTextColor.copy(alpha = 0.7f) else Color.Gray
            )
        }
    }
}

@Composable
private fun ImageContent(url: String?) {
    val context = LocalContext.current
    val imageRequest = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun VideoContent(url: String?) {
    val context = LocalContext.current
    val imageRequest = remember(url) {
        ImageRequest.Builder(context)
            .data(url) // Using video URL as thumbnail source, Coil handles some video frame fetching
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    Box(contentAlignment = Alignment.Center) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .alpha(0.8f),
            contentScale = ContentScale.Crop
        )
        Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun AudioContent(
    isPlaying: Boolean,
    progress: Float,
    duration: String,
    contentColor: Color,
    onPlayAudio: (Message) -> Unit,
    message: Message
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.width(200.dp).clickable { onPlayAudio(message) }
    ) {
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            null,
            tint = contentColor
        )
        Spacer(Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape),
            color = contentColor,
            trackColor = contentColor.copy(alpha = 0.2f)
        )
        Spacer(Modifier.width(8.dp))
        Text(duration, style = MaterialTheme.typography.labelSmall, color = contentColor)
    }
}

@Composable
private fun StickerContent(url: String?, content: String) {
    val context = LocalContext.current
    val imageRequest = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = imageRequest,
            contentDescription = "Sticker",
            modifier = Modifier.size(160.dp)
        )
    } else {
        Text(text = content, fontSize = 40.sp)
    }
}

@Composable
private fun StatusIcon(status: String, tint: Color) {
    val icon = when (status.lowercase()) {
        "read" -> Icons.Default.DoneAll
        "delivered", "received" -> Icons.Default.DoneAll
        "sent" -> Icons.Default.Done
        else -> Icons.Default.AccessTime
    }
    val iconTint = if (status.lowercase() == "read") Color(0xFF34B7F1) else tint
    Icon(icon, null, modifier = Modifier.size(14.dp), tint = iconTint)
}

@Composable
private fun ReactionsRow(reactions: Map<String, List<String>>, onReactionClick: (String) -> Unit) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        reactions.keys.take(3).forEach { emoji ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { onReactionClick(emoji) }
            ) {
                Text(emoji, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 12.sp)
            }
        }
    }
}

private fun formatIsoTimestamp(isoString: String): String {
    return try {
        isoString.substringAfter("T").substringBeforeLast(":").take(5)
    } catch (e: Exception) { "" }
}
