package conectate.com.presentation.components

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import coil.compose.AsyncImage
import conectate.com.util.MediaPlaybackDataSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.min

data class DeviceAudioTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val album: String = "",
    val artworkUri: Uri? = null,
)

data class PreparedAudioClip(
    val uri: Uri,
    val displayName: String,
    val mimeType: String = "audio/mp4",
    val title: String = displayName.substringBeforeLast('.', displayName),
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUri: Uri? = null,
    /** Original metadata/artwork source when the audio was exported as a crop. */
    val sourceUri: Uri? = null,
)

/**
 * Small, rights-labelled starter catalog shared by Messages, Moments and LIVE.
 * The remote files are only previewed until the user explicitly confirms a
 * clip; the confirmed fragment is exported to app cache before upload/mixing.
 */
fun novvinRoyaltyFreeAudioCatalog(): List<DeviceAudioTrack> = listOf(
    DeviceAudioTrack(
        id = -10_001L,
        uri = Uri.parse("https://upload.wikimedia.org/wikipedia/commons/9/9f/Chill_Beat.ogg"),
        title = "Chill Beat",
        artist = "Maddy · CC0 · Wikimedia Commons",
        durationMs = 25_000L,
        sizeBytes = 0L,
    ),
    DeviceAudioTrack(
        id = -10_002L,
        uri = Uri.parse("https://upload.wikimedia.org/wikipedia/commons/d/d0/Moonlight_Sonata.ogg"),
        title = "Moonlight Sonata",
        artist = "Beethoven · dominio público · Wikimedia Commons",
        durationMs = 25_000L,
        sizeBytes = 0L,
    ),
)

private const val PROFILE_AUDIO_MAX_CLIP_MS = 60_000L
private const val DEFAULT_PROFILE_AUDIO_CLIP_MS = 30_000L
private const val MIN_AUDIO_CLIP_MS = 1_000L

