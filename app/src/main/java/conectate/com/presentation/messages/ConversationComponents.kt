package conectate.com.presentation.messages

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.repository.message.otherDirectParticipant
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.AdminBadge
import java.util.*
import androidx.compose.material.icons.automirrored.filled.VolumeOff

@Composable
fun ConversationItem(
    conversation: ChatWithLastMessage,
    currentUserId: String,
    onClick: () -> Unit,
    onMute: () -> Unit = {},
    onPin: () -> Unit = {},
    onFavorite: () -> Unit = {},
    onArchive: () -> Unit = {},
    onBlock: () -> Unit = {},
    onManageFolders: (() -> Unit)? = null,
    onDelete: () -> Unit = {},
    onReport: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val hasPeer = conversation.chat.otherDirectParticipant(currentUserId) != null
    val draftContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val drafts = remember(draftContext) { conectate.com.data.local.ConversationDraftStore(draftContext) }
    val draftScope = if (conversation.chat.is_group) "group" else "chat"
    val draftRoom = conversation.chat.id
    val draftFlow = remember(currentUserId, draftScope, draftRoom) { drafts.observe(currentUserId, draftScope, draftRoom) }
    val conversationDraft by draftFlow.collectAsState(initial = drafts.read(currentUserId, draftScope, draftRoom))
    val inboxRed = colors.primary
    val otherUserName = if (conversation.chat.is_group) {
        conversation.chat.group_name ?: "Grupo"
    } else {
        conversation.chat.nickname ?: conversation.otherUserName ?: "Usuario"
    }
    val otherUserProfileImage = if (conversation.chat.is_group) conversation.chat.group_image else conversation.otherUserImage ?: ""
    val unreadCount = conversation.unreadCount
    var showMenu by remember { mutableStateOf(false) }

    val formattedTime = remember(conversation.lastMessage?.created_at, conversation.chat.last_message_at) {
        val timestamp = conversation.lastMessage?.created_at ?: conversation.chat.last_message_at
        timestamp?.let { ts ->
            formatTimestampSmart(ts)
        } ?: ""
    }

    val lastMessage = conversation.lastMessage
    val cleanCurrentUserId = currentUserId.trim().lowercase(Locale.ROOT)
    val isFromMe = lastMessage?.sender_id?.trim()?.lowercase(Locale.ROOT) == cleanCurrentUserId

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProfileImage(ownerId = if (conversation.chat.is_group) null else conversation.otherUserId, 
            imageUrl = otherUserProfileImage,
            size = 48.dp,
            showBorder = false,
            isVerified = if (conversation.chat.is_group) false else conversation.otherUserVerified,
            fallbackName = otherUserName
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = otherUserName,
                        color = colors.onBackground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!conversation.chat.is_group && conversation.otherUserIsAdmin) {
                        Spacer(modifier = Modifier.width(4.dp))
                        AdminBadge(showVerifiedLine = false)
                    }
                    if (!conversation.chat.is_group && conversation.otherUserVerified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Verificado",
                            tint = Color(0xFF1DA1F2),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.chat.is_pinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Chat fijado",
                            modifier = Modifier.size(16.dp),
                            tint = inboxRed
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formattedTime,
                        fontSize = 12.sp,
                        color = if (unreadCount > 0) inboxRed else colors.onSurfaceVariant,
                        fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(2.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isFromMe && conversationDraft.isEmpty()) {
                    val statusIcon = when(lastMessage?.status) {
                        "read" -> Icons.Default.DoneAll
                        "received" -> Icons.Default.DoneAll
                        "sent" -> Icons.Default.Done
                        else -> Icons.Default.AccessTime
                    }
                    val statusTint = if (lastMessage?.status == "read") Color(0xFF34B7F1) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = statusTint
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                
                if (conversationDraft.isEmpty() && lastMessage?.content_type != "text" && lastMessage != null && !lastMessage.deleted_for_all) {
                    val typeIcon = when(lastMessage.content_type) {
                        "image" -> Icons.Default.PhotoCamera
                        "video" -> Icons.Default.Videocam
                        "audio" -> Icons.Default.Mic
                        else -> null
                    }
                    if (typeIcon != null) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }

                val displayText = inboxMessagePreview(lastMessage)
                val previewText = if (conversationDraft.isNotEmpty()) {
                    "Borrador: $conversationDraft"
                } else if (isFromMe && lastMessage?.deleted_for_all != true && displayText.isNotBlank() && displayText != "Sin actividad reciente") {
                    "Tú: $displayText"
                } else {
                    displayText
                }
                
                Text(
                    text = previewText,
                    fontSize = 12.5.sp,
                    color = if (conversationDraft.isNotEmpty()) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (conversation.chat.is_muted) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "Chat silenciado",
                        modifier = Modifier.size(14.dp),
                        tint = colors.onSurfaceVariant
                    )
                }
                
                if (unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .background(Color(0xFFE53935), CircleShape)
                            .height(20.dp)
                            .widthIn(min = 20.dp)
                            .padding(horizontal = 6.dp)
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Opciones",
                    tint = colors.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (conversation.chat.is_muted) "Activar notificaciones" else "Silenciar") },
                    onClick = {
                        showMenu = false
                        onMute()
                    },
                    leadingIcon = {
                        Icon(
                            if (conversation.chat.is_muted) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                            contentDescription = null
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (conversation.chat.is_pinned) "Desfijar" else "Fijar arriba") },
                    onClick = {
                        showMenu = false
                        onPin()
                    },
                    leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text(if (conversation.chat.is_favorite) "Quitar de favoritos" else "Añadir a favoritos") },
                    onClick = {
                        showMenu = false
                        onFavorite()
                    },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text(if (conversation.chat.is_archived) "Desarchivar" else "Archivar") },
                    onClick = {
                        showMenu = false
                        onArchive()
                    },
                    leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) }
                )
                if (onManageFolders != null) {
                    DropdownMenuItem(
                        text = { Text("Añadir a carpeta") },
                        onClick = {
                            showMenu = false
                            onManageFolders()
                        },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) }
                    )
                }
                if (hasPeer) {
                    DropdownMenuItem(
                        text = { Text("Bloquear usuario") },
                        onClick = {
                            showMenu = false
                            onBlock()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Block,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Eliminar") },
                    onClick = { 
                        showMenu = false
                        onDelete()
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) }
                )
                if (hasPeer) DropdownMenuItem(
                    text = { Text("Reportar") },
                    onClick = { 
                        showMenu = false
                        onReport()
                    },
                    leadingIcon = { Icon(Icons.Default.Report, contentDescription = null) }
                )
            }
        }
    }
}

