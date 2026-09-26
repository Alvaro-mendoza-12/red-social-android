package conectate.com.presentation.videos

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import conectate.com.data.repository.OfflineVideoDownloadWorker
import conectate.com.data.repository.OfflineVideoEntry
import conectate.com.data.repository.OfflineVideoRepository
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.common.ConnectivityViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class OfflineVideosViewModel @Inject constructor(
    private val repository: OfflineVideoRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val videos: StateFlow<List<OfflineVideoEntry>> = repository.videos
    val downloadProgress: StateFlow<Map<String, Float>> = repository.downloadProgress
    private val _targetCount = kotlinx.coroutines.flow.MutableStateFlow(repository.configuredTargetCount())
    val targetCount: StateFlow<Int> = _targetCount

    init {
        repository.refreshForCurrentAccount()
        if (_targetCount.value > repository.videos.value.size) enqueueReplenishment()
    }

    fun configureTarget(count: Int) {
        val safeCount = count.coerceIn(1, 150)
        repository.setConfiguredTargetCount(safeCount)
        _targetCount.value = safeCount
        enqueueReplenishment()
        AppFeedbackBus.show(
            "Novvin completará $safeCount videos cuando tengas conexión. Puedes salir de esta pantalla."
        )
    }

    fun replenishNow() {
        if (_targetCount.value <= 0) {
            AppFeedbackBus.showError("Primero elige cuántos videos quieres guardar.")
            return
        }
        enqueueReplenishment()
        AppFeedbackBus.show("Comprobando videos pendientes…")
    }

    fun markCycleCompleted() {
        if (_targetCount.value <= 0) return
        repository.requestFreshCycle()
        enqueueReplenishment()
    }

    fun remove(videoId: String) {
        viewModelScope.launch {
            repository.remove(videoId)
                .onSuccess { AppFeedbackBus.showSuccess("Video eliminado del dispositivo") }
                .onFailure { AppFeedbackBus.showError(it.message ?: "No se pudo eliminar el video") }
        }
    }

    fun removeAll() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        repository.setConfiguredTargetCount(0)
        _targetCount.value = 0
        viewModelScope.launch {
            repository.removeAll()
                .onSuccess { AppFeedbackBus.showSuccess("Videos sin conexión eliminados") }
                .onFailure { AppFeedbackBus.showError(it.message ?: "No se pudieron eliminar los videos") }
        }
    }

    private fun enqueueReplenishment() {
        val request = OneTimeWorkRequestBuilder<OfflineVideoDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private companion object {
        private const val UNIQUE_WORK_NAME = "novvin-offline-video-prefetch"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineVideosScreen(
    navController: NavController,
    viewModel: OfflineVideosViewModel = hiltViewModel(),
    connectivityViewModel: ConnectivityViewModel = hiltViewModel()
) {
    val videos by viewModel.videos.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val targetCount by viewModel.targetCount.collectAsState()
    val isConnected by connectivityViewModel.isConnected.collectAsState()
    var showFeed by remember { mutableStateOf(!isConnected && videos.isNotEmpty()) }

    LaunchedEffect(isConnected, videos.size) {
        if (!isConnected && videos.isNotEmpty()) showFeed = true
        if (videos.isEmpty()) showFeed = false
    }

    if (showFeed && videos.isNotEmpty()) {
        OfflineSwipeFeed(
            videos = videos,
            onBack = { showFeed = false },
            onCycleCompleted = viewModel::markCycleCompleted
        )
        return
    }

    OfflineVideoLibraryScreen(
        videos = videos,
        targetCount = targetCount,
        downloadProgress = downloadProgress,
        isConnected = isConnected,
        onBack = { navController.popBackStack() },
        onConfigureTarget = viewModel::configureTarget,
        onReplenish = viewModel::replenishNow,
        onPlayFeed = { showFeed = true },
        onDelete = viewModel::remove,
        onDeleteAll = viewModel::removeAll
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflineVideoLibraryScreen(
    videos: List<OfflineVideoEntry>,
    targetCount: Int,
    downloadProgress: Map<String, Float>,
    isConnected: Boolean,
    onBack: () -> Unit,
    onConfigureTarget: (Int) -> Unit,
    onReplenish: () -> Unit,
    onPlayFeed: () -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit
) {
    var selectedTarget by remember(targetCount) { mutableStateOf(targetCount.takeIf { it > 0 } ?: 25) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val activeDownload = downloadProgress.values.maxOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Videos sin conexión") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    if (videos.isNotEmpty()) {
                        IconButton(onClick = { confirmDeleteAll = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar todos")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.OfflineBolt, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Tu feed para cuando no haya internet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "Elige una cantidad y Novvin descargará videos públicos aleatorios. " +
                                "La descarga se pausa sin conexión y continúa automáticamente cuando vuelva la red."
                        )
                        Text(
                            "Los videos ocupan espacio en tu teléfono. Puedes reducir la cantidad o borrarlos cuando quieras.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                        )
                    }
                }
            }

            item {
                Text("Cantidad de videos", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(10, 25, 50, 100, 150).forEach { count ->
                        FilterChip(
                            selected = selectedTarget == count,
                            onClick = { selectedTarget = count },
                            label = { Text(count.toString()) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onConfigureTarget(selectedTarget) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isConnected
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (targetCount == selectedTarget && videos.size < selectedTarget) {
                            "Continuar descarga"
                        } else {
                            "Guardar $selectedTarget videos"
                        }
                    )
                }
                if (!isConnected) {
                    Text(
                        "Sin conexión: podrás iniciar o continuar la descarga cuando vuelva internet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            if (targetCount > 0) {
                item {
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Biblioteca preparada", fontWeight = FontWeight.SemiBold)
                                Text("${videos.size}/$targetCount")
                            }
                            LinearProgressIndicator(
                                progress = { (videos.size.toFloat() / targetCount.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            activeDownload?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        progress = { it },
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Descargando video ${(it * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            if (videos.size < targetCount) {
                                TextButton(onClick = onReplenish, enabled = isConnected) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Comprobar pendientes")
                                }
                            }
                        }
                    }
                }
            }

            if (videos.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            if (isConnected) Icons.Default.DownloadDone else Icons.Default.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Aún no tienes videos descargados",
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                item {
                    Button(onClick = onPlayFeed, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ver feed sin conexión")
                    }
                }
                items(videos, key = { it.video.id }) { entry ->
                    OfflineVideoRow(entry = entry, onDelete = { onDelete(entry.video.id) })
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Eliminar videos sin conexión") },
            text = {
                Text(
                    "Se borrarán las copias guardadas y se desactivará la descarga automática para esta cuenta."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        onDeleteAll()
                    }
                ) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun OfflineVideoRow(
    entry: OfflineVideoEntry,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = entry.localThumbnailPath?.let(::File),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 58.dp, height = 82.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.video.title.ifBlank { "Video de @${entry.video.userName}" },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "@${entry.video.userName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${formatBytes(entry.sizeBytes)} · ${
                        DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(Date(entry.downloadedAtEpochMs))
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar descarga")
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OfflineSwipeFeed(
    videos: List<OfflineVideoEntry>,
    onBack: () -> Unit,
    onCycleCompleted: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pagerState = rememberPagerState(pageCount = { videos.size + 1 })
    var playbackRequested by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    var isForeground by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val latestPlaybackRequested by rememberUpdatedState(playbackRequested)
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    isForeground = true
                    if (latestPlaybackRequested && pagerState.currentPage < videos.size) {
                        player.play()
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    isForeground = false
                    player.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.pause()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(pagerState.currentPage, videos) {
        val entry = videos.getOrNull(pagerState.currentPage)
        if (entry == null) {
            player.stop()
            onCycleCompleted()
            delay(1_800)
            pagerState.scrollToPage(0)
        } else {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(entry.localVideoPath))))
            player.prepare()
            player.playWhenReady = isForeground && playbackRequested
        }
    }

    LaunchedEffect(isForeground, playbackRequested, pagerState.currentPage) {
        if (
            isForeground &&
            playbackRequested &&
            pagerState.currentPage < videos.size
        ) {
            player.play()
        } else {
            player.pause()
        }
    }

    LaunchedEffect(playbackSpeed) {
        player.setPlaybackSpeed(playbackSpeed)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page >= videos.size) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Llegaste al final",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Volviendo al inicio. Cuando regrese internet, Novvin preparará una selección nueva.",
                        color = Color.White.copy(alpha = 0.78f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val entry = videos[page]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            playbackRequested = !playbackRequested
                            if (playbackRequested && isForeground) player.play() else player.pause()
                        }
                ) {
                    if (page == pagerState.currentPage) {
                        AndroidView(
                            factory = { playerContext ->
                                PlayerView(playerContext).apply {
                                    useController = false
                                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    this.player = player
                                }
                            },
                            update = { it.player = player },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f))
                                )
                            )
                            .navigationBarsPadding()
                            .padding(20.dp)
                    ) {
                        Text(
                            "@${entry.video.userName}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            entry.video.title.ifBlank { "Video sin conexión" },
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Solo reproducción · comentarios y perfiles requieren internet",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(
                modifier = Modifier.clickable {
                    playbackSpeed = if (playbackSpeed == 1f) 2f else 1f
                },
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.62f)
            ) {
                Text(
                    text = if (playbackSpeed == 2f) "2×" else "1×",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.55f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.OfflineBolt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Sin conexión", color = Color.White)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L ->
        String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L ->
        String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
