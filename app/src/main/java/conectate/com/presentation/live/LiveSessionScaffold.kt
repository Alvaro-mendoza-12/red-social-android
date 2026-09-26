package conectate.com.presentation.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import conectate.com.R
import conectate.com.data.model.LiveComment
import conectate.com.data.model.LiveCreatorLayoutStyle
import conectate.com.data.model.LiveStream
import conectate.com.data.model.LiveSupporter
import conectate.com.data.model.LiveViewerParticipant
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.WebRtcVideoView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import kotlinx.coroutines.launch

@Composable
fun LiveSessionScaffold(
    stream: LiveStream?,
    viewerCount: Int,
    reactionCount: Long,
    videoTrack: VideoTrack?,
    sharedEglContext: EglBase.Context?,
    mirrorVideo: Boolean,
    comments: List<LiveComment>,
    reactions: List<conectate.com.data.model.LiveReaction>,
    modifier: Modifier = Modifier,
    supporters: List<LiveSupporter> = emptyList(),
    joinedViewers: List<LiveViewerParticipant> = emptyList(),
    onSendComment: (String) -> Unit,
    onSendReaction: () -> Unit,
    onOpenGift: (() -> Unit)? = null,
    onRequestJoin: (() -> Unit)? = null,
    joinRequestLabel: String = "Solicitar unirme",
    creatorLayoutStyle: LiveCreatorLayoutStyle = LiveCreatorLayoutStyle.FUSION,
    commentingEnabled: Boolean = true,
    commentingDisabledReason: String = "El chat está pausado",
    videoScalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
    stateOverlayText: String? = null,
    topStatusBannerText: String? = null,
    placeholderText: String = "Conectando video…",
    leadingTopBarAction: @Composable (() -> Unit)? = null,
    topBarActions: @Composable RowScope.() -> Unit,
    floatingOverlay: @Composable BoxScope.() -> Unit = {},
    creatorControls: @Composable ColumnScope.() -> Unit = {},
    isViewerLayout: Boolean = false,
) {
    var message by remember { mutableStateOf("") }
    var replyingTo by remember(stream?.id) { mutableStateOf<LiveComment?>(null) }

    if (isViewerLayout) {
        LiveViewerExperience(
            stream = stream,
            viewerCount = viewerCount,
            reactionCount = reactionCount,
            videoTrack = videoTrack,
            sharedEglContext = sharedEglContext,
            mirrorVideo = mirrorVideo,
            comments = comments,
            reactions = reactions,
            supporters = supporters,
            joinedViewers = joinedViewers,
            onSendComment = onSendComment,
            onSendReaction = onSendReaction,
            onOpenGift = onOpenGift,
            onRequestJoin = onRequestJoin,
            joinRequestLabel = joinRequestLabel,
            commentingEnabled = commentingEnabled,
            commentingDisabledReason = commentingDisabledReason,
            videoScalingType = videoScalingType,
            stateOverlayText = stateOverlayText,
            topStatusBannerText = topStatusBannerText,
            modifier = modifier,
            leadingTopBarAction = leadingTopBarAction,
            topBarActions = topBarActions,
            floatingOverlay = floatingOverlay,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LiveVideoBackground(
            stream = stream,
            videoTrack = videoTrack,
            sharedEglContext = sharedEglContext,
            mirror = mirrorVideo,
            scalingType = videoScalingType,
            placeholderText = placeholderText,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.42f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f)
                        )
                    )
                )
        )

        LiveReactionsOverlay(
            reactions = reactions,
            modifier = Modifier.fillMaxSize()
        )

        floatingOverlay()

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LiveTopBar(
                stream = stream,
                viewerCount = viewerCount,
                reactionCount = reactionCount,
                supporters = supporters,
                leadingAction = leadingTopBarAction,
                actions = topBarActions,
                modifier = Modifier.fillMaxWidth()
            )
            if (!topStatusBannerText.isNullOrBlank()) {
                LiveTopStatusBanner(text = topStatusBannerText)
            }
        }

        if (stateOverlayText != null) {
            LiveStateOverlay(
                text = stateOverlayText,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 12.dp, bottom = 88.dp)
                .fillMaxWidth(0.72f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveChatOverlay(
                comments = comments,
                supporters = supporters,
                joinedViewers = joinedViewers,
                onReply = { replyingTo = it },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (stream != null) {
            val creatorControlsModifier = when (creatorLayoutStyle) {
                LiveCreatorLayoutStyle.STUDIO -> Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 174.dp)
                LiveCreatorLayoutStyle.IMMERSIVE -> Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 174.dp)
                LiveCreatorLayoutStyle.ROOM -> Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 174.dp)
                LiveCreatorLayoutStyle.FUSION -> Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 174.dp)
            }
            Column(
                modifier = creatorControlsModifier.widthIn(max = 190.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
                content = creatorControls
            )
        }

        if (onOpenGift != null) {
            LiveViewerLikeButton(
                reactionCount = reactionCount,
                onSendReaction = onSendReaction,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 102.dp),
            )
        }

        LiveCommentComposer(
            value = message,
            onValueChange = { if (commentingEnabled) message = it },
            onSend = {
                if (!commentingEnabled) return@LiveCommentComposer
                if (message.isBlank()) return@LiveCommentComposer
                onSendComment(buildLiveReplyMessage(replyingTo, message))
                message = ""
                replyingTo = null
            },
            onOpenGift = onOpenGift,
            onRequestJoin = onRequestJoin,
            joinRequestLabel = joinRequestLabel,
            replyingTo = replyingTo,
            onCancelReply = { replyingTo = null },
            enabled = commentingEnabled,
            disabledReason = commentingDisabledReason,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 16.dp)
        )
    }
}