private fun formatTimestampSmart(isoString: String): String {
    return try {
        if (isoString.isBlank()) return ""
        
        // Robust parsing matching Message model for consistency
        val cleaned = isoString.trim()
            .replace(" ", "T")
            .let { if (!it.contains("Z") && !it.contains("+") && it.length > 10) "${it}Z" else it }

        val instant = try {
            java.time.Instant.parse(cleaned)
        } catch (e: Exception) {
            try {
                if (cleaned.contains("Z") || cleaned.contains("+")) {
                    java.time.OffsetDateTime.parse(cleaned).toInstant()
                } else {
                    java.time.LocalDateTime.parse(cleaned.substringBefore("Z")).toInstant(java.time.ZoneOffset.UTC)
                }
            } catch (e2: Exception) { null }
        } ?: return ""

        val localDateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val now = java.time.LocalDateTime.now()
        val formatter = java.time.format.DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())
        when {
            localDateTime.toLocalDate() == now.toLocalDate() -> localDateTime.format(formatter)
            localDateTime.toLocalDate() == now.toLocalDate().minusDays(1) -> "Ayer"
            localDateTime.isAfter(now.minusDays(7)) -> {
                val dayFormat = java.time.format.DateTimeFormatter.ofPattern(
                    "EEEE",
                    Locale.forLanguageTag("es-ES")
                )
                localDateTime.format(dayFormat).replaceFirstChar { it.uppercase() }
            }
            else -> localDateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy", Locale.getDefault()))
        }
    } catch (e: Exception) { "" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationItemWithSwipe(
    conversation: ChatWithLastMessage,
    currentUserId: String,
    onClick: () -> Unit,
    onMute: () -> Unit,
    onPin: () -> Unit,
    onFavorite: () -> Unit = {},
    onArchive: () -> Unit = {},
    onBlock: () -> Unit,
    onManageFolders: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onReport: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.97f }
    )
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            // Si el repositorio rechaza o demora la eliminación, la fila vuelve
            // a una posición interactiva en vez de quedar fuera de pantalla.
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val isSwiping = direction == SwipeToDismissBoxValue.EndToStart
            val color = if (isSwiping) Color.Red else Color.Transparent
            Box(
                modifier = Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (isSwiping) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Eliminar", tint = Color.White)
                }
            }
        },
        content = {
           ConversationItem(
               conversation = conversation,
               currentUserId = currentUserId,
               onClick = onClick,
               onMute = onMute,
               onPin = onPin,
               onFavorite = onFavorite,
               onArchive = onArchive,
               onBlock = onBlock,
               onManageFolders = onManageFolders,
               onDelete = onDelete,
               onReport = onReport
           )
        }
    )
}
