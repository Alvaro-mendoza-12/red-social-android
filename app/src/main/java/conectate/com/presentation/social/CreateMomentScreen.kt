package conectate.com.presentation.social

import conectate.com.core.media.ShareableMediaFileStore

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.ImageCropPreset
import conectate.com.presentation.components.SimpleImageCropDialog
import conectate.com.presentation.components.DeviceAudioPickerDialog
import conectate.com.presentation.components.rememberReliableBottomSafePadding
import conectate.com.data.model.User
import conectate.com.util.UbuntuMedia
import java.util.Locale
import androidx.compose.material.icons.automirrored.filled.Send

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
    val audienceUsers by viewModel.audienceUsers.collectAsState()
    val isSearchingAudience by viewModel.isSearchingAudience.collectAsState()
    val musicCatalogState by viewModel.musicCatalogState.collectAsState()
    val communityMusicUploadState by viewModel.communityMusicUploadState.collectAsState()
    var showCommunityMusicUpload by remember { mutableStateOf(false) }
    var showDeviceMusicPicker by remember { mutableStateOf(false) }
    var pendingDeviceAudioUri by remember { mutableStateOf<Uri?>(null) }

    // UI States: GALLERY, TRIMMER, EDITOR
    var currentStep by rememberSaveable { mutableStateOf("GALLERY") }
    var selectedMedia by remember { mutableStateOf<GalleryMedia?>(null) }
    var finalUriValue by rememberSaveable { mutableStateOf<String?>(null) }
    val finalUri = remember(finalUriValue) { finalUriValue?.let(Uri::parse) }
    var finalMediaType by rememberSaveable { mutableStateOf("image") }
    var finalMediaDurationMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingMomentCropUriValue by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingMomentCropUri = remember(pendingMomentCropUriValue) {
        pendingMomentCropUriValue?.let(Uri::parse)
    }
    
    // Editor State
    var durationHours by remember { mutableStateOf(24f) }
    var audience by remember { mutableStateOf(MomentAudienceOption.EVERYONE) }
    var selectedAudienceUsers by remember { mutableStateOf<List<User>>(emptyList()) }
    var allowReplies by remember { mutableStateOf(true) }
    var caption by remember { mutableStateOf("") }
    var overlayText by remember { mutableStateOf("") }
    var overlayColor by remember { mutableStateOf("#FFFFFFFF") }
    var overlayFont by remember { mutableStateOf("sans") }
    var overlayScale by remember { mutableStateOf(1f) }
    var overlayX by remember { mutableStateOf(0.5f) }
    var overlayY by remember { mutableStateOf(0.38f) }
    var musicSelection by remember { mutableStateOf<MomentMusicSelection?>(null) }

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
    // External camera apps may recreate this Activity. Keep the destination URI
    // in saved state so TakePicture/CaptureVideo can still resolve their result.
    var tempCameraUriValue by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraScope = rememberCoroutineScope()
    
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capturedUri = tempCameraUriValue?.let(Uri::parse)
        tempCameraUriValue = null
        if (success && capturedUri != null) {
            // Some camera apps return before the external-cache FileProvider
            // stream is stable. Copy it into our private cache first so the
            // crop editor never receives a stale/half-written URI (which was
            // causing the editor to close and return to the gallery).
            cameraScope.launch {
                val stableUri = withContext(Dispatchers.IO) {
                    stabilizeCapturedCameraUri(context, capturedUri, ".jpg")
                }
                if (stableUri != null) {
                    pendingMomentCropUriValue = stableUri.toString()
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "No se pudo leer la foto capturada. Inténtalo otra vez.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    val recordVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val capturedUri = tempCameraUriValue?.let(Uri::parse)
        tempCameraUriValue = null
        if (success && capturedUri != null) {
            cameraScope.launch {
                val recordedUri = withContext(Dispatchers.IO) {
                    stabilizeCapturedCameraUri(context, capturedUri, ".mp4")
                }
                if (recordedUri != null) {
                    val recordedDuration = resolveMomentMediaDuration(context, recordedUri).coerceAtLeast(1_000L)
                    selectedMedia = GalleryMedia(
                        id = System.currentTimeMillis(),
                        uri = recordedUri,
                        type = MediaType.VIDEO,
                        duration = recordedDuration,
                        dateAdded = System.currentTimeMillis() / 1_000L,
                    )
                    currentStep = "TRIMMER"
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "No se pudo leer el video capturado. Inténtalo otra vez.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
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
            onDismiss = { pendingMomentCropUriValue = null },
            onCropped = { croppedUri ->
                finalUriValue = croppedUri.toString()
                finalMediaType = "image"
                pendingMomentCropUriValue = null
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
                    tempCameraUriValue = uri.toString()
                    recordVideoLauncher.launch(uri)
                }) {
                    Text("Grabar Video")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCameraOptions = false
                    val uri = createMomentCameraUri(context, ".jpg")
                    tempCameraUriValue = uri.toString()
                    takePictureLauncher.launch(uri)
                }) {
                    Text("Tomar Foto")
                }
            }
        )
    }

    val deviceAudioFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingDeviceAudioUri = uri
    }

    if (showCommunityMusicUpload) {
        CommunityMusicUploadDialog(
            state = communityMusicUploadState,
            onSubmit = viewModel::uploadCommunityMusic,
            onDismiss = {
                if (!communityMusicUploadState.isUploading) {
                    showCommunityMusicUpload = false
                    viewModel.clearCommunityMusicUploadState()
                }
            },
        )
    }

    if (showDeviceMusicPicker) {
        DeviceAudioPickerDialog(
            initialUri = pendingDeviceAudioUri,
            title = "Mi música",
            confirmLabel = "Usar en mi Momento",
            maxClipDurationMs = 60_000L,
            defaultClipDurationMs = if (finalMediaType == "video") {
                (finalMediaDurationMs ?: 6_000L).coerceIn(1_000L, 60_000L)
            } else 6_000L,
            allowFullTrack = false,
            defaultToFullTrack = false,
            catalogTracks = emptyList(),
            enableOpenCatalog = false,
            onBrowseFiles = { deviceAudioFileLauncher.launch(arrayOf("audio/*")) },
            onDismiss = {
                showDeviceMusicPicker = false
                pendingDeviceAudioUri = null
            },
            onClipReady = { clip ->
                musicSelection = clip.toMomentMusicSelection().let { selected ->
                    musicSelection?.let { previous ->
                        selected.copy(
                            musicVolume = previous.musicVolume,
                            originalAudioVolume = previous.originalAudioVolume,
                            duckOriginalAudio = previous.duckOriginalAudio,
                        )
                    } ?: selected
                }
                showDeviceMusicPicker = false
                pendingDeviceAudioUri = null
            },
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
                            if (media.type == MediaType.VIDEO) {
                                currentStep = "TRIMMER"
                            } else if (media.type == MediaType.IMAGE) {
                                pendingMomentCropUriValue = media.uri.toString()
                            } else {
                                finalUriValue = media.uri.toString()
                                finalMediaType = "video"
                                finalMediaDurationMs = media.duration
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
                                        finalUriValue = trimmedUri.toString()
                                        finalMediaType = "video"
                                        finalMediaDurationMs = (endMs - startMs).coerceAtLeast(1_000L)
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
                            state = MomentEditorUiState(
                                uri = finalUri!!,
                                isVideo = finalMediaType == "video",
                                caption = caption,
                                durationHours = durationHours,
                                audience = audience,
                                selectedAudienceUsers = selectedAudienceUsers,
                                audienceSearchResults = audienceUsers,
                                isSearchingAudience = isSearchingAudience,
                                allowReplies = allowReplies,
                                overlayText = overlayText,
                                overlayColor = overlayColor,
                                overlayFont = overlayFont,
                                overlayScale = overlayScale,
                                overlayX = overlayX,
                                overlayY = overlayY,
                                musicSelection = musicSelection,
                                musicCatalogState = musicCatalogState,
                                 contentDurationMs = finalMediaDurationMs,
                                 isUploading = isUploading,
                                 suspendMusicPlayback = showDeviceMusicPicker || showCommunityMusicUpload,
                            ),
                            actions = MomentEditorActions(
                                onCaptionChange = { caption = it },
                                onDurationChange = { durationHours = it },
                                onAudienceChange = {
                                    audience = it
                                    if (!it.requiresPeople) selectedAudienceUsers = emptyList()
                                },
                                onSearchAudience = viewModel::searchAudienceUsers,
                                onToggleAudienceUser = { user ->
                                    selectedAudienceUsers = if (selectedAudienceUsers.any { it.id == user.id }) {
                                        selectedAudienceUsers.filterNot { it.id == user.id }
                                    } else {
                                        selectedAudienceUsers + user
                                    }
                                },
                                onAllowRepliesChange = { allowReplies = it },
                                onOverlayTextChange = { overlayText = it.take(120) },
                                onOverlayColorChange = { overlayColor = it },
                                onOverlayFontChange = { overlayFont = it },
                                onOverlayScaleChange = { overlayScale = it.coerceIn(0.7f, 2.1f) },
                                onOverlayPositionChange = { x, y ->
                                    overlayX = x.coerceIn(0.04f, 0.96f)
                                    overlayY = y.coerceIn(0.06f, 0.86f)
                                },
                                onSearchMusic = viewModel::searchApprovedMusic,
                                onLoadMoreMusic = viewModel::loadMoreApprovedMusic,
                                onToggleMusicFavorite = viewModel::toggleMusicFavorite,
                                onReportMusic = viewModel::reportMusic,
                                onNotInterestedMusic = viewModel::hideMusicRecommendation,
                                onMusicPreviewStarted = viewModel::recordMusicPreviewStarted,
                                onOpenCommunityMusicUpload = { showCommunityMusicUpload = true },
                                onOpenDeviceMusic = { showDeviceMusicPicker = true },
                                onMusicSelected = { musicSelection = it },
                                onRemoveMusic = { musicSelection = null },
                                onBack = { currentStep = "GALLERY" },
                                onPost = {
                                viewModel.uploadMoment(
                                    context = context,
                                    uri = finalUri!!,
                                    mediaType = finalMediaType,
                                    durationHours = durationHours.toInt(),
                                    durationType = "${durationHours.toInt()}h",
                                    visibility = audience.storageValue,
                                    visibilityType = if (audience == MomentAudienceOption.ONLY_ME) "private" else "public",
                                    excludedIds = if (audience == MomentAudienceOption.EXCEPT_SELECTED) selectedAudienceUsers.map { it.id } else emptyList(),
                                    audienceIds = if (audience == MomentAudienceOption.ONLY_SELECTED) selectedAudienceUsers.map { it.id } else emptyList(),
                                    caption = caption,
                                    allowReplies = allowReplies,
                                    overlayText = overlayText.takeIf { it.isNotBlank() },
                                    overlayColor = overlayColor,
                                    overlayFont = overlayFont,
                                    overlayScale = overlayScale,
                                    overlayX = overlayX,
                                    overlayY = overlayY,
                                    musicUri = musicSelection?.localUri,
                                    // A device track is uploaded to the
                                    // owner's community-music bucket before
                                    // publishing. Keep it explicitly local in
                                    // the request even if an older picker
                                    // returned an incomplete catalog source.
                                    musicRemoteUrl = musicSelection
                                        ?.takeIf { it.localUri == null }
                                        ?.track
                                        ?.audioUrl,
                                    musicTitle = musicSelection?.track?.title,
                                    musicId = musicSelection?.let { selection ->
                                        selection.localUri?.let { uri ->
                                            selection.track.id.takeIf { it.startsWith("device:") }
                                                ?: localMomentMusicId(uri)
                                        } ?: selection.track.id
                                    },
                                    musicArtist = musicSelection?.track?.artist,
                                    musicSource = musicSelection?.let { selection ->
                                        if (selection.localUri != null) NOVVIN_LOCAL_MOMENT_MUSIC_SOURCE
                                        else selection.track.source
                                    },
                                    musicSourceUrl = musicSelection
                                        ?.takeIf { it.localUri == null }
                                        ?.track
                                        ?.sourcePageUrl,
                                    musicLicense = musicSelection?.track?.license,
                                    musicLicenseUrl = musicSelection?.track?.licenseUrl,
                                    musicStartMs = musicSelection?.startMs ?: 0L,
                                    musicDurationMs = when {
                                        musicSelection == null -> 6_000L
                                        finalMediaType.equals("image", ignoreCase = true) -> 6_000L
                                        else -> (musicSelection!!.endMs - musicSelection!!.startMs)
                                            .coerceAtLeast(1_000L)
                                    },
                                    musicVolume = musicSelection?.musicVolume ?: 1f,
                                    originalAudioVolume = musicSelection?.originalAudioVolume ?: 1f,
                                    musicFadeInMs = musicSelection?.fadeInMs ?: 0L,
                                    musicFadeOutMs = musicSelection?.fadeOutMs ?: 0L,
                                    duckOriginalAudio = musicSelection?.duckOriginalAudio ?: false,
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
                                },
                            )
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
    val videoDurationMs = media.duration.coerceAtLeast(1_000L)
    var selectedRange by remember(media.uri, videoDurationMs) {
        mutableStateOf(0f..minOf(videoDurationMs, MAX_MOMENT_VIDEO_DURATION_MS).toFloat())
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Revisa y recorta tu video", style = MaterialTheme.typography.titleMedium)
        Text(
            "Reproduce, pausa y elige un fragmento de hasta 25 segundos antes de publicarlo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        // Preview with Video Player
        if (trimmingState !is TrimmingState.Trimming) {
            val startMs = selectedRange.start.toLong()
            val endMs = selectedRange.endInclusive.toLong()
            
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
        
        Text("Fragmento seleccionado")
        RangeSlider(
            value = selectedRange,
            onValueChange = { candidate ->
                var start = candidate.start.coerceIn(0f, videoDurationMs.toFloat())
                var end = candidate.endInclusive.coerceIn(start, videoDurationMs.toFloat())
                if (end - start > MAX_MOMENT_VIDEO_DURATION_MS) {
                    end = start + MAX_MOMENT_VIDEO_DURATION_MS
                }
                if (end - start < 1_000f) {
                    if (start + 1_000f <= videoDurationMs) end = start + 1_000f
                    else start = (end - 1_000f).coerceAtLeast(0f)
                }
                selectedRange = start..end
            },
            valueRange = 0f..videoDurationMs.toFloat(),
        )
        
        val startMs = selectedRange.start.toLong()
        val endMs = selectedRange.endInclusive.toLong()
        
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

private data class MomentEditorUiState(
    val uri: Uri,
    val isVideo: Boolean,
    val caption: String,
    val durationHours: Float,
    val audience: MomentAudienceOption,
    val selectedAudienceUsers: List<User>,
    val audienceSearchResults: List<User>,
    val isSearchingAudience: Boolean,
    val allowReplies: Boolean,
    val overlayText: String,
    val overlayColor: String,
    val overlayFont: String,
    val overlayScale: Float,
    val overlayX: Float,
    val overlayY: Float,
    val musicSelection: MomentMusicSelection?,
    val musicCatalogState: MusicCatalogUiState,
    val contentDurationMs: Long?,
    val isUploading: Boolean,
    val suspendMusicPlayback: Boolean,
)

private class MomentEditorActions(
    val onCaptionChange: (String) -> Unit,
    val onDurationChange: (Float) -> Unit,
    val onAudienceChange: (MomentAudienceOption) -> Unit,
    val onSearchAudience: (String) -> Unit,
    val onToggleAudienceUser: (User) -> Unit,
    val onAllowRepliesChange: (Boolean) -> Unit,
    val onOverlayTextChange: (String) -> Unit,
    val onOverlayColorChange: (String) -> Unit,
    val onOverlayFontChange: (String) -> Unit,
    val onOverlayScaleChange: (Float) -> Unit,
    val onOverlayPositionChange: (Float, Float) -> Unit,
    val onSearchMusic: (String, String, String) -> Unit,
    val onLoadMoreMusic: () -> Unit,
    val onToggleMusicFavorite: (conectate.com.data.music.ApprovedMomentMusic) -> Unit,
    val onReportMusic: (conectate.com.data.music.ApprovedMomentMusic) -> Unit,
    val onNotInterestedMusic: (conectate.com.data.music.ApprovedMomentMusic) -> Unit,
    val onMusicPreviewStarted: (conectate.com.data.music.ApprovedMomentMusic) -> Unit,
    val onOpenCommunityMusicUpload: () -> Unit,
    val onOpenDeviceMusic: () -> Unit,
    val onMusicSelected: (MomentMusicSelection) -> Unit,
    val onRemoveMusic: () -> Unit,
    val onBack: () -> Unit,
    val onPost: () -> Unit,
)

@Composable
private fun MomentEditorView(
    state: MomentEditorUiState,
    actions: MomentEditorActions,
) {
    val uri = state.uri
    val isVideo = state.isVideo
    val caption = state.caption
    val durationHours = state.durationHours
    val audience = state.audience
    val selectedAudienceUsers = state.selectedAudienceUsers
    val audienceSearchResults = state.audienceSearchResults
    val isSearchingAudience = state.isSearchingAudience
    val allowReplies = state.allowReplies
    val overlayText = state.overlayText
    val overlayColor = state.overlayColor
    val overlayFont = state.overlayFont
    val overlayScale = state.overlayScale
    val overlayX = state.overlayX
    val overlayY = state.overlayY
    val musicSelection = state.musicSelection
    val musicCatalogState = state.musicCatalogState
    val contentDurationMs = state.contentDurationMs
    val isUploading = state.isUploading
    val suspendMusicPlayback = state.suspendMusicPlayback
    val onCaptionChange = actions.onCaptionChange
    val onDurationChange = actions.onDurationChange
    val onAudienceChange = actions.onAudienceChange
    val onSearchAudience = actions.onSearchAudience
    val onToggleAudienceUser = actions.onToggleAudienceUser
    val onAllowRepliesChange = actions.onAllowRepliesChange
    val onOverlayTextChange = actions.onOverlayTextChange
    val onOverlayColorChange = actions.onOverlayColorChange
    val onOverlayFontChange = actions.onOverlayFontChange
    val onOverlayScaleChange = actions.onOverlayScaleChange
    val onOverlayPositionChange = actions.onOverlayPositionChange
    val onSearchMusic = actions.onSearchMusic
    val onLoadMoreMusic = actions.onLoadMoreMusic
    val onToggleMusicFavorite = actions.onToggleMusicFavorite
    val onReportMusic = actions.onReportMusic
    val onNotInterestedMusic = actions.onNotInterestedMusic
    val onMusicPreviewStarted = actions.onMusicPreviewStarted
    val onOpenCommunityMusicUpload = actions.onOpenCommunityMusicUpload
    val onOpenDeviceMusic = actions.onOpenDeviceMusic
    val onMusicSelected = actions.onMusicSelected
    val onRemoveMusic = actions.onRemoveMusic
    val onBack = actions.onBack
    val onPost = actions.onPost
    val reliableBottomPadding = rememberReliableBottomSafePadding()
    var showAudienceDialog by remember { mutableStateOf(false) }
    var showPeopleDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showMusicDialog by remember { mutableStateOf(false) }
    var showAudioMixDialog by remember { mutableStateOf(false) }
    var imageScale by remember(uri) { mutableStateOf(1f) }
    var imageOffset by remember(uri) { mutableStateOf(Offset.Zero) }
    var imageSize by remember(uri) { mutableStateOf(IntSize.Zero) }
    val imageTransformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val oldScale = imageScale
        val newScale = (oldScale * zoomChange).coerceIn(1f, 4f)
        val appliedZoom = newScale / oldScale.coerceAtLeast(0.0001f)
        val imageCenter = Offset(imageSize.width / 2f, imageSize.height / 2f)
        val effectiveCentroid = centroid.takeIf { it.isSpecified } ?: imageCenter

        imageOffset = if (newScale <= 1.01f) {
            Offset.Zero
        } else {
            imageOffset * appliedZoom +
                (effectiveCentroid - imageCenter) * (1f - appliedZoom) +
                panChange
        }
        imageScale = newScale
    }
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
                volume = musicSelection?.let {
                    it.originalAudioVolume * if (it.duckOriginalAudio) 0.35f else 1f
                } ?: 1f,
                playWhenReady = !showMusicDialog && !suspendMusicPlayback,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = uri,
                contentDescription = "Vista previa del momento; pellizca para ampliar",
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { imageSize = it }
                    .graphicsLayer(
                        scaleX = imageScale,
                        scaleY = imageScale,
                        translationX = imageOffset.x,
                        translationY = imageOffset.y
                    )
                    .transformable(imageTransformState),
                contentScale = ContentScale.Fit
            )
        }

        if (!isVideo && overlayText.isNotBlank()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                val currentOverlayX by rememberUpdatedState(overlayX)
                val currentOverlayY by rememberUpdatedState(overlayY)
                Text(
                    text = overlayText,
                    color = momentOverlayColor(overlayColor),
                    fontFamily = momentOverlayFont(overlayFont),
                    fontWeight = FontWeight.Bold,
                    fontSize = (24f * overlayScale).sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = maxWidth * overlayX - 76.dp,
                            y = maxHeight * overlayY - 26.dp
                        )
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.34f))
                        .pointerInput(widthPx, heightPx) {
                            var dragX = currentOverlayX
                            var dragY = currentOverlayY
                            detectDragGestures(
                                onDragStart = {
                                    dragX = currentOverlayX
                                    dragY = currentOverlayY
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragX = (dragX + dragAmount.x / widthPx).coerceIn(0.04f, 0.96f)
                                    dragY = (dragY + dragAmount.y / heightPx).coerceIn(0.06f, 0.86f)
                                    onOverlayPositionChange(dragX, dragY)
                                },
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        if (musicSelection != null) {
            MomentMusicPreviewChip(
                title = "${musicSelection.track.title} — ${musicSelection.track.artist}",
                durationMs = musicSelection.endMs - musicSelection.startMs,
                onClick = { showMusicDialog = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 62.dp)
            )
        }
        if (musicSelection != null && !showMusicDialog && !suspendMusicPlayback) {
            MomentEditorMusicPlayback(
                uri = musicSelection.localUri ?: Uri.parse(
                    UbuntuMedia.resolveUrl(musicSelection.track.audioUrl)
                        ?: musicSelection.track.audioUrl,
                ),
                startMs = musicSelection.startMs,
                durationMs = musicSelection.endMs - musicSelection.startMs,
                volume = musicSelection.musicVolume,
            )
        }
        
        // Top Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cerrar editor",
                    tint = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.58f), CircleShape)
                        .padding(6.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            if (!isVideo) {
                MomentEditorToolButton(
                    icon = Icons.Default.TextFields,
                    label = "Texto",
                    onClick = { showTextDialog = true }
                )
                Spacer(Modifier.width(6.dp))
            }
            MomentEditorToolButton(
                icon = Icons.Default.MusicNote,
                label = "Música",
                onClick = {
                    if (musicCatalogState.items.isEmpty() && !musicCatalogState.isLoading) {
                        onSearchMusic("", "Todo", "community")
                    }
                    showMusicDialog = true
                }
            )
            Spacer(Modifier.width(8.dp))
            // Visibility Toggle
            if (isVideo && musicSelection != null) {
                MomentEditorToolButton(
                    icon = Icons.Default.Tune,
                    label = "Audio",
                    onClick = { showAudioMixDialog = true },
                )
                Spacer(Modifier.width(6.dp))
            }
            Button(
                onClick = { showAudienceDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Visibility, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(audience.label, maxLines = 1)
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
                .padding(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = reliableBottomPadding + 4.dp
                )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.52f))
                    .clickable { onAllowRepliesChange(!allowReplies) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ChatBubbleOutline, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Permitir respuestas", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = allowReplies, onCheckedChange = onAllowRepliesChange)
            }

            if (audience.requiresPeople) {
                TextButton(
                    onClick = { showPeopleDialog = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Group, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (selectedAudienceUsers.isEmpty()) "Elegir personas" else "${selectedAudienceUsers.size} personas elegidas")
                }
            }
            
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
                        if (!isUploading && (!audience.requiresPeople || selectedAudienceUsers.isNotEmpty())) {
                            onPost()
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, "Publicar")
                    }
                }
            }
        }
    }

    if (showAudienceDialog) {
        MomentAudienceDialog(
            selected = audience,
            onDismiss = { showAudienceDialog = false },
            onSelected = { selected ->
                onAudienceChange(selected)
                showAudienceDialog = false
                if (selected.requiresPeople) showPeopleDialog = true
            }
        )
    }

    if (showPeopleDialog) {
        MomentPeopleDialog(
            title = if (audience == MomentAudienceOption.EXCEPT_SELECTED) "Ocultar a…" else "Compartir con…",
            results = audienceSearchResults,
            selectedUsers = selectedAudienceUsers,
            isSearching = isSearchingAudience,
            onSearch = onSearchAudience,
            onToggle = onToggleAudienceUser,
            onDismiss = { showPeopleDialog = false }
        )
    }

    if (showTextDialog) {
        MomentTextOverlayDialog(
            text = overlayText,
            color = overlayColor,
            font = overlayFont,
            scale = overlayScale,
            onTextChange = onOverlayTextChange,
            onColorChange = onOverlayColorChange,
            onFontChange = onOverlayFontChange,
            onScaleChange = onOverlayScaleChange,
            onDismiss = { showTextDialog = false }
        )
    }

    if (showAudioMixDialog && isVideo && musicSelection != null) {
        MomentAudioMixDialog(
            selection = musicSelection,
            onChange = onMusicSelected,
            onDismiss = { showAudioMixDialog = false },
        )
    }

    if (showMusicDialog) {
        MomentMusicPickerDialog(
            state = musicCatalogState,
            contentDurationMs = contentDurationMs,
            hasOriginalAudio = isVideo,
            initialSelection = musicSelection,
            onSearch = onSearchMusic,
            onLoadMore = onLoadMoreMusic,
            onToggleFavorite = onToggleMusicFavorite,
            onReport = onReportMusic,
            onNotInterested = onNotInterestedMusic,
            onPreviewStarted = onMusicPreviewStarted,
            onOpenCommunityUpload = {
                showMusicDialog = false
                onOpenCommunityMusicUpload()
            },
            onOpenDeviceMusic = {
                showMusicDialog = false
                onOpenDeviceMusic()
            },
            onDismiss = { showMusicDialog = false },
            onRemove = onRemoveMusic,
            onSelect = {
                onMusicSelected(it)
                showMusicDialog = false
            },
        )
    }
}

