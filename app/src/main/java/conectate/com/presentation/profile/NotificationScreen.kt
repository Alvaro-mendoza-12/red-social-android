package conectate.com.presentation.profile

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.data.model.Notification
import conectate.com.util.postMentionAction
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.navigation.Screen
import conectate.com.util.DateUtils
import conectate.com.util.UbuntuMedia
import coil.compose.SubcomposeAsyncImage
import conectate.com.util.notificationDisplayType as resolveNotificationType
import conectate.com.util.notificationExtra as extractNotificationExtra
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    navController: NavController,
    viewModel: NotificationViewModel = hiltViewModel()
) {
    val ownerRevision by viewModel.ownerRevision.collectAsStateWithLifecycle()
    key(ownerRevision) { NotificationFeedForAccount(navController, viewModel) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationFeedForAccount(navController: NavController, viewModel: NotificationViewModel) {
    val state by viewModel.notificationsState.collectAsStateWithLifecycle()
    val canAct = viewModel.ownerAtRender()
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedNotice by remember { mutableStateOf<Notification?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadNotifications()
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar todas las notificaciones?") },
            text = { Text("Se quitarán de tu lista de notificaciones.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (canAct()) viewModel.deleteAllNotifications()
                }) { Text("Borrar todas") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    selectedNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { selectedNotice = null },
            title = { Text("Notificación") },
            text = { Text(buildNotificationText(notice)) },
            confirmButton = { TextButton(onClick = { selectedNotice = null }) { Text("Cerrar") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notificaciones") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.NotificationSettings.route) }) {
                        Icon(Icons.Default.Tune, contentDescription = "Configurar resumen")
                    }
                    IconButton(
                        onClick = { if (canAct()) confirmDelete = true },
                        enabled = (state as? NotificationsUiState.Success)?.notifications?.isNotEmpty() == true,
                    ) {
                        Icon(Icons.Default.DeleteSweep, "Borrar Todo", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { if (canAct()) viewModel.markAllAsRead() }) {
                        Icon(
                            Icons.Default.DoneAll,
                            contentDescription = "Marcar todo como leído"
                        )
                    }
                }
            )
        }
    ) { padding ->
        val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
        
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isRefreshing && state is NotificationsUiState.Success) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).zIndex(1f))
            }
            when (val s = state) {
                is NotificationsUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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
                    val grouped = remember(s.notifications) {
                        s.notifications.groupBy { notificationDateSection(it.createdAt) }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (s.notifications.isEmpty()) {
                            item(key = "empty-notifications") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("No tienes notificaciones", color = Color.Gray)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    TextButton(onClick = { viewModel.loadNotifications() }) {
                                        Text("Recargar")
                                    }
                                }
                            }
                        } else {
                            listOf("Hoy", "Ayer", "Anteriores").forEach { section ->
                                val sectionItems = grouped[section].orEmpty()
                                if (sectionItems.isNotEmpty()) {
                                    item(key = "notification-section:$section") {
                                        Text(
                                            text = section,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.background)
                                                .padding(horizontal = 20.dp, vertical = 12.dp),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    items(sectionItems, key = { it.id }) { notification ->
                                        NotificationItem(
                                            notification = notification,
                                            onClick = {
                                                if (canAct()) {
                                                    viewModel.markAsRead(notification.id)
                                                    if (notificationDestination(notification) == null) selectedNotice = notification
                                                    else handleNotificationNavigation(navController, notification)
                                                }
                                            }
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
}

@Composable
private fun NotificationDigestOverviewCard(
    state: NotificationDigestUiState,
    onConfigure: () -> Unit,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(onClick = onConfigure),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Resumen de actividad",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                when (state) {
                    NotificationDigestUiState.Loading -> {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is NotificationDigestUiState.Error -> {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                            Text("Reintentar")
                        }
                    }
                    is NotificationDigestUiState.Ready -> {
                        val overview = state.overview
                        if (!overview.preferences.enabled) {
                            Text(
                                "Desactivado. Puedes recibir un resumen diario o semanal.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            val frequency = if (overview.preferences.frequency == "weekly") {
                                "Semanal"
                            } else {
                                "Diario"
                            }
                            Text(
                                "$frequency · ${overview.summary.total} actividades recientes",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val categories = overview.summary.categories
                            val details = listOfNotNull(
                                categories.mentions.takeIf { it > 0 }?.let { "$it menciones" },
                                categories.directMessages.takeIf { it > 0 }?.let { "$it mensajes" },
                                categories.communityActivity.takeIf { it > 0 }?.let { "$it comunidades" },
                                categories.upcomingLives.takeIf { it > 0 }?.let { "$it directos" }
                            ).joinToString(" · ")
                            if (details.isNotBlank()) {
                                Text(details, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Icon(Icons.Default.Tune, contentDescription = null)
        }
    }
}

@Composable
fun NotificationItem(
    notification: Notification,
    onClick: () -> Unit
) {
    val bgColor = if (notification.isRead) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f)
    }
    val type = resolveNotificationType(notification)
    val eventStyle = notificationEventStyle(type)
    val targetThumbnail = notificationTargetThumbnail(notification)
    val targetPreviewText = notificationTargetPreviewText(notification)
    val targetContentType = notification.targetType?.trim()?.lowercase().orEmpty()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(58.dp)) {
            CircularProfileImage(ownerId = notification.actorId, 
                imageUrl = notification.actorAvatarUrl ?: "",
                size = 54.dp
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(23.dp),
                shape = CircleShape,
                color = eventStyle.second,
                border = androidx.compose.foundation.BorderStroke(
                    2.dp,
                    MaterialTheme.colorScheme.surface
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = eventStyle.first,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(14.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildNotificationText(notification),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp
            )
            
            if (type == "birthday") {
                androidx.compose.material3.TextButton(onClick = onClick) { Text("Felicítalo ahora") }
            }
            val commentPreview = notificationCommentPreview(notification)
            if (!commentPreview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "\"$commentPreview\"",
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

        if (!targetThumbnail.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(10.dp))
            SubcomposeAsyncImage(
                model = UbuntuMedia.resolveUrl(targetThumbnail) ?: targetThumbnail,
                contentDescription = "Contenido relacionado",
                contentScale = ContentScale.Crop,
                loading = { NotificationTargetFallback(targetContentType, targetPreviewText) },
                error = { NotificationTargetFallback(targetContentType, targetPreviewText) },
                modifier = Modifier
                    .size(width = 58.dp, height = 66.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else if (
            targetPreviewText != null ||
            targetContentType == "post" ||
            targetContentType == "video" ||
            targetContentType == "moment"
        ) {
            Spacer(modifier = Modifier.width(10.dp))
            NotificationTargetFallback(
                targetType = targetContentType,
                previewText = targetPreviewText
            )
        } else if (!notification.isRead) {
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
private fun NotificationTargetFallback(
    targetType: String,
    previewText: String?
) {
    Surface(
        modifier = Modifier.size(width = 58.dp, height = 66.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (targetType == "video" || targetType == "moment") {
                    Icons.Default.PlayCircle
                } else {
                    Icons.Default.ChatBubble
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            if (!previewText.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    maxLines = 3,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun notificationEventStyle(type: String): Pair<ImageVector, Color> = when (type) {
    "like", "like_post", "like_video", "like_moment" -> Icons.Default.Favorite to Color(0xFFF44336)
    "comment", "comment_post", "comment_video", "reply_post", "reply_video" ->
        Icons.Default.ChatBubble to Color(0xFF1687F8)
    "mention", "mention_post", "mention_video", "mention_video_description" ->
        Icons.Default.AlternateEmail to Color(0xFF1687F8)
    "follow", "friend_request", "friend_request_accepted" ->
        Icons.Default.PersonAdd to Color(0xFF18A66A)
    "birthday" -> Icons.Default.Cake to Color(0xFFEC4899)
    "new_video", "share_video", "new_moment", "view_moment" ->
        Icons.Default.PlayCircle to Color(0xFF8B5CF6)
    "live_started", "new_live" -> Icons.Default.PlayCircle to Color(0xFFE53935)
    else -> Icons.Default.NotificationsActive to Color(0xFF64748B)
}

private fun notificationTargetThumbnail(notification: Notification): String? {
    val keys = listOf(
        "target_thumbnail_url",
        "thumbnail_url",
        "video_thumbnail_url",
        "post_image_url",
        "cover_url",
        "image_url",
        "media_url"
    )
    return keys.firstNotNullOfOrNull { key ->
        extractNotificationExtra(notification, key)
    }
}

private fun notificationTargetPreviewText(notification: Notification): String? =
    extractNotificationExtra(notification, "target_preview_text")

private fun notificationCommentPreview(notification: Notification): String? {
    if (!resolveNotificationType(notification).contains("comment")) return null
    val content = notification.content?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = content.lowercase()
    if (
        normalized == "comentó tu video." ||
        normalized == "comentó tu video" ||
        normalized == "comentó tu publicación." ||
        normalized == "comentó tu publicación" ||
        normalized == "comentó en tu video." ||
        normalized == "comentó en tu publicación."
    ) return null
    return content
}

private fun notificationDateSection(createdAt: String): String {
    val date = runCatching {
        val raw = createdAt.trim()
        val instant = runCatching { Instant.parse(raw) }.getOrElse {
            runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrElse {
                LocalDateTime.parse(raw.replace(" ", "T"))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            }
        }
        instant.atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrNull() ?: return "Anteriores"
    val today = LocalDate.now()
    return when (date) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> "Anteriores"
    }
}

internal fun buildNotificationText(n: Notification): androidx.compose.ui.text.AnnotatedString {
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
        "mention_video_description" -> " te mencionó en la descripción de un video"
        "share_video" -> " compartió tu video"
        "like_post" -> " le dio like a tu publicación"
        "comment_post" -> " comentó tu publicación"
        "share_post" -> " compartió tu publicación"
        "reply_post" -> " respondió a tu comentario en una publicación"
        "mention_post" -> " " + postMentionAction(n).replaceFirstChar { it.lowercaseChar() }
        "follow" -> " te empezó a seguir"
        "new_video" -> " subió un video nuevo"
        "new_post" -> " hizo una nueva publicación"
        "friend_request" -> " te envió una solicitud de amistad"
        "friend_request_accepted" -> " aceptó tu solicitud de amistad"
        "birthday" -> " cumple años hoy. ¡Felicítalo!"
        "live_started", "new_live" -> " inició un directo"
        "group_member_added" -> {
            val groupName = n.content?.trim().orEmpty()
            if (groupName.isNotBlank()) " te agregó al grupo $groupName" else " te agregó a un grupo"
        }
        "community_member_added" -> {
            val communityName = n.content?.trim().orEmpty()
            if (communityName.isNotBlank()) " te agregó a la comunidad $communityName" else " te agregó a una comunidad"
        }
        "message", "new_message" -> {
            val target = when {
                n.targetType.equals("group", ignoreCase = true) -> " en un grupo"
                n.targetType.equals("community", ignoreCase = true) -> " en una comunidad"
                else -> ""
            }
            val body = n.content?.trim().orEmpty()
            if (body.isNotBlank()) " te escribió$target: $body" else " te envió un mensaje$target"
        }
        "call_invite" -> " te está llamando"
        "call_missed" -> " tiene una llamada perdida contigo"
        "view_moment" -> " vio tu estado"
        "new_moment" -> " subió un nuevo estado"
        "like_moment" -> " le dio like a tu momento"
        "challenge_invite" -> {
            val title = extractNotificationExtra(n, "challenge_title")
            if (title.isNullOrBlank()) " te invitó a participar en un reto"
            else " te invitó al reto: $title"
        }
        "voice_access_request" -> " solicitó acceso a la sección de voz"
        "account_suspended" -> " notificó una suspensión de cuenta"
        "account_enabled" -> " notificó que tu cuenta fue habilitada"
        "admin_access_changed" -> adminAccessChangedAction(n)
        "admin_access_audit" -> notificationContentAction(
            notification = n,
            actor = actor,
            fallback = " cambió un permiso administrativo"
        )
        "admin_warning_video" -> notificationContentAction(
            notification = n,
            actor = actor,
            fallback = " te envió una advertencia sobre tu video"
        )
        "notification_digest" -> " preparó tu resumen de actividad"
        "system_announcement" -> " · ${n.content.orEmpty()}"
        else -> notificationContentAction(
            notification = n,
            actor = actor,
            fallback = when (n.targetType?.trim()?.lowercase()) {
                "post" -> " realizó una acción en tu publicación"
                "video" -> " realizó una acción en tu video"
                "moment" -> " realizó una acción en tu estado"
                "profile" -> " realizó una acción en tu perfil"
                else -> " generó una notificación"
            }
        )
    }
    
    builder.append(actionText)
    return builder.toAnnotatedString()
}

private fun notificationContentAction(
    notification: Notification,
    actor: String,
    fallback: String
): String {
    val raw = notification.content?.trim()?.takeIf { it.isNotBlank() } ?: return fallback
    val withoutActor = if (raw.startsWith(actor, ignoreCase = true)) {
        raw.drop(actor.length)
    } else {
        raw
    }.trimStart(' ', ',', ':', '-', '–')
    if (withoutActor.isBlank()) return fallback
    return " $withoutActor"
}

private fun adminAccessChangedAction(notification: Notification): String {
    val content = notification.content?.trim().orEmpty()
    val prefix = "Tu acceso administrativo fue"
    if (content.startsWith(prefix, ignoreCase = true)) {
        val access = content.drop(prefix.length).trim().trimEnd('.')
        if (access.isNotBlank()) return " actualizó tu acceso administrativo a $access"
    }
    return " actualizó tu acceso administrativo"
}
