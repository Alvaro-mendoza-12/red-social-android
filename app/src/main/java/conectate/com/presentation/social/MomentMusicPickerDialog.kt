package conectate.com.presentation.social

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.compose.AsyncImage
import conectate.com.data.music.ApprovedMomentMusic
import conectate.com.util.MediaPlaybackDataSources
import conectate.com.util.InteractiveAudioBuffer
import conectate.com.util.UbuntuMedia
import kotlinx.coroutines.delay

data class MomentMusicSelection(
    val track: ApprovedMomentMusic,
    val startMs: Long,
    val endMs: Long,
    val musicVolume: Float,
    val originalAudioVolume: Float,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val duckOriginalAudio: Boolean = false,
    /** URI local temporal cuando la pista viene del dispositivo. */
    val localUri: Uri? = null,
)

internal val MOMENT_MUSIC_PICKER_SECTIONS = listOf(
    "mine" to "Mi música",
    "community" to "Comunidad",
)

/**
 * Community rows stream the original, not the short moderation preview. A
 * Moment must use the original approved audio so a crop can start after the
 * preview window. Local device audio always wins over a remote URL.
 */
internal fun momentMusicRemoteUrl(track: ApprovedMomentMusic, useFullTrack: Boolean): String =
    if (useFullTrack || track.source == "novvin_community") track.audioUrl else track.previewUrl ?: track.audioUrl

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentMusicPickerDialog(
    state: MusicCatalogUiState,
    contentDurationMs: Long?,
    hasOriginalAudio: Boolean,
    initialSelection: MomentMusicSelection?,
    onSearch: (String, String, String) -> Unit,
    onLoadMore: () -> Unit,
    onToggleFavorite: (ApprovedMomentMusic) -> Unit,
    onReport: (ApprovedMomentMusic) -> Unit,
    onNotInterested: (ApprovedMomentMusic) -> Unit,
    onPreviewStarted: (ApprovedMomentMusic) -> Unit,
    onOpenCommunityUpload: () -> Unit,
    onOpenDeviceMusic: () -> Unit,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onSelect: (MomentMusicSelection) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var query by remember { mutableStateOf(state.query) }
    var category by remember { mutableStateOf(state.category) }
    var section by remember {
        mutableStateOf(if (initialSelection?.localUri != null) "mine" else "community")
    }
    var selected by remember { mutableStateOf(initialSelection?.track) }
    var selectedLocalUri by remember { mutableStateOf(initialSelection?.localUri) }
    var range by remember {
        mutableStateOf(
            initialSelection?.let {
                MomentMusicTrimPolicy(it.track.durationMs, contentDurationMs)
                    .normalize(it.startMs.toFloat()..it.endMs.toFloat())
            } ?: (0f..6_000f),
        )
    }
    var musicVolume by remember { mutableStateOf(initialSelection?.musicVolume ?: 1f) }
    var originalVolume by remember { mutableStateOf(initialSelection?.originalAudioVolume ?: 1f) }
    var fadeInMs by remember { mutableLongStateOf(initialSelection?.fadeInMs ?: 0L) }
    var fadeOutMs by remember { mutableLongStateOf(initialSelection?.fadeOutMs ?: 0L) }
    var duckOriginal by remember { mutableStateOf(initialSelection?.duckOriginalAudio ?: false) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var playingSelection by remember { mutableStateOf(false) }
    var isPlayerPlaying by remember { mutableStateOf(false) }
    var isPlayerBuffering by remember { mutableStateOf(false) }
    var previewStartMs by remember { mutableLongStateOf(0L) }
    var previewEndMs by remember { mutableLongStateOf(30_000L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    val categories = remember { listOf("Todo", "Instrumental", "Piano", "Clásica", "Ambiental", "Naturaleza") }
    val sections = remember { MOMENT_MUSIC_PICKER_SECTIONS }
    var showMyUploads by remember { mutableStateOf(false) }

    LaunchedEffect(selected?.id, selected?.durationMs, contentDurationMs) {
        selected?.let { track -> range = MomentMusicTrimPolicy(track.durationMs, contentDurationMs).normalize(range) }
    }

    LaunchedEffect(range) {
        val fadeLimit = minOf(5_000L, ((range.endInclusive - range.start) / 2f).toLong().coerceAtLeast(0L))
        fadeInMs = fadeInMs.coerceIn(0L, fadeLimit)
        fadeOutMs = fadeOutMs.coerceIn(0L, fadeLimit)
    }

    val player = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(InteractiveAudioBuffer.loadControl())
            // Community music is streaming-only. Do not use an offline cache or
            // a DownloadService for this catalog.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    MediaPlaybackDataSources.interactiveAudioFactory(context),
                ),
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isPlayerBuffering = state == Player.STATE_BUFFERING
            }
            override fun onIsPlayingChanged(value: Boolean) {
                isPlayerPlaying = value
                if (value) playbackError = null
                if (!value && player.playbackState == Player.STATE_ENDED) playingId = null
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    isPlayerBuffering = false
                    playingId = null
                    playbackError = "La reproducción se interrumpió. Toca escuchar para continuar."
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playingId = null
                playbackError = "No se pudo escuchar esta pista. Prueba con otra."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                playingId = null
                isPlayerBuffering = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(playingId, range, playingSelection) {
        while (playingId != null) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val isSelectedTrack = playingSelection && selected?.id == playingId
            val activeStartMs = if (isSelectedTrack) range.start.toLong() else previewStartMs
            val activeEndMs = if (isSelectedTrack) range.endInclusive.toLong() else previewEndMs
            if (positionMs >= activeEndMs) {
                if (isSelectedTrack) {
                    player.seekTo(activeStartMs)
                    player.play()
                } else {
                    player.pause()
                    playingId = null
                }
            }
            delay(100)
        }
    }

    fun play(
        track: ApprovedMomentMusic,
        startMs: Long,
        endMs: Long,
        localUri: Uri? = null,
        useFullTrack: Boolean = false,
        toggleIfAlreadyPlaying: Boolean = true,
        loopSelection: Boolean = false,
    ) {
        playbackError = null
        if (toggleIfAlreadyPlaying && playingId == track.id && (isPlayerPlaying || isPlayerBuffering) && playingSelection == loopSelection) {
            player.pause()
            playingId = null
            return
        }
        previewStartMs = startMs.coerceAtLeast(0L)
        previewEndMs = endMs.coerceAtLeast(previewStartMs + 1L)
        playingSelection = loopSelection
        player.volume = musicVolume
        val rawRemoteUrl = momentMusicRemoteUrl(track, useFullTrack)
        val playbackUri = localUri ?: Uri.parse(UbuntuMedia.resolveUrl(rawRemoteUrl) ?: rawRemoteUrl)
        val loadedUri = player.currentMediaItem?.localConfiguration?.uri
        if (player.currentMediaItem?.mediaId != track.id || loadedUri != playbackUri) {
            player.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(playbackUri)
                    .apply {
                        if (playbackUri.scheme == "http" || playbackUri.scheme == "https") {
                            // Preview and original audio have different byte
                            // ranges. Sharing the track id here can make a
                            // previously cached 30-second preview shadow the
                            // full community track.
                            setCustomCacheKey("${track.id}|$playbackUri")
                        }
                    }
                    .build(),
                previewStartMs,
            )
            player.prepare()
        } else {
            player.seekTo(previewStartMs)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }
        player.playWhenReady = true
        if (player.playWhenReady) {
            playingId = track.id
        } else {
            playingId = null
            isPlayerBuffering = false
            playbackError = "No se pudo iniciar el audio. Toca escuchar para reintentar."
        }
        positionMs = startMs
        onPreviewStarted(track)
    }

    fun selectTrack(track: ApprovedMomentMusic) {
        selected = track
        selectedLocalUri = null
        range = MomentMusicTrimPolicy(track.durationMs, contentDurationMs).initialRange(contentDurationMs)
        play(
            track,
            range.start.toLong(),
            range.endInclusive.toLong(),
            useFullTrack = true,
            toggleIfAlreadyPlaying = false,
            loopSelection = true,
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (selected == null) "Añadir música" else "Elegir fragmento") },
                        navigationIcon = {
                            IconButton(onClick = {
                                 if (selected != null) {
                                     player.pause()
                                     playingId = null
                                     selected = null
                                     selectedLocalUri = null
                                } else onDismiss()
                            }) {
                                Icon(if (selected == null) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                            }
                        },
                    )
                },
                bottomBar = {
                    selected?.let { track ->
                        val policy = MomentMusicTrimPolicy(track.durationMs, contentDurationMs)
                        val safeRange = policy.normalize(range)
                        val maxFadeMs = policy.fadeLimitMs(safeRange)
                        Surface(tonalElevation = 3.dp) {
                            Row(
                                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        play(track, safeRange.start.toLong(), safeRange.endInclusive.toLong(),
                                            selectedLocalUri, useFullTrack = true, loopSelection = true)
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    if (playingId == track.id && isPlayerBuffering) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(if (playingId == track.id && isPlayerPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (playingId == track.id && isPlayerPlaying) "Pausar" else "Escuchar")
                                }
                                Button(
                                    onClick = {
                                        player.pause()
                                        onSelect(MomentMusicSelection(
                                            track, safeRange.start.toLong(), safeRange.endInclusive.toLong(),
                                            musicVolume, originalVolume, fadeInMs.coerceIn(0L, maxFadeMs),
                                            fadeOutMs.coerceIn(0L, maxFadeMs), duckOriginal, selectedLocalUri,
                                        ))
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Default.Check, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Usar audio")
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                if (selected == null) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .navigationBarsPadding(),
                    ) {
                        item(key = "catalog-controls") {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    sections.forEach { (id, label) ->
                                        FilterChip(
                                            selected = section == id,
                                            onClick = {
                                                section = id
                                                if (id == "community") onSearch(query, category, id)
                                            },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = if (section == "mine") onOpenDeviceMusic else onOpenCommunityUpload,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) {
                                        Icon(if (section == "mine") Icons.Default.LibraryMusic else Icons.Default.CloudUpload, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (section == "mine") "Elegir del dispositivo" else "Publicar una canción")
                                    }
                                    IconButton(onClick = { player.pause(); playingId = null; showMyUploads = true }) {
                                        Icon(Icons.Default.History, "Mis canciones publicadas")
                                    }
                                }
                                if (section == "community") OutlinedTextField(
                                    value = query,
                                    onValueChange = {
                                        query = it
                                        onSearch(it, category, section)
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    singleLine = true,
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    placeholder = { Text("Canción o artista") },
                                    trailingIcon = {
                                        if (query.isNotEmpty()) IconButton(onClick = {
                                            query = ""
                                            onSearch("", category, section)
                                        }) { Icon(Icons.Default.Close, "Borrar búsqueda") }
                                    },
                                )
                                if (section == "community") Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    categories.forEach { item ->
                                        FilterChip(
                                            selected = category == item,
                                            onClick = {
                                                category = item
                                                onSearch(query, item, section)
                                            },
                                            label = { Text(item) },
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (section == "mine") {
                                            "Elige música propia o con permiso para compartir."
                                        } else {
                                            "Toca una canción para escucharla y elegir tu fragmento."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                playbackError?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                }
                                state.actionMessage?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                }
                                Text(
                                    if (section == "mine") "Tu biblioteca" else "Música de la comunidad",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        when {
                            section == "mine" -> item { CatalogMessage(
                                "Elige una canción de tu dispositivo. Novvin detectará título, artista, álbum, duración y portada cuando estén disponibles.",
                                action = "Abrir mi música",
                                onAction = onOpenDeviceMusic,
                            ) }
                            state.isLoading && state.items.isEmpty() -> item {
                                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            state.error != null && state.items.isEmpty() -> item { CatalogMessage(
                                state.error,
                                action = "Reintentar",
                                onAction = { onSearch(query, category, section) },
                            ) }
                            state.items.isEmpty() -> item { CatalogMessage(
                                "Todavía no hay canciones aprobadas de la comunidad para esta búsqueda.",
                            ) }
                            else -> {
                                if (state.isLoading) {
                                    item(key = "refresh-progress") {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                state.error?.let { message ->
                                    item(key = "refresh-error") {
                                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                                        TextButton(onClick = { onSearch(query, category, section) }) {
                                            Text("Reintentar actualización")
                                        }
                                    }
                                }
                                items(state.items, key = { it.id }) { track ->
                                    MusicCatalogRow(
                                        track = track,
                                        isPlaying = playingId == track.id && isPlayerPlaying,
                                        isBuffering = playingId == track.id && isPlayerBuffering,
                                        onPreview = { play(track, 0L, track.durationMs, useFullTrack = true) },
                                        onSelect = { selectTrack(track) },
                                        onToggleFavorite = { onToggleFavorite(track) },
                                        onShare = {
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, "🎵 ${track.title} — ${track.artist}\n${track.sourcePageUrl}")
                                            }
                                            context.startActivity(Intent.createChooser(share, "Compartir música"))
                                        },
                                        onReport = { onReport(track) },
                                        onNotInterested = { onNotInterested(track) },
                                    )
                                }
                                if (state.nextCursor != null) {
                                    item(key = "load-more") {
                                        state.loadMoreError?.let { message ->
                                            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                                        }
                                        OutlinedButton(
                                            onClick = onLoadMore,
                                            enabled = !state.isLoading && !state.isLoadingMore,
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        ) {
                                            if (state.isLoadingMore) {
                                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            Text(if (state.loadMoreError == null) "Cargar más" else "Reintentar cargar más")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val track = selected!!
                    val trimPolicy = MomentMusicTrimPolicy(track.durationMs, contentDurationMs)
                    val sourceEndMs = trimPolicy.sourceDurationMs
                    val safeRange = trimPolicy.normalize(range)
                    val maxFadeMs = trimPolicy.fadeLimitMs(safeRange)
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding()
                            .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        MusicCatalogRow(
                            track = track,
                            isPlaying = playingId == track.id && isPlayerPlaying,
                            isBuffering = playingId == track.id && isPlayerBuffering,
                            onPreview = {
                                play(
                                    track,
                                    range.start.toLong(),
                                    range.endInclusive.toLong(),
                                    selectedLocalUri,
                                    useFullTrack = true,
                                    loopSelection = true,
                                )
                            },
                            onSelect = {},
                            selectable = false,
                            onToggleFavorite = { onToggleFavorite(track) },
                            onShare = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "🎵 ${track.title} — ${track.artist}\n${track.sourcePageUrl}")
                                }
                                context.startActivity(Intent.createChooser(share, "Compartir música"))
                            },
                            onReport = { onReport(track) },
                            onNotInterested = { onNotInterested(track) },
                        )
                        Text("Inicio en la canción", fontWeight = FontWeight.Bold)
                        Text(
                            "Recorre toda la canción (${formatDuration(sourceEndMs)}). Puedes usar hasta ${formatDuration(trimPolicy.maxClipMs)} por fragmento.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val latestStart = sourceEndMs.toFloat() - (safeRange.endInclusive - safeRange.start)
                        Slider(
                            value = safeRange.start,
                            onValueChange = { start ->
                                range = trimPolicy.moveStart(start, safeRange)
                                if (playingId == track.id && playingSelection) player.seekTo(range.start.toLong())
                            },
                            valueRange = 0f..latestStart.coerceAtLeast(1f),
                            enabled = latestStart > 0f,
                        )
                        Text("Ajustar duración del fragmento", fontWeight = FontWeight.Bold)
                        RangeSlider(
                            value = safeRange,
                            onValueChange = { candidate ->
                                range = trimPolicy.resize(candidate, safeRange)
                                if (playingId == track.id && playingSelection) player.seekTo(range.start.toLong())
                            },
                            valueRange = 0f..sourceEndMs.toFloat(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(range.start.toLong()))
                            Text("${formatDuration((range.endInclusive - range.start).toLong())} seleccionados")
                            Text(formatDuration(range.endInclusive.toLong()))
                        }
                        LinearProgressIndicator(
                            progress = { ((positionMs - safeRange.start) / (safeRange.endInclusive - safeRange.start)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Volumen de la música ${percent(musicVolume)}", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = musicVolume,
                            onValueChange = {
                                musicVolume = it
                                player.volume = it
                            },
                            valueRange = 0f..1f,
                        )
                        if (hasOriginalAudio) {
                            Text("Audio original ${percent(originalVolume)}", fontWeight = FontWeight.SemiBold)
                            Slider(value = originalVolume, onValueChange = { originalVolume = it }, valueRange = 0f..1f)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Reducir voz/sonido mientras suena la música", fontWeight = FontWeight.SemiBold)
                                    Text("Evita que ambos audios compitan.", style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(checked = duckOriginal, onCheckedChange = { duckOriginal = it })
                            }
                        }
                        Text("Entrada suave ${formatDuration(fadeInMs)}", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = fadeInMs.coerceIn(0L, maxFadeMs).toFloat(),
                            onValueChange = { fadeInMs = it.toLong() },
                            valueRange = 0f..maxFadeMs.toFloat(),
                        )
                        Text("Salida suave ${formatDuration(fadeOutMs)}", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = fadeOutMs.coerceIn(0L, maxFadeMs).toFloat(),
                            onValueChange = { fadeOutMs = it.toLong() },
                            valueRange = 0f..maxFadeMs.toFloat(),
                        )
                        playbackError?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        val attributionUrl = (track.licenseUrl ?: track.sourcePageUrl)
                            .takeIf { it.startsWith("https://") }
                        OutlinedButton(
                            onClick = {
                                attributionUrl?.let { url ->
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                }
                            },
                            enabled = attributionUrl != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (attributionUrl != null) "Ver atribución y licencia" else "Licencia validada por Novvin")
                        }
                        if (initialSelection != null) {
                            OutlinedButton(
                                onClick = {
                                    player.pause()
                                    onRemove()
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Close, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Quitar música")
                            }
                        }
                    }
                }
            }
        }
    }
    if (showMyUploads) CommunityMusicLibraryDialog(
        onDismiss = { showMyUploads = false },
        onRemoved = { id ->
            if (selected?.id == id) { player.pause(); selected = null; selectedLocalUri = null }
            if (playingId == id) { player.pause(); playingId = null }
            onSearch(query, category, section)
        },
    )
}

@Composable
@OptIn(UnstableApi::class)
private fun MusicCatalogRow(
    track: ApprovedMomentMusic,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    onPreview: () -> Unit,
    onSelect: () -> Unit,
    selectable: Boolean = true,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onReport: () -> Unit,
    onNotInterested: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = selectable, onClick = onSelect).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (!track.artworkUrl.isNullOrBlank()) {
                AsyncImage(track.artworkUrl, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else Icon(Icons.Default.MusicNote, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sourceLabel = when (track.source) {
                "novvin_community" -> "Comunidad Novvin"
                "wikimedia_commons" -> "Wikimedia Commons"
                else -> track.provider
            }
            Text(
                "$sourceLabel · ${formatDuration(track.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onPreview) {
            if (isBuffering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausar" else "Escuchar")
        }
        Box {
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (track.isFavorite) "Quitar de favoritos" else "Añadir a favoritos") },
                    leadingIcon = { Icon(if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                    onClick = { showMenu = false; onToggleFavorite() },
                )
                DropdownMenuItem(
                    text = { Text("Compartir en Novvin") },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = { showMenu = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text("No me interesa") },
                    leadingIcon = { Icon(Icons.Default.Block, null) },
                    onClick = { showMenu = false; onNotInterested() },
                )
                DropdownMenuItem(
                    text = { Text("Reportar") },
                    leadingIcon = { Icon(Icons.Default.Flag, null) },
                    onClick = { showMenu = false; onReport() },
                )
            }
        }
    }
}

@Composable
private fun CatalogMessage(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.let { OutlinedButton(onClick = onAction) { Text(it) } }
        }
    }
}

private fun formatDuration(valueMs: Long): String {
    val seconds = (valueMs.coerceAtLeast(0L) / 1_000L)
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

private fun percent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100).toInt()}%"
