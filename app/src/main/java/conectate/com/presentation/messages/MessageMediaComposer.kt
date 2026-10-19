package conectate.com.presentation.messages

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.min
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OutgoingMessageMedia(
    val uri: Uri,
    val type: String
)

internal data class DeviceMedia(
    val id: Long,
    val uri: Uri,
    val type: String,
    val durationMs: Long,
    val dateAdded: Long,
    val dateModified: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
)

private data class ComposerEditState(
    val rotateQuarterTurns: Int = 0,
    val drawEnabled: Boolean = false,
    val brushColor: Color = Color.White,
    val overlayText: String = "",
    val overlayEmoji: String = "",
    val strokes: List<List<Offset>> = emptyList(),
    val strokeColors: List<Int> = emptyList(),
    val strokeWidths: List<Float> = emptyList(),
    val brushWidth: Float = 0.008f,
    val textSize: Float = 0.052f,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L
)

private data class ComposerPhotoRepresentation(val source: Uri, val state: MessagePhotoRenderState)

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
    var cropOriginalUri by remember { mutableStateOf<Uri?>(null) }
    val cropPreparedPhotos = remember { mutableStateListOf<Uri>() }
    val readyPhotos = remember { mutableStateMapOf<Uri, ComposerPhotoRepresentation>() }
    val photoSources = remember { mutableStateMapOf<Uri, Uri>() }
    val sourceFailures = remember { mutableStateMapOf<Uri, Boolean>() }
    val ownedCrops = remember { mutableStateListOf<Uri>() }
    val photoSourceStore = remember(context) { MessagePhotoSourceStore(context) }
    var sourceRetry by remember { mutableStateOf(0) }
    DisposableEffect(photoSourceStore) {
        onDispose {
            photoSourceStore.close()
            discardPreparedComposerOutputs(context, cropPreparedPhotos.toList())
            discardOwnedComposerCrops(context, ownedCrops.toList())
        }
    }
    LaunchedEffect(selected.map { it.uri to it.type }, sourceRetry) {
        selected.toList().filter { it.type == "image" && it.uri !in photoSources }.forEach { item ->
            try {
                val source = photoSourceStore.sourceFor(item.uri)
                if (selected.any { it.uri == item.uri }) {
                    photoSources[item.uri] = source
                    sourceFailures.remove(item.uri)
                } else photoSourceStore.forget(item.uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { sourceFailures[item.uri] = true }
        }
    }
    var viewOnceEnabled by remember { mutableStateOf(false) }

    val permissions = remember(selectableMediaTypes) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> buildList {
                if ("image" in selectableMediaTypes) add(Manifest.permission.READ_MEDIA_IMAGES)
                if ("video" in selectableMediaTypes) add(Manifest.permission.READ_MEDIA_VIDEO)
                if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }.toTypedArray()
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
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val picks = withContext(Dispatchers.IO) { uris.take(maxItems.coerceAtLeast(1)).mapNotNull { uri ->
                val type = context.contentResolver.getType(uri)?.substringBefore('/')?.lowercase()
                if (type !in selectableMediaTypes) return@mapNotNull null
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                DeviceMedia(id = uri.hashCode().toLong(), uri = uri, type = type!!, durationMs = 0, dateAdded = System.currentTimeMillis() / 1000)
            } }
            picks.forEach { item -> if (selected.size < maxItems && selected.none { it.uri == item.uri }) selected.add(item) }
            if (picks.isNotEmpty()) { activeIndex = selected.lastIndex; step = "editor" }
        }
    }

    LaunchedEffect(step) {
        if (step == "gallery" && !hasPermission) permissionLauncher.launch(permissions)
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

    LaunchedEffect(hasPermission, step, selectableMediaTypes) {
        if (step != "gallery") return@LaunchedEffect
        if (!hasPermission) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        try { media = withContext(Dispatchers.IO) { loadMessageGalleryMedia(context, selectableMediaTypes) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "No se pudo leer la galería. Puedes elegir archivos con Android." }
        finally { isLoading = false }
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
                "camera" -> ChatCameraCapture(
                    allowedTypes = selectableMediaTypes,
                    onClose = onDismiss,
                    onGallery = { step = "gallery" },
                    onCaptured = { captured, durationMs ->
                        if (selected.size < maxItems.coerceAtLeast(1)) {
                            selected.add(DeviceMedia(
                                id = captured.uri.hashCode().toLong(),
                                uri = captured.uri,
                                type = captured.type,
                                durationMs = durationMs,
                                dateAdded = System.currentTimeMillis() / 1000,
                            ))
                            activeIndex = selected.lastIndex
                            step = "editor"
                        }
                    },
                )
                "gallery" -> MessageGalleryStep(
                    media = media.filter { it.type in selectableMediaTypes },
                    filter = filter,
                    onFilterChange = { filter = it },
                    allowedTypes = selectableMediaTypes,
                    selected = selected,
                    isLoading = isLoading,
                    hasPermission = hasPermission,
                    onDismiss = onDismiss,
                    onSystemPicker = { systemPicker.launch(selectableMediaTypes.map { "$it/*" }.toTypedArray()) },
                    onToggle = { item ->
                        if (selected.any { it.uri == item.uri }) {
                            selected.removeAll { it.uri == item.uri }
                            photoSourceStore.forget(item.uri)
                            photoSources.remove(item.uri)
                            sourceFailures.remove(item.uri)
                            readyPhotos.remove(item.uri)
                            edits.remove(item.uri)
                            if (ownedCrops.remove(item.uri)) discardOwnedComposerCrops(context, listOf(item.uri))
                            if (cropPreparedPhotos.remove(item.uri)) discardPreparedComposerOutputs(context, listOf(item.uri))
                        } else if (selected.size < maxItems.coerceAtLeast(1)) {
                            selected.add(item)
                        }
                    },
                    title = title,
                    onCamera = if (selected.size < maxItems.coerceAtLeast(1)) ({ step = "camera" }) else null,
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
                    photoSources = photoSources,
                    animatedPhotos = photoSources.keys.filter { photoSourceStore.mimeTypeFor(it) == "image/gif" }.toSet(),
                    sourceFailures = sourceFailures,
                    onRetrySource = { uri ->
                        if (!isSending && !preparing) {
                            photoSourceStore.forget(uri)
                            photoSources.remove(uri)
                            sourceFailures.remove(uri)
                            readyPhotos.remove(uri)
                            sourceRetry++
                        }
                    },
                    activeIndex = activeIndex.coerceIn(0, (selected.size - 1).coerceAtLeast(0)),
                    caption = caption,
                    viewOnceEnabled = viewOnceEnabled,
                    showViewOnceOption = showViewOnceOption,
                    showCaptionInput = showCaptionInput,
                    isSending = isSending || preparing,
                    error = error,
                    canSend = selected.getOrNull(activeIndex)?.let { item ->
                        item.type != "image" || photoSources[item.uri]?.let { source ->
                            readyPhotos[item.uri] == ComposerPhotoRepresentation(source, (edits[item.uri] ?: ComposerEditState()).photoState())
                        } == true
                    } == true,
                    onPhotoReady = { uri, source, state, ready ->
                        if (photoSources[uri] == source && (edits[uri] ?: ComposerEditState()).photoState() == state) {
                            if (ready) readyPhotos[uri] = ComposerPhotoRepresentation(source, state) else readyPhotos.remove(uri)
                        }
                    },
                    confirmContentDescription = confirmContentDescription,
                    onCaptionChange = { if (!isSending && !preparing) caption = it.take(800) },
                    onViewOnceChange = { enabled ->
                        if (!isSending && !preparing) viewOnceEnabled = enabled && selected.size == 1
                    },
                    onBack = { if (!isSending && !preparing) step = "gallery" },
                    onDismiss = onDismiss,
                    onActiveIndexChange = { if (!isSending && !preparing) activeIndex = it.coerceIn(0, selected.lastIndex) },
                    onRemove = { item ->
                        if (isSending || preparing) return@MessageEditorStep
                        selected.removeAll { it.uri == item.uri }
                        edits.remove(item.uri)
                        readyPhotos.remove(item.uri)
                        photoSourceStore.forget(item.uri)
                        photoSources.remove(item.uri)
                        sourceFailures.remove(item.uri)
                        if (ownedCrops.remove(item.uri)) discardOwnedComposerCrops(context, listOf(item.uri))
                        if (cropPreparedPhotos.remove(item.uri)) discardPreparedComposerOutputs(context, listOf(item.uri))
                        activeIndex = activeIndex.coerceAtMost((selected.size - 1).coerceAtLeast(0))
                        if (selected.isEmpty()) step = "gallery"
                    },
                    onEditChange = { uri, edit -> if (!isSending && !preparing) edits[uri] = edit },
                    onRequestCrop = { item ->
                        val state = (edits[item.uri] ?: ComposerEditState()).photoState()
                        if (!isSending && !preparing && photoSources[item.uri]?.let { readyPhotos[item.uri] == ComposerPhotoRepresentation(it, state) } == true) {
                            preparing = true
                            error = null
                            scope.launch {
                                try {
                                    // Bake all visible edits once; crop this exact representation.
                                    val source = photoSources[item.uri] ?: photoSourceStore.sourceFor(item.uri)
                                    val baked = exportMessagePhoto(context, source, state)
                                    cropPreparedPhotos += baked
                                    cropOriginalUri = item.uri
                                    cropTargetUri = baked
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = "No se pudo preparar la foto para recortar. Inténtalo nuevamente."
                                } finally { preparing = false }
                            }
                        }
                    },
                    onSend = {
                        if (isSending || preparing || selected.isEmpty()) return@MessageEditorStep
                        val active = selected.getOrNull(activeIndex) ?: return@MessageEditorStep
                        if (active.type == "image" && photoSources[active.uri]?.let {
                                readyPhotos[active.uri] == ComposerPhotoRepresentation(it, (edits[active.uri] ?: ComposerEditState()).photoState())
                            } != true) {
                            return@MessageEditorStep
                        }
                        error = null
                        preparing = true
                        val selectedSnapshot = selected.toList()
                        val editsSnapshot = edits.toMap()
                        val captionSnapshot = caption.trim()
                        val viewOnceSnapshot = viewOnceEnabled
                        scope.launch {
                            val outgoing = mutableListOf<OutgoingMessageMedia>()
                            val preparedOutputs = mutableListOf<Uri>()
                            var failedPhoto: Uri? = null
                            try {
                                selectedSnapshot.forEach { item ->
                                    failedPhoto = item.uri.takeIf { item.type == "image" }
                                    val edit = editsSnapshot[item.uri] ?: ComposerEditState()
                                    val source = if (item.type == "image") photoSourceStore.sourceFor(item.uri) else item.uri
                                    val isGif = item.type == "image" && photoSourceStore.mimeTypeFor(item.uri) == "image/gif"
                                    val outputUri = when {
                                        item.type == "image" && isGif && !edit.hasRealEdits() ->
                                            prepareComposerGif(context, source).also { preparedOutputs += it }
                                        item.type == "image" ->
                                            exportMessagePhoto(context, source, edit.photoState()).also { preparedOutputs += it }
                                        item.type == "video" && shouldTrimVideo(item, edit) -> trimComposerVideo(
                                            context = context,
                                            sourceUri = item.uri,
                                            startMs = edit.trimStartMs,
                                            endMs = edit.trimEndMs.coerceAtMost(item.durationMs)
                                        )
                                        else -> item.uri
                                    }
                                    if (outputUri != item.uri && outputUri !in preparedOutputs) preparedOutputs += outputUri
                                    val outgoingType = if (viewOnceSnapshot && selectedSnapshot.size == 1) {
                                        if (item.type == "video") "view_once_video" else "view_once_image"
                                    } else {
                                        item.type
                                    }
                                    outgoing += OutgoingMessageMedia(outputUri, outgoingType)
                                    failedPhoto = null
                                }
                            } catch (cancelled: CancellationException) {
                                discardPreparedComposerOutputs(context, preparedOutputs)
                                throw cancelled
                            } catch (_: Exception) {
                                discardPreparedComposerOutputs(context, preparedOutputs)
                                failedPhoto?.let { uri ->
                                    // Retry explicitly creates and represents a new snapshot before Send.
                                    photoSources.remove(uri)
                                    readyPhotos.remove(uri)
                                    sourceFailures[uri] = true
                                }
                                error = "No se pudo preparar la foto o el video. Revisa el archivo e inténtalo nuevamente."
                                return@launch
                            } finally {
                                preparing = false
                            }
                            onSend(outgoing, captionSnapshot)
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
            onDismiss = {
                discardPreparedComposerOutputs(context, listOf(targetUri))
                cropPreparedPhotos.remove(targetUri)
                cropTargetUri = null
                cropOriginalUri = null
            },
            onCropped = { croppedUri ->
                val originalUri = cropOriginalUri
                val index = selected.indexOfFirst { it.uri == originalUri }
                if (index >= 0) {
                    val previous = selected[index]
                    originalUri?.let {
                        edits.remove(it); readyPhotos.remove(it)
                        photoSourceStore.forget(it); photoSources.remove(it); sourceFailures.remove(it)
                        if (ownedCrops.remove(it)) discardOwnedComposerCrops(context, listOf(it))
                        if (cropPreparedPhotos.remove(it)) discardPreparedComposerOutputs(context, listOf(it))
                    }
                    if (croppedUri != targetUri) ownedCrops += croppedUri
                    selected[index] = previous.copy(
                        id = croppedUri.hashCode().toLong(),
                        uri = croppedUri,
                        dateModified = System.currentTimeMillis()
                    )
                    // Rotation, text and strokes are already baked into the cropped pixels.
                    edits[croppedUri] = ComposerEditState()
                    activeIndex = index
                }
                if (croppedUri != targetUri) {
                    discardPreparedComposerOutputs(context, listOf(targetUri))
                    cropPreparedPhotos.remove(targetUri)
                }
                cropTargetUri = null
                cropOriginalUri = null
            }
        )
    }
}

@Composable
private fun KeepMediaCanvasFixedWhileTyping() {
    val dialogView = LocalView.current
    DisposableEffect(dialogView) {
        val window = (dialogView.parent as? DialogWindowProvider)?.window
        val usesLegacyResize = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        val previousMode = window?.attributes?.softInputMode?.takeIf { usesLegacyResize }
        // Let Compose receive the IME inset so the action row stays above the
        // keyboard on short and tall devices. ADJUST_NOTHING caused the send
        // controls to be painted underneath the keyboard on several OEMs. On
        // Android 11+ decorFitsSystemWindows=false + imePadding is authoritative.
        if (usesLegacyResize) window?.enableLegacyImeResize()
        onDispose {
            if (previousMode != null) window?.setSoftInputMode(previousMode)
        }
    }
}

@Suppress("DEPRECATION")
private fun android.view.Window.enableLegacyImeResize() {
    // API 24-29 require this flag; the constant is deprecated only because
    // edge-to-edge/insets replaced it on newer Android versions.
    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageGalleryStep(
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
    onContinue: () -> Unit,
    onCamera: (() -> Unit)? = null,
    onSystemPicker: (() -> Unit)? = null,
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
            if (onSystemPicker != null) IconButton(onClick = onSystemPicker) {
                Icon(Icons.Default.FolderOpen, "Elegir archivos con Android", tint = Color.White)
            }
            if (onCamera != null) IconButton(onClick = onCamera) {
                Icon(Icons.Default.CameraAlt, "Abrir cámara", tint = Color.White)
            }
            if (selected.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onContinue,
                    modifier = Modifier.size(48.dp),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Done, contentDescription = "Continuar")
                }
            }
        }

        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                itemsIndexed(filtered, key = { _, item -> "${item.type}-${item.id}" }) { index, item ->
                    val selectedIndex = selected.indexOfFirst { it.uri == item.uri }
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .semantics {
                                contentDescription = "${if (item.type == "video") "Video" else "Foto"} ${index + 1}"
                                this.selected = selectedIndex >= 0
                                stateDescription = if (selectedIndex >= 0) "Seleccionado, posición ${selectedIndex + 1}"
                                    else "Sin seleccionar"
                            }
                            .testTag("composer_gallery_${item.type}_${item.id}")
                            .clickable(role = Role.Checkbox) { onToggle(item) }
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
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected == id) Color.White else Color.Black,
        )
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageEditorStep(
    selected: List<DeviceMedia>,
    edits: Map<Uri, ComposerEditState>,
    photoSources: Map<Uri, Uri>,
    animatedPhotos: Set<Uri>,
    sourceFailures: Map<Uri, Boolean>,
    onRetrySource: (Uri) -> Unit,
    activeIndex: Int,
    caption: String,
    viewOnceEnabled: Boolean,
    showViewOnceOption: Boolean,
    showCaptionInput: Boolean,
    isSending: Boolean,
    error: String?,
    canSend: Boolean,
    onPhotoReady: (Uri, Uri, MessagePhotoRenderState, Boolean) -> Unit,
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
    var showTextEditor by remember(active.uri) { mutableStateOf(false) }
    var showEmojiEditor by remember(active.uri) { mutableStateOf(false) }
    if (showTextEditor) AlertDialog(
        onDismissRequest = { showTextEditor = false }, title = { Text("Texto sobre la foto") },
        text = { Column {
            OutlinedTextField(value = edit.overlayText, onValueChange = { onEditChange(active.uri, edit.copy(overlayText = it.take(120))) }, label = { Text("Escribe tu texto") }, maxLines = 3)
            Text("Tamaño")
            Slider(value = edit.textSize, onValueChange = { onEditChange(active.uri, edit.copy(textSize = it)) }, valueRange = 0.025f..0.12f)
        } },
        confirmButton = { TextButton(onClick = { showTextEditor = false }) { Text("Listo") } },
        dismissButton = { TextButton(onClick = { onEditChange(active.uri, edit.copy(overlayText = "")); showTextEditor = false }) { Text("Quitar texto") } },
    )
    if (showEmojiEditor) AlertDialog(
        onDismissRequest = { showEmojiEditor = false }, title = { Text("Añadir un emoji") },
        text = { FlowRow { listOf("✨", "❤️", "😂", "🔥", "🎉", "😍", "👍", "🌈", "🎂", "💫").forEach { emoji ->
            TextButton(onClick = { onEditChange(active.uri, edit.copy(overlayEmoji = emoji)); showEmojiEditor = false }) { Text(emoji, style = MaterialTheme.typography.headlineMedium) }
        } } },
        confirmButton = { TextButton(onClick = { onEditChange(active.uri, edit.copy(overlayEmoji = "")); showEmojiEditor = false }) { Text("Quitar emoji") } },
    )

    val isAnimatedImage = active.uri in animatedPhotos
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
            userScrollEnabled = !isSending && !edit.drawEnabled,
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
                val photoState = pageEdit.photoState()
                val source = photoSources[pageItem.uri]
                if (source == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (sourceFailures[pageItem.uri] == true) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No se pudo abrir la foto.", color = Color.White)
                                TextButton(onClick = { onRetrySource(pageItem.uri) }, enabled = !isSending) { Text("Reintentar") }
                            }
                        } else CircularProgressIndicator()
                    }
                } else MessagePhotoPreview(
                    sourceUri = source,
                    state = photoState,
                    drawEnabled = page == activeIndex && pageEdit.drawEnabled,
                    interactionEnabled = page == activeIndex && !isSending,
                    onStrokeAdded = { stroke ->
                        onEditChange(pageItem.uri, pageEdit.copy(strokes = pageEdit.strokes + listOf(stroke), strokeColors = pageEdit.strokeColors + pageEdit.brushColor.toArgb(), strokeWidths = pageEdit.strokeWidths + pageEdit.brushWidth))
                    },
                    onReadyChanged = { ready -> onPhotoReady(pageItem.uri, source, photoState, ready) },
                    modifier = Modifier.fillMaxSize(),
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Galería", tint = Color.White)
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
                            selected = viewOnceEnabled,
                            enabled = !isSending,
                        ) {
                            onViewOnceChange(!viewOnceEnabled)
                        }
                    }
                }
                if (active.type == "image") {
                    item { RoundToolButton(Icons.AutoMirrored.Filled.Undo, "Deshacer último trazo", enabled = !isSending && edit.strokes.isNotEmpty()) {
                        onEditChange(active.uri, edit.copy(strokes = edit.strokes.dropLast(1), strokeColors = edit.strokeColors.dropLast(1), strokeWidths = edit.strokeWidths.dropLast(1)))
                    } }
                    item { RoundToolButton(Icons.Default.RestartAlt, "Restablecer edición", enabled = !isSending && edit.hasRealEdits()) { onEditChange(active.uri, ComposerEditState()) } }
                    item {
                        RoundToolButton(Icons.AutoMirrored.Filled.RotateRight, "Girar", enabled = !isSending) {
                            onEditChange(active.uri, edit.copy(rotateQuarterTurns = (edit.rotateQuarterTurns + 1) % 4))
                        }
                    }
                    item {
                        RoundToolButton(Icons.Default.Crop, "Recortar libre", enabled = !isSending && canSend) {
                            onRequestCrop(active)
                        }
                    }
                    item {
                        RoundToolButton(Icons.Default.Brush, "Dibujar", selected = edit.drawEnabled, enabled = !isSending) {
                            onEditChange(
                                active.uri,
                                edit.copy(drawEnabled = !edit.drawEnabled)
                            )
                        }
                    }
                    item {
                        RoundToolButton(Icons.Default.EmojiEmotions, "Emoji", enabled = !isSending) {
                            showEmojiEditor = true
                        }
                    }
                    if (showCaptionInput) item {
                        RoundToolButton(Icons.Default.TextFields, "Texto", selected = edit.overlayText.isNotBlank(), enabled = !isSending) {
                            showTextEditor = true
                        }
                    }
                }
            }
            IconButton(onClick = onDismiss, enabled = !isSending) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset(y = -(reliableBottomPadding + 12.dp))
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.94f))))
                .padding(
                    start = 12.dp,
                    top = 12.dp,
                    end = 12.dp,
                    bottom = reliableBottomPadding
                )
        ) {
            if (active.type == "image" && edit.drawEnabled) {
                Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Color del pincel", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            brushPalette.forEach { color ->
                                Box(Modifier.size(32.dp).padding(3.dp).clip(CircleShape).background(color)
                                    .border(if (edit.brushColor == color) 3.dp else 1.dp, if (edit.brushColor == color) MaterialTheme.colorScheme.primary else Color.White, CircleShape)
                                    .semantics { contentDescription = "Color del pincel"; this.selected = edit.brushColor == color }
                                    .clickable(enabled = !isSending) { onEditChange(active.uri, edit.copy(brushColor = color)) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Grosor", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            Slider(value = edit.brushWidth, onValueChange = { onEditChange(active.uri, edit.copy(brushWidth = it)) }, valueRange = 0.002f..0.04f, enabled = !isSending, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

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
                            .clickable(enabled = !isSending) { onActiveIndexChange(index) }
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
                            enabled = !isSending,
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
                    enabled = !isSending,
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
                    modifier = Modifier.fillMaxWidth().testTag("message_video_trim")
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

            // The caption row is visually lifted by the safe inset below. Reserve
            // that height in the layout too so thumbnails and captions can never
            // occupy the same pixels on compact or gesture-navigation devices.
            Spacer(Modifier.height(reliableBottomPadding))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .offset(y = -reliableBottomPadding),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    if (showCaptionInput) {
                        OutlinedTextField(
                            value = caption,
                            onValueChange = onCaptionChange,
                            enabled = !isSending,
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
                    if (isAnimatedImage && edit.hasRealEdits()) {
                        Text("Al editar un GIF se enviará como una foto fija.", color = Color.White,
                            style = MaterialTheme.typography.bodySmall)
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
                    onClick = { if (!isSending && canSend) onSend() },
                    modifier = Modifier.semantics {
                        contentDescription = confirmContentDescription
                        if (isSending || !canSend) disabled()
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    if (isSending) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(44.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp)
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Color.White else Color.Transparent,
                shape = CircleShape
            )
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
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

private fun loadMessageGalleryMedia(context: Context, allowedTypes: Set<String>): List<DeviceMedia> {
    val out = mutableListOf<DeviceMedia>()
    val imageProjection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT
    )
    if ("image" in allowedTypes) try { context.contentResolver.query(
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

    } catch (_: SecurityException) { /* Android may grant access to videos only. The system picker remains available. */ }

    val videoProjection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT
    )
    if ("video" in allowedTypes) try { context.contentResolver.query(
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
    } catch (_: SecurityException) { /* Partial gallery access is valid. */ }
    return out.sortedByDescending { it.dateAdded }
}

private fun ComposerEditState.hasRealEdits(): Boolean {
    return rotateQuarterTurns != 0 ||
        overlayText.isNotBlank() ||
        overlayEmoji.isNotBlank() ||
        strokes.any { it.size > 1 }
}

private fun ComposerEditState.photoState() = MessagePhotoRenderState(
    rotateQuarterTurns = rotateQuarterTurns,
    overlayText = overlayText,
    overlayEmoji = overlayEmoji,
    strokes = strokes,
    brushColor = brushColor.toArgb(),
    strokeColors = strokeColors,
    strokeWidths = strokeWidths,
    textSize = textSize,
)

private fun discardPreparedComposerOutputs(context: Context, outputs: List<Uri>) {
    outputs.filter { it.scheme == "file" }.forEach { uri ->
        val file = File(uri.path ?: return@forEach).canonicalFile
        if (file.parentFile == context.cacheDir.canonicalFile &&
            (file.name.startsWith("message_media_") || file.name.startsWith("message_video_"))) file.delete()
    }
}

// These content URIs are registered only from our crop callback; never delete an input URI.
private fun discardOwnedComposerCrops(context: Context, outputs: List<Uri>) {
    val root = conectate.com.core.media.ShareableMediaFileStore.crops(context).canonicalFile
    outputs.filter { it.scheme == "content" && it.authority == "${context.packageName}.provider" }
        .forEach { uri ->
            if (uri.pathSegments.size != 2 || uri.pathSegments.first() != "shared_crops") return@forEach
            val name = uri.lastPathSegment ?: return@forEach
            if (!Regex("crop_[0-9a-f-]{36}\\.jpg").matches(name)) return@forEach
            val file = File(root, name).canonicalFile
            if (file.parentFile == root) file.delete()
        }
}

private suspend fun prepareComposerGif(context: Context, source: Uri): Uri {
    var output: File? = null
    try {
        return withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "message_media_${UUID.randomUUID()}.gif")
            output = file
            val input = context.contentResolver.openInputStream(source) ?: throw java.io.IOException("IMAGE_SOURCE_UNAVAILABLE")
            input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
            check(file.length() > 0) { "IMAGE_OUTPUT_EMPTY" }
            currentCoroutineContext().ensureActive()
            file.toUri()
        }
    } catch (failure: Throwable) { output?.delete(); throw failure }
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
                    if (!output.isFile || output.length() == 0L) {
                        output.delete()
                        if (continuation.isActive) continuation.resumeWithException(java.io.IOException("VIDEO_OUTPUT_EMPTY"))
                    } else if (continuation.isActive) continuation.resume(output.toUri())
                    else output.delete()
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
            val cancelExport = Runnable {
                try { transformer.cancel() } finally { output.delete() }
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) cancelExport.run()
            else android.os.Handler(android.os.Looper.getMainLooper()).post(cancelExport)
        }
        if (continuation.isActive) runCatching { transformer.start(clippedItem, output.absolutePath) }
            .onFailure { error ->
                output.delete()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }
}

private fun formatComposerDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
