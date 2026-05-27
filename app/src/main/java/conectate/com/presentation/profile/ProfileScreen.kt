package conectate.com.presentation.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import conectate.com.data.model.Post
import conectate.com.data.model.Badge
import conectate.com.data.model.MomentHighlight
import conectate.com.data.model.User
import conectate.com.presentation.common.ConnectivityViewModel
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.OfflineNoticeBar
import conectate.com.presentation.components.VideoThumbnail
import conectate.com.data.repository.FriendStatus
import kotlinx.coroutines.launch

@Composable
fun StatItem(number: Int, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = number.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun DiscordProfileDetails(
    user: User,
    onOpenLink: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (user.interests.isNotEmpty()) {
            ProfilePillSection(
                title = "Intereses",
                items = user.interests,
                icon = Icons.Default.LocalOffer
            )
        }

        if (user.linked_games.isNotEmpty()) {
            Text(
                text = "Juegos",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            user.linked_games.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { game ->
                        GamePill(
                            name = game,
                            logoUrl = user.game_logo_urls[game],
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        val details = listOfNotNull(
            user.favorite_music_url?.takeIf { it.isNotBlank() }?.let {
                ProfileDetail.Music(it)
            },
            user.favorite_book?.takeIf { it.isNotBlank() }?.let {
                ProfileDetail.Text(Icons.Default.Book, "Libro", it)
            },
            user.favorite_sport?.takeIf { it.isNotBlank() }?.let {
                ProfileDetail.Text(Icons.Default.SportsSoccer, "Deporte", it)
            },
            user.career?.takeIf { it.isNotBlank() }?.let {
                ProfileDetail.Text(Icons.Default.School, "Carrera", it)
            },
            user.availability_schedule?.takeIf { it.isNotBlank() }?.let {
                ProfileDetail.Text(Icons.Default.Schedule, "Horario", it)
            }
        )

        details.forEach { detail ->
            when (detail) {
                is ProfileDetail.Music -> ProfileLinkPill(
                    icon = Icons.Default.MusicNote,
                    label = "Música",
                    value = detail.url,
                    onClick = { onOpenLink(detail.url) }
                )
                is ProfileDetail.Text -> ProfileTextPill(
                    icon = detail.icon,
                    label = detail.label,
                    value = detail.value
                )
            }
        }
    }
}

@Composable
private fun ProfilePillSection(
    title: String,
    items: List<String>,
    icon: ImageVector
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    items.chunked(3).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { item ->
                ProfileTextPill(
                    icon = icon,
                    label = null,
                    value = item,
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun GamePill(
    name: String,
    logoUrl: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.SportsEsports, null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProfileTextPill(
    icon: ImageVector,
    label: String?,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (label.isNullOrBlank()) value else "$label: $value",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProfileLinkPill(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$label: $value",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private sealed class ProfileDetail {
    data class Music(val url: String) : ProfileDetail()
    data class Text(val icon: ImageVector, val label: String, val value: String) : ProfileDetail()
}

private fun profileAvatarStatusColor(user: User): Color {
    val status = user.status?.trim()?.lowercase().orEmpty()
    return when {
        status.contains("ocup") || status.contains("busy") || status.contains("dnd") -> Color(0xFFED4245)
        status.contains("ausente") || status.contains("idle") || status.contains("away") -> Color(0xFFFEE75C)
        user.is_online -> Color(0xFF57F287)
        else -> Color(0xFF747F8D)
    }
}

@Composable
private fun ProfileHighlightsRow(
    highlights: List<MomentHighlight>,
    isOwnProfile: Boolean,
    onOpenAll: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Destacados",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onOpenAll) {
                Text(if (highlights.isEmpty()) "Ver" else "Ver todos")
            }
        }

        if (highlights.isEmpty()) {
            if (isOwnProfile) {
                Text(
                    "Guarda tus momentos desde el visor para crear highlights en tu perfil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(highlights.take(12), key = { it.id }) { highlight ->
                Column(
                    modifier = Modifier
                        .width(82.dp)
                        .clickable(onClick = onOpenAll),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(70.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        if (!highlight.coverMediaUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = highlight.coverMediaUrl,
                                contentDescription = highlight.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = highlight.title,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    mainNavController: NavController? = null,
    userId: String? = null,
    viewModel: ProfileViewModel = hiltViewModel(),
    highlightViewModel: MomentHighlightViewModel = hiltViewModel(),
    connectivityViewModel: ConnectivityViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    val profileState by viewModel.profileState.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val userVideos by viewModel.userVideos.collectAsState()
    val userPosts by viewModel.userPosts.collectAsState()
    val sharedPosts by viewModel.sharedPosts.collectAsState()
    val sharedVideos by viewModel.sharedVideos.collectAsState()
    val highlightState by highlightViewModel.uiState.collectAsState()
    val isConnected by connectivityViewModel.isConnected.collectAsState()
    
    var showCreatePostDialog by remember { mutableStateOf(false) }
    var postToShare by remember { mutableStateOf<conectate.com.data.model.Post?>(null) }
    
    // Comments Sheet State
    var showCommentsSheet by remember { mutableStateOf(false) }
    var postToComment by remember { mutableStateOf<Post?>(null) }
    
    // Edit Post State
    var showEditPostDialog by remember { mutableStateOf(false) }
    var postToEdit by remember { mutableStateOf<Post?>(null) }
    
    val comments by viewModel.comments.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val notificationViewModel: NotificationViewModel = hiltViewModel()
    val unreadCount by notificationViewModel.unreadCount.collectAsState()

    val scope = rememberCoroutineScope() // Moved scope here to survive dialog dismissal
    var isPosting by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var showProfileQrDialog by remember { mutableStateOf(false) }

    // ... (existing code)

    // Comment Sheet
    val activePostToComment = postToComment
    if (showCommentsSheet && activePostToComment != null) {
        ModalBottomSheet(
            onDismissRequest = {
                showCommentsSheet = false
                postToComment = null
            },
            sheetState = sheetState
        ) {
            conectate.com.presentation.components.CommentsSheet(
                comments = comments,
                onSendComment = { content: String, parentId: String? ->
                    viewModel.addComment(activePostToComment.id, content, parentId,
                        onSuccess = { },
                        onError = { error: String ->
                            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                onDeleteComment = { commentId: String ->
                    viewModel.deleteComment(commentId,
                        onSuccess = { },
                        onError = { error: String ->
                            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                onNavigateToProfile = { targetUserId: String ->
                    showCommentsSheet = false
                    if (targetUserId != viewModel.currentUserId) {
                        navController.navigate(Screen.UserProfile.createRoute(targetUserId))
                    }
                },
                onDismiss = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            showCommentsSheet = false
                            postToComment = null
                        }
                    }
                },
                isLoading = false,
                isPostOwner = postToComment?.userId == viewModel.currentUserId
            )
        }
    }

    if (isPosting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }

    


    // Load profile on first composition
    LaunchedEffect(userId) {
        viewModel.loadUserProfile(userId)
        (userId ?: viewModel.currentUserId)?.let { highlightViewModel.loadHighlights(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .height(48.dp) // Compact height (Standard was 64.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (userId != null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, "Atrás", tint = Color.White)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    Text(
                        text = "Perfil",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (userId == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BadgedBox(
                                     badge = {
                                         if (unreadCount > 0) {
                                                 androidx.compose.material3.Badge(
                                                     containerColor = Color(0xFFE53935),
                                                     contentColor = Color.White,
                                                     modifier = Modifier
                                                         .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                                                         .offset(x = (-10).dp, y = 10.dp) // Pull it closer to the icon
                                                 ) {
                                                     Text(
                                                         text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                                         fontSize = 9.sp,
                                                         fontWeight = FontWeight.ExtraBold,
                                                         color = Color.White,
                                                         textAlign = TextAlign.Center,
                                                         modifier = Modifier.padding(horizontal = 2.dp)
                                                     )
                                                 }
                                         }
                                     }
                                 ) {
                                     IconButton(onClick = { navController.navigate(Screen.Notifications.route) }) {
                                         Icon(Icons.Default.Notifications, "Notificaciones", tint = Color.White)
                                     }
                                 }
                            // Friends Icon
                            IconButton(onClick = { 
                                // Ideally navigation to a dedicated Friends List. 
                                // Reusing FollowList for now with type="friends" (backend logic needed later)
                                // or just navigating to FriendRequests for management
                                navController.navigate(Screen.FollowList.createRoute(viewModel.currentUserId ?: "", "friends"))
                            }) {
                                Icon(Icons.Default.Group, "Amigos", tint = Color.White)
                            }
                            IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                                Icon(Icons.Default.Settings, "Ajustes", tint = Color.White)
                            }
                        }
                    } else {
                        // Ocupar el mismo espacio para mantener el título centrado si se desea, 
                        // o simplemente un Spacer.
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 1 && (userId == null || userId == viewModel.currentUserId)) {
                FloatingActionButton(
                    onClick = { showCreatePostDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, "Crear Publicación")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isConnected) {
                OfflineNoticeBar(
                    message = "Sin conexión. Perfil cargado desde datos locales.",
                    onRetry = {
                        connectivityViewModel.retrySync("profile_retry")
                        viewModel.loadUserProfile(userId)
                    }
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
            when (val state = profileState) {
                is ProfileUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                is ProfileUiState.Success -> {
                    val user = state.user
                    val isOwnProfile = viewModel.currentUserId == user.id

                    if (showProfileQrDialog) {
                        SocialProfileQrDialog(
                            user = user,
                            clipboardManager = clipboardManager,
                            context = context,
                            onDismiss = { showProfileQrDialog = false },
                            onOpenChat = if (isOwnProfile) {
                                null
                            } else {
                                {
                                    showProfileQrDialog = false
                                    navController.navigate(Screen.Chat.createRoute(user.id))
                                }
                            }
                        )
                    }

                    LaunchedEffect(user.avatar_url, user.cover_url) {
                        val imageLoader = context.imageLoader
                        user.avatar_url?.takeIf { it.isNotBlank() }?.let { avatarUrl ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(context)
                                    .data(avatarUrl)
                                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                    .build()
                            )
                        }
                        user.cover_url?.takeIf { it.isNotBlank() }?.let { coverUrl ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(context)
                                    .data(coverUrl)
                                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                    .build()
                            )
                        }
                    }
                    
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding)
                    ) {
                        // 1. Cover item
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .clickable {
                                        user.cover_url?.let {
                                            (mainNavController ?: navController).navigate(Screen.FullScreenImage.createRoute(it))
                                        }
                                    }
                            ) {
                                if (!user.cover_url.isNullOrBlank()) {
                                    AsyncImage(
                                        model = user.cover_url,
                                        contentDescription = "Portada",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }

                        // 2. Personal Info item
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .offset(y = (-50).dp)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Box(
                                        modifier = Modifier.clickable {
                                            user.avatar_url?.let {
                                                (mainNavController ?: navController).navigate(Screen.FullScreenImage.createRoute(it))
                                            }
                                        }
                                    ) {
                                        CircularProfileImage(
                                            imageUrl = user.avatar_url ?: "",
                                            size = 110.dp,
                                            showBorder = true,
                                            borderWidth = 4.dp,
                                            borderColor = profileAvatarStatusColor(user),
                                            isVerified = user.is_verified
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.width(16.dp))
                                    
                                    Column(modifier = Modifier.weight(1f).padding(top = 64.dp)) {
                                        val nameToDisplay = user.display_name ?: user.full_name ?: user.username ?: "Usuario"
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = nameToDisplay,
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            if (user.is_verified) {
                                                Spacer(Modifier.width(4.dp))
                                                Icon(Icons.Default.CheckCircle, "Verificado", tint = Color(0xFF1DA1F2), modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        Text(text = "@${user.username ?: "usuario"}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        // Badges Row
                                        conectate.com.presentation.components.BadgeRow(
                                            userName = user.username,
                                            userEmail = user.email,
                                            badges = user.badges ?: emptyList()
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // Action Buttons Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isOwnProfile) {
                                        OutlinedButton(
                                            onClick = { navController.navigate(Screen.EditProfile.route) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Editar")
                                        }
                                        OutlinedButton(
                                            onClick = { (mainNavController ?: navController).navigate(Screen.MyReports.route) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Reportes")
                                        }
                                    } else {
                                        val isFollowing by viewModel.isFollowing.collectAsState()
                                        Button(
                                            onClick = { viewModel.toggleFollow() },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = if (isFollowing) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) else ButtonDefaults.buttonColors()
                                        ) {
                                            Text(if (isFollowing) "Siguiendo" else "Seguir")
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        val friendStatus by viewModel.friendStatus.collectAsState()
                                        
                                        var showUnfriendDialog by remember { mutableStateOf(false) }

                                        if (showUnfriendDialog) {
                                            AlertDialog(
                                                onDismissRequest = { showUnfriendDialog = false },
                                                title = { Text("¿Dejar de ser amigos?") },
                                                text = { Text("¿Seguro que quieres eliminar a ${user.display_name ?: "este usuario"} de tus amigos?") },
                                                confirmButton = {
                                                    Button(onClick = {
                                                        viewModel.unfriendUser()
                                                        showUnfriendDialog = false
                                                    }) {
                                                        Text("Confirmar")
                                                    }
                                                },
                                                dismissButton = {
                                                    OutlinedButton(onClick = { showUnfriendDialog = false }) {
                                                        Text("Cancelar")
                                                    }
                                                }
                                            )
                                        }

                                        Button(
                                            onClick = { 
                                                when(friendStatus) {
                                                    FriendStatus.NONE -> viewModel.sendFriendRequest()
                                                    FriendStatus.PENDING_SENT -> viewModel.cancelFriendRequest()
                                                    FriendStatus.PENDING_RECEIVED -> navController.navigate(Screen.FriendRequests.route)
                                                    FriendStatus.FRIENDS -> showUnfriendDialog = true
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (friendStatus == FriendStatus.NONE || friendStatus == FriendStatus.PENDING_RECEIVED) MaterialTheme.colorScheme.primary else Color.Gray
                                            )
                                        ) {
                                            Text(
                                                when(friendStatus) {
                                                    FriendStatus.NONE -> "Añadir amigo"
                                                    FriendStatus.PENDING_SENT -> "Cancelar solicitud"
                                                    FriendStatus.PENDING_RECEIVED -> "Responder"
                                                    FriendStatus.FRIENDS -> "Amigos"
                                                },
                                                maxLines = 1,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontSize = 12.sp
                                            )
                                        }
                                        IconButton(onClick = { navController.navigate(Screen.Chat.createRoute(user.id)) }) {
                                            Icon(Icons.Default.Email, "Mensaje", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { showProfileQrDialog = true },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (isOwnProfile) "Mi QR" else "QR perfil")
                                    }
                                    OutlinedButton(
                                        onClick = { (mainNavController ?: navController).navigate(Screen.SocialQrScanner.route) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Escanear amigo")
                                    }
                                }
                                
                                // Bio & Stats
                                if (!user.bio.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(text = user.bio, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                if (!user.status.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(profileAvatarStatusColor(user), shape = androidx.compose.foundation.shape.CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = user.status,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Location (City, Country)
                                if (!user.city.isNullOrBlank() || !user.country.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.LocationOn,
                                            contentDescription = "Ubicación",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        val location = listOfNotNull(user.city, user.country).joinToString(", ")
                                        Text(text = location, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                }

                                if (
                                    user.interests.isNotEmpty() ||
                                    user.linked_games.isNotEmpty() ||
                                    !user.favorite_music_url.isNullOrBlank() ||
                                    !user.favorite_book.isNullOrBlank() ||
                                    !user.favorite_sport.isNullOrBlank() ||
                                    !user.career.isNullOrBlank() ||
                                    !user.availability_schedule.isNullOrBlank()
                                ) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    DiscordProfileDetails(
                                        user = user,
                                        onOpenLink = { url ->
                                            val safeUrl = if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "https://$url"
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
                                            }
                                        }
                                    )
                                }

                                if (highlightState.highlights.isNotEmpty() || isOwnProfile) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    ProfileHighlightsRow(
                                        highlights = highlightState.highlights,
                                        isOwnProfile = isOwnProfile,
                                        onOpenAll = {
                                            (mainNavController ?: navController).navigate(Screen.MomentHighlights.createRoute(user.id))
                                        }
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    StatItem(number = userVideos.size, label = "Publicaciones") { }
                                    StatItem(number = user.followers_count ?: 0, label = "Seguidores") {
                                        navController.navigate(Screen.FollowList.createRoute(user.id, "followers"))
                                    }
                                    StatItem(number = user.following_count ?: 0, label = "Siguiendo") {
                                        navController.navigate(Screen.FollowList.createRoute(user.id, "following"))
                                    }
                                }
                            }
                        }

                        // 3. STICKY TABS
                        stickyHeader {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 1.dp
                            ) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    val tabs = listOf("Mis Videos", "Publicaciones", "Compartidos")
                                    tabs.forEachIndexed { index, title ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { viewModel.selectTab(index) }
                                                .padding(vertical = 12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = title,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Gray,
                                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                                )
                                                if (selectedTab == index) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Box(modifier = Modifier.width(24.dp).height(2.dp).background(MaterialTheme.colorScheme.primary))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Tab Content
                        when (selectedTab) {
                            0 -> { // Videos
                                if (userVideos.isEmpty()) {
                                    item {
                                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                            Text("No hay videos aún", color = Color.Gray)
                                        }
                                    }
                                } else {
                                    // We use a simulated grid for simplicity within LazyColumn
                                    val rows = userVideos.chunked(3)
                                    items(rows) { rowVideos ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            rowVideos.forEach { video ->
                                                Box(modifier = Modifier.weight(1f)) {
                                                    VideoThumbnail(
                                                        video = video,
                                                        videoRepository = viewModel.videoRepository,
                                                        onClick = { (mainNavController ?: navController).navigate(Screen.VideoPlayer.createRoute(video.id)) }
                                                    )
                                                }
                                            }
                                            // Fill empty spots if last row is incomplete
                                            repeat(3 - rowVideos.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                            1 -> { // Personal Posts
                                if (userPosts.isEmpty()) {
                                    item {
                                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                            Text("No hay publicaciones aún", color = Color.Gray)
                                        }
                                    }
                                } else {
                                    items(userPosts) { post ->
                                        conectate.com.presentation.components.PostCard(
                                            post = post,
                                            onLikeClick = { viewModel.togglePostLike(post) },
                                            onCommentClick = { 
                                                postToComment = post
                                                showCommentsSheet = true
                                                viewModel.loadComments(post.id)
                                            },
                                            onShareClick = { viewModel.togglePostShare(post) },
                                            // User click not needed on own profile
                                            onUserClick = { },
                                            isOwner = isOwnProfile,
                                            onDeleteClick = {
                                                if (isOwnProfile) {
                                                    viewModel.deletePost(post.id,
                                                        onSuccess = { android.widget.Toast.makeText(context, "Eliminado", android.widget.Toast.LENGTH_SHORT).show() },
                                                        onError = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
                                                    )
                                                }
                                            },
                                            onVisibilityClick = { visibility ->
                                                if (isOwnProfile) {
                                                    viewModel.updatePostVisibility(post.id, visibility,
                                                        onSuccess = { android.widget.Toast.makeText(context, "Actualizado", android.widget.Toast.LENGTH_SHORT).show() },
                                                        onError = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
                                                    )
                                                }
                                            },
                                            onEditClick = {
                                                if (isOwnProfile) {
                                                    postToEdit = post
                                                    showEditPostDialog = true
                                                }
                                            },
                                            onUrlClick = { url ->
                                                if (url.contains("conectate.com/post/")) {
                                                    val postId = url.substringAfter("post/")
                                                    navController.navigate(conectate.com.presentation.navigation.Screen.PostDetail.createRoute(postId))
                                                } else if (url.contains("conectate.com/video/")) {
                                                    val videoId = url.substringAfter("video/")
                                                    navController.navigate(conectate.com.presentation.navigation.Screen.VideoPlayer.createRoute(videoId))
                                                }
                                            },
                                            onPostClick = {
                                                navController.navigate(Screen.PostDetail.createRoute(post.id))
                                            },
                                            onReactionSelected = { reaction -> viewModel.selectPostReaction(post, reaction.name) },
                                            onReactionRemoved = { viewModel.clearPostReaction(post) },
                                            onSendToFriend = {
                                                postToShare = post
                                            }
                                        )
                                    }
                                }
                            }
                            2 -> { // Shared Tab (Posts and Videos)
                                if (sharedPosts.isEmpty() && sharedVideos.isEmpty()) {
                                    item {
                                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                            Text("No has compartido nada aún", color = Color.Gray)
                                        }
                                    }
                                } else {
                                    // Show shared videos first in grid
                                    if (sharedVideos.isNotEmpty()) {
                                        item {
                                            Text("Videos Compartidos", modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                                        }
                                        val rows = sharedVideos.chunked(3)
                                        items(rows) { rowVideos ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                rowVideos.forEach { video ->
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        VideoThumbnail(
                                                            video = video,
                                                            videoRepository = viewModel.videoRepository,
                                                            onClick = { (mainNavController ?: navController).navigate(Screen.VideoPlayer.createRoute(video.id)) }
                                                        )
                                                    }
                                                }
                                                repeat(3 - rowVideos.size) { Spacer(modifier = Modifier.weight(1f)) }
                                            }
                                        }
                                    }

                                    // Show shared posts
                                    if (sharedPosts.isNotEmpty()) {
                                        item {
                                            Text("Publicaciones Compartidas", modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                                        }
                                        items(sharedPosts) { post ->
                                            conectate.com.presentation.components.PostCard(
                                                post = post,
                                                onLikeClick = { viewModel.togglePostLike(post) },
                                                onCommentClick = { 
                                                    postToComment = post
                                                    showCommentsSheet = true
                                                    viewModel.loadComments(post.id)
                                                },
                                                onShareClick = { viewModel.togglePostShare(post) },
                                                onUserClick = { 
                                                    if (it != viewModel.currentUserId) navController.navigate(Screen.UserProfile.createRoute(it))
                                                },
                                                onPostClick = {
                                                    navController.navigate(Screen.PostDetail.createRoute(post.id))
                                                },
                                                onReactionSelected = { reaction -> viewModel.selectPostReaction(post, reaction.name) },
                                                onReactionRemoved = { viewModel.clearPostReaction(post) },
                                                onSendToFriend = {
                                                    postToShare = post
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Preserving Admin and Sign out buttons as final items - REMOVED per user request
                        // item {
                        //    Spacer(modifier = Modifier.height(24.dp))
                        //    // Moved to Settings
                        // }
                    }
                }

                is ProfileUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(text = state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            if (showCreatePostDialog) {
                conectate.com.presentation.components.CreatePostDialog(
                    onDismiss = { showCreatePostDialog = false },
                    onPostCreated = { content, uris, visibility ->
                        showCreatePostDialog = false
                        isPosting = true
                        android.widget.Toast.makeText(context, "Publicando...", android.widget.Toast.LENGTH_SHORT).show()
                        
                        scope.launch {
                            viewModel.postRepository.createPost(content, uris, visibility)
                                .onSuccess {
                                    isPosting = false
                                    android.widget.Toast.makeText(context, "Publicado con éxito", android.widget.Toast.LENGTH_SHORT).show()
                                    viewModel.loadUserProfile(userId) // Reload to see the new post
                                }
                                .onFailure { e ->
                                    isPosting = false
                                    android.widget.Toast.makeText(context, "Error al publicar: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                )
            }

            // Comment Dialog

            

            
            if (isPosting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            
            postToShare?.let { post ->
                conectate.com.presentation.components.InternalShareDialog(
                    userRepository = viewModel.userRepository,
                    onDismiss = { postToShare = null },
                    onShareWithUser = { user ->
                        postToShare = null
                        scope.launch {
                             val postLink = "https://conectate.com/post/${post.id}"
                             val route = Screen.Chat.createRoute(user.id, initialMessage = "Compartió una publicación:\n$postLink\n\n${post.content.take(50)}...")
                             (mainNavController ?: navController).navigate(route)
                        }
                    }
                )
            }
            
            val editingPost = postToEdit
            if (showEditPostDialog && editingPost != null) {
                conectate.com.presentation.components.EditPostDialog(
                    initialContent = editingPost.content,
                    onDismiss = { 
                        showEditPostDialog = false
                        postToEdit = null
                    },
                    onConfirm = { newContent ->
                        // Show loading? Reuse isPosting maybe or just toast
                         android.widget.Toast.makeText(context, "Guardando...", android.widget.Toast.LENGTH_SHORT).show()
                         viewModel.updatePostContent(editingPost.id, newContent,
                            onSuccess = {
                                showEditPostDialog = false
                                postToEdit = null
                                android.widget.Toast.makeText(context, "Actualizado", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onError = {
                                android.widget.Toast.makeText(context, "Error: $it", android.widget.Toast.LENGTH_SHORT).show()
                            }
                         )
                    }
                )
            }
            }
        }
    }
}
