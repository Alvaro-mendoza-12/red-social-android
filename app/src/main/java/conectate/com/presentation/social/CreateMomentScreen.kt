package conectate.com.presentation.social

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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.ImageCropPreset
import conectate.com.presentation.components.SimpleImageCropDialog
import java.util.Locale

private const val MAX_MOMENT_VIDEO_DURATION_MS = 25_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun CreateMomentScreen(
    navController: NavController,
    viewModel: CreateMomentViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val galleryMedia by viewModel.galleryMedia.collectAsState()
    val trimmingState by viewModel.trimmingState.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()

    // UI States: GALLERY, TRIMMER, EDITOR
    var currentStep by remember { mutableStateOf("GALLERY") }
    var selectedMedia by remember { mutableStateOf<GalleryMedia?>(null) }
    var finalUri by remember { mutableStateOf<Uri?>(null) }
    var finalMediaType by remember { mutableStateOf("image") }
    var pendingMomentCropUri by remember { mutableStateOf<Uri?>(null) }
    
    // Editor State
    var durationHours by remember { mutableStateOf(24f) }
    var visibility by remember { mutableStateOf("all") }
    var caption by remember { mutableStateOf("") }

    // Include partial-photo access permission on Android 14+.
    val permissionsToRequest = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                android.Manifest.permission.CAMERA
            )
        }
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> {
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.CAMERA
            )
        }
        else -> {
            listOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.CAMERA
            )
        }
    }

    val permissionsState = com.google.accompanist.permissions.rememberMultiplePermissionsState(
        permissions = permissionsToRequest
    )

    LaunchedEffect(Unit) {
        permissionsState.launchMultiplePermissionRequest()
    }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.loadGalleryMedia(context)
        } else {
             // Try to load anyway if we have some permissions, or just log
             android.util.Log.d("CreateMoment", "Permissions not all granted: ${permissionsState.permissions.map { it.permission to it.status }}")
        }
    }

    // Camera Launchers
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempCameraUri != null) {
            pendingMomentCropUri = tempCameraUri
        }
    }

    val recordVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success && tempCameraUri != null) {
            finalUri = tempCameraUri
            finalMediaType = "video"
            currentStep = "EDITOR"
        }
    }


    var showCameraOptions by remember { mutableStateOf(false) }

    pendingMomentCropUri?.let { sourceUri ->
        SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Recortar momento",
            presets = listOf(
                ImageCropPreset.ORIGINAL,
                ImageCropPreset.SQUARE,
                ImageCropPreset.PORTRAIT,
                ImageCropPreset.WIDE
            ),
            initialPreset = ImageCropPreset.PORTRAIT,
            onDismiss = { pendingMomentCropUri = null },
            onCropped = { croppedUri ->
                finalUri = croppedUri
                finalMediaType = "image"
                pendingMomentCropUri = null
                currentStep = "EDITOR"
            }
        )
    }

    if (showCameraOptions) {
        AlertDialog(
            onDismissRequest = { showCameraOptions = false },
            title = { Text("Cámara") },
            text = { Text("¿Qué deseas capturar?") },
            confirmButton = {
                TextButton(onClick = {
                    showCameraOptions = false
                    val uri = createMomentCameraUri(context, ".mp4")
                    tempCameraUri = uri
                    recordVideoLauncher.launch(uri)
                }) {
                    Text("Grabar Video")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCameraOptions = false
                    val uri = createMomentCameraUri(context, ".jpg")
                    tempCameraUri = uri
                    takePictureLauncher.launch(uri)
                }) {
                    Text("Tomar Foto")
                }
            }
        )
    }

    // Main Content
    Scaffold(
        topBar = {
            if (currentStep != "EDITOR") {
                TopAppBar(
                    title = { Text(if (currentStep == "TRIMMER") "Recortar Video" else "Nuevo Momento") },
                    navigationIcon = {
                        IconButton(
                            enabled = !isUploading,
                            onClick = {
                                if (currentStep == "GALLERY") navController.popBackStack()
                                else currentStep = "GALLERY"
                            }
                        ) {
                            Icon(Icons.Default.Close, "Cerrar")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (currentStep) {
                "GALLERY" -> {
                    GalleryGridView(
                        mediaList = galleryMedia,
                        onMediaSelected = { media ->
                            selectedMedia = media
                            if (media.type == MediaType.VIDEO && media.duration > MAX_MOMENT_VIDEO_DURATION_MS + 1_000L) {
                                currentStep = "TRIMMER"
                            } else if (media.type == MediaType.IMAGE) {
                                pendingMomentCropUri = media.uri
                            } else {
                                finalUri = media.uri
                                finalMediaType = "video"
                                currentStep = "EDITOR"
                            }
                        },
                        onCameraClick = {
                            showCameraOptions = true
                        }
                    )
                }
                "TRIMMER" -> {
                    selectedMedia?.let { media ->
                        VideoTrimmerView(
                            media = media,
                            trimmingState = trimmingState,
                            onTrim = { startMs, endMs ->
                                viewModel.trimVideo(context, media.uri, startMs, endMs, 
                                    onSuccess = { trimmedUri ->
                                        finalUri = trimmedUri
                                        finalMediaType = "video"
                                        currentStep = "EDITOR"
                                    },
                                    onError = { 
                                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        )
                    }
                }
                "EDITOR" -> {
                    if (finalUri != null) {
                        MomentEditorView(
                            uri = finalUri!!,
                            isVideo = finalMediaType == "video",
                            caption = caption,
                            onCaptionChange = { caption = it },
                            durationHours = durationHours,
                            onDurationChange = { durationHours = it },
                            visibility = visibility,
                            onVisibilityChange = { visibility = it },
                            isUploading = isUploading,
                            onBack = { currentStep = "GALLERY" },
                            onPost = {
                                viewModel.uploadMoment(
                                    uri = finalUri!!,
                                    mediaType = finalMediaType,
                                    durationHours = durationHours.toInt(),
                                    durationType = "${durationHours.toInt()}h",
                                    visibility = visibility,
                                    visibilityType = "public",
                                    excludedIds = emptyList(),
                                    caption = caption,
                                    onSuccess = {
                                        android.widget.Toast.makeText(context, "Momento compartido!", android.widget.Toast.LENGTH_SHORT).show()
                                        navController.popBackStack()
                                    },
                                    onError = { error ->
                                        android.widget.Toast.makeText(
                                            context,
                                            "Error al publicar: $error",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                )
                            }
                        )
                    }
                }
            }
            if (isUploading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(onClick = {}),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
fun GalleryGridView(
    mediaList: List<GalleryMedia>,
    onMediaSelected: (GalleryMedia) -> Unit,
    onCameraClick: () -> Unit
) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            // Camera Button
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { onCameraClick() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CameraAlt, "Cámara", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Cámara", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        
        items(mediaList.size) { index ->
            val media = mediaList[index]
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clickable { onMediaSelected(media) }
            ) {
                AsyncImage(
                    model = media.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (media.type == MediaType.VIDEO) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayCircle, null, tint = Color.White)
                    }
                    Text(
                        text = formatDuration(media.duration),
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun VideoTrimmerView(
    media: GalleryMedia,
    trimmingState: TrimmingState,
    onTrim: (Long, Long) -> Unit
) {
    var startPercentage by remember { mutableStateOf(0f) }
    val videoDurationMs = media.duration.coerceAtLeast(1)
    
    val maxSegmentPercentage = (MAX_MOMENT_VIDEO_DURATION_MS.toFloat() / videoDurationMs).coerceAtMost(1f)
    
    // Determine the actual selected range
    // Simplified UI: A slider selects the start; the end is capped at the moment limit.
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Selecciona hasta 25 segundos", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(24.dp))
        
        // Preview with Video Player
        if (trimmingState !is TrimmingState.Trimming) {
            val startMs = (startPercentage * videoDurationMs).toLong()
            val endMs = (startMs + MAX_MOMENT_VIDEO_DURATION_MS).coerceAtMost(videoDurationMs)
            
            MomentVideoPlayer(
                uri = media.uri,
                startMs = startMs,
                endMs = endMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 120.dp, max = 300.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 120.dp, max = 300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text("Desliza para elegir el segmento")
        Slider(
            value = startPercentage,
            onValueChange = { startPercentage = it },
            valueRange = 0f..(1f - maxSegmentPercentage)
        )
        
        val startMs = (startPercentage * videoDurationMs).toLong()
        val endMs = (startMs + MAX_MOMENT_VIDEO_DURATION_MS).coerceAtMost(videoDurationMs)
        
        Text("${formatDuration(startMs)} - ${formatDuration(endMs)}")
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (trimmingState is TrimmingState.Trimming) {
            CircularProgressIndicator()
            Text("Procesando...")
        } else {
            Button(
                onClick = { onTrim(startMs, endMs) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continuar")
            }
        }
    }
}

@Composable
fun MomentEditorView(
    uri: Uri,
    isVideo: Boolean,
    caption: String,
    onCaptionChange: (String) -> Unit,
    durationHours: Float,
    onDurationChange: (Float) -> Unit,
    visibility: String,
    onVisibilityChange: (String) -> Unit,
    isUploading: Boolean,
    onBack: () -> Unit,
    onPost: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .imePadding()
    ) {
        // Media Preview
        if (isVideo) {
            MomentVideoPlayer(
                uri = uri,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        
        // Top Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
            // Visibility Toggle
            Button(
                onClick = { 
                    // Cycle visibility for simplicity or show dialog
                    val next = when(visibility) {
                        "all" -> "friends"
                        "friends" -> "followers"
                        else -> "all"
                    }
                    onVisibilityChange(next)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Visibility, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when(visibility) {
                        "all" -> "Todos"
                        "friends" -> "Amigos"
                        else -> "Seguidores"
                    }
                )
            }
        }
        
        // Bottom Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))
                ))
                .padding(16.dp)
        ) {
            // Caption
            OutlinedTextField(
                value = caption,
                onValueChange = onCaptionChange,
                placeholder = { Text("Añade un comentario...", color = Color.White.copy(alpha = 0.7f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Black.copy(alpha = 0.62f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.52f),
                    focusedBorderColor = Color.White.copy(alpha = 0.9f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.38f),
                    cursorColor = Color.White,
                    focusedPlaceholderColor = Color.White.copy(alpha = 0.7f),
                    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.7f)
                ),
                minLines = 1,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                 // Duration Control
                Column(modifier = Modifier.weight(1f)) {
                     Text("Duración: ${durationHours.toInt()}h", color = Color.White, fontSize = 12.sp)
                     Slider(
                         value = durationHours,
                         onValueChange = onDurationChange,
                         valueRange = 5f..24f,
                         steps = 18
                     )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                FloatingActionButton(
                    onClick = {
                        if (!isUploading) {
                            onPost()
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Default.Send, "Publicar")
                    }
                }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun MomentVideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    startMs: Long = 0,
    endMs: Long = -1
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }

    // Handle range/trimming preview
    LaunchedEffect(startMs, endMs) {
        if (endMs != -1L) {
            exoPlayer.seekTo(startMs)
            // Ideally we'd use a ClippingMediaSource or set a listener to loop precisely 
            // but seeking to startMs is a good enough preview for now.
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = modifier
    )
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}

private fun createMomentCameraUri(context: android.content.Context, extension: String): Uri {
    val directory = context.externalCacheDir ?: context.cacheDir
    val tmpFile = java.io.File.createTempFile("moment_camera_", extension, directory)
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        tmpFile
    )
}
