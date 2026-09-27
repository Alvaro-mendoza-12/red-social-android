package conectate.com.presentation.videos

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.Player
import conectate.com.presentation.common.AppFeedbackBus
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun VideoEditorScreen(
    navController: NavController,
    videoUri: Uri,
    initialSoundUri: Uri? = null,
    viewModel: VideoEditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewPlayer = remember(viewModel, context) { viewModel.getPlayer(context) }
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        )
    }
    var previewPlaybackRequested by remember(previewPlayer) {
        mutableStateOf(previewPlayer.playWhenReady)
    }
    val editorDensity = LocalDensity.current
    val editorWindowSize = LocalWindowInfo.current.containerSize
    val editorHeight = with(editorDensity) { editorWindowSize.height.toDp() }
    val editorWidth = with(editorDensity) { editorWindowSize.width.toDp() }
    val compactHeight = editorHeight < 700.dp
    val compactWidth = editorWidth < 380.dp
    val previewHeight = (editorHeight * if (compactHeight) 0.25f else 0.31f)
        .coerceIn(if (compactHeight) 150.dp else 210.dp, if (compactHeight) 215.dp else 330.dp)
    val timelineHeight = if (compactHeight) 132.dp else 168.dp
    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val timeline by viewModel.timeline.collectAsState()
    val selectedClipId by viewModel.selectedClipId.collectAsState()
    val currentTimeMs by viewModel.currentTimeMs.collectAsState()
    val performanceProfile by viewModel.performanceProfile.collectAsState()
    val selectedExportQuality by viewModel.selectedExportQuality.collectAsState()
    val editorHint by viewModel.editorHint.collectAsState()
    val exportStage by viewModel.exportStage.collectAsState()
    val timelineThumbnails by viewModel.timelineThumbnails.collectAsState()
    val isPreparingTimeline by viewModel.isPreparingTimeline.collectAsState()

    var showTextDialog by remember { mutableStateOf(false) }
    var currentEditingText by remember { mutableStateOf("") }
    var showStickerPicker by remember { mutableStateOf(false) }
    var activeCategory by remember { mutableStateOf(EditorCategory.EDIT) }
    var showQualityMenu by remember { mutableStateOf(false) }
    val videoClipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        viewModel.addVideoClips(context, uris)
    }

    DisposableEffect(lifecycleOwner, previewPlayer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            lifecycleResumed = lifecycleOwner.lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (!lifecycleResumed) {
                previewPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            previewPlayer.pause()
        }
    }

    LaunchedEffect(lifecycleResumed, previewPlaybackRequested, isExporting, timeline) {
        if (lifecycleResumed && previewPlaybackRequested && !isExporting) {
            previewPlayer.play()
        } else {
            previewPlayer.pause()
        }
    }

    LaunchedEffect(editorHint) {
        editorHint?.let {
            AppFeedbackBus.show(it)
            viewModel.dismissEditorHint()
        }
    }

    LaunchedEffect(videoUri, initialSoundUri) {
        viewModel.setInitialVideo(context, videoUri)
        if (initialSoundUri != null && timeline.tracks.none { it.type == TrackType.AUDIO && it.clips.isNotEmpty() }) {
            viewModel.addMusicTrack(context, initialSoundUri)
            activeCategory = EditorCategory.AUDIO
        }
    }

    val previewMusicClip = timeline.tracks
        .firstOrNull { it.type == TrackType.AUDIO }
        ?.clips
        ?.firstOrNull { it.uri != null }
    EditorMusicPreviewPlayback(
        clip = previewMusicClip,
        currentTimeMs = currentTimeMs,
        shouldPlay = lifecycleResumed && previewPlaybackRequested && !isExporting,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (compactWidth) "Editar" else "Editar video", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.Close, "Cerrar")
                    }
                },
                actions = {
                    Box {
                        TextButton(
                            onClick = { showQualityMenu = true },
                            enabled = !isExporting
                        ) {
                            Icon(Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(17.dp))
                            if (!compactWidth) {
                                Spacer(Modifier.width(4.dp))
                                Text(selectedExportQuality.label, maxLines = 1)
                            }
                        }
                        DropdownMenu(
                            expanded = showQualityMenu,
                            onDismissRequest = { showQualityMenu = false }
                        ) {
                            ExportQuality.values().forEach { quality ->
                                DropdownMenuItem(
                                    text = { Text(quality.label) },
                                    onClick = {
                                        viewModel.setExportQuality(quality)
                                        showQualityMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.exportVideo(
                                context = context,
                                onSuccess = { exportedUri ->
                                    navController.previousBackStackEntry?.savedStateHandle?.set("editedVideo", exportedUri.toString())
                                    navController.popBackStack()
                                },
                                onError = { error ->
                                    AppFeedbackBus.showError(error)
                                }
                            )
                        },
                        enabled = !isExporting && timeline.tracks.any { track ->
                            track.type == TrackType.VIDEO && track.clips.isNotEmpty()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        if (!compactWidth || isExporting) {
                            Text(if (isExporting) "Procesando" else "Guardar")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White, navigationIconContentColor = Color.White)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
        ) {
            // 1. Preview Area
            Box(
                modifier = Modifier
                    .height(previewHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF080A0E))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                val mainVideoClip = selectedClipId
                    ?.let { selectedId -> timeline.tracks.flatMap { it.clips }.firstOrNull { it.id == selectedId } }
                    ?.takeIf { it.type == ClipType.VIDEO }
                    ?: timeline.tracks.find { it.type == TrackType.VIDEO }?.clips?.firstOrNull()
                if (mainVideoClip != null) {
                    val player = previewPlayer
                    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }

                    DisposableEffect(player) {
                        val listener = object : Player.Listener {
                            override fun onIsPlayingChanged(playing: Boolean) {
                                isPlaying = playing
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) {
                                    isPlaying = false
                                    previewPlaybackRequested = false
                                    viewModel.updatePlaybackPosition()
                                }
                            }
                        }
                        player.addListener(listener)
                        onDispose {
                            player.removeListener(listener)
                            player.pause()
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    this.player = player
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        
                        // Play/Pause Overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    previewPlaybackRequested = !previewPlaybackRequested
                                }
                                .background(Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!isPlaying) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                                        .padding(8.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.48f))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            IconButton(onClick = {
                                previewPlaybackRequested = !previewPlaybackRequested
                            }) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                    tint = Color.White
                                )
                            }
                            IconButton(onClick = {
                                viewModel.updateCurrentTime(0L)
                            }) {
                                Icon(Icons.Default.RestartAlt, contentDescription = "Ir al inicio", tint = Color.White)
                            }
                            Text(
                                text = "${formatEditorTime(currentTimeMs)} / ${formatEditorTime(timeline.durationMs())}",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }

                        LaunchedEffect(player) {
                            while (true) {
                                if (player.isPlaying) {
                                    viewModel.updatePlaybackPosition()
                                }
                                delay(120)
                            }
                        }
                    }
                }

                performanceProfile?.let { profile ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                        color = Color.Black.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Vista ${profile.label}",
                                color = Color.White,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // Render Overlays based on currentTimeMs
                timeline.tracks.forEach { track ->
                    track.clips.filter { it.startMs <= currentTimeMs && it.endMs >= currentTimeMs }.forEach { clip ->
                        when (clip.type) {
                            ClipType.TEXT -> {
                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(clip.x.roundToInt(), clip.y.roundToInt()) }
                                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                        .padding(4.dp)
                                        .pointerInput(clip.id) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                viewModel.updateClipPosition(clip.id, clip.x + dragAmount.x, clip.y + dragAmount.y)
                                            }
                                        }
                                        .border(
                                            if (selectedClipId == clip.id) 2.dp else 0.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable { viewModel.selectClip(context, clip.id) }
                                ) {
                                    Text(
                                        text = clip.text ?: "",
                                        color = Color(clip.color),
                                        fontSize = clip.fontSize.sp
                                    )
                                }
                            }
                            ClipType.STICKER -> {
                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(clip.x.roundToInt(), clip.y.roundToInt()) }
                                        .pointerInput(clip.id) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                viewModel.updateClipPosition(clip.id, clip.x + dragAmount.x, clip.y + dragAmount.y)
                                            }
                                        }
                                        .clickable { viewModel.selectClip(context, clip.id) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Face,
                                        contentDescription = null,
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(60.dp)
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }

                if (isExporting) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.78f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.padding(24.dp),
                            color = Color(0xFF171A20),
                            shape = RoundedCornerShape(18.dp),
                            tonalElevation = 8.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.MovieCreation,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp)
                                )
                                Text(exportStage.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                                LinearProgressIndicator(
                                    progress = { exportProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .clip(CircleShape)
                                )
                                Text(
                                    "${(exportProgress * 100).toInt()}% · ${selectedExportQuality.label}",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // 2. Professional Timeline
            Box(
                modifier = Modifier
                    .height(timelineHeight)
                    .fillMaxWidth()
                    .background(Color(0xFF121212))
            ) {
                TimelineView(
                    timeline = timeline,
                    currentTimeMs = currentTimeMs,
                    selectedClipId = selectedClipId,
                    thumbnails = timelineThumbnails,
                    isPreparingThumbnails = isPreparingTimeline,
                    pixelsPerSecond = performanceProfile?.editorPixelsPerSecond ?: 20f,
                    onSeek = { viewModel.updateCurrentTime(it) },
                    onSelectClip = { viewModel.selectClip(context, it) },
                    onTrimChange = { clipId, startMs, endMs ->
                        viewModel.updateClipTrim(context, clipId, startMs, endMs)
                    }
                )
            }

            // 3. TITAN Tool System (Nested Categories)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0F0F0F))
            ) {
                // Sub-Panel (Shows tools of the active category)
                AnimatedVisibility(
                    visible = true,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        when (activeCategory) {
                            EditorCategory.EDIT -> {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(horizontal = 8.dp),
                                    verticalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        SubToolItem(Icons.Default.VideoLibrary, "Añadir") {
                                            videoClipPickerLauncher.launch("video/*")
                                        }
                                        SubToolItem(Icons.Default.ContentCut, "Dividir") {
                                            selectedClipId?.let { viewModel.splitClip(context, it, currentTimeMs) }
                                        }
                                        SubToolItem(Icons.Default.Delete, "Eliminar") {
                                            selectedClipId?.let { viewModel.removeClip(context, it) }
                                        }
                                        SubToolItem(Icons.Default.ContentCopy, "Duplicar") {
                                            selectedClipId?.let { viewModel.duplicateClip(context, it) }
                                        }
                                    }
                                    val clip = timeline.tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                    val maxSpeed = (performanceProfile?.editorMaxSpeed ?: 3f).coerceAtLeast(1f)
                                    if (clip != null && clip.type == ClipType.VIDEO) {
                                        var trimStart by remember(clip.id, clip.startOffsetMs) { mutableStateOf(clip.startOffsetMs.toFloat()) }
                                        var trimEnd by remember(clip.id, clip.startOffsetMs, clip.durationMs) {
                                            mutableStateOf((clip.startOffsetMs + clip.durationMs).toFloat())
                                        }
                                        val sourceDurationMs = clip.sourceDurationMs
                                            .takeIf { it > 0L }
                                            ?: (clip.startOffsetMs + clip.durationMs).coerceAtLeast(1L)
                                        val sourceEnd = sourceDurationMs.toFloat()
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = "Recorte ${formatEditorTime(trimStart.toLong())} - ${formatEditorTime(trimEnd.toLong())}",
                                                color = Color.White,
                                                fontSize = 11.sp
                                            )
                                            RangeSlider(
                                                value = trimStart..trimEnd,
                                                onValueChange = { range ->
                                                    val safe = sanitizeTrimRange(
                                                        sourceDurationMs = sourceDurationMs,
                                                        requestedStartMs = range.start.toLong(),
                                                        requestedEndMs = range.endInclusive.toLong()
                                                    )
                                                    trimStart = safe.first.toFloat()
                                                    trimEnd = safe.last.toFloat()
                                                },
                                                onValueChangeFinished = {
                                                    viewModel.updateClipTrim(context, clip.id, trimStart.toLong(), trimEnd.toLong())
                                                },
                                                valueRange = 0f..sourceEnd.coerceAtLeast(1_000f),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            val selectedDurationMs = (trimEnd - trimStart)
                                                .toLong()
                                                .coerceAtLeast(1_000L)
                                            val maximumWindowStartMs = (sourceDurationMs - selectedDurationMs)
                                                .coerceAtLeast(0L)
                                            if (maximumWindowStartMs > 0L) {
                                                Text(
                                                    text = "Mover el fragmento por el video",
                                                    color = Color.LightGray,
                                                    fontSize = 10.sp
                                                )
                                                Slider(
                                                    value = trimStart.coerceIn(0f, maximumWindowStartMs.toFloat()),
                                                    onValueChange = { requestedStart ->
                                                        val moved = moveTrimRange(
                                                            sourceDurationMs = sourceDurationMs,
                                                            selectionDurationMs = selectedDurationMs,
                                                            requestedStartMs = requestedStart.toLong()
                                                        )
                                                        trimStart = moved.first.toFloat()
                                                        trimEnd = moved.last.toFloat()
                                                    },
                                                    onValueChangeFinished = {
                                                        viewModel.updateClipTrim(
                                                            context,
                                                            clip.id,
                                                            trimStart.toLong(),
                                                            trimEnd.toLong()
                                                        )
                                                    },
                                                    valueRange = 0f..maximumWindowStartMs.toFloat(),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                            Text(
                                                text = "Velocidad ${String.format(Locale.US, "%.2fx", clip.speed)}",
                                                color = Color.White,
                                                fontSize = 11.sp
                                            )
                                            Slider(
                                                value = clip.speed,
                                                onValueChange = { viewModel.updateClipSpeed(clip.id, it) },
                                                valueRange = 0.5f..maxSpeed,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    } else {
                                        Text(
                                            "Selecciona un clip para editarlo",
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                        )
                                    }
                                }
                            }
                            EditorCategory.AUDIO -> {
                                val videoClip = timeline.tracks.find { it.type == TrackType.VIDEO }?.clips?.firstOrNull()
                                val audioTrack = timeline.tracks.find { it.type == TrackType.AUDIO }
                                val musicClip = audioTrack?.clips?.firstOrNull()
                                
                                // File picker launcher
                                val audioPickerLauncher = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.GetContent()
                                ) { uri: Uri? ->
                                    uri?.let { 
                                        viewModel.addMusicTrack(context, it)
                                        android.util.Log.d("VideoEditor", "Audio file selected: $it")
                                    }
                                }
                                
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp)
                                ) {
                                    // Add music button
                                    SubToolItem(Icons.Default.MusicNote, if (musicClip != null) "Cambiar" else "Añadir") {
                                        audioPickerLauncher.launch("audio/*")
                                    }
                                    
                                    Spacer(Modifier.height(8.dp))
                                    
                                    // Show music file name if added
                                    if (musicClip != null) {
                                        Text(
                                            text = "🎵 Música añadida",
                                            color = Color.Green,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                    }
                                    
                                    // Original audio volume slider
                                    if (videoClip != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Videocam, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Video", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                                            Slider(
                                                value = videoClip.originalAudioVolume,
                                                onValueChange = { viewModel.setOriginalAudioVolume(videoClip.id, it) },
                                                valueRange = 0f..1f,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text("${(videoClip.originalAudioVolume * 100).toInt()}%", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(35.dp))
                                        }
                                    }
                                    
                                    // Music volume slider (only if music is added)
                                    if (musicClip != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Música", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                                            Slider(
                                                value = musicClip.musicVolume,
                                                onValueChange = { viewModel.setMusicVolume(it) },
                                                valueRange = 0f..1f,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text("${(musicClip.musicVolume * 100).toInt()}%", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(35.dp))
                                        }
                                    }
                                }
                            }
                            EditorCategory.ADJUST -> {
                                val clip = timeline.tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (clip != null) {
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        AdjustmentSlider("Brillo", clip.brightness, -1f..1f) {
                                            viewModel.updateClipFilter(context, clip.id, it, clip.contrast, clip.saturation)
                                        }
                                        AdjustmentSlider("Contraste", clip.contrast, 0f..2f) {
                                            viewModel.updateClipFilter(context, clip.id, clip.brightness, it, clip.saturation)
                                        }
                                        AdjustmentSlider("Saturación", clip.saturation, 0f..2f) {
                                            viewModel.updateClipFilter(context, clip.id, clip.brightness, clip.contrast, it)
                                        }
                                    }
                                } else {
                                    Text("Selecciona un clip", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                            EditorCategory.TEXT -> {
                                SubToolItem(Icons.Default.TextFields, "Añadir") { showTextDialog = true }
                            }
                            EditorCategory.STICKER -> {
                                SubToolItem(Icons.Default.SentimentSatisfied, "Stickers") { showStickerPicker = true }
                            }
                            EditorCategory.FILTERS -> {
                                val clip = timeline.tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (clip != null && clip.type == ClipType.VIDEO) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .horizontalScroll(rememberScrollState()),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        SubToolItem(Icons.Default.Contrast, "Original") {
                                            viewModel.applyFilter(context, clip.id, FilterType.NONE)
                                        }
                                        SubToolItem(Icons.Default.Contrast, "B&N") {
                                            viewModel.applyFilter(context, clip.id, FilterType.BLACK_WHITE)
                                        }
                                        SubToolItem(Icons.Default.WbSunny, "Sepia") {
                                            viewModel.applyFilter(context, clip.id, FilterType.SEPIA)
                                        }
                                        SubToolItem(Icons.Default.CameraAlt, "Vintage") {
                                            viewModel.applyFilter(context, clip.id, FilterType.VINTAGE)
                                        }
                                        SubToolItem(Icons.Default.Brightness7, "Alto C.") {
                                            viewModel.applyFilter(context, clip.id, FilterType.HIGH_CONTRAST)
                                        }
                                        SubToolItem(Icons.Default.Colorize, "Alta S.") {
                                            viewModel.applyFilter(context, clip.id, FilterType.HIGH_SATURATION)
                                        }
                                    }
                                } else {
                                    Text("Selecciona un clip de video", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                            EditorCategory.RATIO -> {
                                val clip = timeline.tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (clip != null && clip.type == ClipType.VIDEO) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .horizontalScroll(rememberScrollState()),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        SubToolItem(Icons.Default.CropOriginal, "Original") {
                                            viewModel.setAspectRatio(context, clip.id, AspectRatio.ORIGINAL)
                                        }
                                        SubToolItem(Icons.Default.CropLandscape, "16:9") {
                                            viewModel.setAspectRatio(context, clip.id, AspectRatio.RATIO_16_9)
                                        }
                                        SubToolItem(Icons.Default.CropSquare, "1:1") {
                                            viewModel.setAspectRatio(context, clip.id, AspectRatio.RATIO_1_1)
                                        }
                                        SubToolItem(Icons.Default.CropPortrait, "9:16") {
                                            viewModel.setAspectRatio(context, clip.id, AspectRatio.RATIO_9_16)
                                        }
                                    }
                                } else {
                                    Text("Selecciona un clip de video", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Primary Category Bar (TITAN 10 Categories)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp)
                        .horizontalScroll(rememberScrollState())
                        .background(Color.Black),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CategoryItem(Icons.Default.Edit, "Editar", activeCategory == EditorCategory.EDIT) { activeCategory = EditorCategory.EDIT }
                    CategoryItem(Icons.Default.MusicNote, "Audio", activeCategory == EditorCategory.AUDIO) { activeCategory = EditorCategory.AUDIO }
                    CategoryItem(Icons.Default.TextFields, "Texto", activeCategory == EditorCategory.TEXT) { activeCategory = EditorCategory.TEXT }
                    CategoryItem(Icons.Default.SentimentSatisfied, "Stickers", activeCategory == EditorCategory.STICKER) { activeCategory = EditorCategory.STICKER }
                    CategoryItem(Icons.Default.FilterBAndW, "Filtros", activeCategory == EditorCategory.FILTERS) { activeCategory = EditorCategory.FILTERS }
                    CategoryItem(Icons.Default.Tune, "Ajustar", activeCategory == EditorCategory.ADJUST) { activeCategory = EditorCategory.ADJUST }
                    CategoryItem(Icons.Default.AspectRatio, "Relación", activeCategory == EditorCategory.RATIO) { activeCategory = EditorCategory.RATIO }
                }
            }
        }

        // Dialogs...
        if (showTextDialog) {
            AlertDialog(
                onDismissRequest = { showTextDialog = false },
                title = { Text("Nuevo Texto") },
                text = {
                    TextField(value = currentEditingText, onValueChange = { currentEditingText = it })
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.addTextOverlay(currentEditingText, currentTimeMs)
                        currentEditingText = ""
                        showTextDialog = false
                    }) { Text("Añadir") }
                }
            )
        }
        
        if (showStickerPicker) {
             AlertDialog(
                onDismissRequest = { showStickerPicker = false },
                title = { Text("Elegir Sticker") },
                text = {
                    LazyRow {
                        items(viewModel.stickerOptions) { resId ->
                            IconButton(onClick = {
                                viewModel.addStickerOverlay(context, resId, currentTimeMs)
                                showStickerPicker = false
                            }) {
                                Box(modifier = Modifier.size(40.dp).background(Color.DarkGray, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(id = resId),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun EditorMusicPreviewPlayback(
    clip: EditorClip?,
    currentTimeMs: Long,
    shouldPlay: Boolean,
) {
    val context = LocalContext.current
    val uri = clip?.uri
    val player = remember(uri) {
        uri?.let {
            androidx.media3.exoplayer.ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        conectate.com.util.VideoCache.getCacheDataSourceFactory(context),
                    ),
                )
                .build()
                .apply {
                    setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        false,
                    )
                    setHandleAudioBecomingNoisy(true)
                    repeatMode = Player.REPEAT_MODE_ONE
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        .build()
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(it))
                    prepare()
                }
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    LaunchedEffect(player, clip?.musicVolume) {
        player?.volume = clip?.musicVolume?.coerceIn(0f, 1f) ?: 1f
    }

    LaunchedEffect(player, shouldPlay) {
        if (shouldPlay) player?.play() else player?.pause()
    }

    LaunchedEffect(player, currentTimeMs / 500L, clip?.startOffsetMs, clip?.sourceDurationMs) {
        val activeClip = clip ?: return@LaunchedEffect
        val audioPlayer = player ?: return@LaunchedEffect
        val sourceDuration = activeClip.sourceDurationMs.takeIf { it > 0L }
            ?: audioPlayer.duration.takeIf { it > 0L }
            ?: activeClip.durationMs.coerceAtLeast(1_000L)
        val relativeTimeline = (currentTimeMs - activeClip.startMs).coerceAtLeast(0L)
        val expected = activeClip.startOffsetMs + (relativeTimeline % sourceDuration.coerceAtLeast(1L))
        if (kotlin.math.abs(audioPlayer.currentPosition - expected) > 650L) {
            audioPlayer.seekTo(expected.coerceIn(0L, sourceDuration.coerceAtLeast(1L) - 1L))
        }
    }
}

@Composable
fun TimelineView(
    timeline: EditorTimeline,
    currentTimeMs: Long,
    selectedClipId: Long?,
    thumbnails: List<TimelineThumbnail>,
    isPreparingThumbnails: Boolean,
    pixelsPerSecond: Float,
    onSeek: (Long) -> Unit,
    onSelectClip: (Long?) -> Unit,
    onTrimChange: (Long, Long, Long) -> Unit
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val effectivePixelsPerSecond = pixelsPerSecond.coerceIn(18f, 42f)
    val durationMs = timeline.durationMs().coerceAtLeast(1_000L)
    var viewportWidthPx by remember { mutableStateOf(1) }
    val viewportWidthDp = with(density) { viewportWidthPx.toDp() }
    val timelineWidthDp = max(
        viewportWidthDp.value,
        (durationMs / 1000f) * effectivePixelsPerSecond
    ).dp
    val playheadXDp = ((currentTimeMs.coerceIn(0L, durationMs) / 1000f) * effectivePixelsPerSecond).dp

    LaunchedEffect(currentTimeMs, viewportWidthPx, scrollState.maxValue) {
        if (viewportWidthPx <= 1 || scrollState.isScrollInProgress) return@LaunchedEffect
        val playheadPx = with(density) { playheadXDp.toPx() }
        val visibleStart = scrollState.value + viewportWidthPx * 0.12f
        val visibleEnd = scrollState.value + viewportWidthPx * 0.88f
        val target = when {
            playheadPx < visibleStart -> playheadPx - viewportWidthPx * 0.2f
            playheadPx > visibleEnd -> playheadPx - viewportWidthPx * 0.72f
            else -> null
        }
        target?.let {
            scrollState.animateScrollTo(it.roundToInt().coerceIn(0, scrollState.maxValue))
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF11141A),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "LÍNEA DE TIEMPO",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                if (isPreparingThumbnails) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatEditorTime(currentTimeMs)} / ${formatEditorTime(durationMs)}",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { viewportWidthPx = it.width.coerceAtLeast(1) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(scrollState)
                ) {
                    Box(
                        modifier = Modifier
                            .width(timelineWidthDp)
                            .fillMaxHeight()
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            TimelineRuler(
                                durationMs = durationMs,
                                pixelsPerSecond = effectivePixelsPerSecond,
                                onSeek = onSeek,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(25.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                timeline.tracks.forEach { track ->
                                    TimelineTrackRow(
                                        track = track,
                                        thumbnails = thumbnails,
                                        selectedClipId = selectedClipId,
                                        pixelsPerSecond = effectivePixelsPerSecond,
                                        onSeek = onSeek,
                                        onSelectClip = onSelectClip,
                                        onTrimChange = onTrimChange,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(if (track.type == TrackType.VIDEO) 64.dp else 34.dp)
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                            }
                        }

                        Box(
                            modifier = Modifier
                                .offset(x = playheadXDp - 1.dp)
                                .padding(top = 20.dp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(Color(0xFFFF4D67))
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Posición actual",
                            tint = Color(0xFFFF4D67),
                            modifier = Modifier
                                .offset(x = playheadXDp - 12.dp, y = 11.dp)
                                .size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRuler(
    durationMs: Long,
    pixelsPerSecond: Float,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.LTGRAY
        }
    }
    val pixelsPerSecondPx = with(density) { pixelsPerSecond.dp.toPx() }
    val labelEverySeconds = when {
        pixelsPerSecond >= 36f -> 1
        pixelsPerSecond >= 22f -> 2
        else -> 5
    }
    val seekFromX: (Float) -> Unit = { x ->
        val timeMs = ((x / pixelsPerSecondPx) * 1_000f)
            .toLong()
            .coerceIn(0L, durationMs)
        onSeek(timeMs)
    }

    Canvas(
        modifier = modifier.pointerInput(durationMs, pixelsPerSecond) {
            detectTapGestures { offset -> seekFromX(offset.x) }
        }.pointerInput(durationMs, pixelsPerSecond, "ruler-drag") {
            detectDragGestures(
                onDragStart = { offset -> seekFromX(offset.x) },
                onDrag = { change, _ ->
                    change.consume()
                    seekFromX(change.position.x)
                }
            )
        }
    ) {
        labelPaint.textSize = 9.sp.toPx()
        val totalSeconds = ceil(durationMs / 1000.0).toInt()
        for (second in 0..totalSeconds) {
            val x = second * pixelsPerSecondPx
            val isMajor = second % labelEverySeconds == 0
            drawLine(
                color = Color.White.copy(alpha = if (isMajor) 0.42f else 0.17f),
                start = androidx.compose.ui.geometry.Offset(x, if (isMajor) size.height * 0.46f else size.height * 0.68f),
                end = androidx.compose.ui.geometry.Offset(x, size.height),
                strokeWidth = if (isMajor) 1.5f else 1f
            )
            if (isMajor) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        formatEditorTime(second * 1_000L),
                        x + 4.dp.toPx(),
                        size.height * 0.45f,
                        labelPaint
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineTrackRow(
    track: EditorTrack,
    thumbnails: List<TimelineThumbnail>,
    selectedClipId: Long?,
    pixelsPerSecond: Float,
    onSeek: (Long) -> Unit,
    onSelectClip: (Long?) -> Unit,
    onTrimChange: (Long, Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val trackColor = when (track.type) {
        TrackType.VIDEO -> MaterialTheme.colorScheme.primary
        TrackType.AUDIO -> Color(0xFF4DB6AC)
        TrackType.TEXT -> Color(0xFFFFC857)
        TrackType.STICKER -> Color(0xFF67B7FF)
    }
    val trackIcon = when (track.type) {
        TrackType.VIDEO -> Icons.Default.Videocam
        TrackType.AUDIO -> Icons.Default.GraphicEq
        TrackType.TEXT -> Icons.Default.TextFields
        TrackType.STICKER -> Icons.Default.SentimentSatisfied
    }

    Box(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.025f))
            .border(0.5.dp, Color.White.copy(alpha = 0.06f))
    ) {
        if (track.clips.isEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp).align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(trackIcon, null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Pista vacía", color = Color.Gray, fontSize = 10.sp)
            }
        }

        track.clips.forEach { clip ->
            val clipWidthDp = max(34f, (clip.durationMs / 1000f) * pixelsPerSecond).dp
            val clipStartDp = ((clip.startMs / 1000f) * pixelsPerSecond).dp
            val isSelected = selectedClipId == clip.id
            val sourceEndMs = clip.startOffsetMs + clip.durationMs
            val minimumDurationMs = minOf(1_000L, clip.durationMs.coerceAtLeast(1L))
            val clipWidthPx = with(density) { clipWidthDp.toPx() }
            val minimumWidthPx = with(density) {
                ((minimumDurationMs / 1000f) * pixelsPerSecond).dp.toPx()
            }
            var leftDragPx by remember(clip.id, clip.startOffsetMs, clip.durationMs) { mutableStateOf(0f) }
            var rightDragPx by remember(clip.id, clip.startOffsetMs, clip.durationMs) { mutableStateOf(0f) }

            Box(
                modifier = Modifier
                    .offset(x = clipStartDp)
                    .width(clipWidthDp)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) Color(0xFFFFD45A) else trackColor.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(7.dp)
                    )
                    .pointerInput(clip.id, clip.durationMs) {
                        detectTapGestures { offset ->
                            onSelectClip(clip.id)
                            val relative = (offset.x / size.width.coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                            onSeek(clip.startMs + (clip.durationMs * relative).toLong())
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(trackColor.copy(alpha = if (track.type == TrackType.VIDEO) 0.3f else 0.62f))
                ) {
                    if (track.type == TrackType.VIDEO) {
                        TimelineThumbnailStrip(
                            thumbnails = thumbnails,
                            sourceUri = clip.uri?.toString(),
                            sourceStartMs = clip.startOffsetMs,
                            sourceEndMs = sourceEndMs,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(Modifier.fillMaxSize().background(trackColor.copy(alpha = 0.12f)))
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.48f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(trackIcon, null, tint = Color.White, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = when (clip.type) {
                                ClipType.VIDEO -> formatEditorTime(clip.durationMs)
                                ClipType.AUDIO -> "Música"
                                ClipType.TEXT -> clip.text.orEmpty()
                                ClipType.STICKER -> "Sticker"
                            },
                            color = Color.White,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }

                if (isSelected && clip.type == ClipType.VIDEO) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(leftDragPx.roundToInt(), 0) }
                            .width(11.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFFFD45A), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                            .pointerInput(clip.id, clip.startOffsetMs, clip.durationMs, "left-trim") {
                                detectDragGestures(
                                    onDragEnd = {
                                        val deltaMs = ((leftDragPx / with(density) { pixelsPerSecond.dp.toPx() }) * 1_000f).toLong()
                                        if (abs(deltaMs) >= 50L) {
                                            onTrimChange(
                                                clip.id,
                                                (clip.startOffsetMs + deltaMs).coerceAtMost(sourceEndMs - minimumDurationMs),
                                                sourceEndMs
                                            )
                                        }
                                        leftDragPx = 0f
                                    },
                                    onDragCancel = { leftDragPx = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        leftDragPx = (leftDragPx + dragAmount.x)
                                            .coerceIn(0f, (clipWidthPx - minimumWidthPx).coerceAtLeast(0f))
                                    }
                                )
                            }
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .offset { IntOffset(rightDragPx.roundToInt(), 0) }
                            .width(11.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFFFD45A), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                            .pointerInput(clip.id, clip.startOffsetMs, clip.durationMs, "right-trim") {
                                val availableSourceMs = (clip.sourceDurationMs - sourceEndMs).coerceAtLeast(0L)
                                val maxPositivePx = (availableSourceMs / 1000f) * with(density) { pixelsPerSecond.dp.toPx() }
                                detectDragGestures(
                                    onDragEnd = {
                                        val deltaMs = ((rightDragPx / with(density) { pixelsPerSecond.dp.toPx() }) * 1_000f).toLong()
                                        if (abs(deltaMs) >= 50L) {
                                            onTrimChange(
                                                clip.id,
                                                clip.startOffsetMs,
                                                (sourceEndMs + deltaMs).coerceIn(
                                                    clip.startOffsetMs + minimumDurationMs,
                                                    clip.sourceDurationMs.coerceAtLeast(sourceEndMs)
                                                )
                                            )
                                        }
                                        rightDragPx = 0f
                                    },
                                    onDragCancel = { rightDragPx = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        rightDragPx = (rightDragPx + dragAmount.x).coerceIn(
                                            -(clipWidthPx - minimumWidthPx).coerceAtLeast(0f),
                                            maxPositivePx
                                        )
                                    }
                                )
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineThumbnailStrip(
    thumbnails: List<TimelineThumbnail>,
    sourceUri: String?,
    sourceStartMs: Long,
    sourceEndMs: Long,
    modifier: Modifier = Modifier
) {
    val inRange = remember(thumbnails, sourceUri, sourceStartMs, sourceEndMs) {
        val sourceFrames = thumbnails.filter { it.sourceUri == sourceUri }
        sourceFrames.filter { it.timeMs in sourceStartMs..sourceEndMs }
            .ifEmpty {
                val center = sourceStartMs + (sourceEndMs - sourceStartMs) / 2L
                sourceFrames.minByOrNull { abs(it.timeMs - center) }?.let(::listOf).orEmpty()
            }
    }
    Row(modifier) {
        if (inRange.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF252B35)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.VideoLibrary, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
        } else {
            inRange.forEach { thumbnail ->
                Image(
                    bitmap = thumbnail.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
fun AdjustmentSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(80.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text("${(value * 100).toInt()}%", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(40.dp))
    }
}

@Composable
fun CategoryItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .clickable { onClick() }
            .padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) Color.Red else Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isSelected) Color.Red else Color.White,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun SubToolItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(45.dp)
                .background(Color(0xFF1A1A1A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.Gray, fontSize = 10.sp)
    }
}

private fun formatEditorTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(Locale.US, minutes, seconds)
}

enum class EditorCategory {
    EDIT, AUDIO, TEXT, STICKER, FILTERS, ADJUST, RATIO
}

@Composable
fun EditorToolItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}
