package conectate.com.presentation.profile

import androidx.compose.runtime.Composable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.Post
import conectate.com.data.model.Badge
import conectate.com.presentation.common.ConnectivityViewModel
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.OfflineNoticeBar
import conectate.com.presentation.components.VideoThumbnail
import conectate.com.presentation.components.ProfileAmbientBackground
import conectate.com.presentation.components.ProfileWelcomeBanner
import conectate.com.presentation.components.CommentAudioPlayer
import conectate.com.presentation.theme.profileThemeColors
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
private fun ProfileDetailsSections(user: conectate.com.data.model.User) {
    val rows = mapOf(
        "career" to ("Profesión o carrera" to user.career.orEmpty()),
        "sports" to ("Deporte" to user.favorite_sport.orEmpty()),
        "books" to ("Libro" to user.favorite_book.orEmpty()),
        "hobbies" to ("Hobbies" to user.hobbies.joinToString(" · ")),
        "interests" to ("Intereses" to user.interests.joinToString(" · ")),
        "games" to ("Videojuegos" to user.linked_games.joinToString(" · ")),
        "availability" to ("Disponibilidad" to user.availability_schedule.orEmpty()),
    )
    val musicUrl = user.favorite_music_url?.takeIf(String::isNotBlank)
    val defaultOrder = listOf("music", "interests", "hobbies", "career", "games", "sports", "books", "availability")
    val order = (user.profile_section_order + defaultOrder).distinct()
    if (rows.values.none { it.second.isNotBlank() } && musicUrl == null) return

    Spacer(modifier = Modifier.height(12.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            order.forEach { section ->
                if (section == "music") {
                    musicUrl?.let { url ->
                        Text(
                            "Música de entrada",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        CommentAudioPlayer(audioUrl = url, modifier = Modifier.fillMaxWidth())
                    }
                } else if (section == "games" && user.linked_games.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Videojuegos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            user.linked_games.forEach { game ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val logoUrl = user.game_logo_urls.entries
                                        .firstOrNull { it.key.equals(game, ignoreCase = true) }
                                        ?.value
                                    if (!logoUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = UbuntuStorageUrl.rewrite(logoUrl) ?: logoUrl,
                                            contentDescription = game,
                                            modifier = Modifier.size(28.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.SportsEsports,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(game, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                } else {
                    rows[section]?.takeIf { it.second.isNotBlank() }?.let { (label, value) ->
                        Column {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
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
    connectivityViewModel: ConnectivityViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    val profileState by viewModel.profileState.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val userVideos by viewModel.userVideos.collectAsState()
    val userPosts by viewModel.userPosts.collectAsState()
    val sharedPosts by viewModel.sharedPosts.collectAsState()
    val sharedVideos by viewModel.sharedVideos.collectAsState()
    val favoriteVideos by viewModel.favoriteVideos.collectAsState()
    val isConnected by connectivityViewModel.isConnected.collectAsState()
    
    var showCreatePostDialog by remember { mutableStateOf(false) }
    var postToShare by remember { mutableStateOf<conectate.com.data.model.Post?>(null) }
    
    // Comments Sheet State
    var showCommentsSheet by remember { mutableStateOf(false) }
    var postToComment by remember { mutableStateOf<Post?>(null) }
    var postToReport by remember { mutableStateOf<Post?>(null) }
    var commentToReport by remember { mutableStateOf<conectate.com.data.model.Comment?>(null) }
    
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
    val lifecycleOwner = LocalLifecycleOwner.current

    // ... (existing code)

    // Comment Sheet
    if (showCommentsSheet && postToComment != null) {
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
                    viewModel.addComment(postToComment!!.id, content, parentId,
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
                onReportComment = { comment ->
                    commentToReport = comment
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
                isPostOwner = postToComment?.userId
                    ?.trim()
                    ?.equals(viewModel.currentUserId?.trim(), ignoreCase = true) == true,
                showReportAction = true
            )
        }
    }

    postToReport?.let { reportPost ->
        conectate.com.presentation.components.ContentReportDialog(
            title = "Reportar publicación",
            subjectText = reportPost.userName,
            reasonOptions = listOf(
                "Spam",
                "Acoso",
                "Violencia",
                "Desinformación",
                "Contenido sexual",
                "Otro"
            ),
            attachEvidenceEnabled = true,
            onDismiss = { postToReport = null },
            onSubmit = { reason, details, evidenceUri ->
                viewModel.reportPost(
                    post = reportPost,
                    reason = reason,
                    details = details,
                    evidenceUri = evidenceUri,
                    onSuccess = {
                        android.widget.Toast.makeText(context, "Reporte enviado", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onError = {
                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                    }
                )
                postToReport = null
            }
        )
    }

    commentToReport?.let { reportComment ->
        conectate.com.presentation.components.ContentReportDialog(
            title = "Reportar comentario",
            subjectText = reportComment.userName ?: "Usuario",
            reasonOptions = listOf(
                "Spam",
                "Acoso",
                "Violencia",
                "Desinformación",
                "Contenido sexual",
                "Otro"
            ),
            attachEvidenceEnabled = true,
            onDismiss = { commentToReport = null },
            onSubmit = { reason, details, evidenceUri ->
                viewModel.reportComment(
                    comment = reportComment,
                    reason = reason,
                    details = details,
                    evidenceUri = evidenceUri,
                    onSuccess = {
                        android.widget.Toast.makeText(context, "Reporte enviado", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onError = {
                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                    }
                )
                commentToReport = null
            }
        )
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
    }

    DisposableEffect(lifecycleOwner, userId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Reload from local database without forcing network.
                // This ensures optimistic updates from EditProfileScreen are immediately reflected
                // without triggering a stale network fetch.
                viewModel.loadUserProfile(userId, forceNetwork = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
                    val rewrittenAvatarUrl = remember(user.avatar_url) {
                        UbuntuStorageUrl.rewrite(user.avatar_url)
                    }
                    val rewrittenCoverUrl = remember(user.cover_url) {
                        UbuntuStorageUrl.rewrite(user.cover_url)
                    }
                    val profileColors = profileThemeColors(
                        rawTheme = user.profile_theme,
                        dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    )

                    LaunchedEffect(rewrittenAvatarUrl, rewrittenCoverUrl) {
                        val imageLoader = context.imageLoader
                        rewrittenAvatarUrl?.takeIf { it.isNotBlank() }?.let { avatarUrl ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(context)
                                    .data(avatarUrl)
                                    .memoryCacheKey(UbuntuStorageUrl.stableCacheKey(user.avatar_url))
                                    .diskCacheKey(UbuntuStorageUrl.stableCacheKey(user.avatar_url))
                                    .placeholderMemoryCacheKey(UbuntuStorageUrl.stableCacheKey(user.avatar_url))
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .build()
                            )
                        }
                        rewrittenCoverUrl?.takeIf { it.isNotBlank() }?.let { coverUrl ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(context)
                                    .data(coverUrl)
                                    .memoryCacheKey(UbuntuStorageUrl.stableCacheKey(user.cover_url))
                                    .diskCacheKey(UbuntuStorageUrl.stableCacheKey(user.cover_url))
                                    .placeholderMemoryCacheKey(UbuntuStorageUrl.stableCacheKey(user.cover_url))
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .build()
                            )
                        }
                    }
                    val coverRequest = remember(rewrittenCoverUrl, user.cover_url) {
                        rewrittenCoverUrl?.let { coverUrl ->
                            ImageRequest.Builder(context)
                                .data(coverUrl)
                                .memoryCacheKey(UbuntuStorageUrl.stableCacheKey(user.cover_url))
                                .diskCacheKey(UbuntuStorageUrl.stableCacheKey(user.cover_url))
                                .placeholderMemoryCacheKey(UbuntuStorageUrl.stableCacheKey(user.cover_url))
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .crossfade(false)
                                .build()
                        }
                    }
                    
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding)
                    ) {
                        // 1. Cover item
                        item {
                            ProfileAmbientBackground(
                                themeId = user.profile_theme,
                                effectId = user.profile_effect,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clickable {
                                        rewrittenCoverUrl?.let {
                                            (mainNavController ?: navController).navigate(Screen.FullScreenImage.createRoute(it))
                                        }
                                    }
                            ) {
                                if (!user.cover_url.isNullOrBlank()) {
                                    AsyncImage(
                                        model = coverRequest,
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
                                    val nameToDisplay = user.display_name ?: user.full_name ?: user.username ?: "Usuario"
                                    Box(
                                        modifier = Modifier.clickable {
                                            rewrittenAvatarUrl?.let {
                                                val route = if (user.isProfileAvatarVideo) {
                                                    Screen.FullScreenVideo.createRoute(it)
                                                } else {
                                                    Screen.FullScreenImage.createRoute(it)
                                                }
                                                (mainNavController ?: navController).navigate(route)
                                            }
                                        }
                                    ) {
                                        val profileFrameColor = when (user.profile_frame.lowercase()) {
                                            "gold" -> Color(0xFFFFC107)
                                            "neon" -> Color(0xFF00E5FF)
                                            "ruby" -> Color(0xFFE53935)
                                            "ocean" -> Color(0xFF1976D2)
                                            else -> profileColors.accent
                                        }
                                        CircularProfileImage(
                                            imageUrl = rewrittenAvatarUrl,
                                            size = 110.dp,
                                            showBorder = true,
                                            borderWidth = if (user.profile_frame.isBlank()) 4.dp else 6.dp,
                                            borderColor = profileFrameColor,
                                            isVerified = user.is_verified,
                                            mediaType = user.avatar_media_type,
                                            muted = user.avatar_video_muted,
                                            allowSound = true,
                                            fallbackName = nameToDisplay
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.width(16.dp))
                                    
                                    Column(modifier = Modifier.weight(1f).padding(top = 64.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = nameToDisplay,
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (user.is_admin || conectate.com.presentation.components.isUserAdmin(user.username, user.email)) {
                                                Spacer(Modifier.width(4.dp))
                                                conectate.com.presentation.components.AdminBadge(showVerifiedLine = false)
                                            }
                                            if (user.is_verified) {
                                                Spacer(Modifier.width(4.dp))
                                                Icon(Icons.Default.CheckCircle, "Verificado", tint = Color(0xFF1DA1F2), modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        Text(
                                            text = "@${user.username ?: "usuario"}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = profileColors.accent
                                        )
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        // Badges Row
                                        conectate.com.presentation.components.BadgeRow(
                                            userName = user.username,
                                            userEmail = user.email,
                                            badges = user.badges ?: emptyList(),
                                            includeAdmin = false
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
                                            Text("Editar", maxLines = 1, fontSize = 12.sp)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                (mainNavController ?: navController).navigate(Screen.WebLoginScanner.createRoute()) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Web QR", maxLines = 1, fontSize = 12.sp)
                                        }
                                        OutlinedButton(
                                            onClick = { (mainNavController ?: navController).navigate(Screen.MyReports.route) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Reportes", maxLines = 1, fontSize = 12.sp)
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

                                if (isOwnProfile) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    FilledTonalButton(
                                        onClick = { navController.navigate(Screen.CustomizeApp.route) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.Palette, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Personalizar perfil")
                                    }
                                }

                                user.profile_welcome
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { welcome ->
                                        Spacer(modifier = Modifier.height(12.dp))
                                        ProfileWelcomeBanner(
                                            message = welcome,
                                            themeId = user.profile_theme
                                        )
                                    }
                                
                                // Bio & Stats
                                if (!user.bio.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(text = user.bio, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                                ProfileDetailsSections(user)
                                
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
                                    val showFavorites = userId == null ||
                                        userId == viewModel.currentUserId ||
                                        user.favorites_public
                                    val tabs = if (showFavorites) {
                                        listOf("Videos", "Publicaciones", "Compartidos", "Favoritos")
                                    } else {
                                        listOf("Videos", "Publicaciones", "Compartidos")
                                    }
                                    tabs.forEachIndexed { index, title ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { viewModel.selectTab(index) }
                                                .padding(vertical = 12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                if (index == 3) {
                                                    Icon(
                                                        imageVector = if (selectedTab == index) Icons.Default.Star else Icons.Default.StarBorder,
                                                        contentDescription = "Favoritos",
                                                        tint = if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Gray
                                                    )
                                                } else {
                                                    Text(
                                                        text = title,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Gray,
                                                        fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
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
                                                        onClick = {
                                                            (mainNavController ?: navController).navigate(
                                                                Screen.ProfileVideoFeed.createRoute(
                                                                    profileId = user.id,
                                                                    videoId = video.id
                                                                )
                                                            )
                                                        }
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
                                            onSendToFriend = {
                                                postToShare = post
                                            },
                                            onReportClick = { postToReport = post },
                                            showReportAction = true
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
                                                isOwner = post.userId == viewModel.currentUserId,
                                                showOwnerActions = false,
                                                onPostClick = {
                                                    navController.navigate(Screen.PostDetail.createRoute(post.id))
                                                },
                                                onSendToFriend = {
                                                    postToShare = post
                                                },
                                                onReportClick = { postToReport = post },
                                                showReportAction = true
                                            )
                                        }
                                    }
                                }
                            }
                            3 -> {
                                if (favoriteVideos.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("No hay videos favoritos aún", color = Color.Gray)
                                        }
                                    }
                                } else {
                                    val rows = favoriteVideos.chunked(3)
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
                                                        onClick = {
                                                            (mainNavController ?: navController)
                                                                .navigate(Screen.VideoPlayer.createRoute(video.id))
                                                        }
                                                    )
                                                }
                                            }
                                            repeat(3 - rowVideos.size) { Spacer(modifier = Modifier.weight(1f)) }
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
            
            if (showEditPostDialog && postToEdit != null) {
                conectate.com.presentation.components.EditPostDialog(
                    initialContent = postToEdit!!.content,
                    onDismiss = { 
                        showEditPostDialog = false
                        postToEdit = null
                    },
                    onConfirm = { newContent ->
                        // Show loading? Reuse isPosting maybe or just toast
                         android.widget.Toast.makeText(context, "Guardando...", android.widget.Toast.LENGTH_SHORT).show()
                         viewModel.updatePostContent(postToEdit!!.id, newContent,
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
