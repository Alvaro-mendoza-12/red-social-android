package conectate.com.presentation.social

import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import conectate.com.data.music.importMusicLyrics
import conectate.com.data.music.readBoundedMusicLyrics
import conectate.com.data.music.timedMusicLyrics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import conectate.com.data.music.CommunityMusicUploadDraft
import conectate.com.data.music.CommunityMusicPolicy
import conectate.com.data.music.readCommunityMusicMetadata
import coil.compose.AsyncImage
import conectate.com.presentation.components.PreparedAudioClip
import conectate.com.presentation.components.DeviceAudioPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publicar en Comunidad es deliberadamente distinto de usar una canción local
 * en un Momento. La app detecta metadatos y solo solicita el nombre y una
 * confirmación legal corta; el resto se valida y modera en el servidor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityMusicUploadDialog(
    state: CreateMomentViewModel.CommunityMusicUploadUiState,
    initialAudio: PreparedAudioClip? = null,
    onSubmit: (Uri, Uri?, CommunityMusicUploadDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var artworkSourceUri by remember(initialAudio) { mutableStateOf(initialAudio?.sourceUri ?: initialAudio?.uri) }
    var albumArtUri by remember(initialAudio) { mutableStateOf(initialAudio?.artworkUri) }
    var detectedCover by remember { mutableStateOf<Uri?>(null) }
    val preparedCovers = remember { mutableListOf<java.io.File>() }
    var readingMetadata by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose { preparedCovers.forEach { it.delete() } }
    }
    var audioUri by remember(initialAudio?.uri) { mutableStateOf(initialAudio?.uri) }
    val scope = rememberCoroutineScope()
    var sourceStartMs by remember(initialAudio?.uri) { mutableLongStateOf(initialAudio?.sourceStartMs ?: 0L) }
    var lyrics by remember(audioUri) { mutableStateOf("") }
    var acceptedRights by remember(audioUri) { mutableStateOf(false) }
    var lyricsError by remember(audioUri) { mutableStateOf<String?>(null) }
    var readingLyrics by remember { mutableStateOf(false) }
    var showLyricsPicker by remember { mutableStateOf(false) }
    var audioName by remember(initialAudio?.uri) { mutableStateOf(initialAudio?.displayName.orEmpty()) }
    var title by remember(initialAudio?.uri) {
        mutableStateOf(
            initialAudio?.title
                ?.trim()
                ?.ifBlank { initialAudio.displayName.substringBeforeLast('.', initialAudio.displayName) }
                .orEmpty(),
        )
    }
    var detectedArtist by remember(initialAudio?.uri) { mutableStateOf(initialAudio?.artist.orEmpty()) }
    var detectedAlbum by remember(initialAudio?.uri) { mutableStateOf(initialAudio?.album.orEmpty()) }
    var durationMs by remember(initialAudio?.uri) { mutableLongStateOf(initialAudio?.durationMs ?: 0L) }
    // Community publishing starts in the same library used by Momentos.  The
    // old flow showed an empty form plus a second "Elegir música..." step;
    // that made it look as if the device library was unavailable.  Open the
    // library immediately when this screen has no preselected track.
    var showDeviceLibrary by remember(initialAudio?.uri) {
        mutableStateOf(initialAudio == null)
    }

    if (showDeviceLibrary) {
        DeviceAudioPickerDialog(
            title = "Mi música",
            confirmLabel = "Elegir para publicar",
            initialUri = artworkSourceUri ?: audioUri,
            maxClipDurationMs = CommunityMusicPolicy.MAX_DURATION_MS,
            defaultClipDurationMs = CommunityMusicPolicy.MAX_DURATION_MS,
            allowFullTrack = true,
            defaultToFullTrack = true,
            catalogTracks = emptyList(),
            enableOpenCatalog = false,
            // Community publishing uses exactly the same MediaStore library
            // as Momentos. Do not send the user to an unstructured system
            // folder picker from this flow.
            showBrowseFiles = false,
            dismissAfterConfirm = false,
            onBrowseFiles = {},
            onDismiss = onDismiss,
            onClipReady = { clip ->
                audioUri = clip.uri
                artworkSourceUri = clip.sourceUri ?: clip.uri
                albumArtUri = clip.artworkUri
                detectedCover = null
                audioName = clip.displayName
                title = clip.title.trim()
                    .ifBlank { clip.displayName.substringBeforeLast('.', clip.displayName) }
                detectedArtist = clip.artist
                detectedAlbum = clip.album
                durationMs = clip.durationMs
                sourceStartMs = clip.sourceStartMs
                showDeviceLibrary = false
            },
        )
    }

    LaunchedEffect(audioUri, artworkSourceUri, albumArtUri) {
        val selectedUri = audioUri ?: return@LaunchedEffect
        readingMetadata = true
        detectedCover = null
        val metadata = withContext(Dispatchers.IO) {
            readCommunityMusicMetadata(context, selectedUri, artworkSourceUri ?: selectedUri, albumArtUri)
        }
        metadata.let { detected ->
            title = detected.title
                .trim()
                .ifBlank { audioName.substringBeforeLast('.', audioName) }
                .ifBlank { "Mi canción" }
                .take(140)
            if (detected.artist.isNotBlank()) detectedArtist = detected.artist
            if (detected.album.isNotBlank()) detectedAlbum = detected.album
            if (detected.durationMs > 0L) durationMs = detected.durationMs
            detected.artworkFile?.let { file ->
                preparedCovers.add(file)
                detectedCover = Uri.fromFile(file)
            }
        }
        readingMetadata = false
    }


    if (!showDeviceLibrary) {
        Dialog(
            onDismissRequest = { if (!state.isUploading) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Publicar en Comunidad") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss, enabled = !state.isUploading) {
                                Icon(Icons.Default.Close, "Cerrar")
                            }
                        },
                    )
                },
            ) { padding ->
                if (state.submittedTrack != null) {
                    CommunityMusicSubmitted(onDismiss)
                    return@Scaffold
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "Se publica la canción completa o el recorte que elijas. ${CommunityMusicPolicy.DURATION_HELP}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (audioUri != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (detectedCover != null) {
                                    AsyncImage(model = detectedCover, contentDescription = "Portada que se publicará", modifier = Modifier.size(52.dp))
                                } else {
                                    Icon(Icons.Default.AudioFile, contentDescription = null)
                                }
                                Spacer(Modifier.size(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        audioName.ifBlank { "Pista seleccionada" },
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    if (durationMs > 0L) {
                                        Text(
                                            "Duración ${formatMomentMusicDuration(durationMs)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = { showDeviceLibrary = true },
                                    enabled = !state.isUploading && !readingLyrics,
                                ) { Text("Cambiar") }
                            }
                        }
                    } else {
                        Text(
                            "Selecciona una pista de tu biblioteca para continuar.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedTextField(value = title, onValueChange = { title = it.take(140) },
                        label = { Text("Título") }, singleLine = true, enabled = !state.isUploading,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = detectedArtist, onValueChange = { detectedArtist = it.take(140) },
                        label = { Text("Artista o autor") }, singleLine = true, enabled = !state.isUploading,
                        modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = acceptedRights,
                            onCheckedChange = { acceptedRights = it }, enabled = !state.isUploading)
                        Text("Soy titular de esta música y autorizo que se publique para su uso en Novvin.",
                            style = MaterialTheme.typography.bodySmall)
                    }

                    CommunityMusicLyricsSection(
                        lyrics = lyrics, durationMs = durationMs, reading = readingLyrics,
                        error = lyricsError,
                        onAdd = { showLyricsPicker = true },
                        onRemove = { lyrics = ""; lyricsError = null },
                        enabled = !state.isUploading && !readingMetadata && durationMs > 0,
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!readingMetadata && !CommunityMusicPolicy.acceptsDuration(durationMs)) {
                        Text("Elige Cambiar para seleccionar un recorte de entre 5 segundos y 4:30.", color = MaterialTheme.colorScheme.error)
                    }
                    if (state.isUploading) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Publicando… ${(state.progress * 100).toInt()}%")
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val selectedUri = audioUri ?: return@Button
                            onSubmit(
                                selectedUri,
                                detectedCover,
                                CommunityMusicUploadDraft(
                                    title = title.trim(),
                                    artist = detectedArtist.trim().ifBlank { "Autor de Novvin" },
                                    album = detectedAlbum.trim(),
                                    genre = "",
                                    mood = "",
                                    tags = emptyList(),
                                    description = "",
                                    lyrics = lyrics,
                                    rightsType = "ORIGINAL_OWNER",
                                    visibility = "PUBLIC_REUSABLE",
                                    allowReuse = true,
                                    allowRemix = false,
                                    explicit = false,
                                    instrumental = false,
                                    acceptedRights = acceptedRights,
                                ),
                            )
                        },
                        enabled = !state.isUploading && !readingMetadata && !readingLyrics && acceptedRights && audioUri != null && title.isNotBlank() && CommunityMusicPolicy.acceptsDuration(durationMs),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Publicar para la Comunidad")
                    }
                    Text(
                        "La pista aparecerá en Comunidad después de una revisión automática o de moderación.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
        }
    }
    if (showLyricsPicker) {
        conectate.com.presentation.documents.DocumentPickerSheet(
            onDismiss = { showLyricsPicker = false },
            allowedExtensions = setOf("lrc"), pickerTitle = "Letra sincronizada",
            onPicked = { uri ->
                showLyricsPicker = false
                val selectedAudio = audioUri
                val selectedStart = sourceStartMs
                val selectedDuration = durationMs
                readingLyrics = true
                scope.launch {
                    try {
                        val imported = withContext(Dispatchers.IO) {
                            val bytes = context.contentResolver.openInputStream(uri)?.use(::readBoundedMusicLyrics)
                                ?: throw IllegalArgumentException("No se pudo abrir la letra.")
                            importMusicLyrics(bytes, selectedDuration, selectedStart)
                        }
                        if (audioUri == selectedAudio) { lyrics = imported; lyricsError = null }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (audioUri == selectedAudio) lyricsError = when (error) {
                            is IllegalArgumentException -> error.message
                            else -> "No se pudo leer la letra. Revisa el acceso al archivo y vuelve a intentar."
                        }
                    } finally { readingLyrics = false }
                }
            },
        )
    }

}

@Composable
private fun CommunityMusicSubmitted(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text("Música publicada", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Ya está disponible para la Comunidad. También recibirás una notificación de confirmación.")
            Spacer(Modifier.height(18.dp))
            Button(onClick = onDismiss) { Text("Listo") }
        }
    }
}

/** Compact optional lyrics control, shared by the upload screen and its UI checks. */
@Composable
internal fun CommunityMusicLyricsSection(
    lyrics: String,
    durationMs: Long,
    reading: Boolean,
    error: String?,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (reading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (lyrics.isBlank()) {
                TextButton(onClick = onAdd, enabled = enabled && !reading,
                    modifier = Modifier.testTag("community_music_add_lyrics")) { Text("Añadir letras (.lrc) · opcional") }
                Text("Usa letras con tiempos reales de esta canción. No se generan automáticamente.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val cues = remember(lyrics, durationMs) { timedMusicLyrics(lyrics, durationMs) }
                Text("Letra sincronizada añadida · ${cues.count { it.text.isNotBlank() }} líneas",
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("community_music_lyrics_added"))
                Text(cues.firstOrNull { it.text.isNotBlank() }?.text.orEmpty(), maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                TextButton(onClick = onRemove, enabled = enabled && !reading) { Text("Quitar letra") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
