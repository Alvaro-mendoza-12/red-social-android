package conectate.com.presentation.social

import androidx.compose.runtime.Composable

import conectate.com.presentation.social.ChallengeViewModel
import conectate.com.presentation.components.Line
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.model.Post
import conectate.com.presentation.common.ConnectivityViewModel
import conectate.com.presentation.components.OfflineNoticeBar
import conectate.com.presentation.components.PostCard
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.profile.NotificationViewModel
import conectate.com.presentation.components.MomentGrid
import conectate.com.presentation.social.MomentViewModel
import conectate.com.presentation.messages.VoiceState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.blur
import coil.compose.rememberAsyncImagePainter
import conectate.com.util.UbuntuMedia
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

private fun formatRemainingTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", m, s)
}

private fun socialReactionEmoji(type: String): String = when (type.uppercase()) {
    "LOVE" -> "❤️"
    "HAHA" -> "😂"
    "WOW" -> "😮"
    "SAD" -> "😢"
    "ANGRY" -> "😡"
    "CARE" -> "🤗"
    "FIRE" -> "🔥"
    else -> "👍"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SocialScreen(
    navController: NavController,
    viewModel: SocialViewModel = hiltViewModel(),
    challengeViewModel: ChallengeViewModel = hiltViewModel(),
    notificationViewModel: NotificationViewModel = hiltViewModel(),
    momentViewModel: MomentViewModel = hiltViewModel(),
    createMomentViewModel: CreateMomentViewModel = hiltViewModel(),
    connectivityViewModel: ConnectivityViewModel = hiltViewModel(),
    sharedText: String? = null,
    sharedUri: String? = null
) {
    val posts by viewModel.posts.collectAsState()
    val activeStreams by viewModel.activeStreams.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val unreadCount by notificationViewModel.unreadCount.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val moments by momentViewModel.moments.collectAsState()
    val activeChallenge by challengeViewModel.activeChallenge.collectAsState()
    val isSubmittingChallenge by challengeViewModel.isSubmittingChallenge.collectAsState()
    val hasParticipated by challengeViewModel.hasParticipated.collectAsState()
    val votingCountdown by challengeViewModel.votingCountdown.collectAsState()
    val winnersCountdown by challengeViewModel.winnersCountdown.collectAsState()
    val isConnected by connectivityViewModel.isConnected.collectAsState()
    val reactionUsers by viewModel.reactionUsers.collectAsState()
    val reactionsLoading by viewModel.reactionsLoading.collectAsState()
    val reactionsError by viewModel.reactionsError.collectAsState()
    
    // Friend Request Count
    val friendViewModel: conectate.com.presentation.profile.FriendRequestsViewModel = hiltViewModel()
    val friendRequests by friendViewModel.requests.collectAsState()
    val pendingRequestCount = friendRequests.size
    
    val pullRefreshState = rememberPullRefreshState(isRefreshing, { 
        viewModel.loadPosts()
        viewModel.refreshLives()
        momentViewModel.refreshMoments()
        challengeViewModel.refreshChallengeData()
    })
    
    val tabs = listOf("Social", "Momentos", "Retos")
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    val socialListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        notificationViewModel.updateUnreadCount()
        friendViewModel.loadRequests()
        viewModel.refreshLives()
        // Moments auto-load in ViewModel init - no manual call needed
    }

    var showCreatePostDialog by remember { mutableStateOf(false) }
    var postToEdit by remember { mutableStateOf<conectate.com.data.model.Post?>(null) }
    var postToReport by remember { mutableStateOf<conectate.com.data.model.Post?>(null) }
    var showInternalShareDialog by remember { mutableStateOf(false) }
    var selectedPostForShare by remember { mutableStateOf<conectate.com.data.model.Post?>(null) }
    var reactionPost by remember { mutableStateOf<Post?>(null) }
    var challengesUnlocked by remember { mutableStateOf(false) }
    var showDrawingCanvas by remember { mutableStateOf(false) }
    var showTextChallengeEntry by remember { mutableStateOf(false) }
    var challengeText by remember { mutableStateOf("") }
    var showCreateChallengeDialog by remember { mutableStateOf(false) }
    var showPassTorchDialog by remember { mutableStateOf(false) }
    val submissions by challengeViewModel.challengeSubmissions.collectAsState()
    val galleryMedia by createMomentViewModel.galleryMedia.collectAsState()
    var showChallengeMediaPicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    
    // Camera Handlers
    var tempCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempCameraUri != null) {
            challengeViewModel.submitChallengeMediaFromUri(tempCameraUri!!)
        }
    }

    val recordVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success && tempCameraUri != null) {
            challengeViewModel.submitChallengeMediaFromUri(tempCameraUri!!)
        }
    }

    // Picker Launchers (Old simple one - we'll keep it for backup or remove if handled by picker)
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        uri?.let { challengeViewModel.submitChallengeMediaFromUri(it) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            android.widget.Toast.makeText(context, "Permiso de micrófono denegado. Es necesario para este reto.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(showChallengeMediaPicker) {
        if (showChallengeMediaPicker) {
            createMomentViewModel.loadGalleryMedia(context)
        }
    }

    LaunchedEffect(sharedText, sharedUri) {
        if (sharedText != null || sharedUri != null) {
            showCreatePostDialog = true
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 2) {
            challengeViewModel.loadActiveChallenge()
        }
    }

    LaunchedEffect(socialListState, posts) {
        snapshotFlow {
            socialListState.layoutInfo.visibleItemsInfo
                .mapNotNull { it.key as? String }
                .filterNot { it == "active-lives" }
                .toSet()
        }
            .distinctUntilChanged()
            .collect { visibleIds ->
                visibleIds.forEach { id ->
                    posts.firstOrNull { it.id == id }?.let(viewModel::recordPostImpression)
                }
            }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Forzar refresco si estamos en la pestaña de Retos al volver a la app
                if (pagerState.currentPage == 2) {
                    challengeViewModel.loadActiveChallenge()
                } else if (pagerState.currentPage == 0) {
                    viewModel.loadPosts()
                    coroutineScope.launch { socialListState.scrollToItem(0) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val userChallenges by challengeViewModel.userChallenges.collectAsState()
    LaunchedEffect(showCreateChallengeDialog) {
        if (showCreateChallengeDialog) {
            challengeViewModel.loadUserChallenges()
        }
    }

    if (showCreatePostDialog) {
        conectate.com.presentation.components.CreatePostDialog(
            onDismiss = { showCreatePostDialog = false },
            onPostCreated = { content, uris, visibility ->
                viewModel.createPost(content, uris, visibility)
                showCreatePostDialog = false
            },
            initialText = sharedText,
            initialUri = if (sharedUri != null) android.net.Uri.parse(sharedUri) else null
        )
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
                viewModel.reportPost(reportPost, reason, details, evidenceUri)
                postToReport = null
            }
        )
    }

    reactionPost?.let { selectedPost ->
        LaunchedEffect(selectedPost.id) {
            viewModel.loadPostReactions(selectedPost.id)
        }
        ModalBottomSheet(
            onDismissRequest = {
                reactionPost = null
                viewModel.clearPostReactions()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = "${selectedPost.reactionCounts.values.sum().takeIf { it > 0 } ?: selectedPost.likeCount} reacciones",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                when {
                    reactionsLoading -> Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    reactionsError != null -> Text(
                        reactionsError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                    reactionUsers.isEmpty() -> Text(
                        "Todavía no hay reacciones.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                        items(reactionUsers, key = { "${it.userId}:${it.reactionType}" }) { reaction ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        reactionPost = null
                                        viewModel.clearPostReactions()
                                        if (reaction.userId != viewModel.currentUserId) {
                                            navController.navigate(Screen.UserProfile.createRoute(reaction.userId))
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                conectate.com.presentation.components.CircularProfileImage(
                                    imageUrl = reaction.userAvatarUrl.orEmpty(),
                                    size = 42.dp,
                                    isVerified = reaction.isVerified,
                                    fallbackName = reaction.userName
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    reaction.userName,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(socialReactionEmoji(reaction.reactionType), fontSize = 24.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    
    if (showCreateChallengeDialog) {
        var newTitle by remember { mutableStateOf("") }
        var newDesc by remember { mutableStateOf("") }
        var newType by remember { mutableStateOf("text") }
        var titleError by remember { mutableStateOf(false) }
        var descError by remember { mutableStateOf(false) }
        
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showCreateChallengeDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Premium
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🎯", fontSize = 36.sp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Crear Reto Épico",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        "Desafía a la comunidad",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            IconButton(onClick = { showCreateChallengeDialog = false }) {
                                Icon(Icons.Default.Close, "Cerrar")
                            }
                        }
                    }
                    
                    // Contenido con scroll
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp)
                    ) {
                        // Paso 1: Tipo de Reto
                        Text(
                            "Tipo de Reto",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        
                        // Cards visuales para tipos
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val types = listOf(
                                "text" to "📝",
                                "camera" to "📷",
                                "drawing" to "🎨",
                                "audio" to "🎤"
                            )
                            
                            types.forEach { (type, emoji) ->
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(80.dp)
                                        .clickable { newType = type },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (newType == type) 
                                            MaterialTheme.colorScheme.primaryContainer
                                        else 
                                            MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    border = if (newType == type)
                                        BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                                    else null,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(emoji, fontSize = 28.sp)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            type.replaceFirstChar { it.uppercase() },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (newType == type) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        
                        // Paso 2: Título
                        Text(
                            "Título del Reto",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        
                        val maxTitleChars = 50
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { 
                                if (it.length <= maxTitleChars) {
                                    newTitle = it
                                    titleError = it.trim().length < 5
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Ejemplo: \"Dibuja tu mascota favorita\"") },
                            isError = titleError && newTitle.isNotEmpty(),
                            supportingText = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        if (titleError && newTitle.isNotEmpty()) "Mínimo 5 caracteres" else "Corto y directo",
                                        color = if (titleError && newTitle.isNotEmpty()) 
                                            MaterialTheme.colorScheme.error 
                                        else 
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "${newTitle.length}/$maxTitleChars",
                                        color = when {
                                            newTitle.length > 45 -> Color(0xFFFF9800)
                                            newTitle.length >= 5 -> Color(0xFF4CAF50)
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            shape = RoundedCornerShape(16.dp)
                        )
                        
                        Spacer(Modifier.height(20.dp))
                        
                        // Paso 3: Descripción
                        Text(
                            "Descripción",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        
                        val maxDescChars = 200
                        OutlinedTextField(
                            value = newDesc,
                            onValueChange = { 
                                if (it.length <= maxDescChars) {
                                    newDesc = it
                                    descError = it.trim().length < 10
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            placeholder = { Text("Explica qué deben hacer los participantes...") },
                            isError = descError && newDesc.isNotEmpty(),
                            supportingText = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        if (descError && newDesc.isNotEmpty()) "Mínimo 10 caracteres" else "Instrucciones claras",
                                        color = if (descError && newDesc.isNotEmpty()) 
                                            MaterialTheme.colorScheme.error 
                                        else 
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "${newDesc.length}/$maxDescChars",
                                        color = when {
                                            newDesc.length > 180 -> Color(0xFFFF9800)
                                            newDesc.length >= 10 -> Color(0xFF4CAF50)
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            shape = RoundedCornerShape(16.dp)
                        )
                        
                        Spacer(Modifier.height(24.dp))
                        
                        // Preview del reto
                        if (newTitle.isNotEmpty() || newDesc.isNotEmpty()) {
                            Text(
                                "Vista Previa",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            when(newType) {
                                                "text" -> "📝"
                                                "camera" -> "📷"
                                                "drawing" -> "🎨"
                                                else -> "🎤"
                                            },
                                            fontSize = 24.sp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (newTitle.isNotEmpty()) newTitle else "Tu reto aquí",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (newDesc.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            newDesc,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                        
                        if (userChallenges.isNotEmpty()) {
                            Spacer(Modifier.height(32.dp))
                            Text(
                                "Mi Historial de Retos",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(12.dp))
                            
                            userChallenges.forEach { challenge ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            when(challenge.challengeType) {
                                                "camera" -> "📷"
                                                "drawing" -> "🎨"
                                                "audio" -> "🎤"
                                                else -> "📝"
                                            },
                                            fontSize = 20.sp
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(challenge.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                            if (challenge.description != null) {
                                                Text(challenge.description, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            }
                                        }
                                        IconButton(onClick = { challengeViewModel.deleteUserChallenge(challenge.id) }) {
                                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Footer con botones
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 12.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showCreateChallengeDialog = false },
                                modifier = Modifier.weight(1f).height(56.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Cancelar", fontWeight = FontWeight.Bold)
                            }
                            
                            val isValid = newTitle.trim().length >= 5 && newDesc.trim().length >= 10
                            Button(
                                onClick = {
                                    if (isValid) {
                                        challengeViewModel.createChallenge(newTitle, newDesc, newType, "medium")
                                        showCreateChallengeDialog = false
                                    }
                                },
                                modifier = Modifier.weight(2f).height(56.dp),
                                enabled = isValid,
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(
                                    "🚀 LANZAR RETO",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTextChallengeEntry) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showTextChallengeEntry = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                // Header con gradiente sutil
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📝", fontSize = 28.sp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                activeChallenge?.challenge?.title ?: "Responder Reto",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                "Comparte tu creatividad",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    IconButton(onClick = { showTextChallengeEntry = false }) {
                        Icon(Icons.Default.Close, "Cerrar")
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // Descripción del reto
                if (!activeChallenge?.challenge?.description.isNullOrEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            activeChallenge?.challenge?.description ?: "",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                
                // TextField mejorado
                val maxChars = 500
                val charCount = challengeText.length
                val charColor = when {
                    charCount > 490 -> Color(0xFFE53935)
                    charCount > 400 -> Color(0xFFFF9800)
                    else -> Color(0xFF4CAF50)
                }
                
                OutlinedTextField(
                    value = challengeText,
                    onValueChange = { if (it.length <= maxChars) challengeText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    placeholder = { 
                        Text(
                            "Comparte tu perspectiva única...",
                            style = MaterialTheme.typography.bodyLarge
                        ) 
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge
                )
                
                // Contador de caracteres
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        "$charCount/$maxChars",
                        style = MaterialTheme.typography.labelMedium,
                        color = charColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(Modifier.height(24.dp))
                
                // Botón de envío mejorado
                Button(
                    onClick = {
                        if (challengeText.trim().isNotEmpty()) {
                            challengeViewModel.submitText(challengeText)
                            showTextChallengeEntry = false
                            challengeText = ""
                            challengesUnlocked = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = challengeText.trim().isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        "✨ ENVIAR RESPUESTA",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
                
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    
    
    if (showDrawingCanvas) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showDrawingCanvas = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column {
                    // Header Premium
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🎨", fontSize = 32.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            "Tu Obra Maestra",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Black
                                        )
                                        Text(
                                            activeChallenge?.challenge?.title ?: "Reto de Dibujo",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                                IconButton(onClick = { showDrawingCanvas = false }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cerrar")
                                }
                            }
                            
                            // Descripción del reto
                            if (!activeChallenge?.challenge?.description.isNullOrEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        activeChallenge?.challenge?.description ?: "",
                                        modifier = Modifier.padding(10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                    
                    // Canvas
                    Box(modifier = Modifier.weight(1f)) {
                        conectate.com.presentation.components.DrawingCanvas(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            onSave = { lines ->
                                challengeViewModel.submitDrawing(lines)
                                showDrawingCanvas = false
                                challengesUnlocked = true
                            }
                        )
                    }
                    
                    // Footer motivacional
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "¡Dale rienda suelta a tu creatividad!",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }


    if (showPassTorchDialog) {
        val friends by viewModel.friends.collectAsState()
        AlertDialog(
            onDismissRequest = { showPassTorchDialog = false },
            title = { Text("Pasar la Antorcha 🔥") },
            text = {
                if (friends.isEmpty()) {
                    Text("Aún no tienes amigos a quienes desafiar.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(friends) { friend ->
                            ListItem(
                                headlineContent = { Text(friend.displayName ?: "Amigo") },
                                trailingContent = {
                                    Button(onClick = {
                                        challengeViewModel.passTorch(friend.id)
                                        showPassTorchDialog = false
                                    }) { Text("DESAFIAR") }
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPassTorchDialog = false }) { Text("CERRAR") } }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { } // El BottomBar se maneja en el Main scaffold habitualmente
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            if (!isConnected) {
                OfflineNoticeBar(
                    message = "Conéctate a internet para actualizar Social.",
                    onRetry = {
                        connectivityViewModel.retrySync("social_retry")
                        viewModel.loadPosts()
                        momentViewModel.refreshMoments()
                        challengeViewModel.refreshChallengeData()
                    }
                )
            }
            // Header Super Pegado Arriba - Fixed for Notches
            Column(modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
            ) {
                // Custom Compact Header (48dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Title Area
                    Column(verticalArrangement = Arrangement.Center) {
                         Text(
                             text = when (pagerState.currentPage) {
                                 0 -> "Novvin"
                                 1 -> "Momentos"
                                 else -> "Retos"
                             },
                             style = MaterialTheme.typography.headlineSmall,
                             fontWeight = FontWeight.ExtraBold,
                             color = MaterialTheme.colorScheme.primary,
                             maxLines = 1
                         )
                    }

                    // Actions Area
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pagerState.currentPage == 0) {
                            IconButton(onClick = {
                                navController.navigate(Screen.Ranking.route) { launchSingleTop = true }
                            }) {
                                Icon(Icons.Default.Leaderboard, contentDescription = "Ranking", tint = MaterialTheme.colorScheme.primary)
                            }

                            BadgedBox(
                                badge = {
                                    if (pendingRequestCount > 0) {
                                        Badge(
                                            containerColor = Color(0xFFE53935), 
                                            contentColor = Color.White,
                                            modifier = Modifier
                                                .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                                                .offset(x = (-10).dp, y = 10.dp)
                                        ) {
                                            Text(
                                                text = if (pendingRequestCount > 99) "99+" else pendingRequestCount.toString(),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            ) {
                                IconButton(onClick = {
                                    navController.navigate(Screen.FriendRequests.route) { launchSingleTop = true }
                                }) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = "Solicitudes", tint = MaterialTheme.colorScheme.primary)
                                }
                            }

                            BadgedBox(
                                badge = {
                                    if (unreadCount > 0) {
                                        Badge(
                                            containerColor = Color(0xFFE53935), 
                                            contentColor = Color.White,
                                            modifier = Modifier
                                                .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                                                .offset(x = (-10).dp, y = 10.dp)
                                        ) {
                                            Text(
                                                text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            ) {
                                IconButton(onClick = { 
                                    navController.navigate(Screen.Notifications.route)
                                }) {
                                    Icon(Icons.Default.Notifications, contentDescription = "Notificaciones", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            
                            IconButton(onClick = { showCreatePostDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Nueva publicación", tint = MaterialTheme.colorScheme.primary)
                            }
                        } else if (pagerState.currentPage == 2) {
                            IconButton(onClick = { showCreateChallengeDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Poner Reto", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            height = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    divider = {}
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 14.sp,
                                    fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        )
                    }
                }
            }

            // Pager principal
            Box(modifier = Modifier.weight(1f).pullRefresh(pullRefreshState)) {
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    when (pageIndex) {
                        0 -> {
                            if (posts.isEmpty() && activeStreams.isEmpty() && !isRefreshing) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (isConnected) {
                                        Text("No hay publicaciones recientes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.padding(horizontal = 32.dp)
                                        ) {
                                            Icon(Icons.Default.WifiOff, contentDescription = null, modifier = Modifier.size(56.dp))
                                            Text(
                                                "No se pudieron cargar las publicaciones porque no hay conexión.",
                                                textAlign = TextAlign.Center,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Button(onClick = {
                                                connectivityViewModel.retrySync("social_empty_retry")
                                                viewModel.loadPosts()
                                            }) { Text("Reintentar") }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    state = socialListState,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (activeStreams.isNotEmpty()) {
                                        item(key = "active-lives") {
                                            conectate.com.presentation.live.LiveStreamsCarousel(
                                                streams = activeStreams,
                                                onOpenLive = { liveId ->
                                                    navController.navigate(Screen.LiveViewer.createRoute(liveId))
                                                },
                                                onOpenAll = {
                                                    navController.navigate(Screen.LiveDiscovery.route) { launchSingleTop = true }
                                                }
                                            )
                                        }
                                    }
                                    items(posts, key = { it.id }) { post ->
                                        PostCard(
                                            post = post,
                                            onLikeClick = { viewModel.toggleLike(post) },
                                            onReactionSelect = { reaction -> viewModel.setReaction(post, reaction) },
                                            onReactionSummaryClick = { reactionPost = post },
                                            onCommentClick = {
                                                viewModel.recordPostComment(post)
                                                navController.navigate(Screen.PostDetail.createRoute(post.id))
                                            },
                                            onShareClick = { viewModel.togglePostShare(post) },
                                            onUserClick = { userId -> if (userId != viewModel.currentUserId) navController.navigate(Screen.UserProfile.createRoute(userId)) },
                                            onPostClick = {
                                                viewModel.recordPostOpen(post)
                                                navController.navigate(Screen.PostDetail.createRoute(post.id))
                                            },
                                            onSendToFriend = {
                                                selectedPostForShare = post
                                                showInternalShareDialog = true
                                            },
                                            onReportClick = { postToReport = post },
                                            showReportAction = true,
                                            isOwner = post.userId == viewModel.currentUserId,
                                            onDeleteClick = { viewModel.deletePost(post.id) },
                                            onVisibilityClick = { newVis -> viewModel.updatePostVisibility(post.id, newVis) },
                                            onEditClick = { postToEdit = post }
                                        )
                                    }
                                }
                            }
                        }
                        1 -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                MomentGrid(
                                    moments = moments,
                                    currentUser = currentUser,
                                    onAddMomentClick = {
                                        val myMoments = moments.filter { it.userId == viewModel.currentUserId }
                                        if (myMoments.isNotEmpty()) navController.navigate(Screen.MomentViewer.createRoute(myMoments.first().id))
                                        else navController.navigate(Screen.CreateMoment.route)
                                    },
                                    onMomentClick = { moment -> navController.navigate(Screen.MomentViewer.createRoute(moment.id)) },
                                    bottomContent = {
                                        val myMoments = moments.filter { it.userId == viewModel.currentUserId }
                                        conectate.com.presentation.components.MyMomentManagementRow(
                                            moments = myMoments,
                                            onDeleteClick = { momentId ->
                                                momentViewModel.deleteMoment(momentId)
                                            }
                                        )
                                    }
                                )
                                FloatingActionButton(
                                    onClick = { navController.navigate(Screen.CreateMoment.route) },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.PhotoCamera, "Crear Momento")
                                }
                            }
                        }
                        2 -> {
                            ChallengeSection(
                                activeChallenge = activeChallenge,
                                submissions = submissions,
                                hasParticipated = hasParticipated,
                                onAcceptChallenge = {
                                    if (activeChallenge?.challenge?.challengeType == "drawing") {
                                        showDrawingCanvas = true
                                    } else {
                                        showChallengeMediaPicker = true // Ahora maneja Text, Audio y Media internamente
                                    }
                                },
                                onPassTorch = { showPassTorchDialog = true },
                                onVote = { id -> challengeViewModel.toggleChallengeVote(id) },
                                onRefresh = { challengeViewModel.loadActiveChallenge() },
                                votingCountdown = votingCountdown,
                                winnersCountdown = winnersCountdown
                            )
                        }
                    }
                }

                PullRefreshIndicator(
                    refreshing = isRefreshing,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                if (isSubmittingChallenge) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    
    
    if (showChallengeMediaPicker) {
        ChallengeMediaPicker(
            activeChallenge = activeChallenge,
            onDismiss = { showChallengeMediaPicker = false },
            mediaList = galleryMedia,
            onMediaSelected = { uri ->
                showChallengeMediaPicker = false
                challengeViewModel.submitChallengeMediaFromUri(uri)
            },
            onTakePhoto = {
                try {
                    val file = java.io.File.createTempFile("challenge_", ".jpg", context.cacheDir)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.provider", file
                    )
                    tempCameraUri = uri
                    takePictureLauncher.launch(uri)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Error al abrir cámara", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onRecordVideo = {
                try {
                    val file = java.io.File.createTempFile("challenge_", ".mp4", context.cacheDir)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.provider", file
                    )
                    tempCameraUri = uri
                    recordVideoLauncher.launch(uri)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Error al abrir video", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onTextSubmit = { text ->
                showChallengeMediaPicker = false
                challengeViewModel.submitText(text)
            },
            onAudioSubmit = { file ->
                showChallengeMediaPicker = false
                challengeViewModel.submitAudio(file)
            },
            voiceManager = viewModel.voiceMessageManager,
            onMicPermissionRequest = {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        )
    }

    // Diálogo de Edición de Post
    if (postToEdit != null) {
        var editContent by remember { mutableStateOf(postToEdit?.content ?: "") }
        AlertDialog(
            onDismissRequest = { postToEdit = null },
            title = { Text("Editar Publicación") },
            text = {
                OutlinedTextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    placeholder = { Text("Escribe algo...") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    postToEdit?.id?.let { pid ->
                        viewModel.updatePostContent(pid, editContent)
                    }
                    postToEdit = null
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { postToEdit = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo de Compartir Interno (Movido fuera del Scaffold para evitar problemas de refresco de UI)
    if (showInternalShareDialog && selectedPostForShare != null) {
        conectate.com.presentation.components.InternalShareDialog(
            userRepository = viewModel.userRepository,
            onDismiss = { showInternalShareDialog = false; selectedPostForShare = null },
            onShareWithUser = { user ->
                showInternalShareDialog = false
                val route = Screen.Chat.createRoute(user.id, initialMessage = "¡Mira esto!\nhttps://conectate.com/post/${selectedPostForShare?.id}")
                selectedPostForShare = null
                navController.navigate(route)
            }
        )
    }
}
@Composable
fun ChallengeSection(
    activeChallenge: conectate.com.data.model.ActiveChallenge?,
    submissions: List<conectate.com.data.model.ChallengeSubmission>,
    hasParticipated: Boolean,
    onAcceptChallenge: () -> Unit,
    onPassTorch: () -> Unit,
    onVote: (submissionId: String) -> Unit,
    onRefresh: () -> Unit,
    votingCountdown: Int,
    winnersCountdown: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Spacer(Modifier.height(8.dp)) // ELIMINADO PARA "APEGADITO"
        
        // Fase de Reto / Votación / Ganadores
        val isVoting = activeChallenge?.status == "voting"
        val isCompleted = activeChallenge?.status == "completed"

        // Timer dinámico
        val durationSeconds = if (isVoting) {
            votingCountdown
        } else {
            activeChallenge?.let {
                val expires = java.time.ZonedDateTime.parse(it.expiresAt).toInstant().toEpochMilli()
                val now = java.time.Instant.now().toEpochMilli()
                maxOf(0L, (expires - now) / 1000).toInt()
            } ?: 0
        }

        if (isCompleted) {
            // Header de Ganadores con cuenta regresiva interna
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "¡TENEMOS GANADORES!", 
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                if (winnersCountdown > 0) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ) {
                        Text(
                            "$winnersCountdown",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else if (!isVoting) {
            conectate.com.presentation.social.components.ChallengeTimer(
                durationSeconds = durationSeconds,
                onTimerFinished = { onRefresh() }
            )
        } else if (votingCountdown == 0 || (isCompleted && winnersCountdown == 0)) {
            // Disparador automático para cuando termina la votación o el podio
            LaunchedEffect(activeChallenge?.status, votingCountdown, winnersCountdown) { onRefresh() }
        }

        Spacer(Modifier.height(16.dp))

        // Banner Pro Principal
        Card(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = if (isVoting) Color(0xFFFF9800) else if (isCompleted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
        ) {
            Box(Modifier.fillMaxSize()) {
                // Refresh Button in top right of the banner as a safeguard
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    Icon(Icons.Filled.Refresh, null, tint = Color.White.copy(alpha = 0.5f))
                }

                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (isVoting) "FASE DE VOTACIÓN" else if (isCompleted) "RETO FINALIZADO" else "RETO ACTUAL",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        activeChallenge?.challenge?.title ?: "Cargando...",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    
                    if (isVoting) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            formatRemainingTime(votingCountdown),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        if (isCompleted) {
            // Pantalla de Ganadores (Bonita) - Ordenados según winnerIds del servidor
            val winners = activeChallenge?.winnerIds?.mapNotNull { winnerId ->
                submissions.find { it.userId == winnerId }
            } ?: emptyList()
            
            if (activeChallenge?.winnerIds?.isNotEmpty() == true && winners.isEmpty()) {
                // Si el servidor dice que hay ganadores pero aún no tenemos sus datos (submissions)
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Preparando el podio...", style = MaterialTheme.typography.titleMedium)
                    
                    // Trigger manual refresh if it takes too long
                    LaunchedEffect(Unit) {
                        delay(2000)
                        if (winners.isEmpty()) onRefresh()
                    }
                }
            } else {
                WinnerPodium(winners)
            }
        } else if (activeChallenge == null) {
            // Estado de "No hay retos" o "Cargando" profesional
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.AccessTime, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                Spacer(Modifier.height(16.dp))
                Text("Buscando el próximo desafío...", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("💥 GENERAR DESAFÍO COMUNITARIO", fontWeight = FontWeight.Black)
                }
            Text(
                "¡Sé el primero en activar un reto hoy!",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        } else if (hasParticipated) {
            // Lista de respuestas para los que participaron (Votación o Active)
            // Lista de respuestas para votar o ver
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isVoting) "Vota por el mejor" else "Comunidad Activa", fontWeight = FontWeight.ExtraBold)
                    if (!isVoting && hasParticipated) TextButton(onClick = onPassTorch) { Text("Pasar la antorcha 🔥") }
                }
                
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(submissions) { sub ->
                        SubmissionCard(
                            sub, 
                            isVoting = isVoting, 
                            challengeType = activeChallenge?.challenge?.challengeType ?: "image",
                            hasParticipated = hasParticipated,
                            isCompleted = isCompleted,
                            blurred = !hasParticipated, 
                            onVote = { onVote(sub.id) }
                        )
                    }
                }
            }
        } else if (isVoting) {
            // No participó y ya es fase de votación -> Bloqueado
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Lock, 
                    null, 
                    modifier = Modifier.size(64.dp), 
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "¡Tiempo de participación agotado!", 
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Espera a que se revelen los ganadores.", 
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Locked State con botón de aceptar Y Blur en el fondo si hay contenido
            if (submissions.isNotEmpty()) {
                Box(Modifier.weight(1f)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.blur(15.dp)) {
                        items(submissions) { sub ->
                            SubmissionCard(
                                sub, 
                                isVoting = false, 
                                challengeType = activeChallenge?.challenge?.challengeType ?: "image",
                                hasParticipated = false,
                                isCompleted = isCompleted,
                                blurred = true, 
                                onVote = {}
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.4f))) // Overlay para suavizar
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        LockedRetoUI(onAcceptChallenge)
                        Text(
                            "Sube tu aporte para ver los ${submissions.size} aportes de la comunidad",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            } else {
                LockedRetoUI(onAcceptChallenge)
            }
        }
    }
}

@Composable
fun ChallengeAudioPlayer(url: String) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var progress by remember { mutableStateOf(0f) }

    DisposableEffect(url) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying) {
                mediaPlayer?.let { mp ->
                    if (mp.duration > 0) {
                        progress = mp.currentPosition.toFloat() / mp.duration.toFloat()
                    }
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    val color = if (isPlaying) MaterialTheme.colorScheme.primary else Color(0xFFD81B60)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(color, RoundedCornerShape(12.dp))
            .clickable {
                try {
                     if (mediaPlayer == null) {
                         isLoading = true
                         mediaPlayer = android.media.MediaPlayer().apply {
                             setAudioAttributes(
                                 android.media.AudioAttributes.Builder()
                                     .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                     .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                     .build()
                             )
                             // Use context and Uri to avoid error -38
                             UbuntuMedia.setDataSource(context, this, url)
                             prepareAsync()
                             setOnPreparedListener { 
                                 isLoading = false
                                 start()
                                 isPlaying = true
                             }
                             setOnCompletionListener { 
                                 isPlaying = false 
                                 progress = 0f
                             }
                             setOnErrorListener { _, what, extra ->
                                 isLoading = false
                                 isPlaying = false
                                 android.util.Log.e("ChallengeAudio", "MediaPlayer error: $what, $extra")
                                 android.widget.Toast.makeText(context, "Error al reproducir audio: $what, $extra", android.widget.Toast.LENGTH_SHORT).show()
                                 true
                             }
                         }
                     } else {
                        if (mediaPlayer!!.isPlaying) {
                            mediaPlayer!!.pause()
                            isPlaying = false
                        } else {
                            mediaPlayer!!.start()
                            isPlaying = true
                        }
                    }
                } catch (e: Exception) {
                    isLoading = false
                    android.util.Log.e("ChallengeAudio", "Error setup player", e)
                    android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Color.White)
            } else {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayCircle,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (isLoading) "Cargando..." else if (isPlaying) "Reproduciendo..." else "Audio del reto",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        
        if (isPlaying || progress > 0f) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun ChallengeVideoPlayer(url: String) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(UbuntuMedia.mediaItem(url))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(url) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = {
            PlayerView(context).apply {
                player = exoPlayer
                useController = true
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
    )
}

@Composable
fun SubmissionCard(
    submission: conectate.com.data.model.ChallengeSubmission,
    isVoting: Boolean,
    challengeType: String,
    hasParticipated: Boolean,
    isCompleted: Boolean = false,
    blurred: Boolean = false,
    onVote: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().then(if (blurred) Modifier.blur(10.dp) else Modifier),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            // Header del Usuario con Foto Real
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (submission.user?.avatar_url != null) {
                    coil.compose.AsyncImage(
                        model = submission.user.avatar_url,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Text(submission.user?.displayName?.take(1) ?: "?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(submission.user?.displayName ?: "Usuario", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyMedium)
                    Text(submission.createdAt.split("T").firstOrNull() ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            
            // Contenido (Imagen o Texto)
            var showFullScreen by remember { mutableStateOf(false) }
            
            if (submission.contentUrl != null) {
                val isVideo = submission.contentUrl.endsWith(".mp4", ignoreCase = true) || 
                              submission.contentUrl.contains("/videos/") || 
                              submission.contentUrl.contains(".m3u8")

                if (challengeType == "audio") {
                     ChallengeAudioPlayer(submission.contentUrl)
                } else if (isVideo) {
                    Box(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                        ChallengeVideoPlayer(submission.contentUrl)
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(250.dp).clickable { showFullScreen = true }) {
                        Image(
                            painter = rememberAsyncImagePainter(submission.contentUrl),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Icono de expansión
                        Icon(
                            Icons.Filled.Fullscreen, 
                            null, 
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(24.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(4.dp),
                            tint = Color.White
                        )
                    }
                    
                    if (showFullScreen) {
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = { showFullScreen = false },
                            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable { showFullScreen = false }) {
                                Image(
                                    painter = rememberAsyncImagePainter(submission.contentUrl),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                IconButton(
                                    onClick = { showFullScreen = false },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                                ) {
                                    Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    }
                }
            } else if (submission.textContent != null) {
                Text(
                    submission.textContent,
                    modifier = Modifier.padding(16.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            
            // Footer Votación mejorado
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Botón de votación habilitado siempre que haya participado y no esté completado el reto
                    val canVoteNow = hasParticipated && !isCompleted
                    IconButton(onClick = {
                        if (canVoteNow) onVote() 
                        else if (!hasParticipated) android.widget.Toast.makeText(context, "Participa para desbloquear votaciones 🔓", android.widget.Toast.LENGTH_SHORT).show()
                        else android.widget.Toast.makeText(context, "El reto ya ha finalizado", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            if (submission.votedByMe) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Votar",
                            tint = if (submission.votedByMe) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${submission.voteCount}", 
                        fontWeight = FontWeight.Bold, 
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("votos", style = MaterialTheme.typography.bodySmall)
                }
                
                if (isVoting) {
                    Text(
                        if (submission.votedByMe) "¡VOTASTE!" else "VOTA AQUÍ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = if (submission.votedByMe) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun WinnerPodium(winners: List<conectate.com.data.model.ChallengeSubmission>) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "🏆 PODIO DE GANADORES",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(winners) { index, winner ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = when(index) {
                        0 -> Color(0xFFFFD700).copy(alpha = 0.2f) // Oro
                        1 -> Color(0xFFC0C0C0).copy(alpha = 0.2f) // Plata
                        else -> Color(0xFFCD7F32).copy(alpha = 0.2f) // Bronce
                    }),
                    border = BorderStroke(2.dp, when(index) {
                        0 -> Color(0xFFFFD700)
                        1 -> Color(0xFFC0C0C0)
                        else -> Color(0xFFCD7F32)
                    })
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${index + 1}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(16.dp))
                        if (winner.user?.avatar_url != null) {
                            coil.compose.AsyncImage(
                                model = winner.user.avatar_url,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(winner.user?.displayName ?: "Ganador", fontWeight = FontWeight.ExtraBold)
                            Text("${winner.voteCount} votos", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        
        // Mensaje de felicitación final
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "🎉 ¡FELICITACIONES A TODOS!",
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Gracias por participar y hacer vibrar la comunidad.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Próximo reto en breve...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LockedRetoUI(onAcceptChallenge: () -> Unit) {
    val motivationalPhrases = remember {
        listOf(
            "¡Desbloquea el Muro de Cristal! ✨",
            "Tu turno de brillar ⭐",
            "La comunidad te espera 🌟",
            "¡Muestra tu talento! 🎨",
            "Acepta el desafío 🔥"
        )
    }
    
    val currentPhrase = remember { mutableStateOf(motivationalPhrases.random()) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Lock icon con animación
        Icon(
            Icons.Default.Lock, 
            null, 
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        )
        Spacer(Modifier.height(16.dp))
        
        Text(
            "Reto Bloqueado", 
            style = MaterialTheme.typography.headlineMedium, 
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            currentPhrase.value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            "Cumple el reto para votar y ver a los demás.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        
        Spacer(Modifier.height(32.dp))
        
        Button(
            onClick = onAcceptChallenge, 
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(64.dp),
            shape = RoundedCornerShape(32.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 8.dp,
                pressedElevation = 12.dp
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    "🎯 ACEPTAR DESAFÍO",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeMediaPicker(
    activeChallenge: conectate.com.data.model.ActiveChallenge?,
    onDismiss: () -> Unit,
    mediaList: List<conectate.com.presentation.social.GalleryMedia>,
    onMediaSelected: (android.net.Uri) -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    onTextSubmit: (String) -> Unit,
    onAudioSubmit: (java.io.File) -> Unit,
    voiceManager: conectate.com.presentation.messages.VoiceMessageManager,
    onMicPermissionRequest: () -> Unit
) {
    if (activeChallenge == null) return

    // Lógica específica según el tipo de reto
    when (activeChallenge.challenge.challengeType) {
        "text" -> {
            TextSubmissionDialog(onDismiss, onTextSubmit)
        }
        "audio" -> {
            AudioSubmissionDialog(onDismiss, onAudioSubmit, voiceManager, onMicPermissionRequest)
        }
        else -> {
            // Camera, Video, Drawing, etc. (Comportamiento existente + mejoras)
            DefaultMediaPicker(onDismiss, mediaList, onMediaSelected, onTakePhoto, onRecordVideo)
        }
    }
}

@Composable
fun TextSubmissionDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tu Respuesta") },
        text = { 
            OutlinedTextField(
                value = text, 
                onValueChange = { text = it }, 
                label = { Text("Escribe aquí...") },
                modifier = Modifier.fillMaxWidth().height(150.dp)
            ) 
        },
        confirmButton = {
            Button(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) {
                Text("ENVIAR")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultMediaPicker(
    onDismiss: () -> Unit,
    mediaList: List<conectate.com.presentation.social.GalleryMedia>,
    onMediaSelected: (android.net.Uri) -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit
) {
    var showCameraOptions by remember { mutableStateOf(false) }

    if (showCameraOptions) {
        AlertDialog(
            onDismissRequest = { showCameraOptions = false },
            title = { Text("Cámara") },
            text = { Text("¿Qué deseas capturar para este reto?") },
            confirmButton = {
                TextButton(onClick = {
                    showCameraOptions = false
                    onRecordVideo()
                }) {
                    Text("Grabar Video")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCameraOptions = false
                    onTakePhoto()
                }) {
                    Text("Tomar Foto")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.85f).padding(16.dp)) {
            Text(
                "Muestra tu talento",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Elige de tu galería o usa la cámara",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(Modifier.height(20.dp))

            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    // Botón de Cámara
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .clickable { showCameraOptions = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.CameraAlt, 
                                contentDescription = "Cámara", 
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "CAPTURA", 
                                style = MaterialTheme.typography.labelSmall, 
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                items(items = mediaList) { media ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onMediaSelected(media.uri) }
                    ) {
                        coil.compose.AsyncImage(
                            model = media.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (media.type == conectate.com.presentation.social.MediaType.VIDEO) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancelar")
            }
        }
    }
}

@Composable
fun AudioSubmissionDialog(
    onDismiss: () -> Unit, 
    onSubmit: (java.io.File) -> Unit,
    voiceManager: conectate.com.presentation.messages.VoiceMessageManager,
    onMicPermissionRequest: () -> Unit
) {
    val state by voiceManager.state.collectAsState()
    val duration by voiceManager.duration.collectAsState()
    val playbackProgress by voiceManager.playbackProgress.collectAsState()
    val isPlaying by voiceManager.isDraftPlaying.collectAsState()
    val context = LocalContext.current
    
    DisposableEffect(Unit) {
        onDispose { voiceManager.reset() }
    }

    AlertDialog(
        onDismissRequest = {
            voiceManager.reset()
            onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎤", fontSize = 24.sp)
                Spacer(Modifier.width(8.dp))
                Text("Reto de Voz", fontWeight = FontWeight.Black)
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally, 
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            ) {
                // Timer central o visualización
                Text(
                    voiceManager.formatDuration(duration),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    color = if (state == VoiceState.RECORDING) Color.Red else MaterialTheme.colorScheme.primary
                )
                
                Spacer(Modifier.height(24.dp))
                
                when (state) {
                    VoiceState.IDLE -> {
                        IconButton(
                            onClick = { 
                                if (androidx.core.content.ContextCompat.checkSelfPermission(
                                        context, android.Manifest.permission.RECORD_AUDIO
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    voiceManager.startRecording()
                                } else {
                                    // Triggered via a passed lambda or just check here? 
                                    // Since we are in a dialog, the launcher must be in the Activity/Screen context.
                                    // We'll use the launcher from SocialScreen passed down or accessible.
                                    onMicPermissionRequest()
                                }
                            },
                            modifier = Modifier.size(80.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            Icon(Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                        Text("Toca para empezar", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    }
                    VoiceState.RECORDING -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            // Pause button
                            IconButton(
                                onClick = { voiceManager.pauseRecording() },
                                modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                            ) {
                                Icon(Icons.Filled.Pause, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            
                            // Stop button
                            IconButton(
                                onClick = { voiceManager.stopRecordingForReview() },
                                modifier = Modifier.size(80.dp).background(Color.Red, CircleShape)
                            ) {
                                Icon(Icons.Filled.Stop, null, tint = Color.White, modifier = Modifier.size(40.dp))
                            }
                        }
                        Text("Grabando...", color = Color.Red, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    }
                    VoiceState.PAUSED -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            // Resume button
                            IconButton(
                                onClick = { voiceManager.resumeRecording() },
                                modifier = Modifier.size(80.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                            ) {
                                Icon(Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(40.dp))
                            }
                            
                            // Stop button (Finish)
                            IconButton(
                                onClick = { voiceManager.stopRecordingForReview() },
                                modifier = Modifier.size(56.dp).background(Color.Red.copy(alpha = 0.7f), CircleShape)
                            ) {
                                Icon(Icons.Filled.Stop, null, tint = Color.White)
                            }
                        }
                        Text("Pausado", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    }
                    VoiceState.REVIEWING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Playback bar
                            Slider(
                                value = playbackProgress,
                                onValueChange = { voiceManager.seekPreview(it) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                            
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                // Delete/Restart
                                IconButton(
                                    onClick = { voiceManager.reset() },
                                    modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                                ) {
                                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                                
                                // Play/Pause Preview
                                IconButton(
                                    onClick = { 
                                        if (isPlaying) voiceManager.pausePreview() else voiceManager.playPreview() 
                                    },
                                    modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                                ) {
                                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                                
                                // Resume recording from review (Continue singing)
                                IconButton(
                                    onClick = { voiceManager.resumeRecordingFromReview() },
                                    modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                                ) {
                                    Icon(Icons.Filled.RestartAlt, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                            Text("Revisa tu grabación", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { voiceManager.getCurrentFile()?.let { onSubmit(it) } },
                enabled = state == VoiceState.REVIEWING,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("ENVIAR RETO")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                voiceManager.reset()
                onDismiss()
            }) {
                Text("Cancelar")
            }
        }
    )
}
