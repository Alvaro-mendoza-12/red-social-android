package conectate.com.presentation.messages

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import conectate.com.presentation.components.ImageCropPreset
import conectate.com.presentation.components.SimpleImageCropDialog
import conectate.com.presentation.components.rememberReliableBottomSafePadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.request.videoFrameMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OutgoingMessageMedia(
    val uri: Uri,
    val type: String
)

private data class DeviceMedia(
    val id: Long,
    val uri: Uri,
    val type: String,
    val durationMs: Long,
    val dateAdded: Long,
    val dateModified: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
)

private data class NormalizedCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
)

private data class ComposerEditState(
    val rotateQuarterTurns: Int = 0,
    val drawEnabled: Boolean = false,
    val cropEnabled: Boolean = false,
    val crop: NormalizedCrop = NormalizedCrop(),
    val brushColor: Color = Color.White,
    val overlayText: String = "",
    val overlayEmoji: String = "",
    val strokes: List<List<Offset>> = emptyList(),
    val previewSize: IntSize = IntSize.Zero,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L
)

@Composable
fun MessageMediaComposer(
    initialFilter: String = "all",
    initialSelectedUris: List<Pair<Uri, String>> = emptyList(),
    initialStep: String = "gallery",
    maxItems: Int = 30,
    allowedMediaTypes: Set<String> = setOf("image", "video"),
    title: String = "Enviar foto o video",
    confirmContentDescription: String = "Enviar",
    showViewOnceOption: Boolean = true,
    showCaptionInput: Boolean = true,
    isSending: Boolean,
    onDismiss: () -> Unit,
    onSend: (List<OutgoingMessageMedia>, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectableMediaTypes = remember(allowedMediaTypes) {
        allowedMediaTypes
            .map(String::lowercase)
            .filter { it == "image" || it == "video" }
            .toSet()
            .ifEmpty { setOf("image", "video") }
    }
    var filter by remember(initialFilter, selectableMediaTypes) {
        mutableStateOf(
            when {
                initialFilter == "all" && selectableMediaTypes.size > 1 -> "all"
                initialFilter in selectableMediaTypes -> initialFilter
                else -> selectableMediaTypes.first()
            }
        )
    }
    var isLoading by remember { mutableStateOf(true) }
    var media by remember { mutableStateOf<List<DeviceMedia>>(emptyList()) }
    val selected = remember { mutableStateListOf<DeviceMedia>() }
    val edits = remember { mutableStateMapOf<Uri, ComposerEditState>() }
    var step by remember { mutableStateOf(initialStep) }
    var activeIndex by remember { mutableStateOf(0) }
    var caption by remember { mutableStateOf("") }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cropTargetUri by remember { mutableStateOf<Uri?>(null) }
    var viewOnceEnabled by remember { mutableStateOf(false) }

    val permissions = remember {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    var hasPermission by remember {
        mutableStateOf(permissions.any { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.any { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(permissions)
    }

    LaunchedEffect(initialSelectedUris) {
        if (initialSelectedUris.isNotEmpty() && selected.isEmpty()) {
            selected.addAll(initialSelectedUris.take(maxItems.coerceAtLeast(1)).map {
                DeviceMedia(
                    id = it.first.hashCode().toLong(),
                    uri = it.first,
                    type = if (it.second.startsWith("video")) "video" else "image",
                    durationMs = 0L,
                    dateAdded = 0L
                )
            })
        }
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        media = withContext(Dispatchers.IO) { loadMessageGalleryMedia(context) }
        isLoading = false
    }

    LaunchedEffect(selected.size) {
        if (selected.size != 1) viewOnceEnabled = false
    }

    Dialog(
        onDismissRequest = { if (!isSending && !preparing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        KeepMediaCanvasFixedWhileTyping()
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            when (step) {
                "gallery" -> MessageGalleryStep(
                    media = media.filter { it.type in selectableMediaTypes },
                    filter = filter,
                    onFilterChange = { filter = it },
                    allowedTypes = selectableMediaTypes,
                    selected = selected,
                    isLoading = isLoading,
                    hasPermission = hasPermission,
                    onDismiss = onDismiss,
                    onToggle = { item ->
                        if (selected.any { it.uri == item.uri }) {
                            selected.removeAll { it.uri == item.uri }
                        } else if (selected.size < maxItems.coerceAtLeast(1)) {
                            selected.add(item)
                        }
                    },
                    title = title,
                    onContinue = {
                        if (selected.isNotEmpty()) {
                            activeIndex = 0
                            step = "editor"
                        }
                    }
                )
                else -> MessageEditorStep(
                    selected = selected,
                    edits = edits,
                    activeIndex = activeIndex.coerceIn(0, (selected.size - 1).coerceAtLeast(0)),
                    caption = caption,
                    viewOnceEnabled = viewOnceEnabled,
                    showViewOnceOption = showViewOnceOption,
                    showCaptionInput = showCaptionInput,
                    isSending = isSending || preparing,
                    error = error,
                    confirmContentDescription = confirmContentDescription,
                    onCaptionChange = { caption = it.take(800) },
                    onViewOnceChange = { enabled ->
                        viewOnceEnabled = enabled && selected.size == 1
                    },
                    onBack = { step = "gallery" },
                    onDismiss = onDismiss,
                    onActiveIndexChange = { activeIndex = it.coerceIn(0, selected.lastIndex) },
                    onRemove = { item ->
                        selected.removeAll { it.uri == item.uri }
                        edits.remove(item.uri)
                        activeIndex = activeIndex.coerceAtMost((selected.size - 1).coerceAtLeast(0))
                        if (selected.isEmpty()) step = "gallery"
                    },
                    onEditChange = { uri, edit -> edits[uri] = edit },
                    onRequestCrop = { item -> cropTargetUri = item.uri },
                    onSend = {
                        if (isSending || preparing || selected.isEmpty()) return@MessageEditorStep
                        error = null
                        preparing = true
                        scope.launch {
                            val outgoing = mutableListOf<OutgoingMessageMedia>()
                            selected.forEach { item ->
                                val edit = edits[item.uri] ?: ComposerEditState()
                                val isGif = context.contentResolver.getType(item.uri)
                                    ?.equals("image/gif", ignoreCase = true) == true
                                val outputUri = when {
                                    item.type == "image" && !isGif -> withContext(Dispatchers.Default) {
                                        runCatching { processComposerImage(context, item.uri, edit) }
                                            .getOrElse { item.uri }
                                    }
                                    item.type == "video" && shouldTrimVideo(item, edit) -> {
                                        try {
                                            trimComposerVideo(
                                                context = context,
                                                sourceUri = item.uri,
                                                startMs = edit.trimStartMs,
                                                endMs = edit.trimEndMs.coerceAtMost(item.durationMs)
                                            )
                                        } catch (trimError: Exception) {
                                            if (trimError is CancellationException) throw trimError
                                            error = "No se pudo recortar el video. Inténtalo nuevamente."
                                            preparing = false
                                            return@launch
                                        }
                                    }
                                    else -> item.uri
                                }
                                val outgoingType = if (viewOnceEnabled && selected.size == 1) {
                                    if (item.type == "video") "view_once_video" else "view_once_image"
                                } else {
                                    item.type
                                }
                                outgoing += OutgoingMessageMedia(outputUri, outgoingType)
                            }
                            preparing = false
                            onSend(outgoing, caption.trim())
                        }
                    }
                )
            }
        }
    }

    cropTargetUri?.let { targetUri ->
        SimpleImageCropDialog(
            sourceUri = targetUri,
            title = "Recortar foto",
            presets = listOf(
                ImageCropPreset.ORIGINAL,
                ImageCropPreset.SQUARE,
                ImageCropPreset.PORTRAIT,
                ImageCropPreset.WIDE
            ),
            initialPreset = ImageCropPreset.FREE,
            onDismiss = { cropTargetUri = null },
            onCropped = { croppedUri ->
                val index = selected.indexOfFirst { it.uri == targetUri }
                if (index >= 0) {
                    val previous = selected[index]
                    val previousEdit = edits.remove(targetUri) ?: ComposerEditState()
                    selected[index] = previous.copy(
                        id = croppedUri.hashCode().toLong(),
                        uri = croppedUri,
                        dateModified = System.currentTimeMillis()
                    )
                    edits[croppedUri] = previousEdit.copy(
                        cropEnabled = false,
                        crop = NormalizedCrop()
                    )
                    activeIndex = index
                }
                cropTargetUri = null
            }
        )
    }
}

@Composable
private fun KeepMediaCanvasFixedWhileTyping() {
    val dialogView = LocalView.current
    DisposableEffect(dialogView) {
        val window = (dialogView.parent as? DialogWindowProvider)?.window
        val previousMode = window?.attributes?.softInputMode
        // Let Compose receive the IME inset so the action row stays above the
        // keyboard on short and tall devices. ADJUST_NOTHING caused the send
        // controls to be painted underneath the keyboard on several OEMs.
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            if (previousMode != null) window?.setSoftInputMode(previousMode)
        }
    }
}

@Composable
private fun MessageGalleryStep(
    media: List<DeviceMedia>,
    filter: String,
    onFilterChange: (String) -> Unit,
    allowedTypes: Set<String>,
    selected: List<DeviceMedia>,
    isLoading: Boolean,
    hasPermission: Boolean,
    onDismiss: () -> Unit,
    onToggle: (DeviceMedia) -> Unit,
    title: String,
    onContinue: () -> Unit
) {
    val filtered = remember(media, filter) {
        when (filter) {
            "image" -> media.filter { it.type == "image" }
            "video" -> media.filter { it.type == "video" }
            else -> media
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (selected.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onContinue,
                    modifier = Modifier.size(44.dp),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Done, contentDescription = "Continuar")
                }
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (allowedTypes.size > 1) {
                FilterChip("all", "Todo", filter, onFilterChange)
            }
            if ("image" in allowedTypes) {
                FilterChip("image", "Fotos", filter, onFilterChange)
            }
            if ("video" in allowedTypes) {
                FilterChip("video", "Videos", filter, onFilterChange)
            }
        }

        when {
            !hasPermission -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Permite acceso a fotos y videos para elegir archivos.", color = Color.White)
            }
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay media disponible.", color = Color.White)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filtered, key = { "${it.type}-${it.id}" }) { item ->
                    val selectedIndex = selected.indexOfFirst { it.uri == item.uri }
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable { onToggle(item) }
                    ) {
                        AsyncImage(
                            model = composerThumbnailRequest(LocalContext.current, item),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (item.type == "video") {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(5.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.62f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(formatComposerDuration(item.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (selectedIndex >= 0) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f))
                                .border(1.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedIndex >= 0) {
                                Text("${selectedIndex + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    id: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    AssistChip(
        onClick = { onSelect(id) },
        label = { Text(label, color = if (selected == id) Color.Black else Color.White) },
        leadingIcon = {
            Icon(
                imageVector = when (id) {
                    "image" -> Icons.Default.Image
                    "video" -> Icons.Default.VideoLibrary
                    else -> Icons.Default.AddPhotoAlternate
                },
                contentDescription = null,
                tint = if (selected == id) Color.Black else Color.White
            )
        },
        modifier = if (selected == id) Modifier.background(Color.White, RoundedCornerShape(20.dp)) else Modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageEditorStep(
    selected: List<DeviceMedia>,
    edits: Map<Uri, ComposerEditState>,
    activeIndex: Int,
    caption: String,
    viewOnceEnabled: Boolean,
    showViewOnceOption: Boolean,
    showCaptionInput: Boolean,
    isSending: Boolean,
    error: String?,
    confirmContentDescription: String,
    onCaptionChange: (String) -> Unit,
    onViewOnceChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onActiveIndexChange: (Int) -> Unit,
    onRemove: (DeviceMedia) -> Unit,
    onEditChange: (Uri, ComposerEditState) -> Unit,
    onRequestCrop: (DeviceMedia) -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    val reliableBottomPadding = rememberReliableBottomSafePadding()
    val active = selected.getOrNull(activeIndex) ?: return
    val edit = edits[active.uri] ?: ComposerEditState()
    val strokes = remember(active.uri, edit.strokes) { mutableStateListOf<List<Offset>>().apply { addAll(edit.strokes) } }
    var activeStroke by remember(active.uri) { mutableStateOf<List<Offset>>(emptyList()) }
    val brushPalette = remember {
        listOf(
            Color.White,
            Color(0xFFFF4D4D),
            Color(0xFFFFD54F),
            Color(0xFF4CAF50),
            Color(0xFF42A5F5),
            Color(0xFFAB47BC)
        )
    }
    val pagerState = rememberPagerState(
        initialPage = activeIndex.coerceIn(0, selected.lastIndex),
        pageCount = { selected.size }
    )

    LaunchedEffect(activeIndex, selected.size) {
        if (activeIndex in selected.indices && pagerState.currentPage != activeIndex) {
            pagerState.scrollToPage(activeIndex)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != activeIndex) {
            onActiveIndexChange(pagerState.currentPage)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .imePadding()
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !edit.drawEnabled && !edit.cropEnabled,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val pageItem = selected[page]
            val pageEdit = edits[pageItem.uri] ?: ComposerEditState()
            if (pageItem.type == "video") {
                ComposerVideoPreview(
                    item = pageItem,
                    edit = pageEdit,
                    isActive = page == activeIndex
                )
            } else {
                AsyncImage(
                    model = pageItem.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (page == activeIndex) {
                                Modifier
                                    .onSizeChanged {
                                        onEditChange(pageItem.uri, pageEdit.copy(previewSize = it))
                                    }
                                    .pointerInput(pageEdit.drawEnabled, pageItem.uri) {
                                        if (!pageEdit.drawEnabled) return@pointerInput
                                        detectDragGestures(
                                            onDragStart = { offset -> activeStroke = listOf(offset) },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                activeStroke = activeStroke + change.position
                                            },
                                            onDragEnd = {
                                                if (activeStroke.size > 1) {
                                                    strokes.add(activeStroke)
                                                    onEditChange(
                                                        pageItem.uri,
                                                        pageEdit.copy(strokes = strokes.toList())
                                                    )
                                                }
                                                activeStroke = emptyList()
                                            },
                                            onDragCancel = { activeStroke = emptyList() }
                                        )
                                    }
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }

        if (active.type == "image") {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                (strokes + listOf(activeStroke)).forEach { stroke ->
                    for (i in 1 until stroke.size) {
                        drawLine(
                            color = edit.brushColor,
                            start = stroke[i - 1],
                            end = stroke[i],
                            strokeWidth = 6f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
            if (edit.overlayText.isNotBlank() || edit.overlayEmoji.isNotBlank()) {
                Text(
                    text = listOf(edit.overlayEmoji, edit.overlayText).filter(String::isNotBlank).joinToString(" "),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.38f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 16.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, enabled = !isSending) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Galería", tint = Color.White)
            }
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showViewOnceOption && selected.size == 1) {
                    item {
                        RoundToolButton(
                            icon = Icons.Default.Visibility,
                            label = "Ver una vez",
                            selected = viewOnceEnabled
                        ) {
                            onViewOnceChange(!viewOnceEnabled)
                        }
                    }
                }
                if (active.type == "image") {
                    item {
                        RoundToolButton(Icons.Default.RotateRight, "Girar") {
                            onEditChange(active.uri, edit.copy(rotateQuarterTurns = (edit.rotateQuarterTurns + 1) % 4))
                        }
                    }
                    item {
                        RoundToolButton(Icons.Default.Crop, "Recortar libre") {
                            onRequestCrop(active)
                        }
                    }
                    item {
                        RoundToolButton(Icons.Default.Brush, "Dibujar", selected = edit.drawEnabled) {
                            onEditChange(
                                active.uri,
                                edit.copy(drawEnabled = !edit.drawEnabled, cropEnabled = false)
                            )
                        }
                    }
                    item {
                        RoundToolButton(Icons.Default.EmojiEmotions, "Emoji") {
                            onEditChange(active.uri, edit.copy(overlayEmoji = if (edit.overlayEmoji.isBlank()) "✨" else ""))
                        }
                    }
                    if (showCaptionInput) item {
                        RoundToolButton(Icons.Default.TextFields, "Texto", selected = edit.overlayText.isNotBlank()) {
                            onEditChange(active.uri, edit.copy(overlayText = if (edit.overlayText.isBlank()) caption.take(32) else ""))
                        }
                    }
                } else {
                    item { RoundToolButton(Icons.Default.Hd, "HD") {} }
                }
            }
            IconButton(onClick = onDismiss, enabled = !isSending) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
            }
        }

        if (active.type == "image") {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (edit.drawEnabled) "Dibujo" else if (edit.overlayText.isNotBlank()) "Texto" else "Editar",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                brushPalette.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (edit.brushColor == color) 3.dp else 1.dp,
                                color = if (edit.brushColor == color) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                shape = CircleShape
                            )
                            .clickable { onEditChange(active.uri, edit.copy(brushColor = color, drawEnabled = true)) }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset(y = -reliableBottomPadding)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.94f))))
                .padding(12.dp)
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(selected.size) { index ->
                    val item = selected[index]
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                width = if (index == activeIndex) 2.dp else 0.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onActiveIndexChange(index) }
                    ) {
                        AsyncImage(
                            model = composerThumbnailRequest(context, item),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (item.type == "video") {
                            Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(22.dp))
                        }
                        IconButton(
                            onClick = { onRemove(item) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            if (active.type == "video" && active.durationMs > 0L) {
                val trimEnd = edit.trimEndMs.takeIf { it > 0L }
                    ?.coerceAtMost(active.durationMs)
                    ?: active.durationMs
                val trimStart = edit.trimStartMs.coerceIn(0L, (trimEnd - 500L).coerceAtLeast(0L))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Recorte ${formatComposerDuration(trimStart)} – ${formatComposerDuration(trimEnd)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
                RangeSlider(
                    value = trimStart.toFloat()..trimEnd.toFloat(),
                    onValueChange = { range ->
                        val start = range.start.toLong()
                            .coerceAtMost((range.endInclusive.toLong() - 500L).coerceAtLeast(0L))
                        val end = range.endInclusive.toLong()
                            .coerceAtLeast(start + 500L)
                            .coerceAtMost(active.durationMs)
                        onEditChange(
                            active.uri,
                            edit.copy(trimStartMs = start, trimEndMs = end)
                        )
                    },
                    valueRange = 0f..active.durationMs.coerceAtLeast(1_000L).toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (showViewOnceOption && viewOnceEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "👁 Ver una vez: no se podrá abrir nuevamente",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    if (showCaptionInput) {
                        OutlinedTextField(
                            value = caption,
                            onValueChange = onCaptionChange,
                            placeholder = { Text("Añade un comentario...", color = Color.White.copy(alpha = 0.7f)) },
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                disabledTextColor = Color.White.copy(alpha = 0.55f),
                                cursorColor = Color.White,
                                focusedContainerColor = Color.Black.copy(alpha = 0.68f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.68f),
                                disabledContainerColor = Color.Black.copy(alpha = 0.42f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.42f),
                                focusedPlaceholderColor = Color.White.copy(alpha = 0.72f),
                                unfocusedPlaceholderColor = Color.White.copy(alpha = 0.72f)
                            )
                        )
                    }
                    if (!error.isNullOrBlank()) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = "${selected.size} seleccionado${if (selected.size == 1) "" else "s"}",
                        color = Color.White.copy(alpha = 0.86f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                FloatingActionButton(
                    onClick = { if (!isSending) onSend() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ) {
                    if (isSending) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Send, contentDescription = confirmContentDescription)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(42.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f),
                CircleShape
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Color.White else Color.Transparent,
                shape = CircleShape
            )
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ComposerVideoPreview(
    item: DeviceMedia,
    edit: ComposerEditState,
    isActive: Boolean
) {
    val context = LocalContext.current
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }

    LaunchedEffect(isActive, edit.trimStartMs, edit.trimEndMs, item.durationMs, player) {
        val start = edit.trimStartMs.coerceAtLeast(0L)
        val end = edit.trimEndMs.takeIf { it > start }
            ?.coerceAtMost(item.durationMs)
            ?: item.durationMs
        if (isActive) {
            player.seekTo(start)
            player.playWhenReady = true
            while (isActive) {
                if (end > start && player.currentPosition >= end) {
                    player.seekTo(start)
                }
                delay(100)
            }
        } else {
            player.pause()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { playerContext ->
            PlayerView(playerContext).apply {
                this.player = player
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setBackgroundColor(AndroidColor.BLACK)
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize()
    )
}

private fun composerThumbnailRequest(context: Context, item: DeviceMedia): ImageRequest {
    val key = "message-composer:${item.uri}:${item.dateModified}:${item.width}x${item.height}"
    return ImageRequest.Builder(context)
        .data(item.uri)
        .apply {
            if (item.type == "video") videoFrameMillis(min(1_000L, (item.durationMs / 3L).coerceAtLeast(0L)))
        }
        .size(Size(720, 720))
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .crossfade(false)
        .build()
}

private fun loadMessageGalleryMedia(context: Context): List<DeviceMedia> {
    val out = mutableListOf<DeviceMedia>()
    val imageProjection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT
    )
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        imageProjection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            out.add(
                DeviceMedia(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    type = "image",
                    durationMs = 0,
                    dateAdded = cursor.getLong(dateColumn),
                    dateModified = cursor.getLong(modifiedColumn),
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn)
                )
            )
        }
    }

    val videoProjection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT
    )
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        videoProjection,
        null,
        null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            out.add(
                DeviceMedia(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    type = "video",
                    durationMs = cursor.getLong(durationColumn),
                    dateAdded = cursor.getLong(dateColumn),
                    dateModified = cursor.getLong(modifiedColumn),
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn)
                )
            )
        }
    }
    return out.sortedByDescending { it.dateAdded }.take(500)
}

private fun ComposerEditState.hasRealEdits(): Boolean {
    return rotateQuarterTurns != 0 ||
        crop != NormalizedCrop() ||
        overlayText.isNotBlank() ||
        overlayEmoji.isNotBlank() ||
        strokes.any { it.size > 1 }
}

private fun processComposerImage(context: Context, sourceUri: Uri, edit: ComposerEditState): Uri {
    val decoded = decodeComposerBitmap(context, sourceUri) ?: return sourceUri
    val rotated = rotateComposerBitmap(decoded, edit.rotateQuarterTurns)
    val cropped = cropComposerBitmap(rotated, edit.crop)
    val merged = drawComposerOverlays(cropped, edit)
    val file = File(context.cacheDir, "message_media_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")
    FileOutputStream(file).use { output ->
        flattenComposerBitmapForJpeg(merged).compress(Bitmap.CompressFormat.JPEG, 86, output)
    }
    return file.toUri()
}

private fun cropComposerBitmap(bitmap: Bitmap, crop: NormalizedCrop): Bitmap {
    if (crop == NormalizedCrop()) return bitmap
    val left = (crop.left.coerceIn(0f, 1f) * bitmap.width).toInt()
        .coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
    val top = (crop.top.coerceIn(0f, 1f) * bitmap.height).toInt()
        .coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
    val right = (crop.right.coerceIn(0f, 1f) * bitmap.width).toInt()
        .coerceIn(left + 1, bitmap.width)
    val bottom = (crop.bottom.coerceIn(0f, 1f) * bitmap.height).toInt()
        .coerceIn(top + 1, bitmap.height)
    return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
}

private fun shouldTrimVideo(item: DeviceMedia, edit: ComposerEditState): Boolean {
    if (item.durationMs <= 0L || edit.trimEndMs <= 0L) return false
    return edit.trimStartMs > 0L || edit.trimEndMs < item.durationMs - 50L
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private suspend fun trimComposerVideo(
    context: Context,
    sourceUri: Uri,
    startMs: Long,
    endMs: Long
): Uri = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val output = File(
            context.cacheDir,
            "message_video_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.mp4"
        )
        val clippedItem = MediaItem.fromUri(sourceUri)
            .buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs.coerceAtLeast(0L))
                    .setEndPositionMs(endMs.coerceAtLeast(startMs + 500L))
                    .build()
            )
            .build()
        val transformer = androidx.media3.transformer.Transformer.Builder(context)
            .addListener(object : androidx.media3.transformer.Transformer.Listener {
                override fun onCompleted(
                    composition: androidx.media3.transformer.Composition,
                    result: androidx.media3.transformer.ExportResult
                ) {
                    if (continuation.isActive) continuation.resume(output.toUri())
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    result: androidx.media3.transformer.ExportResult,
                    exception: androidx.media3.transformer.ExportException
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
        runCatching { transformer.start(clippedItem, output.absolutePath) }
            .onFailure { error ->
                output.delete()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }
}

private fun decodeComposerBitmap(context: Context, uri: Uri): Bitmap? {
    val maxDimension = 2560
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = true
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > maxDimension) {
                    val scale = maxDimension.toFloat() / largest.toFloat()
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }.getOrNull()
}

private fun rotateComposerBitmap(bitmap: Bitmap, quarterTurns: Int): Bitmap {
    val turns = ((quarterTurns % 4) + 4) % 4
    if (turns == 0) return bitmap
    val matrix = Matrix().apply { postRotate((turns * 90).toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun drawComposerOverlays(bitmap: Bitmap, edit: ComposerEditState): Bitmap {
    val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = AndroidCanvas(mutable)
    val w = mutable.width.toFloat()
    val h = mutable.height.toFloat()
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = max(32f, w * 0.052f)
        setShadowLayer(8f, 0f, 0f, AndroidColor.BLACK)
    }
    val label = listOf(edit.overlayEmoji, edit.overlayText).filter(String::isNotBlank).joinToString(" ")
    if (label.isNotBlank()) {
        canvas.drawText(label.take(80), w * 0.08f, h * 0.18f, textPaint)
    }

    val size = edit.previewSize
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = edit.brushColor.toArgb()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = max(6f, w * 0.008f)
    }
    if (size.width > 0 && size.height > 0) {
        edit.strokes.forEach { stroke ->
            for (i in 1 until stroke.size) {
                val p0 = stroke[i - 1]
                val p1 = stroke[i]
                canvas.drawLine(
                    (p0.x / size.width.toFloat()).coerceIn(0f, 1f) * w,
                    (p0.y / size.height.toFloat()).coerceIn(0f, 1f) * h,
                    (p1.x / size.width.toFloat()).coerceIn(0f, 1f) * w,
                    (p1.y / size.height.toFloat()).coerceIn(0f, 1f) * h,
                    strokePaint
                )
            }
        }
    }
    return mutable
}

private fun flattenComposerBitmapForJpeg(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(flattened)
    canvas.drawColor(AndroidColor.WHITE)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    return flattened
}

private fun formatComposerDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
