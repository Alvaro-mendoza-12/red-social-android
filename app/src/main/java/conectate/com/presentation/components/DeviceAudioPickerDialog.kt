package conectate.com.presentation.components

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
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
)

data class PreparedAudioClip(
    val uri: Uri,
    val displayName: String,
    val mimeType: String = "audio/mp4",
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
    onBrowseFiles: () -> Unit,
    onDismiss: () -> Unit,
    onClipReady: (PreparedAudioClip) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { ExoPlayer.Builder(context).build() }
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
    var selectedTrack by remember { mutableStateOf<DeviceAudioTrack?>(null) }
    var trimRangeMs by remember { mutableStateOf(0f..1f) }
    var playingUri by remember { mutableStateOf<Uri?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var previewEndMs by remember { mutableLongStateOf(Long.MAX_VALUE) }
    var preparingClip by remember { mutableStateOf(false) }
    var preparationError by remember { mutableStateOf<String?>(null) }
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
        val shouldReload = playingUri != track.uri || player.currentMediaItem == null
        if (shouldReload) {
            player.setMediaItem(MediaItem.fromUri(track.uri))
            player.prepare()
            playingUri = track.uri
        }
        previewEndMs = endMs.takeIf { it > startMs } ?: Long.MAX_VALUE
        player.seekTo(startMs.coerceAtLeast(0L))
        player.play()
        isPlaying = true
    }

    fun choose(track: DeviceAudioTrack) {
        pause()
        selectedTrack = track
        trimRangeMs = defaultAudioTrimRange(
            durationMs = track.durationMs,
            defaultClipDurationMs = defaultClipDurationMs,
            defaultToFullTrack = defaultToFullTrack,
        )
        currentPositionMs = trimRangeMs.start.toLong()
        preparationError = null
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
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
            tracks = withContext(Dispatchers.IO) { queryDeviceAudioTracks(context) }
            loadingTracks = false
        } else {
            tracks = emptyList()
            loadingTracks = false
        }
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
                            imageVector = if (selectedTrack == null) Icons.Default.Close else Icons.Default.ArrowBack,
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
                    if (selectedTrack == null) {
                        IconButton(onClick = onBrowseFiles) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Buscar en archivos")
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
                            tracks = tracks,
                            loading = loadingTracks,
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
                            allowFullTrack = allowFullTrack,
                            maxClipDurationMs = maxClipDurationMs,
                            onUseFullTrack = {
                                pause()
                                trimRangeMs = 0f..selectedTrack!!.durationMs
                                    .coerceAtLeast(MIN_AUDIO_CLIP_MS)
                                    .toFloat()
                                player.seekTo(0L)
                            },
                            onConfirm = {
                                if (!preparingClip) {
                                    pause()
                                    preparationError = null
                                    val track = selectedTrack!!
                                    val selectedWholeTrack =
                                        trimRangeMs.start <= 50f &&
                                            trimRangeMs.endInclusive >= track.durationMs - 50f
                                    if (allowFullTrack && selectedWholeTrack) {
                                        onClipReady(
                                            PreparedAudioClip(
                                                uri = track.uri,
                                                displayName = track.title.take(140).ifBlank { "audio" },
                                                mimeType = context.contentResolver.getType(track.uri)
                                                    ?.takeIf { it.startsWith("audio/") }
                                                    ?: "audio/*",
                                            ),
                                        )
                                        onDismiss()
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
                                                        ),
                                                    )
                                                    onDismiss()
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
    val normalizedQuery = query.trim().lowercase(Locale.getDefault())
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
            text = "Puedes escuchar antes de elegir. Nada se sube hasta que pulses Listo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                OutlinedButton(onClick = onBrowseFiles) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Buscar en archivos")
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
            IconButton(
                onClick = onPlay,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
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
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(62.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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
            progress = progress,
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

private fun defaultAudioTrimRange(
    durationMs: Long,
    defaultClipDurationMs: Long,
    defaultToFullTrack: Boolean,
): ClosedFloatingPointRange<Float> {
    val safeDuration = durationMs.coerceAtLeast(MIN_AUDIO_CLIP_MS)
    val selectedDuration = if (defaultToFullTrack) {
        safeDuration
    } else {
        min(safeDuration, defaultClipDurationMs.coerceAtLeast(MIN_AUDIO_CLIP_MS))
    }
    return 0f..selectedDuration.toFloat()
}

private fun constrainAudioRange(
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
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
    )
    val tracks = mutableListOf<DeviceAudioTrack>()
    runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.DURATION} > 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
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
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    title = title,
                    artist = cursor.valueOrNull(artistIndex)
                        ?.takeUnless { it.equals("<unknown>", ignoreCase = true) }
                        .orEmpty(),
                    durationMs = cursor.longOrZero(durationIndex),
                    sizeBytes = cursor.longOrZero(sizeIndex),
                )
            }
        }
    }
    return tracks
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
    var durationMs = 0L
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            metadataTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            metadataArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
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
            val request = TransformationRequest.Builder()
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()
            val transformer = Transformer.Builder(context)
                .setTransformationRequest(request)
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
