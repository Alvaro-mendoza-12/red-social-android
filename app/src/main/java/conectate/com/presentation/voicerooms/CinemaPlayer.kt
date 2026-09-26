package conectate.com.presentation.voicerooms

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import conectate.com.util.MediaPlaybackDataSources
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.net.URI
import java.util.Locale
import kotlin.math.abs

private fun forceCinemaSpeakerOutput(audioManager: AudioManager) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }?.let(audioManager::setCommunicationDevice)
        return
    }
    @Suppress("DEPRECATION")
    run { audioManager.isSpeakerphoneOn = true }
}

@OptIn(UnstableApi::class)
@Composable
fun CinemaPlayer(
    videoUrl: String,
    isPlaying: Boolean,
    currentPosition: Long,
    isCreator: Boolean,
    onSync: (Boolean, Long) -> Unit,
    onControl: (String, Long) -> Unit,
    onChangeVideo: () -> Unit,
    viewModel: VoiceRoomActiveViewModel
) {
    val context = LocalContext.current
    val latestOnSync by rememberUpdatedState(onSync)
    var isFullscreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var localVolume by remember { mutableFloatStateOf(0.8f) }
    var retryKey by remember { mutableIntStateOf(0) }
    var isBuffering by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var playerIsPlaying by remember { mutableStateOf(false) }
    var playerPositionMs by remember { mutableLongStateOf(0L) }
    var playerDurationMs by remember { mutableLongStateOf(0L) }

    val audioAttributes = remember {
        AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_VOICE_COMMUNICATION)
            .build()
    }

    val exoPlayer = remember {
        val dataSourceFactory = MediaPlaybackDataSources.httpFactory(includeStorageHeaders = false)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                12_000,
                45_000,
                1_500,
                3_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                setAudioAttributes(audioAttributes, false)
                setHandleAudioBecomingNoisy(false)
                volume = localVolume
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) playbackError = null
            }

            override fun onIsPlayingChanged(value: Boolean) {
                playerIsPlaying = value
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Cinema playback failed: ${error.errorCodeName}")
                playbackError = "No se pudo reproducir este archivo. Verifica el enlace o inténtalo otra vez."
                isBuffering = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(videoUrl, retryKey) {
        val validation = CinemaSourcePolicy.validateManualUrl(videoUrl)
        if (!validation.isValid) {
            playbackError = validation.error ?: "El enlace de reproducción no es válido."
            return@LaunchedEffect
        }

        playbackError = null
        isBuffering = true
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        runCatching { forceCinemaSpeakerOutput(audioManager) }
        val safeHost = runCatching { URI(validation.normalizedUrl.orEmpty()).host }.getOrNull().orEmpty()
        Log.i(TAG, "Preparing cinema media from host=$safeHost")

        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(MediaItem.fromUri(validation.normalizedUrl.orEmpty()))
        if (!isCreator && currentPosition > 0L) exoPlayer.seekTo(currentPosition)
        exoPlayer.playWhenReady = if (isCreator) true else isPlaying
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguage("es")
            .build()
        exoPlayer.prepare()
    }

    LaunchedEffect(localVolume) {
        exoPlayer.volume = localVolume
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            playerPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            playerDurationMs = exoPlayer.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
            delay(250)
        }
    }

    LaunchedEffect(viewModel.cinemaSync, isCreator) {
        viewModel.cinemaSync.collectLatest { event ->
            if (!isCreator && event != null) {
                if (abs(exoPlayer.currentPosition - event.currentPosition) > MAX_SYNC_DRIFT_MS) {
                    exoPlayer.seekTo(event.currentPosition.coerceAtLeast(0L))
                }
                if (event.isPlaying != exoPlayer.isPlaying) {
                    if (event.isPlaying) exoPlayer.play() else exoPlayer.pause()
                }
            }
        }
    }

    LaunchedEffect(exoPlayer, isCreator) {
        if (!isCreator) return@LaunchedEffect
        while (true) {
            if (exoPlayer.isPlaying) latestOnSync(true, exoPlayer.currentPosition)
            delay(SYNC_BROADCAST_INTERVAL_MS)
        }
    }

    LaunchedEffect(showControls, isFullscreen) {
        if (showControls) {
            delay(if (isFullscreen) 3_000L else 5_000L)
            showControls = false
        }
    }

    Box(
        modifier = if (isFullscreen) {
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { showControls = !showControls }
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(16 / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .clickable { showControls = !showControls }
        }
    ) {
        AndroidView(
            factory = { playerContext ->
                PlayerView(playerContext).apply {
                    player = exoPlayer
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(5f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text(
                    "Cargando video...",
                    modifier = Modifier.padding(top = 64.dp),
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }

        playbackError?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(8f)
                    .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(message, color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { retryKey++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reintentar")
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.zIndex(10f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.6f))
                        )
                    )
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = viewModel.cinemaState.value?.videoTitle ?: "Modo cine",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = if (isCreator) "Sincronizado · Tú controlas" else "Sincronizado · Siguiendo al anfitrión",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onChangeVideo,
                            modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)
                        ) {
                            Icon(Icons.Default.VideoLibrary, "Cambiar video", tint = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { isFullscreen = !isFullscreen },
                            modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)
                        ) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                "Pantalla completa",
                                tint = Color.White
                            )
                        }
                        if (isCreator) {
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = viewModel::exitCinemaMode,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Cerrar", fontSize = 12.sp)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    IconButton(
                        enabled = isCreator,
                        onClick = {
                            val target = (exoPlayer.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                            onControl("seek", target)
                        }
                    ) {
                        Icon(Icons.Default.Replay10, "Retroceder 10 segundos", tint = Color.White, modifier = Modifier.size(36.dp))
                    }

                    IconButton(
                        enabled = isCreator,
                        onClick = {
                            val shouldPlay = !exoPlayer.isPlaying
                            if (shouldPlay) exoPlayer.play() else exoPlayer.pause()
                            val position = exoPlayer.currentPosition.coerceAtLeast(0L)
                            onControl(if (shouldPlay) "play" else "pause", position)
                            latestOnSync(shouldPlay, position)
                        }
                    ) {
                        Icon(
                            if (playerIsPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                            if (playerIsPlaying) "Pausar" else "Reproducir",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(82.dp)
                        )
                    }

                    IconButton(
                        enabled = isCreator,
                        onClick = {
                            val upperBound = playerDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                            val target = (exoPlayer.currentPosition + SEEK_STEP_MS).coerceAtMost(upperBound)
                            exoPlayer.seekTo(target)
                            onControl("seek", target)
                        }
                    ) {
                        Icon(Icons.Default.Forward10, "Adelantar 10 segundos", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatCinemaTime(playerPositionMs), color = Color.White, fontSize = 11.sp)
                        Text(formatCinemaTime(playerDurationMs), color = Color.White, fontSize = 11.sp)
                    }
                    Slider(
                        value = playerPositionMs.coerceAtMost(playerDurationMs.coerceAtLeast(1L)).toFloat(),
                        onValueChange = { if (isCreator) exoPlayer.seekTo(it.toLong()) },
                        onValueChangeFinished = {
                            if (isCreator) onControl("seek", exoPlayer.currentPosition.coerceAtLeast(0L))
                        },
                        valueRange = 0f..playerDurationMs.coerceAtLeast(1L).toFloat(),
                        enabled = isCreator && playerDurationMs > 0L,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volumen", tint = Color.White, modifier = Modifier.size(16.dp))
                        Slider(
                            value = localVolume,
                            onValueChange = { localVolume = it },
                            modifier = Modifier.width(80.dp),
                            valueRange = 0f..1f
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(isFullscreen) {
        viewModel.setPlayerFullscreen(isFullscreen)
        val activity = context as? android.app.Activity
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (isFullscreen) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.setPlayerFullscreen(false)
            val activity = context as? android.app.Activity
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            exoPlayer.release()
        }
    }
}

private fun formatCinemaTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    val hours = totalSeconds / 3_600L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

private const val TAG = "CinemaPlayer"
private const val SEEK_STEP_MS = 10_000L
private const val MAX_SYNC_DRIFT_MS = 2_000L
private const val SYNC_BROADCAST_INTERVAL_MS = 3_000L
