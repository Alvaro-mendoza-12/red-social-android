package conectate.com.presentation.profile

import androidx.compose.runtime.Composable
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.imageLoader
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.Post
import conectate.com.data.model.Badge
import conectate.com.data.model.User
import conectate.com.data.model.profilePublicHandle
import conectate.com.data.model.profileShortId
import conectate.com.data.model.MomentHighlight
import conectate.com.core.profile.ProfileSocialLinks
import conectate.com.presentation.common.ConnectivityViewModel
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.OfflineNoticeBar
import conectate.com.presentation.components.VideoThumbnail
import conectate.com.presentation.components.ProfileAmbientBackground
import conectate.com.presentation.components.ProfileWelcomeBanner
import conectate.com.presentation.components.CommentAudioPlayer
import conectate.com.presentation.components.SocialBrandLogo
import conectate.com.presentation.components.SocialPlatform
import conectate.com.presentation.messages.CollectionShareDialog
import conectate.com.presentation.theme.profileThemeColors
import conectate.com.data.repository.FriendStatus
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew

private fun profileShareLink(userId: String): String =
    "https://conectate.com/profile/${Uri.encode(userId.trim())}"

internal fun profileUserIdFromQr(raw: String?): String? {
    val uri = runCatching { Uri.parse(raw?.trim().orEmpty()) }.getOrNull() ?: return null
    val validWebHost = uri.scheme in setOf("https", "http") &&
        uri.host in setOf("conectate.com", "www.conectate.com") &&
        uri.pathSegments.firstOrNull() == "profile"
    val validAppHost = uri.scheme == "conectate" && uri.host == "profile"
    if (!validWebHost && !validAppHost) return null
    return uri.pathSegments.getOrNull(if (validWebHost) 1 else 0)
        ?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= 128 }
}