/**
 * Viewer-first live layout.  The creator controls remain in the legacy scaffold,
 * while viewers get a stable, edge-to-edge composition: identity and live state
 * at the top, guest video in the upper right, chat above the composer and the
 * interaction rail kept clear of the navigation bar.
 */
@Composable
private fun LiveViewerExperience(
    stream: LiveStream?,
    viewerCount: Int,
    reactionCount: Long,
    videoTrack: VideoTrack?,
    sharedEglContext: EglBase.Context?,
    mirrorVideo: Boolean,
    comments: List<LiveComment>,
    reactions: List<conectate.com.data.model.LiveReaction>,
    supporters: List<LiveSupporter>,
    joinedViewers: List<LiveViewerParticipant>,
    onSendComment: (String) -> Unit,
    onSendReaction: () -> Unit,
    onOpenGift: (() -> Unit)?,
    onRequestJoin: (() -> Unit)?,
    joinRequestLabel: String,
    commentingEnabled: Boolean,
    commentingDisabledReason: String,
    videoScalingType: RendererCommon.ScalingType,
    stateOverlayText: String?,
    topStatusBannerText: String?,
    modifier: Modifier,
    leadingTopBarAction: @Composable (() -> Unit)?,
    topBarActions: @Composable RowScope.() -> Unit,
    floatingOverlay: @Composable BoxScope.() -> Unit,
) {
    var message by remember(stream?.id) { mutableStateOf("") }
    var replyingTo by remember(stream?.id) { mutableStateOf<LiveComment?>(null) }

    Box(
        modifier = Modifier
            .then(modifier)
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LiveVideoBackground(
            stream = stream,
            videoTrack = videoTrack,
            sharedEglContext = sharedEglContext,
            mirror = mirrorVideo,
            scalingType = videoScalingType,
            placeholderText = "Conectando directo…",
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.58f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.72f)
                        )
                    )
                )
        )

        LiveReactionsOverlay(reactions = reactions, modifier = Modifier.fillMaxSize())
        floatingOverlay()

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        CircularProfileImage(
                            imageUrl = stream?.hostAvatarUrl,
                            size = 52.dp,
                            showBorder = true,
                            borderColor = Color.White.copy(alpha = 0.88f),
                            borderWidth = 1.5.dp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                text = stream?.hostName?.ifBlank { "Streamer" } ?: "Streamer",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 22.sp
                            )
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Cuenta verificada",
                                tint = Color(0xFF5EEAD4),
                                modifier = Modifier.size(20.dp)
                            )
                            Surface(color = Color(0xFFFF4057), shape = RoundedCornerShape(999.dp)) {
                                Text(
                                    "EN VIVO",
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        topBarActions()
                    }
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.52f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.People, contentDescription = "Espectadores", tint = Color.White, modifier = Modifier.size(18.dp))
                        Text(compactLiveCount(viewerCount.toLong()), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        LiveQualityBars()
                    }
                }
                leadingTopBarAction?.invoke()
            }

            supporters.firstOrNull { it.coinsInLive > 0 }?.let { supporter ->
                Surface(
                    color = Color.Black.copy(alpha = 0.56f),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.fillMaxWidth(0.72f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.EmojiEvents, contentDescription = "Top donadores", tint = Color(0xFFFFC62E), modifier = Modifier.size(20.dp))
                        Text(
                            "Top donadores: 1. ${supporter.name} ${compactLiveCount(supporter.coinsInLive.toLong())}",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(">", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        if (stateOverlayText != null) {
            LiveStateOverlay(
                text = stateOverlayText,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
            )
        }

        LiveViewerChatStream(
            comments = comments,
            joinedViewers = joinedViewers,
            onReply = { replyingTo = it },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.86f)
                .heightIn(min = 84.dp, max = 330.dp)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 8.dp, bottom = 164.dp)
        )

        LiveViewerLikeButton(
            reactionCount = reactionCount,
            onSendReaction = onSendReaction,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 166.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!topStatusBannerText.isNullOrBlank()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.38f),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.fillMaxWidth(0.82f).align(Alignment.CenterHorizontally),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = "Estado de conexión", tint = Color(0xFF5EEAD4), modifier = Modifier.size(16.dp))
                        Text(topStatusBannerText, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (replyingTo != null) {
                Surface(color = Color.Black.copy(alpha = 0.56f), shape = RoundedCornerShape(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.Reply,
                            contentDescription = "Responder",
                            tint = Color(0xFF5EEAD4),
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Respondiendo a ${replyingTo?.userName?.ifBlank { "Usuario" } ?: "Usuario"}",
                                color = Color(0xFF5EEAD4),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                replyingTo?.content.orEmpty(),
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { replyingTo = null }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar respuesta", tint = Color.White)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (onRequestJoin != null) {
                    LiveJoinRequestButton(label = joinRequestLabel, onClick = onRequestJoin)
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { if (commentingEnabled) message = it },
                    modifier = Modifier.weight(1f),
                    enabled = commentingEnabled,
                    placeholder = { Text(if (commentingEnabled) "Escribe algo…" else commentingDisabledReason, maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(999.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White.copy(alpha = 0.78f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.42f),
                        focusedContainerColor = Color.Black.copy(alpha = 0.32f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.32f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedPlaceholderColor = Color.White.copy(alpha = 0.72f),
                        unfocusedPlaceholderColor = Color.White.copy(alpha = 0.72f),
                        cursorColor = Color.White,
                    ),
                )
                if (onOpenGift != null) {
                    LiveComposerActionButton(
                        icon = Icons.Default.CardGiftcard,
                        label = "Abrir regalos",
                        onClick = onOpenGift,
                        tint = Color(0xFFFFD166)
                    )
                }
                IconButton(
                    onClick = {
                        if (message.isNotBlank()) {
                            onSendComment(buildLiveReplyMessage(replyingTo, message))
                            message = ""
                            replyingTo = null
                        }
                    },
                    enabled = commentingEnabled && message.isNotBlank(),
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF3048)),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar comentario", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
private fun LiveViewerMetricChip(label: String) {
    Surface(color = Color.Black.copy(alpha = 0.48f), shape = RoundedCornerShape(999.dp)) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
    }
}

@Composable
private fun LiveJoinRequestButton(label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(74.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.58f)),
        ) {
            Icon(Icons.Default.GroupAdd, contentDescription = label, tint = Color(0xFF5EEAD4), modifier = Modifier.size(25.dp))
        }
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun LiveQualityBars(
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF35D98B),
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(7.dp, 11.dp, 15.dp, 19.dp).forEach { height ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint),
            )
        }
    }
}

