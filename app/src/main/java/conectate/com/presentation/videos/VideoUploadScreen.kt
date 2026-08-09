package conectate.com.presentation.videos

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil.compose.AsyncImage
import conectate.com.data.repository.VideoRepository
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@HiltViewModel
class VideoUploadViewModel @Inject constructor(
    private val videoRepository: VideoRepository
) : ViewModel() {

    private var ownedEditorExport: java.io.File? = null

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()

    private val _isPublic = MutableStateFlow(true)
    val isPublic: StateFlow<Boolean> = _isPublic.asStateFlow()

    private val _tags = MutableStateFlow("")
    val tags: StateFlow<String> = _tags.asStateFlow()

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    private val _thumbnailUri = MutableStateFlow<Uri?>(null)
    val thumbnailUri: StateFlow<Uri?> = _thumbnailUri.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress: StateFlow<Float> = _uploadProgress.asStateFlow()

    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError: StateFlow<String?> = _uploadError.asStateFlow()

    private val _videoDurationMs = MutableStateFlow(0L)
    val videoDurationMs: StateFlow<Long> = _videoDurationMs.asStateFlow()

    private val _requiresEditing = MutableStateFlow(false)
    val requiresEditing: StateFlow<Boolean> = _requiresEditing.asStateFlow()

    fun updateTitle(value: String) { _title.value = value }
    fun updateDescription(value: String) { _description.value = value }
    fun updateIsPublic(value: Boolean) { _isPublic.value = value }
    fun updateTags(value: String) { _tags.value = value }

    fun setVideoUri(uri: Uri, context: android.content.Context): Boolean {
        val info = readVideoInfo(uri, context)
        val durationMs = info.durationMs
        val decision = evaluateVideoPreparation(
            durationMs = durationMs,
            width = info.width,
            height = info.height,
            fps = info.fps,
            fileSizeBytes = info.fileSizeBytes
        )

        val nextOwnedExport = resolveOwnedEditorExportFile(context, uri)
        val previousOwnedExport = ownedEditorExport
        val isSameOwnedFile = runCatching {
            previousOwnedExport?.canonicalFile == nextOwnedExport?.canonicalFile
        }.getOrDefault(false)
        if (!isSameOwnedFile) {
            previousOwnedExport?.let { previous -> runCatching { previous.delete() } }
        }
        ownedEditorExport = nextOwnedExport
        _videoUri.value = uri
        _videoDurationMs.value = durationMs
        _requiresEditing.value = decision.requiresEditor
        _uploadError.value = when (decision.reason) {
            VideoPreparationReason.UNKNOWN_DURATION ->
                "No pudimos validar la duración. Ábrelo en el editor antes de publicar."
            VideoPreparationReason.TOO_LONG ->
                "El video dura ${formatDuration(durationMs)}. Recórtalo a un máximo de 45 segundos."
            VideoPreparationReason.HIGH_RESOLUTION ->
                "Novvin adaptará la resolución del video antes de publicarlo."
            VideoPreparationReason.HIGH_FRAME_RATE ->
                "Novvin adaptará la fluidez del video antes de publicarlo."
            VideoPreparationReason.READY -> null
        }
        return decision.requiresEditor
    }
    
    fun setThumbnailUri(uri: Uri?) { _thumbnailUri.value = uri } // Allow nullable

    private data class VideoPickInfo(
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Float = 0f,
        val fileSizeBytes: Long = -1L
    )

    private fun readVideoInfo(uri: Uri, context: android.content.Context): VideoPickInfo {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
                val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                    ?: 0
                val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                    ?: 0
                val fps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()
                    ?: 0f
                val fileSizeBytes = context.contentResolver
                    .openFileDescriptor(uri, "r")
                    ?.use { descriptor -> descriptor.statSize }
                    ?: -1L
                VideoPickInfo(
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    fps = fps,
                    fileSizeBytes = fileSizeBytes
                )
            } finally {
                runCatching { retriever.release() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            VideoPickInfo()
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
        return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    fun uploadVideo(onComplete: (Boolean) -> Unit) {
        val video = _videoUri.value
        val thumbnail = _thumbnailUri.value
        
        if (video == null || _title.value.isBlank()) {
            _uploadError.value = "Selecciona un video y escribe un título."
            return
        }
        if (_requiresEditing.value) {
            _uploadError.value = "Recorta o procesa el video en el editor antes de publicarlo."
            return
        }

        viewModelScope.launch {
            _isUploading.value = true
            _uploadProgress.value = 0f
            _uploadError.value = null

            try {
                // Parse tags
                val tagsList = _tags.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                val result = videoRepository.uploadVideo(
                    title = _title.value,
                    description = _description.value,
                    videoUri = video,
                    thumbnailUri = thumbnail,
                    isPublic = _isPublic.value,
                    tags = tagsList,
                    onUploadProgress = { progress -> _uploadProgress.value = progress }
                )
                
                result.fold(
                    onSuccess = {
                        _uploadProgress.value = 1.0f
                        _isUploading.value = false
                        cleanupOwnedEditorExport()
                        onComplete(true)
                    },
                    onFailure = {
                        _isUploading.value = false
                        _uploadError.value = it.toUserFacingMessage("No se pudo subir el video.")
                        onComplete(false)
                    }
                )
            } catch (e: Throwable) {
                _isUploading.value = false
                _uploadError.value = e.toUserFacingMessage("No se pudo subir el video.")
                onComplete(false)
            }
        }
    }

    fun resetState() {
        cleanupOwnedEditorExport()
        _title.value = ""
        _description.value = ""
        _isPublic.value = true
        _tags.value = ""
        _videoUri.value = null
        _videoDurationMs.value = 0L
        _requiresEditing.value = false
        _thumbnailUri.value = null
        _uploadError.value = null
        _uploadProgress.value = 0f
    }

    private fun cleanupOwnedEditorExport() {
        ownedEditorExport?.let { file -> runCatching { file.delete() } }
        ownedEditorExport = null
    }

    override fun onCleared() {
        if (!_isUploading.value) cleanupOwnedEditorExport()
        super.onCleared()
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun VideoUploadScreen(
    navController: NavController,
    viewModel: VideoUploadViewModel = hiltViewModel()
) {
    val title by viewModel.title.collectAsState()
    val description by viewModel.description.collectAsState()
    val isPublic by viewModel.isPublic.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val videoUri by viewModel.videoUri.collectAsState()
    val thumbnailUri by viewModel.thumbnailUri.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val uploadError by viewModel.uploadError.collectAsState()
    val videoDurationMs by viewModel.videoDurationMs.collectAsState()
    val requiresEditing by viewModel.requiresEditing.collectAsState()
    var thumbnailToCrop by remember { mutableStateOf<Uri?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewPlaying by remember(videoUri) { mutableStateOf(false) }
    var previewFailed by remember(videoUri) { mutableStateOf(false) }
    val previewPlayer = remember(videoUri) {
        videoUri?.let { uri ->
            runCatching {
                ExoPlayer.Builder(context.applicationContext).build().apply {
                    playWhenReady = false
                    repeatMode = Player.REPEAT_MODE_OFF
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                }
            }.getOrNull()
        }
    }

    DisposableEffect(previewPlayer, lifecycleOwner) {
        val player = previewPlayer
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) previewPlaying = false
            }

            override fun onPlayerError(error: PlaybackException) {
                previewPlaying = false
                previewFailed = true
                player?.pause()
            }
        }
        player?.addListener(listener)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                player?.pause()
                previewPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.removeListener(listener)
            player?.release()
        }
    }

    LaunchedEffect(uploadError) {
        uploadError?.takeIf { it.isNotBlank() }?.let(AppFeedbackBus::showError)
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val mustEdit = viewModel.setVideoUri(it, context)
            if (mustEdit) {
                navController.navigate(conectate.com.presentation.navigation.Screen.VideoEditor.createRoute(it))
            }
        }
    }

    val thumbnailPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> thumbnailToCrop = uri }

    thumbnailToCrop?.let { sourceUri ->
        conectate.com.presentation.components.SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Recortar portada del video",
            initialPreset = conectate.com.presentation.components.ImageCropPreset.PORTRAIT,
            onDismiss = { thumbnailToCrop = null },
            onCropped = { croppedUri ->
                viewModel.setThumbnailUri(croppedUri)
                thumbnailToCrop = null
            }
        )
    }

    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Handle result from Editor
    val uploadBackStackEntry by navController.currentBackStackEntryAsState()
    val editedVideoUriState = uploadBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow<String?>("editedVideo", null)
        ?.collectAsState()
    val editedVideoUri = editedVideoUriState?.value
    
    LaunchedEffect(editedVideoUri) {
        editedVideoUri?.let { uriString ->
            val uri = Uri.parse(uriString)
            viewModel.setVideoUri(uri, context)
            // Clear the handle so it doesn't trigger again
            uploadBackStackEntry?.savedStateHandle?.remove<String>("editedVideo")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subir Video") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Atrás")
                    }
                }
            )
        },
        snackbarHost = {
            conectate.com.presentation.common.KeyboardSafeSnackbarHost(snackbarHostState)
        }
    ) { padding ->
        if (isUploading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp).fillMaxWidth()
                ) {
                    Text("Subiendo video...", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = uploadProgress,
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${(uploadProgress * 100).toInt()}%")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Video Selector (Shows preview if selected)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.2f))
                        .clickable {
                            if (videoUri == null) videoPickerLauncher.launch("video/*")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (videoUri != null) {
                        if (previewPlayer != null && !previewFailed) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = previewPlayer
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                },
                                update = { playerView ->
                                    if (playerView.player !== previewPlayer) {
                                        playerView.player = previewPlayer
                                    }
                                    playerView.useController = false
                                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        if (previewPlayer.isPlaying) {
                                            previewPlayer.pause()
                                            previewPlaying = false
                                        } else {
                                            if (previewPlayer.playbackState == Player.STATE_ENDED) previewPlayer.seekTo(0)
                                            previewPlayer.play()
                                            previewPlaying = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (!previewPlaying) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = "Reproducir vista previa",
                                        tint = Color.White,
                                        modifier = Modifier.size(56.dp)
                                    )
                                }
                            }
                        } else {
                            AsyncImage(
                                model = videoUri,
                                contentDescription = "Miniatura del video",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.VideoCall, null, modifier = Modifier.size(48.dp))
                            Text("Seleccionar Video (Obligatorio)")
                        }
                    }
                }

                if (videoUri != null) {
                    if (requiresEditing) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ContentCut, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = if (videoDurationMs > 0L) {
                                        if (videoDurationMs > MAX_VIDEO_UPLOAD_DURATION_MS) {
                                            "Selecciona el fragmento de 45 segundos que quieres publicar."
                                        } else {
                                            "Prepara el video para publicarlo en calidad compatible."
                                        }
                                    } else {
                                        "Procesa el video en el editor para poder publicarlo."
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            navController.navigate(conectate.com.presentation.navigation.Screen.VideoEditor.createRoute(videoUri!!))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (requiresEditing) "Preparar video" else "Editar video")
                    }

                    TextButton(
                        onClick = { videoPickerLauncher.launch("video/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cambiar video")
                    }
                }

                // Thumbnail Selector (Optional)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.2f))
                        .clickable { thumbnailPickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnailUri != null) {
                         AsyncImage(
                            model = thumbnailUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Remove button
                        IconButton(
                            onClick = { viewModel.setThumbnailUri(null) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        ) {
                             Icon(Icons.Default.Close, contentDescription = "Quitar", tint = Color.White)
                        }

                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(32.dp))
                            Text("Seleccionar Miniatura (Opcional)")
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { viewModel.updateTitle(it) },
                    label = { Text("Título") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { viewModel.updateDescription(it) },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                
                 OutlinedTextField(
                    value = tags,
                    onValueChange = { viewModel.updateTags(it) },
                    label = { Text("Hashtags (separados por coma)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ej: fyp, viral, musica") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Público")
                    Switch(
                        checked = isPublic,
                        onCheckedChange = { viewModel.updateIsPublic(it) }
                    )
                }

                if (uploadError != null) {
                    Text(uploadError!!, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    onClick = {
                        viewModel.uploadVideo { success ->
                            if (success) {
                                viewModel.resetState() 
                                AppFeedbackBus.showSuccess("Video subido exitosamente")
                                
                                // Navigate to Home with Videos tab selected
                                val targetRoute = conectate.com.presentation.navigation.Screen.Home.createRoute(
                                    startTab = conectate.com.presentation.navigation.Screen.Videos.route
                                )
                                
                                try {
                                    navController.navigate(targetRoute) {
                                        // Pop up to the root/login to ensure we clear the stack if needed
                                        // But mainly we want to clear the previous Home instance so it reloads with the new tab
                                        popUpTo(conectate.com.presentation.navigation.Screen.Home.route) { 
                                            inclusive = true 
                                        }
                                        launchSingleTop = true
                                    }
                                } catch (e: Exception) {
                                    // Fallback
                                    navController.navigate("home")
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // Validation: Only Video and Title required now
                    enabled = videoUri != null && title.isNotBlank() && !requiresEditing
                ) {
                    Text("Publicar Video")
                }
            }
        }
    }
}