@Composable
private fun MomentEditorToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.64f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
        modifier = Modifier.size(42.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MomentMusicPreviewChip(
    title: String,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.66f),
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "$title · ${formatMomentMusicDuration(durationMs)}",
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

internal fun formatMomentMusicDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) + 500L) / 1_000L
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun MomentEditorMusicPlayback(uri: Uri, startMs: Long, durationMs: Long, volume: Float) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    androidx.media3.datasource.DefaultDataSource.Factory(
                        context,
                        conectate.com.util.VideoCache.getCacheDataSourceFactory(context),
                    ),
                ),
            )
            .build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
            this.volume = volume.coerceIn(0f, 1f)
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            prepare()
        }
    }
    LaunchedEffect(volume) { exoPlayer.volume = volume.coerceIn(0f, 1f) }
    LaunchedEffect(uri, startMs, durationMs, exoPlayer) {
        val safeStart = startMs.coerceAtLeast(0L)
        val safeDuration = durationMs.coerceIn(1_000L, 60_000L)
        exoPlayer.seekTo(safeStart)
        exoPlayer.playWhenReady = true
        while (true) {
            if (exoPlayer.currentPosition >= safeStart + safeDuration ||
                exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED
            ) {
                exoPlayer.seekTo(safeStart)
                exoPlayer.playWhenReady = true
            }
            kotlinx.coroutines.delay(150)
        }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentTextOverlayDialog(
    text: String,
    color: String,
    font: String,
    scale: Float,
    onTextChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onFontChange: (String) -> Unit,
    onScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = listOf(
        "#FFFFFFFF" to "Blanco",
        "#FFFFD54F" to "Amarillo",
        "#FFFF5252" to "Rojo",
        "#FF69F0D5" to "Menta",
        "#FF82B1FF" to "Azul"
    )
    val fonts = listOf("sans" to "Clásica", "serif" to "Serif", "mono" to "Mono")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Texto del momento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { onTextChange(it.take(120)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Escribe algo") },
                    minLines = 2,
                    maxLines = 4
                )
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    colors.forEach { (hex, label) ->
                        Surface(
                            onClick = { onColorChange(hex) },
                            shape = CircleShape,
                            color = momentOverlayColor(hex),
                            border = BorderStroke(
                                if (color == hex) 3.dp else 1.dp,
                                if (color == hex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (color == hex) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = label,
                                        tint = if (hex == "#FFFFFFFF") Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Tipografía", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fonts.forEach { (id, label) ->
                        FilterChip(
                            selected = font == id,
                            onClick = { onFontChange(id) },
                            label = { Text(label, fontFamily = momentOverlayFont(id)) }
                        )
                    }
                }
                Text("Tamaño", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = scale,
                    onValueChange = onScaleChange,
                    valueRange = 0.7f..2.1f
                )
                Text(
                    "Después puedes arrastrar el texto directamente sobre la imagen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        dismissButton = {
            if (text.isNotBlank()) TextButton(onClick = { onTextChange(""); onDismiss() }) { Text("Quitar") }
        }
    )
}

private fun momentOverlayColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color.White)

private fun momentOverlayFont(font: String): FontFamily = when (font) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

@Composable
private fun MomentAudienceDialog(
    selected: MomentAudienceOption,
    onDismiss: () -> Unit,
    onSelected: (MomentAudienceOption) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quién puede ver este momento") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lazyItems(MomentAudienceOption.entries) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelected(option) }
                            .padding(horizontal = 6.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == option, onClick = { onSelected(option) })
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(option.label, fontWeight = FontWeight.SemiBold)
                            Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun MomentPeopleDialog(
    title: String,
    results: List<User>,
    selectedUsers: List<User>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onToggle: (User) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onSearch(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar por nombre o usuario") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                if (isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    lazyItems(results, key = { it.id }) { user ->
                        val checked = selectedUsers.any { it.id == user.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(user) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProfileImage(imageUrl = user.avatar_url, size = 38.dp, fallbackName = user.displayName)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(user.displayName, fontWeight = FontWeight.SemiBold)
                                Text("@${user.username ?: user.email.substringBefore('@')}", style = MaterialTheme.typography.bodySmall)
                            }
                            Checkbox(checked = checked, onCheckedChange = { onToggle(user) })
                        }
                    }
                }
                if (query.length >= 2 && results.isEmpty() && !isSearching) {
                    Text("No se encontraron personas", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo (${selectedUsers.size})") } }
    )
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun MomentVideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    startMs: Long = 0,
    endMs: Long = -1,
    volume: Float = 1f,
    playWhenReady: Boolean = true,
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        // Gallery and camera media arrive as content:// or file:// URIs. A
        // CacheDataSource only understands the HTTP upstream used by the feed;
        // passing local content through it produces a black PlayerView on some
        // Android providers. Use the cache only for remote media.
        val dataSourceFactory = if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            conectate.com.util.VideoCache.getCacheDataSourceFactory(context)
        } else {
            androidx.media3.datasource.DefaultDataSource.Factory(context)
        }
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    dataSourceFactory,
                ),
            )
            .build().apply {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            this.volume = volume.coerceIn(0f, 1f)
            prepare()
            this.playWhenReady = playWhenReady
        }
    }

    // Handle range/trimming preview
    LaunchedEffect(volume) { exoPlayer.volume = volume.coerceIn(0f, 1f) }
    LaunchedEffect(playWhenReady) { exoPlayer.playWhenReady = playWhenReady }
    LaunchedEffect(startMs, endMs) {
        if (endMs != -1L) {
            exoPlayer.seekTo(startMs)
            while (true) {
                if (exoPlayer.currentPosition >= endMs || exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    exoPlayer.seekTo(startMs)
                    exoPlayer.play()
                }
                kotlinx.coroutines.delay(100)
            }
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
    val directory = ShareableMediaFileStore.momentCaptures(context, preferExternal = true)
    val tmpFile = java.io.File.createTempFile("moment_camera_", extension, directory)
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        tmpFile
    )
}

/**
 * Copies a camera result to an app-owned cache file before opening the editor.
 * OEM camera apps are allowed to release their temporary provider stream as
 * soon as the ActivityResult callback fires; using the copied URI avoids a
 * race that otherwise makes the crop dialog immediately dismiss itself.
 */
private fun stabilizeCapturedCameraUri(
    context: android.content.Context,
    sourceUri: Uri,
    extension: String,
): Uri? {
    return runCatching {
        val targetDirectory = ShareableMediaFileStore.momentCaptures(context, preferExternal = false)
        val target = java.io.File.createTempFile("moment_stable_", extension, targetDirectory)
        context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "No se pudo abrir la captura" }
            java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        require(target.length() > 0L) { "La captura está vacía" }
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            target,
        )
    }.getOrNull()
}

private fun resolveMomentMediaDuration(context: android.content.Context, uri: Uri): Long {
    return runCatching {
        android.media.MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull() ?: 6_000L
}
