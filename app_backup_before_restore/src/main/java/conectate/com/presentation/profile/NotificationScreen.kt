package conectate.com.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.model.Notification
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.navigation.Screen
import conectate.com.util.DateUtils
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    navController: NavController,
    viewModel: NotificationViewModel = hiltViewModel()
) {
    val state by viewModel.notificationsState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadNotifications()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notificaciones") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.markAllAsRead() }) {
                        Icon(Icons.Default.DoneAll, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Todo leído", fontSize = 12.sp)
                    }
                }
            )
        }
    ) { padding ->
        val isRefreshing by viewModel.isRefreshing.collectAsState()
        
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is NotificationsUiState.Loading -> {
                    if (!isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
                is NotificationsUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadNotifications() }) {
                            Text("Reintentar")
                        }
                    }
                }
                is NotificationsUiState.Success -> {
                    if (s.notifications.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No tienes notificaciones", color = Color.Gray)
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(onClick = { viewModel.loadNotifications() }) {
                                Text("Recargar")
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(s.notifications, key = { it.id }) { notification ->
                                NotificationItem(
                                    notification = notification,
                                    onClick = {
                                        viewModel.markAsRead(notification.id)
                                        handleNotificationNavigation(navController, notification)
                                    }
                                )
                                Divider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.5f))
                            }
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
    val bgColor = if (notification.isRead) Color.Transparent else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProfileImage(
            imageUrl = notification.actorAvatarUrl ?: "",
            size = 48.dp
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildNotificationText(notification),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp
            )
            
            // Show comment preview if available
            if (!notification.content.isNullOrBlank() && notification.type.contains("comment")) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "\"${notification.content}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = DateUtils.formatRelativeDate(notification.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
        
        if (!notification.isRead) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}

private fun buildNotificationText(n: Notification): androidx.compose.ui.text.AnnotatedString {
    val actor = n.actorName ?: "Usuario"
    val type = resolveNotificationType(n)
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    
    builder.pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
    builder.append(actor)
    builder.pop()
    
    val actionText = when (type) {
        "like_video" -> " le dio like a tu video"
        "comment_video" -> " comentó tu video"
        "reply_video" -> " respondió a tu comentario en un video"
        "mention" -> " te mencionó en un comentario"
        "mention_video" -> " te mencionó en un comentario de video"
        "share_video" -> " compartió tu video"
        "admin_warning_video" -> " te envió una advertencia sobre tu video"
        "like_post" -> " le dio like a tu publicación"
        "comment_post" -> " comentó tu publicación"
        "share_post" -> " compartió tu publicación"
        "reply_post" -> " respondió a tu comentario en una publicación"
        "mention_post" -> " te mencionó en un comentario"
        "follow" -> " te empezó a seguir"
        "new_video" -> " subió un video nuevo"
        "new_post" -> " hizo una nueva publicación"
        "friend_request" -> " te envió una solicitud de amistad"
        "friend_request_accepted" -> " aceptó tu solicitud de amistad"
        "view_moment" -> " vio tu estado"
        "new_moment" -> " subió un nuevo estado"
        "voice_access_request" -> " solicitó acceso a la sección de voz"
        "account_suspended" -> " notificó una suspensión de cuenta"
        "account_enabled" -> " notificó que tu cuenta fue habilitada"
        else -> " realizó una acción"
    }
    
    builder.append(actionText)
    return builder.toAnnotatedString()
}

fun handleNotificationNavigation(navController: NavController, n: Notification) {
    val type = resolveNotificationType(n)
    val commentId = extractNotificationExtra(n, "comment_id")
        ?: extractNotificationExtra(n, "focus_comment_id")
    val messageId = extractNotificationExtra(n, "message_id")
        ?: extractNotificationExtra(n, "focus_message_id")
    val groupId = extractNotificationExtra(n, "group_id")
    val communityId = extractNotificationExtra(n, "community_id")
    val extraVideoId = extractNotificationExtra(n, "video_id")
    val extraPostId = extractNotificationExtra(n, "post_id")
    val resolvedVideoId = extraVideoId ?: n.targetId
    val resolvedPostId = extraPostId ?: n.targetId

    when (type) {
        "follow", "friend_request_accepted" -> navController.navigate(Screen.UserProfile.createRoute(n.actorId))
        "friend_request" -> navController.navigate(Screen.FriendRequests.route)
        "mention" -> {
            when {
                n.targetType.equals("video", ignoreCase = true) || !extraVideoId.isNullOrBlank() -> {
                    val videoId = resolvedVideoId
                    if (!videoId.isNullOrBlank()) {
                        navController.navigate(Screen.VideoPlayer.createRoute(videoId, focusCommentId = commentId))
                    }
                }
                else -> {
                    val postId = resolvedPostId
                    if (!postId.isNullOrBlank()) {
                        navController.navigate(Screen.PostDetail.createRoute(postId, focusCommentId = commentId))
                    }
                }
            }
        }
        "like_video", "comment_video", "reply_video", "new_video", "mention_video", "share_video", "admin_warning_video" -> {
            resolvedVideoId?.let { navController.navigate(Screen.VideoPlayer.createRoute(it, focusCommentId = commentId)) }
        }
        "like_post", "comment_post", "reply_post", "new_post", "share_post", "mention_post" -> {
            resolvedPostId?.let { navController.navigate(Screen.PostDetail.createRoute(it, focusCommentId = commentId)) }
        }
        "new_message" -> {
            when {
                !communityId.isNullOrBlank() -> navController.navigate(
                    Screen.CommunityChat.createRoute(communityId, focusMessageId = messageId)
                )
                !groupId.isNullOrBlank() -> navController.navigate(
                    Screen.GroupChat.createRoute(groupId, focusMessageId = messageId)
                )
                !n.actorId.isBlank() -> navController.navigate(
                    Screen.Chat.createRoute(n.actorId, focusMessageId = messageId)
                )
                n.targetId != null -> navController.navigate(
                    Screen.Chat.createRoute(n.targetId, focusMessageId = messageId)
                )
            }
        }
        "view_moment", "new_moment" -> {
            n.targetId?.let { navController.navigate(Screen.MomentViewer.createRoute(it)) }
        }
        "voice_access_request" -> {
            navController.navigate(Screen.UserProfile.createRoute(n.actorId))
        }
    }
}

private fun extractNotificationExtra(notification: Notification, key: String): String? {
    return notification.extraData
        ?.get(key)
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
}

private fun resolveNotificationType(notification: Notification): String {
    val subtype = notification.extraData
        ?.get("notification_type")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.lowercase()
    if (!subtype.isNullOrBlank()) return subtype
    val baseType = notification.type.trim().lowercase()
    if (baseType != "message") return baseType
    val lowerContent = notification.content?.trim()?.lowercase().orEmpty()
    return when {
        lowerContent.contains("cuenta fue suspendida") ||
            lowerContent.contains("cuenta ha sido suspendida") -> "account_suspended"
        lowerContent.contains("cuenta ha sido habilitada") ||
            lowerContent.contains("cuenta fue habilitada") ||
            lowerContent.contains("cuenta habilitada nuevamente") -> "account_enabled"
        else -> baseType
    }
}
