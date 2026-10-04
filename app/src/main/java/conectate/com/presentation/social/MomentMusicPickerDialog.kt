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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
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
import conectate.com.data.music.timedMusicLyrics
import conectate.com.data.model.MomentMusicPresentation
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
    val presentation: MomentMusicPresentation = MomentMusicPresentation.CARD,
    val overlayX: Float = .5f,
    val overlayY: Float = .5f,
    val overlayScale: Float = 1f,
    val overlayRotation: Float = 0f,
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
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    // Photos currently last six seconds on the authoritative server.
    val effectiveContentDurationMs = if (hasOriginalAudio) contentDurationMs
        else minOf(6_000L, contentDurationMs?.takeIf { it > 0L } ?: 6_000L)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var query by remember { mutableStateOf(state.query) }
    val category = "Todo"
    var section by remember {
        mutableStateOf(if (initialSelection?.localUri != null) "mine" else "community")
    }
    var selected by remember { mutableStateOf(initialSelection?.track) }
    var selectedLocalUri by remember { mutableStateOf(initialSelection?.localUri) }
    var range by remember {
        mutableStateOf(
            initialSelection?.let {
                MomentMusicTrimPolicy(it.track.durationMs, effectiveContentDurationMs)
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
    val sections = remember { MOMENT_MUSIC_PICKER_SECTIONS }
    var showMyUploads by remember { mutableStateOf(false) }
    var presentation by remember { mutableStateOf(initialSelection?.presentation ?: MomentMusicPresentation.CARD) }
    var showSongInfo by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }

    LaunchedEffect(section) {
        if (section == "community" && (state.section != "community" || state.category != category)) {
            onSearch(query, category, section)
        }
    }

    LaunchedEffect(selected?.id, selected?.durationMs, effectiveContentDurationMs) {
        selected?.let { track -> range = MomentMusicTrimPolicy(track.durationMs, effectiveContentDurationMs).normalize(range) }
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
        if (presentation == MomentMusicPresentation.LYRICS && timedMusicLyrics(track.lyrics, track.durationMs).isEmpty()) {
            presentation = MomentMusicPresentation.CARD
        }
        selected = track
        selectedLocalUri = null
        range = MomentMusicTrimPolicy(track.durationMs, effectiveContentDurationMs).initialRange(effectiveContentDurationMs)
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
                                player.pause(); playingId = null
                                if (selected != null) { selected = null; selectedLocalUri = null }
                                else onDismiss()
                            }) {
                                Icon(if (selected == null) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                    if (selected == null) "Cerrar selector de música" else "Volver al catálogo")
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { showTopMenu = true }) { Icon(Icons.Default.MoreVert, "Opciones de música") }
                                DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                                    if (selected == null) {
                                        DropdownMenuItem(text = { Text("Publicar una canción") }, onClick = {
                                            showTopMenu = false; player.pause(); onOpenCommunityUpload()
                                        })
                                        DropdownMenuItem(text = { Text("Mi biblioteca") }, onClick = {
                                            showTopMenu = false; player.pause(); playingId = null; showMyUploads = true
                                        })
                                    } else {
                                        DropdownMenuItem(text = { Text("Solo música, sin tarjeta ni letras") }, onClick = {
                                            presentation = MomentMusicPresentation.AUDIO_ONLY; showTopMenu = false
                                        })
                                        DropdownMenuItem(text = { Text("Información de la canción") }, onClick = {
                                            showTopMenu = false; showSongInfo = true
                                        })
                                        if (initialSelection != null) DropdownMenuItem(text = { Text("Quitar música") }, onClick = {
                                            showTopMenu = false; player.pause(); onRemove(); onDismiss()
                                        })
                                    }
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    selected?.let { track ->
                        val policy = MomentMusicTrimPolicy(track.durationMs, effectiveContentDurationMs)
                        val safeRange = policy.normalize(range)
                        val maxFadeMs = policy.fadeLimitMs(safeRange)
                        val selectableLyrics = presentation != MomentMusicPresentation.LYRICS ||
                            timedMusicLyrics(track.lyrics, track.durationMs).any {
                                it.text.isNotBlank() && it.startMs < safeRange.endInclusive && it.endMs > safeRange.start
                            }
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
                                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                                        contentDescription = if (playingId == track.id && isPlayerPlaying) "Pausar" else "Escuchar"
                                    },
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
                                    enabled = selectableLyrics,
                                    onClick = {
                                        player.pause()
                                        onSelect(MomentMusicSelection(
                                            track, safeRange.start.toLong(), safeRange.endInclusive.toLong(),
                                            musicVolume, originalVolume, fadeInMs.coerceIn(0L, maxFadeMs),
                                            fadeOutMs.coerceIn(0L, maxFadeMs), duckOriginal, selectedLocalUri, presentation,
                                            overlayX = initialSelection?.overlayX ?: .5f,
                                            overlayY = initialSelection?.overlayY ?: .5f,
                                            overlayScale = initialSelection?.overlayScale ?: 1f,
                                            overlayRotation = initialSelection?.overlayRotation ?: 0f,
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
                    val trimPolicy = MomentMusicTrimPolicy(track.durationMs, effectiveContentDurationMs)
                    val safeRange = trimPolicy.normalize(range)
                    val durations = momentMusicClipDurations(trimPolicy.maxClipMs)
                    var showDurations by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding)
                            .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 240.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Brush.verticalGradient(listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f),
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .15f))))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (presentation == MomentMusicPresentation.AUDIO_ONLY) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Default.MusicNote, null, Modifier.size(40.dp), MaterialTheme.colorScheme.primary)
                                    Text("Solo música", style = MaterialTheme.typography.titleLarge)
                                    Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            } else MomentMusicPresentationPreview(presentation, track.title, track.artist, track.artworkUrl,
                                rawLyrics = track.lyrics, playbackPositionMs = positionMs, durationMs = track.durationMs)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val hasLyrics = timedMusicLyrics(track.lyrics, track.durationMs).isNotEmpty()
                            listOf(MomentMusicPresentation.CARD, MomentMusicPresentation.LABEL,
                                if (hasLyrics) MomentMusicPresentation.LYRICS else MomentMusicPresentation.AUDIO_ONLY).forEach { option ->
                                val label = when (option) {
                                    MomentMusicPresentation.CARD -> "Tarjeta"
                                    MomentMusicPresentation.LABEL -> "Etiqueta"
                                    MomentMusicPresentation.AUDIO_ONLY -> "Solo música"
                                    MomentMusicPresentation.LYRICS -> "Letras"
                                }
                                FilterChip(selected = presentation == option, onClick = { presentation = option },
                                    label = { Text(label) }, modifier = Modifier.testTag("music_presentation_option_${option.wireValue}"))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Fragmento", style = MaterialTheme.typography.titleSmall)
                            Box {
                                TextButton(onClick = { showDurations = true }, enabled = durations.size > 1) {
                                    Text("${(safeRange.endInclusive - safeRange.start).toLong() / 1_000} s")
                                    if (durations.size > 1) Icon(Icons.Default.ExpandMore, null)
                                }
                                DropdownMenu(expanded = showDurations, onDismissRequest = { showDurations = false }) {
                                    durations.forEach { length ->
                                        DropdownMenuItem(text = { Text("${length / 1_000} segundos") }, onClick = {
                                            range = trimPolicy.normalize(safeRange.start..(safeRange.start + length))
                                            showDurations = false
                                            if (playingId == track.id && playingSelection) player.seekTo(range.start.toLong())
                                        })
                                    }
                                }
                            }
                        }
                        MomentMusicExcerptTimeline(
                            sourceDurationMs = trimPolicy.sourceDurationMs,
                            startMs = safeRange.start.toLong(), endMs = safeRange.endInclusive.toLong(),
                            playbackPositionMs = positionMs,
                            onStartChanged = { start ->
                                range = trimPolicy.moveStart(start.toFloat(), safeRange)
                                if (playingId == track.id && playingSelection) player.seekTo(range.start.toLong())
                            },
                        )
                        val cues = remember(track.lyrics, track.durationMs) { timedMusicLyrics(track.lyrics, track.durationMs) }
                        if (cues.isEmpty()) Text("Esta pista no incluye letras sincronizadas.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (presentation == MomentMusicPresentation.LYRICS && cues.none {
                            it.text.isNotBlank() && it.startMs < safeRange.endInclusive && it.endMs > safeRange.start
                        }) {
                            Text("Este fragmento no tiene letra. Elige otra parte de la canción.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = {
                                range = trimPolicy.moveStart(cues.first { it.text.isNotBlank() }.startMs.toFloat(), safeRange)
                                if (playingId == track.id && playingSelection) player.seekTo(range.start.toLong())
                            }) { Text("Ir al inicio de la letra") }
                        }
                        playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall) }
                    }

                }
            }
        }
    }
    if (showSongInfo && selected != null) {
        val track = selected!!
        val attributionUrl = (track.licenseUrl ?: track.sourcePageUrl).takeIf { it.startsWith("https://") }
        AlertDialog(onDismissRequest = { showSongInfo = false },
            title = { Text("Información de la canción") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(track.title); Text(track.artist); Text(track.license)
            } },
            confirmButton = { TextButton(onClick = { showSongInfo = false }) { Text("Listo") } },
            dismissButton = { if (attributionUrl != null) TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attributionUrl))) }
            }) { Text("Ver licencia") } },
        )
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
