package conectate.com.presentation.social

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import conectate.com.data.model.MomentHighlightItemPreview
import conectate.com.data.model.requiresSeparateMusicPlayback
import conectate.com.util.UbuntuMedia
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MomentHighlightViewerScreen(
    navController: NavController,
    highlightId: String,
    title: String,
    viewModel: MomentHighlightViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(highlightId) { viewModel.load(highlightId) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            state.items.isEmpty() -> Column(
                Modifier.align(Alignment.Center).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.HistoryToggleOff, null, tint = Color.White, modifier = Modifier.size(48.dp))
                Text(state.error ?: "Este destacado no está disponible.", color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = { viewModel.load(highlightId, retry = true) }) { Text("Reintentar") }
                TextButton(onClick = { navController.popBackStack() }) { Text("Volver") }
            }
            else -> key(highlightId) {
                HighlightPlayback(
                    state.items, title, onClose = { navController.popBackStack() },
                    onOpenProfile = { authorId ->
                        navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(authorId)) {
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlightPlayback(
    items: List<MomentHighlightItemPreview>,
    title: String,
    onClose: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { items.size })
    val current = items[pager.settledPage]
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val resumed = momentPlaybackResumed()
    // Enrichment changes author fields, never the page visit or the player's identity.
    val session = remember(current.id, pager.settledPage) {
        HighlightPlaybackSession(current.id, pager.settledPage, items.size)
    }
    val latestSession = rememberUpdatedState(session)
    var completed by remember(session) { mutableStateOf(false) }
    var imageReady by remember(session) { mutableStateOf(false) }
    var musicReady by remember(session) { mutableStateOf(false) }
    var progress by remember(session) { mutableFloatStateOf(0f) }
    var userPaused by remember { mutableStateOf(false) }
    var imageZoomed by remember(session) { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val close = { closing = true; onClose() }
    BackHandler(onBack = close)
    val canPlay = resumed && !userPaused && !imageZoomed && !pager.isScrollInProgress && !completed && !closing
    val moment = current.asMoment()
    val finish: (String) -> Unit = { itemId ->
        val target = if (closing) null else session.finish(
            itemId, pager.settledPage, pager.isScrollInProgress, resumed && !userPaused && !imageZoomed,
            currentSession = latestSession.value,
        )
        if (target != null) {
            // Both soundtrack and picture receive this pause before navigation.
            completed = true
            progress = 1f
            if (target == items.size) close()
            else scope.launch { pager.scrollToPage(target) }
        }
    }

    LaunchedEffect(session, canPlay, imageReady, musicReady) {
        if (moment.isVideoMoment() || !canPlay || !imageReady ||
            (moment.requiresSeparateMusicPlayback && !musicReady)) return@LaunchedEffect
        val initial = progress
        val start = SystemClock.elapsedRealtime()
        val duration = resolvedImageMomentPlaybackDurationMs(moment.displayDurationMs, moment.musicDurationMs)
        while (progress < 1f) {
            val nextProgress = imageMomentProgressAt(
                initial,
                start,
                SystemClock.elapsedRealtime(),
                duration,
            )
            progress = nextProgress
            if (nextProgress >= 1f) {
                finish(current.id)
                break
            }
            delay(50)
        }
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager, key = { items[it].id }, modifier = Modifier.fillMaxSize(),
            // An end-of-item transition must not be cancelled halfway by a new drag.
            userScrollEnabled = !completed && !closing,
        ) { page ->
            val item = items[page]
            // Neighbour pages are posters, not competing audio/decoder instances.
            if (page != pager.settledPage) {
                AsyncImage(
                    model = UbuntuMedia.resolveUrl(item.thumbnailUrl ?: item.mediaUrl),
                    contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                )
            } else key(session) {
                MomentContent(
                    moment = item.asMoment(), isVisible = !closing, paused = !canPlay,
                    onVideoDurationKnown = { _, _ -> }, onVideoReady = {},
                    onImageReady = { imageReady = true },
                    onMusicStarted = { musicReady = true },
                    onVideoProgress = { _, value -> if (!completed) progress = value.coerceIn(0f, 1f) },
                    onVideoEnded = { finish(item.id) },
                    allowImageZoom = true,
                    onImageZoomChanged = { imageZoomed = it },
                    onImagePauseChanged = { userPaused = it },
                )
            }
        }
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.58f))
                .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEachIndexed { index, _ ->
                    val filled = when {
                        index < pager.settledPage -> 1f
                        index == pager.settledPage -> progress
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(3.dp).background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(999.dp))) {
                        Box(Modifier.fillMaxWidth(filled).height(3.dp).background(Color.White, RoundedCornerShape(999.dp)))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White)
                }
                Text(title.ifBlank { "Momentos destacados" }, Modifier.weight(1f),
                    color = Color.White, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { userPaused = !userPaused }) {
                    Icon(if (userPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        if (userPaused) "Reanudar momento" else "Pausar momento", tint = Color.White)
                }
            }
            MomentAuthorHeader(
                moment, momentTimestampLabel(moment.createdAt),
                onOpenMusicAttribution = { url -> runCatching { uriHandler.openUri(url) } },
                onOpenProfile = { authorId -> userPaused = true; onOpenProfile(authorId) },
            )
        }
        current.caption?.takeIf { it.isNotBlank() }?.let { caption ->
            Text(caption,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(bottom = 16.dp).background(Color.Black.copy(alpha = 0.48f))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                color = Color.White, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
