package conectate.com.presentation.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import conectate.com.data.model.Notification
import conectate.com.presentation.profile.NotificationViewModel
import conectate.com.presentation.profile.NotificationsUiState
import conectate.com.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    navController: NavController,
    viewModel: NotificationViewModel = hiltViewModel()
) {
    val notificationsState by viewModel.notificationsState.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadNotifications()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notificaciones", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    if (unreadCount > 0) {
                        IconButton(onClick = { viewModel.markAllAsRead() }) {
                            Icon(Icons.Default.DoneAll, "Marcar todo como leído")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        when (val state = notificationsState) {
            is NotificationsUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is NotificationsUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Error: ${state.message}")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.loadNotifications() }) {
                            Text("Reintentar")
                        }
                    }
                }
            }
            is NotificationsUiState.Success -> {
                if (state.notifications.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No hay notificaciones", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        items(state.notifications, key = { it.id }) { notification ->
                            NotificationItem(
                                notification = notification,
                                onClick = {
                                    viewModel.markAsRead(notification.id)
                                    handleNotificationClick(navController, notification)
                                }
                            )
                            Divider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationItem(
    notification: Notification,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        if (!notification.actorAvatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = notification.actorAvatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = notification.actorName ?: "Alguien",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!notification.isRead) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFFE53935), CircleShape)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = notification.content ?: getNotificationTypeLabel(notification.type),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = TimeUtils.getRelativeTimeSpanString(notification.createdAt),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        // Right icon based on type
        val icon = getNotificationIcon(notification.type)
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

fun getNotificationIcon(type: String): ImageVector {
    return when (type.lowercase()) {
        "follow", "friend_request" -> Icons.Default.PersonAdd
        "like", "like_post", "like_video" -> Icons.Default.ThumbUp
        "comment", "comment_post", "comment_video" -> Icons.Default.ChatBubble
        "reply_post", "reply_video" -> Icons.Default.Reply
        "mention_post", "mention", "mention_video" -> Icons.Default.AlternateEmail
        "voice_invite" -> Icons.Default.VolumeUp
        "challenge_invite" -> Icons.Default.EmojiEvents
        "share_video" -> Icons.Default.Share
        else -> Icons.Default.Notifications
    }
}

fun getNotificationTypeLabel(type: String): String {
    return when (type.lowercase()) {
        "follow" -> "Te siguió"
        "friend_request" -> "Te envió una solicitud de amistad"
        "like", "like_post" -> "Le gustó tu publicación"
        "like_video" -> "Le gustó tu video"
        "comment", "comment_post" -> "Comentó tu publicación"
        "comment_video" -> "Comentó tu video"
        "reply_post" -> "Respondió a tu comentario"
        "reply_video" -> "Respondió a tu comentario en video"
        "mention_post", "mention" -> "Te mencionó en un comentario"
        "mention_video" -> "Te mencionó en un comentario de video"
        "voice_invite" -> "Te invitó a una sala de voz"
        "challenge_invite" -> "Te desafió a un reto"
        "share_video" -> "Compartió tu video"
        "admin_warning_video" -> "Tu video recibió una advertencia"
        "account_suspended" -> "Tu cuenta fue suspendida"
        "account_enabled" -> "Tu cuenta fue habilitada"
        else -> "Nueva notificación"
    }
}

fun handleNotificationClick(navController: NavController, notification: Notification) {
    when (notification.type.lowercase()) {
        "follow", "friend_request" -> {
            notification.actorId?.let { userId ->
                navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(userId))
            }
        }
        "like", "like_post", "comment", "comment_post", "reply_post", "mention_post", "share_video" -> {
            notification.targetId?.let { postId ->
                navController.navigate(conectate.com.presentation.navigation.Screen.PostDetail.createRoute(postId))
            }
        }
        "like_video", "comment_video", "reply_video", "mention_video" -> {
            notification.targetId?.let { videoId ->
                navController.navigate(conectate.com.presentation.navigation.Screen.VideoPlayer.createRoute(videoId))
            }
        }
        "voice_invite" -> {
            notification.targetId?.takeIf { it.isNotBlank() }?.let { roomId ->
                navController.navigate(conectate.com.presentation.navigation.Screen.RoomDetail.createRoute(roomId))
            }
        }
        "challenge_invite" -> {
            navController.navigate(conectate.com.presentation.navigation.Screen.Home.createRoute(startTab = "social"))
        }
    }
}