@Composable
private fun LiveViewerChatStream(
    comments: List<LiveComment>,
    joinedViewers: List<LiveViewerParticipant>,
    onReply: (LiveComment) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val joiners = joinedViewers.filter { it.userId.isNotBlank() }.distinctBy { it.userId.lowercase() }.takeLast(3)
    LaunchedEffect(comments.size, joiners.size) {
        val total = comments.size + joiners.size
        if (total > 0) listState.animateScrollToItem(total - 1)
    }
    LazyColumn(modifier = modifier, state = listState, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        items(joiners, key = { "join-${it.socketId}-${it.userId}" }) { viewer ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProfileImage(viewer.avatarUrl, size = 24.dp, showBorder = false)
                Spacer(Modifier.width(7.dp))
                Text("${viewer.name.ifBlank { "Una persona" }} se unió", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        items(comments.takeLast(20), key = { it.id }) { comment ->
            Surface(onClick = { onReply(comment) }, color = Color.Black.copy(alpha = 0.16f), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProfileImage(comment.avatarUrl, size = 27.dp, showBorder = false)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "${comment.userName.ifBlank { "Usuario" }}  ${comment.content}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveVideoBackground(
    stream: LiveStream?,
    videoTrack: VideoTrack?,
    sharedEglContext: EglBase.Context?,
    mirror: Boolean,
    scalingType: RendererCommon.ScalingType,
    placeholderText: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black)) {
        if (videoTrack != null) {
            WebRtcVideoView(
                videoTrack = videoTrack,
                sharedEglContext = sharedEglContext,
                modifier = Modifier.fillMaxSize(),
                mirror = mirror,
                scalingType = scalingType
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF140707),
                                Color(0xFF070707),
                                Color(0xFF140707)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.44f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                                contentDescription = "Novvin",
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Novvin",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    CircularProfileImage(
                        imageUrl = stream?.hostAvatarUrl,
                        size = 74.dp,
                        showBorder = true,
                        borderColor = Color.White.copy(alpha = 0.7f),
                        borderWidth = 2.dp
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stream?.hostName?.ifBlank { "Streamer" } ?: "Streamer",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(1.dp))
                        Text(
                            text = placeholderText,
                            color = Color.White.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTopBar(
    stream: LiveStream?,
    viewerCount: Int,
    reactionCount: Long,
    supporters: List<LiveSupporter>,
    modifier: Modifier = Modifier,
    leadingAction: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit
) {
    var showSupporterRanking by remember { mutableStateOf(false) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingAction != null) {
                leadingAction()
            }
            Surface(
                modifier = Modifier.weight(1f),
                color = Color.Black.copy(alpha = 0.44f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                                contentDescription = "Novvin",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Novvin",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    CircularProfileImage(
                        imageUrl = stream?.hostAvatarUrl,
                        size = 42.dp,
                        showBorder = false
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stream?.hostName?.ifBlank { "Streamer" } ?: "Streamer",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "EN VIVO",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0xFFE53935))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = Color.Black.copy(alpha = 0.46f), shape = RoundedCornerShape(999.dp)) {
                Text(
                    text = "${viewerCount.coerceAtLeast(0)} viendo",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Surface(color = Color.Black.copy(alpha = 0.46f), shape = RoundedCornerShape(999.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color(0xFFFF5C8A),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "${reactionCount.coerceAtLeast(0)} me gusta",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (supporters.isNotEmpty()) {
                Surface(
                    onClick = { showSupporterRanking = true },
                    color = Color(0xFF5E35B1).copy(alpha = 0.92f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.EmojiEvents,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = supporters.firstOrNull()?.let { top ->
                                "Top · ${top.name} ${compactLiveCount(top.coinsInLive.toLong())}"
                            } ?: "Top donadores",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            actions()
        }
    }

    if (showSupporterRanking) {
        SupporterRankingDialog(
            supporters = supporters,
            onDismiss = { showSupporterRanking = false }
        )
    }
}

@Composable
private fun SupporterRankingDialog(
    supporters: List<LiveSupporter>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = Color(0xFFFFB300)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Donadores del directo", fontWeight = FontWeight.Bold)
                        Text(
                            "Clasificación calculada por el servidor con regalos confirmados.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(supporters, key = { _, item -> item.userId }) { _, supporter ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "#${supporter.rank}",
                                    fontWeight = FontWeight.Black,
                                    color = when (supporter.rank) {
                                        1 -> Color(0xFFFFB300)
                                        2 -> Color(0xFF90A4AE)
                                        3 -> Color(0xFFB87333)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.width(36.dp)
                                )
                                CircularProfileImage(
                                    imageUrl = supporter.avatarUrl,
                                    size = 42.dp,
                                    showBorder = supporter.rank <= 3
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        supporter.name,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${supporter.badge} · Nivel ${supporter.level}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "${supporter.coinsInLive} monedas",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cerrar")
                }
            }
        }
    }
}

@Composable
private fun LiveTopStatusBanner(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.58f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun LiveChatOverlay(
    comments: List<LiveComment>,
    supporters: List<LiveSupporter>,
    joinedViewers: List<LiveViewerParticipant>,
    onReply: (LiveComment) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    val visibleJoiners = joinedViewers
        .filter { it.userId.isNotBlank() }
        .distinctBy { it.userId.lowercase() }
        .takeLast(2)

    LaunchedEffect(comments.size, visibleJoiners.size) {
        val totalItems = visibleJoiners.size + comments.size
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    LazyColumn(
        modifier = modifier.heightIn(min = 120.dp, max = 260.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(visibleJoiners, key = { "joined-${it.socketId}-${it.userId}" }) { viewer ->
            Surface(
                color = Color.Black.copy(alpha = 0.34f),
                shape = RoundedCornerShape(999.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProfileImage(imageUrl = viewer.avatarUrl, size = 24.dp, showBorder = false)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "${viewer.name.ifBlank { "Una persona" }} se unió",
                        color = Color.White.copy(alpha = 0.90f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        itemsIndexed(comments, key = { _, item -> item.id }) { index, comment ->
            val supporter = supporters.firstOrNull { it.userId.equals(comment.userId, ignoreCase = true) }
            val normalized = if (comments.size <= 1) 1f else (index + 1) / comments.size.toFloat()
            val alpha = 0.42f + (normalized * 0.58f)

            Surface(
                onClick = { onReply(comment) },
                color = Color.Black.copy(alpha = 0.26f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProfileImage(
                        imageUrl = comment.avatarUrl,
                        size = 28.dp,
                        showBorder = false
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = comment.userName.ifBlank { "Usuario" },
                                color = Color.White.copy(alpha = alpha),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            supporter?.let {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${it.badge} · Nv.${it.level}",
                                    color = Color(0xFFFFD166),
                                    fontWeight = FontWeight.Black,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Text(
                            text = comment.content,
                            color = Color.White.copy(alpha = alpha),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCommentComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenGift: (() -> Unit)?,
    onRequestJoin: (() -> Unit)?,
    joinRequestLabel: String,
    replyingTo: LiveComment?,
    onCancelReply: () -> Unit,
    enabled: Boolean,
    disabledReason: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (replyingTo != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                contentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Respondiendo a ${replyingTo.userName.ifBlank { "Usuario" }}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onCancelReply, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancelar respuesta", tint = Color.White)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text(if (enabled) "Escribe algo…" else disabledReason) },
                trailingIcon = {
                    IconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar comentario", tint = Color.White)
                    }
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                maxLines = 1,
            )
            if (onRequestJoin != null) {
                LiveComposerActionButton(
                    icon = Icons.Default.GroupAdd,
                    label = joinRequestLabel,
                    onClick = onRequestJoin,
                    tint = Color(0xFF5EEAD4),
                )
            }
            if (onOpenGift != null) {
                LiveComposerActionButton(
                    icon = Icons.Default.CardGiftcard,
                    label = "Abrir regalos",
                    onClick = onOpenGift,
                    tint = Color(0xFFFFD166),
                )
            }
        }
    }
}

@Composable
private fun LiveViewerLikeButton(
    reactionCount: Long,
    onSendReaction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reactionScale = remember { Animatable(1f) }
    val reactionScope = rememberCoroutineScope()
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = {
                onSendReaction()
                reactionScope.launch {
                    reactionScale.snapTo(1.24f)
                    reactionScale.animateTo(1f, tween(180))
                }
            },
            modifier = Modifier
                .size(58.dp)
                .graphicsLayer {
                    scaleX = reactionScale.value
                    scaleY = reactionScale.value
                }
                .clip(CircleShape)
                .background(Color(0xFFF9355B)),
        ) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = "Dar me gusta",
                tint = Color.White,
                modifier = Modifier.size(29.dp),
            )
        }
        Text(
            text = compactLiveCount(reactionCount),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .padding(top = 3.dp)
                .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LiveComposerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.58f)),
    ) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

internal fun buildLiveReplyMessage(replyingTo: LiveComment?, message: String): String {
    val clean = message.trim()
    val name = replyingTo?.userName?.trim().orEmpty()
    return if (name.isBlank()) clean else "@$name $clean"
}

internal fun compactLiveCount(count: Long): String = when {
    count >= 1_000_000L -> "${count / 100_000 / 10.0}M"
    count >= 1_000L -> "${count / 100 / 10.0}K"
    else -> count.coerceAtLeast(0).toString()
}

@Composable
fun LiveStateOverlay(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.58f),
        shape = RoundedCornerShape(22.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )
    }
}

@Composable
fun LiveActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Black.copy(alpha = 0.46f),
    contentColor: Color = Color.White
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

data class LiveSecondaryAction(
    val key: String,
    val label: String,
    val description: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LiveSecondaryActionsSheet(
    title: String,
    actions: List<LiveSecondaryAction>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            actions.forEach { action ->
                TextButton(
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    enabled = action.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = action.label,
                            fontWeight = FontWeight.Bold,
                            color = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = action.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveTopBarIconAction(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White
        )
    }
}
