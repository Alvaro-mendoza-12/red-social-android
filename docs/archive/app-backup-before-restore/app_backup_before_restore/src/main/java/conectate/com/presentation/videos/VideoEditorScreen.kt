package conectate.com.presentation.videos

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.MediaItem
import conectate.com.presentation.social.MomentVideoPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun VideoEditorScreen(
    navController: NavController,
    videoUri: Uri,
    viewModel: VideoEditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val timeline by viewModel.timeline.collectAsState()
    val selectedClipId by viewModel.selectedClipId.collectAsState()
    val currentTimeMs by viewModel.currentTimeMs.collectAsState()
    val performanceProfile by viewModel.performanceProfile.collectAsState()
    val selectedExportQuality by viewModel.selectedExportQuality.collectAsState()
    val editorHint by viewModel.editorHint.collectAsState()

    var showTextDialog by remember { mutableStateOf(false) }
    var currentEditingText by remember { mutableStateOf("") }
    var showStickerPicker by remember { mutableStateOf(false) }
    var activeCategory by remember { mutableStateOf<EditorCategory?>(null) }
    var showQualityMenu by remember { mutableStateOf(false) }

    LaunchedEffect(editorHint) {
        editorHint?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.dismissEditorHint()
        }
    }

    LaunchedEffect(videoUri) {
        viewModel.setInitialVideo(context, videoUri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editor Profesional", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.Close, "Cerrar")
                    }
                },
                actions = {
                    Box {
                        AssistChip(
                            onClick = { showQualityMenu = true },
                            label = { Text(selectedExportQuality.label) }
                        )
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
                                    android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Exportar", color = Color.White)
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
        ) {
            performanceProfile?.let { profile ->
                Text(
                    text = "Perfil: ${profile.label}",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            // 1. Preview Area
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxWidth()
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                // Video Preview (Using first video clip as representative for now)
                // Video Preview
                val mainVideoClip = timeline.tracks.find { it.type == TrackType.VIDEO }?.clips?.firstOrNull()
                if (mainVideoClip != null) {
                    val player = viewModel.getPlayer(context)
                    var isPlaying by remember { mutableStateOf(false) }
                    
                    // Ensure player has the correct media when clip changes
                    LaunchedEffect(mainVideoClip.uri) {
                        mainVideoClip.uri?.let { uri ->
                            val mediaItem = MediaItem.fromUri(uri)
                            player.setMediaItem(mediaItem)
                            player.prepare()
                            android.util.Log.d("VideoEditor", "Media prepared: $uri")
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
                                    isPlaying = !isPlaying
                                    if (isPlaying) {
                                        player.play()
                                    } else {
                                        player.pause()
                                    }
                                    android.util.Log.d("VideoEditor", "Player ${if(isPlaying) "playing" else "paused"}")
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
                                        .clickable { viewModel.selectClip(clip.id) }
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
                                        .clickable { viewModel.selectClip(clip.id) }
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
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Renderizando... ${(exportProgress * 100).toInt()}%", color = Color.White)
                        }
                    }
                }
            }

            // 2. Professional Timeline
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF121212))
            ) {
                TimelineView(
                    timeline = timeline,
                    currentTimeMs = currentTimeMs,
                    selectedClipId = selectedClipId,
                    pixelsPerSecond = performanceProfile?.editorPixelsPerSecond ?: 20f,
                    onSeek = { viewModel.updateCurrentTime(it) },
                    onSelectClip = { viewModel.selectClip(it) }
                )
            }

            // 3. TITAN Tool System (Nested Categories)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F0F0F))
            ) {
                // Sub-Panel (Shows tools of the active category)
                AnimatedVisibility(visible = activeCategory != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        IconButton(onClick = { activeCategory = null }) {
                            Icon(Icons.Default.ArrowBackIos, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                        
                        when (activeCategory) {
                            EditorCategory.EDIT -> {
                                SubToolItem(Icons.Default.ContentCut, "Dividir") {
                                    selectedClipId?.let { viewModel.splitClip(context, it, currentTimeMs) }
                                }
                                SubToolItem(Icons.Default.Delete, "Eliminar") {
                                    selectedClipId?.let { viewModel.removeClip(context, it) }
                                }
                                SubToolItem(Icons.Default.ContentCopy, "Duplicar") { 
                                    selectedClipId?.let { viewModel.duplicateClip(context, it) }
                                }
                                val clip = timeline.tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                val maxSpeed = (performanceProfile?.editorMaxSpeed ?: 3f).coerceAtLeast(1f)
                                if (clip != null && clip.type == ClipType.VIDEO) {
                                    Column(modifier = Modifier.width(220.dp)) {
                                        Text(
                                            text = "Velocidad ${String.format(Locale.US, "%.2fx", clip.speed)}",
                                            color = Color.White,
                                            fontSize = 11.sp
                                        )
                                        Slider(
                                            value = clip.speed,
                                            onValueChange = { viewModel.updateClipSpeed(clip.id, it) },
                                            valueRange = 0.5f..maxSpeed
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
                                
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
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
                                    Column(Modifier.weight(1f)) {
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
                                    // Filter selector
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
                                } else {
                                    Text("Selecciona un clip de video", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                            EditorCategory.RATIO -> {
                                val clip = timeline.tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (clip != null && clip.type == ClipType.VIDEO) {
                                    // Aspect ratio selector
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
                                } else {
                                    Text("Selecciona un clip de video", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                            else -> { Text("Próximamente", color = Color.Gray, fontSize = 12.sp) }
                        }
                    }
                }

                // Primary Category Bar (TITAN 10 Categories)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(65.dp)
                        .horizontalScroll(rememberScrollState())
                        .background(Color.Black),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CategoryItem(Icons.Default.Edit, "Editar", activeCategory == EditorCategory.EDIT) { activeCategory = EditorCategory.EDIT }
                    CategoryItem(Icons.Default.MusicNote, "Audio", activeCategory == EditorCategory.AUDIO) { activeCategory = EditorCategory.AUDIO }
                    CategoryItem(Icons.Default.TextFields, "Texto", activeCategory == EditorCategory.TEXT) { activeCategory = EditorCategory.TEXT }
                    CategoryItem(Icons.Default.SentimentSatisfied, "Stickers", activeCategory == EditorCategory.STICKER) { activeCategory = EditorCategory.STICKER }
                    CategoryItem(Icons.Default.Layers, "Superposición", activeCategory == EditorCategory.OVERLAY) { activeCategory = EditorCategory.OVERLAY }
                    CategoryItem(Icons.Default.AutoFixHigh, "Efectos", activeCategory == EditorCategory.EFFECTS) { activeCategory = EditorCategory.EFFECTS }
                    CategoryItem(Icons.Default.FilterBAndW, "Filtros", activeCategory == EditorCategory.FILTERS) { activeCategory = EditorCategory.FILTERS }
                    CategoryItem(Icons.Default.Tune, "Ajustar", activeCategory == EditorCategory.ADJUST) { activeCategory = EditorCategory.ADJUST }
                    CategoryItem(Icons.Default.AspectRatio, "Relación", activeCategory == EditorCategory.RATIO) { activeCategory = EditorCategory.RATIO }
                    CategoryItem(Icons.Default.PhotoSizeSelectActual, "Canvas", activeCategory == EditorCategory.CANVAS) { activeCategory = EditorCategory.CANVAS }
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
fun TimelineView(
    timeline: EditorTimeline,
    currentTimeMs: Long,
    selectedClipId: Long?,
    pixelsPerSecond: Float,
    onSeek: (Long) -> Unit,
    onSelectClip: (Long?) -> Unit
) {
    val scrollState = rememberScrollState()
    val effectivePixelsPerSecond = pixelsPerSecond.coerceIn(10f, 40f)

    Box(Modifier.fillMaxSize()) {
        // Tracks
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState)
                .padding(top = 24.dp)
        ) {
            timeline.tracks.forEach { track ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(Color.Gray.copy(alpha = 0.05f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.05f))
                ) {
                    track.clips.forEach { clip ->
                        val width = ((clip.durationMs / 1000f) * effectivePixelsPerSecond).dp
                        val offset = ((clip.startMs / 1000f) * effectivePixelsPerSecond).dp
                        
                        Box(
                            modifier = Modifier
                                .offset(x = offset)
                                .width(width)
                                .fillMaxHeight()
                                .padding(2.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when (track.type) {
                                        TrackType.VIDEO -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        TrackType.TEXT -> Color.Yellow.copy(alpha = 0.6f)
                                        TrackType.STICKER -> Color.Cyan.copy(alpha = 0.6f)
                                        else -> Color.Gray
                                    }
                                )
                                .border(
                                    if (selectedClipId == clip.id) 2.dp else 0.dp,
                                    Color.White,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { onSelectClip(clip.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (clip.type == ClipType.TEXT) clip.text ?: "" else clip.type.name,
                                color = Color.White,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // Center Playhead (The Red Line)
        // We move the timeline under the playhead, or the playhead over the timeline.
        // CapCut style: Playhead is fixed at the center, timeline scrolls.
        // For simplicity here, we'll put the playhead at the left and it moves, 
        // OR we just use the fixed center line and map scroll position to time.
        
        // FIXED CENTER PLAYHEAD (CapCut Style)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.Red)
        )
        
        // Transparent Overlay for Seeking
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Logic to update currentTimeMs based on scrollState
                }
        )
        
        // Sync currentTimeMs with Scroll
        LaunchedEffect(scrollState.value) {
           val time = ((scrollState.value / effectivePixelsPerSecond) * 1000f).toLong()
           onSeek(time)
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

enum class EditorCategory {
    EDIT, AUDIO, TEXT, STICKER, OVERLAY, EFFECTS, FILTERS, ADJUST, RATIO, CANVAS
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