private fun profileVisitLabel(raw: String): String {
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return "Recientemente"
    val minutes = java.time.Duration.between(instant, java.time.Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "Ahora"
        minutes < 60 -> "Hace $minutes min"
        minutes < 1_440 -> "Hace ${minutes / 60} h"
        else -> "Hace ${minutes / 1_440} d"
    }
}

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
private fun MomentHighlightsRow(
    highlights: List<MomentHighlight>,
    isOwnProfile: Boolean,
    onCreateMoment: () -> Unit,
    onOpenHighlight: (MomentHighlight) -> Unit
) {
    if (!isOwnProfile && highlights.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 14.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (isOwnProfile) {
            Column(
                modifier = Modifier.width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    onClick = onCreateMoment,
                    modifier = Modifier.size(68.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline
                    )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Crear momento",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text("Nuevo", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        highlights.forEach { highlight ->
            Column(
                modifier = Modifier.width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    onClick = { onOpenHighlight(highlight) },
                    modifier = Modifier.size(68.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(
                        2.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    val cover = UbuntuStorageUrl.rewrite(highlight.coverMediaUrl)
                        ?: highlight.coverMediaUrl
                    if (!cover.isNullOrBlank()) {
                        AsyncImage(
                            model = cover,
                            contentDescription = "Destacado ${highlight.title}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Star, contentDescription = null)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    highlight.title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
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

@Composable
private fun ProfileInfoSwitcher(
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Inicio", "Presentación").forEachIndexed { index, label ->
                Surface(
                    onClick = { onSelected(index) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected == index) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected == index) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileIntroduction(user: conectate.com.data.model.User) {
    val hasBio = !user.bio.isNullOrBlank()
    val hasLocation = !user.city.isNullOrBlank() || !user.country.isNullOrBlank()
    val hasSocialLinks = user.social_links.isNotEmpty()
    if (!hasBio && !hasLocation && !hasSocialLinks) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ) {
            Text(
                "Este perfil todavía no ha añadido una presentación.",
                modifier = Modifier.padding(14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (hasBio) {
                Text("Descripción", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(user.bio.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
            if (hasLocation) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Ubicación",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        listOfNotNull(user.city, user.country).joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (hasSocialLinks) {
                Text("Redes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                ProfileSocialLinks(user.social_links)
            }
        }
    }
}

@Composable
private fun ProfileSocialLinks(links: Map<String, String>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val platforms = listOf(
        SocialPlatform.INSTAGRAM,
        SocialPlatform.FACEBOOK,
        SocialPlatform.TIKTOK,
        SocialPlatform.X,
        SocialPlatform.WHATSAPP,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        platforms.forEach { platform ->
            val storedValue = links[platform.key]
                ?: if (platform == SocialPlatform.X) links["twitter"] else null
            storedValue?.takeIf(String::isNotBlank)?.let { rawValue ->
                val url = if (platform == SocialPlatform.WHATSAPP) {
                    "https://wa.me/${rawValue.filter(Char::isDigit)}"
                } else {
                    rawValue
                }
                Surface(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                    modifier = Modifier.widthIn(min = 152.dp, max = 220.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SocialBrandLogo(
                            platform = platform,
                            size = 40.dp,
                            contentDescription = "Abrir ${platform.label}",
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                platform.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                ProfileSocialLinks.displayHandle(platform.key, rawValue)
                                    .ifBlank { "Abrir perfil" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
    val momentHighlights by viewModel.momentHighlights.collectAsState()
    val profileViewers by viewModel.profileViewers.collectAsState()
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
    var profileInfoTab by rememberSaveable(userId) { mutableIntStateOf(0) }
    var profileQrLink by remember { mutableStateOf<String?>(null) }
    var showProfileViewers by remember { mutableStateOf(false) }
    var identityDialogUser by remember { mutableStateOf<User?>(null) }
    
    val comments by viewModel.comments.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val notificationViewModel: NotificationViewModel = hiltViewModel()
    val unreadCount by notificationViewModel.unreadCount.collectAsState()

    val scope = rememberCoroutineScope() // Moved scope here to survive dialog dismissal
    var isPosting by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    identityDialogUser?.let { identityUser ->
        AlertDialog(
            onDismissRequest = { identityDialogUser = null },
            title = { Text("ID de Novvin") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("@${profilePublicHandle(identityUser)}")
                    Text("Código corto: ${profileShortId(identityUser)}")
                    Text(
                        text = identityUser.id,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Comparte el ID completo para que puedan encontrarte sin confundir tu perfil con otro.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ID de Novvin", identityUser.id))
                    identityDialogUser = null
                }) { Text("Copiar ID") }
            },
            dismissButton = {
                TextButton(onClick = { identityDialogUser = null }) { Text("Cerrar") }
            },
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val profileQrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scannedUserId = profileUserIdFromQr(result.contents)
        if (!scannedUserId.isNullOrBlank()) {
            profileQrLink = null
            (mainNavController ?: navController).navigate(Screen.UserProfile.createRoute(scannedUserId)) {
                launchSingleTop = true
            }
        } else if (!result.contents.isNullOrBlank()) {
            android.widget.Toast.makeText(context, "Este código no corresponde a un perfil de Novvin.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    profileQrLink?.let { link ->
        CollectionShareDialog(
            title = "Compartir perfil",
            link = link,
            onScan = {
                profileQrScanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Escanea un perfil de Novvin")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            },
            onDismiss = { profileQrLink = null },
        )
    }

    if (showProfileViewers) {
        ModalBottomSheet(onDismissRequest = { showProfileViewers = false }) {
            Text(
                text = "Visitas recientes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (profileViewers.isEmpty()) {
                Text(
                    text = "Todavía no hay visitas visibles. Solo aparecen cuando ambos usuarios habilitan el historial.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            } else {
                profileViewers.forEach { visitor ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showProfileViewers = false
                                (mainNavController ?: navController).navigate(
                                    Screen.UserProfile.createRoute(visitor.user.id)
                                )
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = UbuntuStorageUrl.rewrite(visitor.user.avatar_url),
                            contentDescription = null,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(50)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(visitor.user.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "@${profilePublicHandle(visitor.user)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            profileVisitLabel(visitor.viewedAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }

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
                color = MaterialTheme.colorScheme.surface,
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
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                "Atrás",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    Text(
                        text = "Perfil",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (userId == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val ownProfile = (profileState as? ProfileUiState.Success)?.user
                            if (ownProfile?.profile_view_history_enabled == true) {
                                Box(
                                    modifier = Modifier
                                        .width(44.dp)
                                        .height(40.dp)
                                        .clickable { showProfileViewers = true },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (profileViewers.isEmpty()) {
                                        Icon(
                                            Icons.Default.Visibility,
                                            contentDescription = "Visitas al perfil",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    } else {
                                        profileViewers.take(3).forEachIndexed { index, visitor ->
                                            AsyncImage(
                                                model = UbuntuStorageUrl.rewrite(visitor.user.avatar_url),
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .offset(x = (index * 10).dp)
                                                    .size(22.dp)
                                                    .clip(RoundedCornerShape(50)),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        if (profileViewers.size > 3) {
                                            Text(
                                                text = "+${profileViewers.size - 3}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(horizontal = 3.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
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
                                         Icon(Icons.Default.Notifications, "Notificaciones", tint = MaterialTheme.colorScheme.onSurface)
                                     }
                                 }
                            // Friends Icon
                            IconButton(onClick = { 
                                // Ideally navigation to a dedicated Friends List. 
                                // Reusing FollowList for now with type="friends" (backend logic needed later)
                                // or just navigating to FriendRequests for management
                                navController.navigate(Screen.FollowList.createRoute(viewModel.currentUserId ?: "", "friends"))
                            }) {
                                Icon(Icons.Default.Group, "Amigos", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                                Icon(Icons.Default.Settings, "Ajustes", tint = MaterialTheme.colorScheme.onSurface)
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
                    contentColor = MaterialTheme.colorScheme.onPrimary
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
                            MediaImageRequestFactory.fullScreen(context, avatarUrl)
                                ?.let(imageLoader::enqueue)
                        }
                        rewrittenCoverUrl?.takeIf { it.isNotBlank() }?.let { coverUrl ->
                            MediaImageRequestFactory.fullScreen(context, coverUrl)
                                ?.let(imageLoader::enqueue)
                        }
                    }
                    val coverRequest = remember(rewrittenCoverUrl, user.cover_url) {
                        MediaImageRequestFactory.preview(context, rewrittenCoverUrl)
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
                                    val nameToDisplay = user.display_name?.takeIf(String::isNotBlank)
                                        ?: user.full_name?.takeIf(String::isNotBlank) ?: "Usuario"
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
                                            showBorder = user.profile_frame.isNotBlank(),
                                            borderWidth = 5.dp,
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
                                            if (
                                                user.is_admin ||
                                                conectate.com.presentation.components.isUserAdmin(user.username, user.email) ||
                                                (isOwnProfile && viewModel.isAdmin)
                                            ) {
                                                Spacer(Modifier.width(4.dp))
                                                conectate.com.presentation.components.AdminBadge(showVerifiedLine = false)
                                            }
                                            if (user.is_verified) {
                                                Spacer(Modifier.width(4.dp))
                                                Icon(Icons.Default.CheckCircle, "Verificado", tint = Color(0xFF1DA1F2), modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                                        ) {
                                            Text(
                                                text = "@${profilePublicHandle(user)}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = profileColors.accent,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            Text(
                                                text = "#${profileShortId(user)}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            TextButton(
                                                onClick = { identityDialogUser = user },
                                                contentPadding = PaddingValues(horizontal = 4.dp),
                                            ) {
                                                Text("Ver ID", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        
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
                                            modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Editar", maxLines = 1, fontSize = 12.sp)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val link = profileShareLink(user.id)
                                                context.startActivity(
                                                    Intent.createChooser(
                                                        Intent(Intent.ACTION_SEND).apply {
                                                            type = "text/plain"
                                                            putExtra(Intent.EXTRA_TEXT, "Mi usuario: @${profilePublicHandle(user)}\nID: ${user.id}\n$link")
                                                        },
                                                        "Compartir perfil",
                                                    )
                                                )
                                            },
                                            modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Compartir", maxLines = 1, fontSize = 12.sp)
                                        }
                                        OutlinedButton(
                                            onClick = { profileQrLink = profileShareLink(user.id) },
                                            modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.QrCode2, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Mi QR", maxLines = 1, fontSize = 12.sp)
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
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                                    ) {
                                        Icon(Icons.Default.Palette, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Personalizar perfil")
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        TextButton(onClick = { (mainNavController ?: navController).navigate(Screen.MyReports.route) }) {
                                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Reportes", fontSize = 11.sp)
                                        }
                                        TextButton(
                                            onClick = {
                                                (mainNavController ?: navController).navigate(Screen.WebLoginScanner.createRoute()) {
                                                    launchSingleTop = true
                                                }
                                            },
                                        ) {
                                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Vincular web", fontSize = 11.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                ProfileInfoSwitcher(
                                    selected = profileInfoTab,
                                    onSelected = { profileInfoTab = it },
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                if (profileInfoTab == 0) {
                                    user.profile_welcome
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { welcome ->
                                            ProfileWelcomeBanner(
                                                message = welcome,
                                                themeId = user.profile_theme
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                        }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    StatItem(number = userVideos.size, label = "Publicaciones") { }
                                    StatItem(number = user.followers_count ?: 0, label = "Seguidores") {
                                        navController.navigate(Screen.FollowList.createRoute(user.id, "followers"))
                                    }
                                    StatItem(number = user.following_count ?: 0, label = "Siguiendo") {
                                        navController.navigate(Screen.FollowList.createRoute(user.id, "following"))
                                    }
                                }
                                MomentHighlightsRow(
                                    highlights = momentHighlights,
                                    isOwnProfile = isOwnProfile,
                                    onCreateMoment = {
                                        (mainNavController ?: navController).navigate(Screen.CreateMoment.route)
                                    },
                                    onOpenHighlight = { highlight ->
                                        (mainNavController ?: navController).navigate(
                                            Screen.MomentHighlightViewer.createRoute(highlight.id, highlight.title)
                                        )
                                    }
                                )
                                } else {
                                    ProfileIntroduction(user)
                                    ProfileDetailsSections(user)
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
                                    itemsIndexed(rows) { rowIndex, rowVideos ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            rowVideos.forEachIndexed { columnIndex, video ->
                                                Box(modifier = Modifier.weight(1f)) {
                                                    VideoThumbnail(
                                                        video = video,
                                                        videoRepository = viewModel.videoRepository,
                                                        onClick = {
                                                            (mainNavController ?: navController).navigate(
                                                                Screen.ProfileVideoFeed.createRoute(
                                                                    profileId = user.id,
                                                                    videoId = video.id,
                                                                    initialIndex = rowIndex * 3 + columnIndex
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.loadUserProfile(userId, forceNetwork = true) },
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text("Reintentar")
                        }
                    }
                }
            }
            
            if (showCreatePostDialog) {
                conectate.com.presentation.components.CreatePostDialog(
                    onDismiss = { showCreatePostDialog = false },
                    onPostCreated = { content, uris, visibility, isSensitive, fromDraft, draftOwnerId ->
                        showCreatePostDialog = false
                        if (draftOwnerId != null && draftOwnerId != viewModel.currentUserId) {
                            android.widget.Toast.makeText(context,
                                "La cuenta cambió. Abre tu borrador en la cuenta original.",
                                android.widget.Toast.LENGTH_LONG).show()
                            return@CreatePostDialog
                        }
                        isPosting = true
                        android.widget.Toast.makeText(context, "Publicando...", android.widget.Toast.LENGTH_SHORT).show()
                        
                        scope.launch {
                            viewModel.postRepository.createPost(content, uris, visibility, isSensitive = isSensitive)
                                .onSuccess { created ->
                                    if (fromDraft && created.userId.equals(draftOwnerId, ignoreCase = true)) {
                                        draftOwnerId?.let { account ->
                                            conectate.com.data.local.PostDraftStore.delete(context, account)
                                                .onFailure { android.util.Log.w("ProfileScreen", "No se pudo limpiar el borrador publicado", it) }
                                        }
                                    }
                                    isPosting = false
                                    if (draftOwnerId != null && draftOwnerId != viewModel.currentUserId) return@onSuccess
                                    android.widget.Toast.makeText(context, "Publicado con éxito", android.widget.Toast.LENGTH_SHORT).show()
                                    viewModel.loadUserProfile(userId) // Reload to see the new post
                                }
                                .onFailure { e ->
                                    isPosting = false
                                    android.widget.Toast.makeText(context, "Error al publicar: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                        }
                    },
                    ownerId = viewModel.currentUserId,
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
