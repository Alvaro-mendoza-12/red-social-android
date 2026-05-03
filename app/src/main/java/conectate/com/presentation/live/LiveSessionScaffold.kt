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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import conectate.com.R
import conectate.com.data.model.LiveComment
import conectate.com.data.model.LiveStream
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.WebRtcVideoView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

@Composable
fun LiveSessionScaffold(
    stream: LiveStream?,
    viewerCount: Int,
    videoTrack: VideoTrack?,
    sharedEglContext: EglBase.Context?,
    mirrorVideo: Boolean,
    comments: List<LiveComment>,
    reactions: List<conectate.com.data.model.LiveReaction>,
    onSendComment: (String) -> Unit,
    onSendReaction: () -> Unit,
    modifier: Modifier = Modifier,
    videoScalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
    stateOverlayText: String? = null,
    topStatusBannerText: String? = null,
    placeholderText: String = "Conectando video…",
    leadingTopBarAction: @Composable (() -> Unit)? = null,
    topBarActions: @Composable RowScope.() -> Unit,
    floatingOverlay: @Composable BoxScope.() -> Unit = {},
    creatorControls: @Composable ColumnScope.() -> Unit = {}
) {
    var message by remember { mutableStateOf("") }

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
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (stream != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
                content = creatorControls
            )
        }

        LiveCommentComposer(
            value = message,
            onValueChange = { message = it },
            onSend = {
                if (message.isBlank()) return@LiveCommentComposer
                onSendComment(message)
                message = ""
            },
            onSendReaction = onSendReaction,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 16.dp)
        )
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
                                contentDescription = "Conéctate",
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Conéctate",
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
    modifier: Modifier = Modifier,
    leadingAction: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit
) {
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
                                contentDescription = "Conéctate",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Conéctate",
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = "${viewerCount.coerceAtLeast(0)} viendo",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
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
            verticalAlignment = Alignment.CenterVertically,
            content = actions
        )
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
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(comments.size) {
        if (comments.isNotEmpty()) {
            listState.animateScrollToItem(comments.lastIndex)
        }
    }

    LazyColumn(
        modifier = modifier.heightIn(min = 120.dp, max = 260.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(comments, key = { _, item -> item.id }) { index, comment ->
            val normalized = if (comments.size <= 1) 1f else (index + 1) / comments.size.toFloat()
            val alpha = 0.42f + (normalized * 0.58f)

            Surface(
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
                        Text(
                            text = comment.userName.ifBlank { "Usuario" },
                            color = Color.White.copy(alpha = alpha),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
    onSendReaction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(
            onClick = onSendReaction,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f))
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Reaccionar",
                tint = Color(0xFFFF5C8A)
            )
        }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Comentar…") },
            shape = RoundedCornerShape(20.dp),
            singleLine = false,
            maxLines = 3
        )

        Button(
            onClick = onSend,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
        ) {
            Text("Enviar", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
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
    containerColor: Color = Color.Black.copy(alpha = 0.46f),
    contentColor: Color = Color.White
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(text, fontWeight = FontWeight.Bold)
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
