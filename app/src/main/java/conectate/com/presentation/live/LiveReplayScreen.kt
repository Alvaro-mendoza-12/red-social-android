package conectate.com.presentation.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import conectate.com.data.model.LiveChapter
import conectate.com.data.model.LiveStream
import conectate.com.util.UbuntuMedia
import conectate.com.util.VideoCache

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun LiveReplayScreen(
    navController: NavController,
    liveId: String,
    viewModel: LiveReplayViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val replay by viewModel.replay.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(VideoCache.getCacheDataSourceFactory(context))
            )
            .build()
    }

    LaunchedEffect(liveId) {
        viewModel.load(liveId)
    }
    LaunchedEffect(replay?.replayUrl) {
        val mediaUrl = replay?.replayUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        exoPlayer.setMediaItem(UbuntuMedia.mediaItem(mediaUrl))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(replay?.title ?: "Repetición") },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading && replay == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            error != null && replay == null -> Box(
                modifier = Modifier.fillMaxSize().padding(24.dp).padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = error ?: "No se pudo cargar la repetición.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            replay != null -> ReplayContent(
                replay = replay!!,
                player = exoPlayer,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }
}

@Composable
private fun ReplayContent(
    replay: LiveStream,
    player: ExoPlayer,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val wideLayout = maxWidth >= 720.dp
        if (wideLayout) {
            Row(modifier = Modifier.fillMaxSize()) {
                ReplayPlayer(
                    player = player,
                    modifier = Modifier.weight(1.45f).fillMaxHeight()
                )
                ReplayDetails(
                    replay = replay,
                    onSeek = { player.seekTo(it) },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                ReplayPlayer(
                    player = player,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                ReplayDetails(
                    replay = replay,
                    onSeek = { player.seekTo(it) },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
private fun ReplayPlayer(
    player: ExoPlayer,
    modifier: Modifier
) {
    AndroidView(
        factory = { playerContext ->
            PlayerView(playerContext).apply {
                this.player = player
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { it.player = player },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplayDetails(
    replay: LiveStream,
    onSeek: (Long) -> Unit,
    modifier: Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = replay.hostAvatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(44.dp).height(44.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(replay.hostName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatDuration(replay.replayDurationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Text("Momentos destacados", style = MaterialTheme.typography.titleMedium)
        }
        if (replay.chapters.isEmpty()) {
            item {
                Text(
                    "Este directo todavía no tiene capítulos.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(replay.chapters, key = LiveChapter::id) { chapter ->
                Card(
                    onClick = { onSeek(chapter.offsetMs) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(chapter.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                formatDuration(chapter.offsetMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