@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAudioPickerDialog(
    initialUri: Uri? = null,
    title: String = "Elegir canción",
    confirmLabel: String = "Listo y guardar",
    maxClipDurationMs: Long? = PROFILE_AUDIO_MAX_CLIP_MS,
    defaultClipDurationMs: Long = DEFAULT_PROFILE_AUDIO_CLIP_MS,
    allowFullTrack: Boolean = false,
    defaultToFullTrack: Boolean = false,
    catalogTracks: List<DeviceAudioTrack> = novvinRoyaltyFreeAudioCatalog(),
    enableOpenCatalog: Boolean = true,
    /** When false the picker is intentionally limited to MediaStore.
     *  Callers that want a single, predictable device-library flow should
     *  not expose the system file browser as a second source of tracks.
     */
    showBrowseFiles: Boolean = true,
    /**
     * Controls whether a successful selection closes the caller-owned dialog.
     * Nested flows such as Community music keep their parent open so they can
     * advance from the library to the publication review step.
     */
    dismissAfterConfirm: Boolean = true,
    onBrowseFiles: () -> Unit,
    onDismiss: () -> Unit,
    onClipReady: (PreparedAudioClip) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    MediaPlaybackDataSources.streamingFactory(context),
                ),
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            volume = 1f
        }
    }
    val audioPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    var tracks by remember { mutableStateOf<List<DeviceAudioTrack>>(emptyList()) }
    var loadingTracks by remember { mutableStateOf(true) }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var query by remember { mutableStateOf("") }
    var remoteCatalogTracks by remember { mutableStateOf<List<DeviceAudioTrack>>(emptyList()) }
    var searchingRemoteCatalog by remember { mutableStateOf(false) }
    var remoteCatalogError by remember { mutableStateOf<String?>(null) }
    var selectedTrack by remember { mutableStateOf<DeviceAudioTrack?>(null) }
    var trimRangeMs by remember { mutableStateOf(0f..1f) }
    var playingUri by remember { mutableStateOf<Uri?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    fun emitWholeTrack(track: DeviceAudioTrack) {
        onClipReady(
            PreparedAudioClip(
                uri = track.uri,
                displayName = track.title.take(140).ifBlank { "audio" },
                mimeType = context.contentResolver.getType(track.uri)
                    ?.takeIf { it.startsWith("audio/") }
                    ?: "audio/*",
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                artworkUri = track.artworkUri,
            ),
        )
        if (dismissAfterConfirm) onDismiss()
    }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var previewEndMs by remember { mutableLongStateOf(Long.MAX_VALUE) }
    var preparingClip by remember { mutableStateOf(false) }
    var preparationError by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var mediaVolume by remember {
        mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var mediaOutputMuted by remember {
        mutableStateOf(
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC) || mediaVolume <= 0,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (!granted) loadingTracks = false
    }

    fun pause() {
        player.pause()
        isPlaying = false
    }

    fun play(track: DeviceAudioTrack, startMs: Long = 0L, endMs: Long = track.durationMs) {
        playbackError = null
        mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        mediaOutputMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC) || mediaVolume <= 0
        if (mediaOutputMuted) {
            pause()
            playbackError = "El volumen multimedia está en cero. Súbelo para escuchar la pista."
            return
        }
        val shouldReload = playingUri != track.uri || player.currentMediaItem == null
        if (shouldReload) {
            player.setMediaItem(MediaItem.fromUri(track.uri))
            player.prepare()
            playingUri = track.uri
        }
        previewEndMs = endMs.takeIf { it > startMs } ?: Long.MAX_VALUE
        player.seekTo(startMs.coerceAtLeast(0L))
        player.volume = 1f
        player.play()
    }

    fun choose(track: DeviceAudioTrack) {
        pause()
        selectedTrack = track
        trimRangeMs = defaultAudioTrimRange(
            durationMs = track.durationMs,
            defaultClipDurationMs = defaultClipDurationMs,
            defaultToFullTrack = defaultToFullTrack,
            maxClipDurationMs = maxClipDurationMs,
        )
        currentPositionMs = trimRangeMs.start.toLong()
        preparationError = null
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                if (value) {
                    mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    mediaOutputMuted =
                        audioManager.isStreamMute(AudioManager.STREAM_MUSIC) || mediaVolume <= 0
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isPlaying = false
                playbackError = "No se pudo reproducir esta pista. Comprueba tu conexión o elige otra."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, previewEndMs) {
        while (true) {
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            if (
                player.isPlaying &&
                previewEndMs != Long.MAX_VALUE &&
                currentPositionMs >= previewEndMs - 80L
            ) {
                player.pause()
                player.seekTo(selectedTrack?.let { trimRangeMs.start.toLong() } ?: 0L)
            }
            delay(180)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasAudioPermission) permissionLauncher.launch(audioPermission)
    }

    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission) {
            loadingTracks = true
            val localTracks = withContext(Dispatchers.IO) { queryDeviceAudioTracks(context) }
            tracks = if (enableOpenCatalog) catalogTracks + localTracks else localTracks
            loadingTracks = false
        } else {
            tracks = if (enableOpenCatalog) catalogTracks else emptyList()
            loadingTracks = false
        }
    }

    LaunchedEffect(query, enableOpenCatalog) {
        val catalogQuery = query.trim()
        if (!enableOpenCatalog || catalogQuery.length < 2) {
            remoteCatalogTracks = emptyList()
            searchingRemoteCatalog = false
            remoteCatalogError = null
            return@LaunchedEffect
        }
        delay(450)
        searchingRemoteCatalog = true
        remoteCatalogError = null
        val result = withContext(Dispatchers.IO) {
            searchOpenAudioCatalog(catalogQuery)
        }
        searchingRemoteCatalog = false
        result.fold(
            onSuccess = { remoteCatalogTracks = it },
            onFailure = {
                remoteCatalogTracks = emptyList()
                remoteCatalogError = "No se pudo consultar el catálogo abierto. Tu música local sigue disponible."
            },
        )
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            val externalTrack = withContext(Dispatchers.IO) {
                resolveDeviceAudioTrack(context, initialUri)
            }
            if (externalTrack != null) choose(externalTrack)
        }
    }

    Dialog(
        onDismissRequest = {
            if (!preparingClip) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !preparingClip,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (selectedTrack != null && !preparingClip) {
                                pause()
                                selectedTrack = null
                            } else if (!preparingClip) {
                                onDismiss()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (selectedTrack == null) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectedTrack == null) "Cerrar" else "Volver a canciones",
                        )
                    }
                    Text(
                        text = if (selectedTrack == null) title else "Recortar y previsualizar",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selectedTrack == null && showBrowseFiles) {
                        IconButton(onClick = onBrowseFiles) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Buscar en archivos")
                        }
                    }
                }

                playbackError?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(error, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (mediaOutputMuted) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "El volumen multimedia está silenciado",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = {
                                    audioManager.adjustStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        AudioManager.ADJUST_UNMUTE,
                                        0,
                                    )
                                    audioManager.adjustStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        AudioManager.ADJUST_RAISE,
                                        AudioManager.FLAG_SHOW_UI,
                                    )
                                    mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    mediaOutputMuted =
                                        audioManager.isStreamMute(AudioManager.STREAM_MUSIC) || mediaVolume <= 0
                                    if (!mediaOutputMuted) playbackError = null
                                },
                            ) {
                                Text("Activar")
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (selectedTrack == null) {
                        AudioLibrary(
                            tracks = (tracks + remoteCatalogTracks).distinctBy { it.uri.toString() },
                            loading = loadingTracks,
                            searchingCatalog = searchingRemoteCatalog,
                            catalogError = remoteCatalogError,
                            openCatalogEnabled = enableOpenCatalog,
                            showBrowseFiles = showBrowseFiles,
                            hasAudioPermission = hasAudioPermission,
                            query = query,
                            playingUri = playingUri,
                            isPlaying = isPlaying,
                            onQueryChange = { query = it.take(80) },
                            onPlay = { track ->
                                if (playingUri == track.uri && player.isPlaying) pause() else play(track)
                            },
                            onChoose = ::choose,
                            onRequestPermission = { permissionLauncher.launch(audioPermission) },
                            onBrowseFiles = onBrowseFiles,
                        )
                    } else {
                        AudioClipEditor(
                            track = selectedTrack!!,
                            trimRangeMs = trimRangeMs,
                            currentPositionMs = currentPositionMs,
                            isPlaying = isPlaying && playingUri == selectedTrack!!.uri,
                            preparing = preparingClip,
                            error = preparationError,
                            onRangeChange = { proposed ->
                                trimRangeMs = constrainAudioRange(
                                    proposed = proposed,
                                    previous = trimRangeMs,
                                    durationMs = selectedTrack!!.durationMs,
                                    maxClipDurationMs = maxClipDurationMs,
                                )
                                if (player.isPlaying) pause()
                                player.seekTo(trimRangeMs.start.toLong())
                            },
                            onPlayPreview = {
                                if (player.isPlaying && playingUri == selectedTrack!!.uri) {
                                    pause()
                                } else {
                                    play(
                                        track = selectedTrack!!,
                                        startMs = trimRangeMs.start.toLong(),
                                        endMs = trimRangeMs.endInclusive.toLong(),
                                    )
                                }
                            },
                            allowFullTrack = allowFullTrack && audioFitsFullTrackLimit(selectedTrack!!.durationMs, maxClipDurationMs),
                            maxClipDurationMs = maxClipDurationMs,
                            onUseFullTrack = useFullTrack@{
                                pause()
                                val track = selectedTrack!!
                                if (!audioFitsFullTrackLimit(track.durationMs, maxClipDurationMs)) return@useFullTrack
                                trimRangeMs = 0f..track.durationMs
                                    .coerceAtLeast(MIN_AUDIO_CLIP_MS)
                                    .toFloat()
                                player.seekTo(0L)
                                emitWholeTrack(track)
                            },
                            onConfirm = {
                                if (!preparingClip) {
                                    pause()
                                    preparationError = null
                                    val track = selectedTrack!!
                                    val selectedWholeTrack =
                                        trimRangeMs.start <= 50f &&
                                            trimRangeMs.endInclusive >= track.durationMs - 50f
                                    if (allowFullTrack && selectedWholeTrack && audioFitsFullTrackLimit(track.durationMs, maxClipDurationMs)) {
                                        emitWholeTrack(track)
                                    } else {
                                        preparingClip = true
                                        scope.launch {
                                            val result = exportAudioClip(
                                                context = context,
                                                sourceUri = track.uri,
                                                startMs = trimRangeMs.start.toLong(),
                                                endMs = trimRangeMs.endInclusive.toLong(),
                                            )
                                            preparingClip = false
                                            result.fold(
                                                onSuccess = { uri ->
                                                    val cleanTitle = track.title
                                                        .substringBeforeLast('.', track.title)
                                                        .ifBlank { "cancion" }
                                                        .take(120)
                                                    onClipReady(
                                                        PreparedAudioClip(
                                                            uri = uri,
                                                            displayName = "${cleanTitle}_recorte.m4a",
                                                            title = track.title,
                                                            artist = track.artist,
                                                            album = track.album,
                                                            durationMs = (trimRangeMs.endInclusive - trimRangeMs.start).toLong(),
                                                            artworkUri = track.artworkUri,
                                                            sourceUri = track.uri,
                                                        ),
                                                    )
                                                    if (dismissAfterConfirm) onDismiss()
                                                },
                                                onFailure = { error ->
                                                    preparationError = when {
                                                        error.message.orEmpty().contains("codec", ignoreCase = true) ->
                                                            "Este formato no se pudo recortar en el teléfono. Prueba otra canción."
                                                        else -> "No se pudo preparar el recorte. Inténtalo nuevamente."
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                            confirmLabel = confirmLabel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioLibrary(
    tracks: List<DeviceAudioTrack>,
    loading: Boolean,
    searchingCatalog: Boolean,
    catalogError: String?,
    openCatalogEnabled: Boolean,
    showBrowseFiles: Boolean,
    hasAudioPermission: Boolean,
    query: String,
    playingUri: Uri?,
    isPlaying: Boolean,
    onQueryChange: (String) -> Unit,
    onPlay: (DeviceAudioTrack) -> Unit,
    onChoose: (DeviceAudioTrack) -> Unit,
    onRequestPermission: () -> Unit,
    onBrowseFiles: () -> Unit,
) {
    val normalizedQuery = query.trim().lowercase(
        androidx.compose.ui.platform.LocalLocale.current.platformLocale
    )
    val visibleTracks = remember(tracks, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            tracks
        } else {
            tracks.filter {
                it.title.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                    it.artist.lowercase(Locale.getDefault()).contains(normalizedQuery)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Buscar por canción o artista") },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (openCatalogEnabled) {
                "Tu música del teléfono aparece primero. Al escribir también buscamos pistas con licencia abierta."
            } else {
                "Toda la música compatible guardada en tu teléfono. Puedes escucharla antes de enviarla."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (searchingCatalog) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        catalogError?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(10.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            visibleTracks.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        query.isNotBlank() -> "No hay coincidencias"
                        !hasAudioPermission -> "Permite acceso a música para mostrar tu biblioteca"
                        else -> "No se encontraron canciones visibles"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (!hasAudioPermission) {
                    Button(onClick = onRequestPermission) {
                        Icon(Icons.Default.MusicNote, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Permitir acceso")
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (showBrowseFiles) {
                    OutlinedButton(onClick = onBrowseFiles) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Buscar en archivos")
                    }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleTracks, key = { it.uri.toString() }) { track ->
                    AudioTrackRow(
                        track = track,
                        isPlaying = playingUri == track.uri && isPlaying,
                        onPlay = { onPlay(track) },
                        onChoose = { onChoose(track) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun AudioTrackRow(
    track: DeviceAudioTrack,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onChoose: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onChoose),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (track.artworkUri != null) {
                    AsyncImage(
                        model = track.artworkUri,
                        contentDescription = "Portada de ${track.title}",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                IconButton(onClick = onPlay, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        tint = if (track.artworkUri == null) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            androidx.compose.ui.graphics.Color.White
                        },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(track.artist.ifBlank { "Artista desconocido" })
                        append(" · ")
                        append(formatAudioTime(track.durationMs))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onChoose) {
                Icon(Icons.Default.Check, contentDescription = "Elegir esta canción")
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioClipEditor(
    track: DeviceAudioTrack,
    trimRangeMs: ClosedFloatingPointRange<Float>,
    currentPositionMs: Long,
    isPlaying: Boolean,
    preparing: Boolean,
    error: String?,
    confirmLabel: String,
    allowFullTrack: Boolean,
    maxClipDurationMs: Long?,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onPlayPreview: () -> Unit,
    onUseFullTrack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val durationMs = track.durationMs.coerceAtLeast(1_000L)
    val progress = (
        (currentPositionMs - trimRangeMs.start) /
            (trimRangeMs.endInclusive - trimRangeMs.start).coerceAtLeast(1f)
        ).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (track.artworkUri != null) {
                AsyncImage(
                    model = track.artworkUri,
                    contentDescription = "Portada de ${track.title}",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(62.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track.artist.ifBlank { "Artista desconocido" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(26.dp))

        Text(
            text = "${formatAudioTime(trimRangeMs.start.toLong())} – " +
                "${formatAudioTime(trimRangeMs.endInclusive.toLong())}  ·  " +
                formatAudioTime((trimRangeMs.endInclusive - trimRangeMs.start).toLong()),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        RangeSlider(
            value = trimRangeMs,
            onValueChange = onRangeChange,
            valueRange = 0f..durationMs.toFloat(),
            enabled = !preparing,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                formatAudioTime(0L),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatAudioTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onPlayPreview,
            enabled = !preparing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (isPlaying) "Pausar previsualización" else "Previsualizar recorte")
        }

        if (allowFullTrack) {
            OutlinedButton(
                onClick = onUseFullTrack,
                enabled = !preparing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Usar la canción completa")
            }
        }

        Text(
            text = when {
                maxClipDurationMs != null && track.durationMs > maxClipDurationMs ->
                    "Esta canción supera ${formatAudioTime(maxClipDurationMs)}. Elige y confirma el fragmento que quieres usar. No se subirá el resto."
                maxClipDurationMs == null ->
                    "Envía la canción completa o mueve los controles para recortar la parte que prefieras."
                else ->
                    "Mueve los dos controles para elegir un fragmento de hasta " +
                        "${maxClipDurationMs / 1_000} segundos."
            },
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
            Text(
                text = error,
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = onConfirm,
            enabled = !preparing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (preparing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.Check, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (preparing) "Preparando recorte…" else confirmLabel)
        }
        Spacer(Modifier.height(48.dp))
    }
}

internal fun defaultAudioTrimRange(
    durationMs: Long,
    defaultClipDurationMs: Long,
    defaultToFullTrack: Boolean,
    maxClipDurationMs: Long? = null,
): ClosedFloatingPointRange<Float> {
    val safeDuration = durationMs.coerceAtLeast(MIN_AUDIO_CLIP_MS)
    val selectedDuration = if (defaultToFullTrack) {
        safeDuration
    } else {
        min(safeDuration, defaultClipDurationMs.coerceAtLeast(MIN_AUDIO_CLIP_MS))
    }
    return 0f..min(selectedDuration, maxClipDurationMs?.coerceAtLeast(MIN_AUDIO_CLIP_MS) ?: safeDuration).toFloat()
}

internal fun audioFitsFullTrackLimit(durationMs: Long, maxClipDurationMs: Long?): Boolean =
    durationMs > 0 && (maxClipDurationMs == null || durationMs <= maxClipDurationMs)

internal fun constrainAudioRange(
    proposed: ClosedFloatingPointRange<Float>,
    previous: ClosedFloatingPointRange<Float>,
    durationMs: Long,
    maxClipDurationMs: Long?,
): ClosedFloatingPointRange<Float> {
    val duration = durationMs.coerceAtLeast(MIN_AUDIO_CLIP_MS).toFloat()
    val minClip = min(MIN_AUDIO_CLIP_MS.toFloat(), duration)
    var start = proposed.start.coerceIn(0f, duration)
    var end = proposed.endInclusive.coerceIn(start, duration)
    val movedStart = abs(proposed.start - previous.start) > abs(proposed.endInclusive - previous.endInclusive)

    if (maxClipDurationMs != null && end - start > maxClipDurationMs.toFloat()) {
        if (movedStart) {
            start = (end - maxClipDurationMs.toFloat()).coerceAtLeast(0f)
        } else {
            end = (start + maxClipDurationMs.toFloat()).coerceAtMost(duration)
        }
    }
    if (end - start < minClip) {
        if (movedStart) {
            start = (end - minClip).coerceAtLeast(0f)
        } else {
            end = (start + minClip).coerceAtMost(duration)
            if (end - start < minClip) start = (end - minClip).coerceAtLeast(0f)
        }
    }
    return start..end
}

private fun queryDeviceAudioTracks(context: Context): List<DeviceAudioTrack> {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.IS_MUSIC,
        MediaStore.Audio.Media.IS_PODCAST,
    )
    val tracks = mutableListOf<DeviceAudioTrack>()
    runCatching {
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.DURATION} > 0 AND " +
                "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0)",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val albumIdIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val durationIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val sizeIndex = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            while (cursor.moveToNext() && tracks.size < 1_500) {
                val id = cursor.getLong(idIndex)
                val name = cursor.valueOrNull(nameIndex).orEmpty()
                val title = cursor.valueOrNull(titleIndex)
                    ?.takeIf(String::isNotBlank)
                    ?: name.substringBeforeLast('.', name).ifBlank { "Canción" }
                tracks += DeviceAudioTrack(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = title,
                    artist = cursor.valueOrNull(artistIndex)
                        ?.takeUnless { it.equals("<unknown>", ignoreCase = true) }
                        .orEmpty(),
                    durationMs = cursor.longOrZero(durationIndex),
                    sizeBytes = cursor.longOrZero(sizeIndex),
                    album = cursor.valueOrNull(albumIndex).orEmpty(),
                    artworkUri = cursor.longOrZero(albumIdIndex)
                        .takeIf { it > 0L }
                        ?.let { albumId ->
                            ContentUris.withAppendedId(
                                Uri.parse("content://media/external/audio/albumart"),
                                albumId,
                            )
                        },
                )
            }
        }
    }
    return tracks
}

/**
 * Searches Internet Archive's open-audio catalog. Results are restricted to
 * items that declare a Creative Commons or public-domain license; the license
 * still remains visible to the user because individual CC variants differ.
 */
private suspend fun searchOpenAudioCatalog(query: String): Result<List<DeviceAudioTrack>> = runCatching {
    val cleanQuery = query
        .trim()
        .replace(Regex("[^\\p{L}\\p{N} _.-]"), " ")
        .replace(Regex("\\s+"), " ")
        .take(60)
    require(cleanQuery.length >= 2) { "Consulta muy corta" }

    val terms = cleanQuery.split(' ').filter(String::isNotBlank).joinToString(" AND ")
    val searchExpression = buildString {
        append("mediatype:audio AND (")
        append("licenseurl:*creativecommons.org* OR licenseurl:*publicdomain*")
        append(") AND NOT licenseurl:*by-nc* AND NOT licenseurl:*by-nd* AND (")
        append("title:($terms) OR creator:($terms) OR subject:($terms) OR description:($terms)")
        append(")")
    }
    val encodedExpression = URLEncoder.encode(searchExpression, Charsets.UTF_8.name())
    val searchUrl = "https://archive.org/advancedsearch.php" +
        "?q=$encodedExpression&fl%5B%5D=identifier&sort%5B%5D=downloads+desc&rows=30&page=1&output=json"
    val root = openCatalogJson.parseToJsonElement(readCatalogUrl(searchUrl)).jsonObject
    val identifiers = root["response"]
        ?.jsonObject
        ?.get("docs")
        ?.jsonArray
        .orEmpty()
        .mapNotNull { it.jsonObject["identifier"]?.jsonPrimitive?.contentOrNull }
        .distinct()

    coroutineScope {
        identifiers.take(18).map { identifier ->
            async(Dispatchers.IO) {
                runCatching { resolveOpenCatalogTrack(identifier) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }
}

private fun resolveOpenCatalogTrack(identifier: String): DeviceAudioTrack? {
    val safeIdentifier = URLEncoder.encode(identifier, Charsets.UTF_8.name())
    val root = openCatalogJson
        .parseToJsonElement(readCatalogUrl("https://archive.org/metadata/$safeIdentifier"))
        .jsonObject
    val metadata = root["metadata"]?.jsonObject ?: return null
    val licenseUrl = metadata["licenseurl"]?.let(::catalogText).orEmpty()
    val normalizedLicense = licenseUrl.lowercase(Locale.ROOT)
    val commerciallyReusable = normalizedLicense.contains("publicdomain") ||
        normalizedLicense.contains("creativecommons.org/licenses/by/") ||
        normalizedLicense.contains("creativecommons.org/licenses/by-sa/")
    if (!commerciallyReusable || normalizedLicense.contains("by-nc") || normalizedLicense.contains("by-nd")) {
        return null
    }

    val candidate = root["files"]
        ?.jsonArray
        .orEmpty()
        .mapNotNull { item ->
            val file = item.jsonObject
            val name = file["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val format = file["format"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val durationMs = file["length"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.let(::parseCatalogDurationMs)
                ?: 0L
            val playable = name.substringBefore('?').lowercase().let { path ->
                path.endsWith(".mp3") || path.endsWith(".ogg") || path.endsWith(".m4a") ||
                    format.contains("MP3", true) || format.contains("Ogg", true)
            }
            if (!playable || durationMs < MIN_AUDIO_CLIP_MS) null
            else Triple(name, durationMs, file["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L)
        }
        .minByOrNull { (name, _, _) ->
            when {
                name.endsWith(".mp3", true) -> 0
                name.endsWith(".m4a", true) -> 1
                else -> 2
            }
        }
        ?: return null

    val (fileName, durationMs, sizeBytes) = candidate
    val encodedName = fileName
        .split('/')
        .joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }
    val title = metadata["title"]?.let(::catalogText)?.takeIf(String::isNotBlank)
        ?: fileName.substringBeforeLast('.', fileName)
    val creator = metadata["creator"]?.let(::catalogText)?.takeIf(String::isNotBlank)
        ?: "Artista del catálogo abierto"
    val licenseLabel = when {
        licenseUrl.contains("publicdomain", true) -> "dominio público"
        licenseUrl.contains("creativecommons.org/publicdomain", true) -> "CC0"
        else -> "Creative Commons"
    }
    return DeviceAudioTrack(
        id = -kotlin.math.abs("archive:$identifier:$fileName".hashCode().toLong()).coerceAtLeast(20_000L),
        uri = Uri.parse("https://archive.org/download/$safeIdentifier/$encodedName"),
        title = title.take(140),
        artist = "$creator · $licenseLabel · Internet Archive".take(220),
        durationMs = durationMs,
        sizeBytes = sizeBytes,
    )
}

private fun readCatalogUrl(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 7_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Novvin-Android/1.0")
        require(connection.responseCode in 200..299) { "Catálogo no disponible" }
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private val openCatalogJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseCatalogDurationMs(raw: String): Long? {
    raw.trim().toDoubleOrNull()?.let { return (it * 1_000.0).toLong() }
    val components = raw.trim().split(':').mapNotNull(String::toDoubleOrNull)
    if (components.size !in 2..3) return null
    val seconds = components.fold(0.0) { total, component -> total * 60.0 + component }
    return (seconds * 1_000.0).toLong()
}

private fun catalogText(value: kotlinx.serialization.json.JsonElement): String = when (value) {
    is kotlinx.serialization.json.JsonArray -> value
        .mapNotNull { it.jsonPrimitive.contentOrNull }
        .joinToString(", ")
    else -> value.jsonPrimitive.contentOrNull.orEmpty()
}

private fun resolveDeviceAudioTrack(context: Context, uri: Uri): DeviceAudioTrack? {
    var displayName: String? = null
    var sizeBytes = 0L
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.valueOrNull(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                sizeBytes = cursor.longOrZero(cursor.getColumnIndex(OpenableColumns.SIZE))
            }
        }
    }

    var metadataTitle: String? = null
    var metadataArtist: String? = null
    var metadataAlbum: String? = null
    var durationMs = 0L
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            metadataTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            metadataArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            metadataAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } finally {
            retriever.release()
        }
    }
    if (durationMs <= 0L) return null
    val fallbackName = displayName
        ?.substringBeforeLast('.', displayName ?: "")
        ?.takeIf(String::isNotBlank)
        ?: "Canción seleccionada"
    return DeviceAudioTrack(
        id = uri.toString().hashCode().toLong(),
        uri = uri,
        title = metadataTitle?.takeIf(String::isNotBlank) ?: fallbackName,
        artist = metadataArtist.orEmpty(),
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        album = metadataAlbum.orEmpty(),
    )
}

private fun android.database.Cursor.valueOrNull(index: Int): String? {
    return if (index >= 0 && !isNull(index)) getString(index)?.trim() else null
}

private fun android.database.Cursor.longOrZero(index: Int): Long {
    return if (index >= 0 && !isNull(index)) getLong(index).coerceAtLeast(0L) else 0L
}

private fun formatAudioTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(Locale.getDefault(), totalSeconds / 60L, totalSeconds % 60L)
}

@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun exportAudioClip(
    context: Context,
    sourceUri: Uri,
    startMs: Long,
    endMs: Long,
): Result<Uri> = runCatching {
    withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val output = File(
                context.cacheDir,
                "audio_clip_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.m4a",
            )
            val mediaItem = MediaItem.fromUri(sourceUri)
                .buildUpon()
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs.coerceAtLeast(0L))
                        .setEndPositionMs(endMs.coerceAtLeast(startMs + MIN_AUDIO_CLIP_MS))
                        .build(),
                )
                .build()
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (!output.exists() || output.length() <= 0L) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IllegalStateException("Empty audio export"))
                            }
                        } else if (continuation.isActive) {
                            continuation.resume(output.toUri())
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        output.delete()
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
                })
                .build()
            continuation.invokeOnCancellation {
                transformer.cancel()
                output.delete()
            }
            runCatching { transformer.start(mediaItem, output.absolutePath) }
                .onFailure { error ->
                    output.delete()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    }
}
