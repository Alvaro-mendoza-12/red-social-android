package conectate.com.presentation.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.util.UbuntuMedia
import conectate.com.util.VideoCache
import conectate.com.util.VideoPerformanceProfiles
import kotlin.math.min
import kotlin.math.abs

data class MediaItem(
    val id: String,
    val url: String,
    val type: String
)

internal const val MIN_MEDIA_ZOOM = 1f
internal const val MAX_MEDIA_ZOOM = 3f
internal const val DOUBLE_TAP_MEDIA_ZOOM = 2f

internal data class MediaDimensions(
    val width: Float,
    val height: Float
) {
    val isUsable: Boolean
        get() = width > 0f && height > 0f
}

internal data class MediaTranslation(
    val x: Float,
    val y: Float
)

internal data class MediaPanBounds(
    val horizontal: Float,
    val vertical: Float
)

internal data class MediaTransform(
    val scale: Float = MIN_MEDIA_ZOOM,
    val translation: MediaTranslation = MediaTranslation(0f, 0f)
)

internal fun fitMediaInViewport(
    viewport: MediaDimensions,
    media: MediaDimensions
): MediaDimensions {
    if (!viewport.isUsable || !media.isUsable) return MediaDimensions(0f, 0f)
    val fitScale = min(viewport.width / media.width, viewport.height / media.height)
    return MediaDimensions(
        width = media.width * fitScale,
        height = media.height * fitScale
    )
}

internal fun calculateMediaPanBounds(
    viewport: MediaDimensions,
    fittedMedia: MediaDimensions,
    scale: Float
): MediaPanBounds {
    if (!viewport.isUsable || !fittedMedia.isUsable) return MediaPanBounds(0f, 0f)
    val boundedScale = scale.coerceIn(MIN_MEDIA_ZOOM, MAX_MEDIA_ZOOM)
    return MediaPanBounds(
        horizontal = ((fittedMedia.width * boundedScale - viewport.width) / 2f).coerceAtLeast(0f),
        vertical = ((fittedMedia.height * boundedScale - viewport.height) / 2f).coerceAtLeast(0f)
    )
}

internal fun clampMediaTranslation(
    translation: MediaTranslation,
    bounds: MediaPanBounds
): MediaTranslation {
    return MediaTranslation(
        x = translation.x.coerceIn(-bounds.horizontal, bounds.horizontal),
        y = translation.y.coerceIn(-bounds.vertical, bounds.vertical)
    )
}

internal fun applyMediaTransformGesture(
    current: MediaTransform,
    centroid: MediaTranslation,
    pan: MediaTranslation,
    zoomChange: Float,
    viewport: MediaDimensions,
    fittedMedia: MediaDimensions
): MediaTransform {
    if (!viewport.isUsable || !fittedMedia.isUsable) return current
    val oldScale = current.scale.coerceIn(MIN_MEDIA_ZOOM, MAX_MEDIA_ZOOM)
    val newScale = (oldScale * zoomChange).coerceIn(MIN_MEDIA_ZOOM, MAX_MEDIA_ZOOM)
    val scaleChange = newScale / oldScale
    val centerX = viewport.width / 2f
    val centerY = viewport.height / 2f
    val translated = MediaTranslation(
        x = centroid.x - centerX - (centroid.x - centerX - current.translation.x) * scaleChange + pan.x,
        y = centroid.y - centerY - (centroid.y - centerY - current.translation.y) * scaleChange + pan.y
    )
    val bounds = calculateMediaPanBounds(viewport, fittedMedia, newScale)
    return MediaTransform(newScale, clampMediaTranslation(translated, bounds))
}

internal fun doubleTapMediaTransform(
    current: MediaTransform,
    tap: MediaTranslation,
    viewport: MediaDimensions,
    fittedMedia: MediaDimensions
): MediaTransform {
    if (current.scale > MIN_MEDIA_ZOOM + 0.01f) return MediaTransform()
    if (!viewport.isUsable || !fittedMedia.isUsable) return current

    val centerX = viewport.width / 2f
    val centerY = viewport.height / 2f
    val targetScale = DOUBLE_TAP_MEDIA_ZOOM
    val targetTranslation = MediaTranslation(
        x = (centerX - tap.x) * (targetScale - MIN_MEDIA_ZOOM),
        y = (centerY - tap.y) * (targetScale - MIN_MEDIA_ZOOM)
    )
    val bounds = calculateMediaPanBounds(viewport, fittedMedia, targetScale)
    return MediaTransform(targetScale, clampMediaTranslation(targetTranslation, bounds))
}

internal fun normalizedMediaIndex(requestedIndex: Int, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    return requestedIndex.coerceIn(0, itemCount - 1)
}

internal fun isVideoMediaType(type: String): Boolean {
    val normalized = type.trim().lowercase()
    return normalized == "video" || normalized.startsWith("video/") || normalized.endsWith("_video")
}

internal fun isGalleryMediaType(type: String): Boolean {
    val normalized = type.trim().lowercase()
    if (normalized.startsWith("view_once_")) return false
    return normalized == "image" ||
        normalized == "gif" ||
        normalized == "sticker" ||
        normalized.startsWith("image/") ||
        isVideoMediaType(normalized)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullScreenImageDialog(
    imageUrl: String? = null,
    mediaItems: List<MediaItem> = emptyList(),
    initialIndex: Int = 0,
    onResolveUrl: (suspend (String) -> String?)? = null,
    onDownload: ((MediaItem) -> Unit)? = null,
    bottomOverlay: (@Composable (page: Int) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val items = remember(imageUrl, mediaItems) {
        if (mediaItems.isNotEmpty()) {
            mediaItems
        } else {
            imageUrl?.takeIf(String::isNotBlank)?.let { listOf(MediaItem("single", it, "image")) }.orEmpty()
        }
    }
    var currentMediaItem by remember(items) {
        mutableStateOf(items.getOrNull(normalizedMediaIndex(initialIndex, items.size)))
    }
    var dismissDragY by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dismissDragY.coerceAtLeast(0f)
                        alpha = (1f - (dismissDragY.coerceAtLeast(0f) / size.height.coerceAtLeast(1f)) * 0.65f)
                            .coerceIn(0.35f, 1f)
                    }
                    .pointerInput(items) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                if (dragAmount > 0f || dismissDragY > 0f) {
                                    change.consume()
                                    dismissDragY = (dismissDragY + dragAmount).coerceAtLeast(0f)
                                }
                            },
                            onDragCancel = { dismissDragY = 0f },
                            onDragEnd = {
                                if (dismissDragY >= size.height * 0.16f || dismissDragY >= 120f) {
                                    onDismiss()
                                } else {
                                    dismissDragY = 0f
                                }
                            }
                        )
                    }
            ) {
                if (items.isNotEmpty()) {
                val safeInitialIndex = normalizedMediaIndex(initialIndex, items.size)
                val pagerState = rememberPagerState(
                    initialPage = safeInitialIndex,
                    pageCount = { items.size }
                )
                val resolver by rememberUpdatedState(onResolveUrl)

                LaunchedEffect(pagerState.currentPage, items) {
                    currentMediaItem = items.getOrNull(pagerState.currentPage)
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val item = items[page]
                    val requiresResolution = resolver != null && (
                        !item.url.startsWith("http", ignoreCase = true) ||
                            item.url.contains("/storage/v1/object/sign/", ignoreCase = true)
                        )
                    var resolvedUrl by remember(item.id, item.url, requiresResolution) {
                        mutableStateOf(item.url.takeUnless { requiresResolution })
                    }
                    var isResolving by remember(item.id, item.url, requiresResolution) {
                        mutableStateOf(requiresResolution)
                    }

                    LaunchedEffect(item.id, item.url, requiresResolution) {
                        if (requiresResolution) {
                            isResolving = true
                            resolvedUrl = runCatching { resolver?.invoke(item.url) }
                                .onFailure { Log.w("FullScreenImageDialog", "Media URL resolution failed", it) }
                                .getOrNull()
                                ?.takeIf(String::isNotBlank)
                                ?: item.url
                            isResolving = false
                        } else {
                            resolvedUrl = item.url
                            isResolving = false
                        }
                    }

                    if (isResolving) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    } else {
                        resolvedUrl?.takeIf(String::isNotBlank)?.let { playableUrl ->
                            MediaViewerItem(
                                item = item.copy(url = playableUrl),
                                isActive = pagerState.currentPage == page
                            )
                        }
                    }
                }

                if (bottomOverlay != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        bottomOverlay(pagerState.currentPage)
                    }
                } else if (items.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${items.size}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.58f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

                Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDownload != null && currentMediaItem != null) {
                    IconButton(
                        onClick = { currentMediaItem?.let(onDownload) },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.58f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Descargar",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.58f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = Color.White
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun MediaViewerItem(
    item: MediaItem,
    isActive: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isVideoMediaType(item.type)) {
            VideoViewerItem(item = item, isActive = isActive)
        } else {
            ZoomableImageViewer(item = item)
        }
    }
}

@Composable
internal fun ZoomableImageViewer(item: MediaItem) {
    val context = LocalContext.current
    val imageRequest = remember(context, item.url) {
        MediaImageRequestFactory.fullScreen(context, item.url)
    }
    val previewRequest = remember(context, item.url) {
        MediaImageRequestFactory.preview(context, item.url)
    }
    var transform by remember(item.id, item.url) { mutableStateOf(MediaTransform()) }
    var viewportSize by remember(item.id, item.url) { mutableStateOf(IntSize.Zero) }

    var intrinsicSize by remember(item.id, item.url) { mutableStateOf(MediaDimensions(0f, 0f)) }
    var isImageLoading by remember(item.id, item.url) { mutableStateOf(true) }
    var hasImageError by remember(item.id, item.url) { mutableStateOf(false) }
    val density = LocalDensity.current
    val viewportDimensions = MediaDimensions(viewportSize.width.toFloat(), viewportSize.height.toFloat())
    val fittedDimensions = fitMediaInViewport(viewportDimensions, intrinsicSize)
    val canZoom = fittedDimensions.isUsable && !isImageLoading && !hasImageError

    LaunchedEffect(viewportSize, fittedDimensions) {
        if (transform.scale <= MIN_MEDIA_ZOOM + 0.01f || !canZoom) {
            transform = MediaTransform()
        } else {
            val bounds = calculateMediaPanBounds(
                viewport = viewportDimensions,
                fittedMedia = fittedDimensions,
                scale = transform.scale
            )
            transform = transform.copy(translation = clampMediaTranslation(transform.translation, bounds))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .clipToBounds()
            .pointerInput(item.id, item.url, canZoom) {
                if (!canZoom) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        transform = doubleTapMediaTransform(
                            current = transform,
                            tap = MediaTranslation(tapOffset.x, tapOffset.y),
                            viewport = viewportDimensions,
                            fittedMedia = fittedDimensions
                        )
                    }
                )
            }
            .pointerInput(item.id, item.url, canZoom) {
                if (!canZoom) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }
                        val shouldHandle = pressedPointers > 1 || transform.scale > MIN_MEDIA_ZOOM + 0.01f
                        if (shouldHandle && pressedPointers > 0) {
                            val centroid: Offset = event.calculateCentroid(useCurrent = true)
                            val pan: Offset = event.calculatePan()
                            val zoom = if (pressedPointers > 1) event.calculateZoom() else 1f
                            if (centroid != Offset.Unspecified) {
                                transform = applyMediaTransformGesture(
                                    current = transform,
                                    centroid = MediaTranslation(centroid.x, centroid.y),
                                    pan = MediaTranslation(pan.x, pan.y),
                                    zoomChange = zoom,
                                    viewport = viewportDimensions,
                                    fittedMedia = fittedDimensions
                                )
                                event.changes.forEach { change ->
                                    if (change.position != change.previousPosition) change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val imageSizeModifier = if (fittedDimensions.isUsable) {
            Modifier.size(
                width = with(density) { fittedDimensions.width.toDp() },
                height = with(density) { fittedDimensions.height.toDp() }
            )
        } else {
            Modifier.fillMaxSize()
        }

        // Keep the already downloaded preview visible while the original is
        // decoded. This also works when Coil evicted it from memory but still
        // has it on disk, avoiding the black flash from a memory-only placeholder.
        AsyncImage(
            model = previewRequest,
            contentDescription = null,
            modifier = imageSizeModifier
                .graphicsLayer(
                    scaleX = transform.scale,
                    scaleY = transform.scale,
                    translationX = transform.translation.x,
                    translationY = transform.translation.y
                ),
            contentScale = ContentScale.Fit
        )

        AsyncImage(
            model = imageRequest,
            contentDescription = if (item.type.equals("gif", ignoreCase = true)) {
                "GIF a pantalla completa"
            } else {
                "Imagen a pantalla completa"
            },
            modifier = imageSizeModifier
                .graphicsLayer(
                    scaleX = transform.scale,
                    scaleY = transform.scale,
                    translationX = transform.translation.x,
                    translationY = transform.translation.y
                ),
            contentScale = ContentScale.Fit,
            onLoading = {
                isImageLoading = true
                hasImageError = false
            },
            onSuccess = { state ->
                val drawable = state.result.drawable
                if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    intrinsicSize = MediaDimensions(
                        drawable.intrinsicWidth.toFloat(),
                        drawable.intrinsicHeight.toFloat()
                    )
                }
                isImageLoading = false
                hasImageError = false
            },
            onError = {
                isImageLoading = false
                hasImageError = true
            }
        )

        if (isImageLoading) {
            CircularProgressIndicator(color = Color.White)
        } else if (hasImageError) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp)
                )
                Text(
                    text = "No se pudo cargar este medio.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoViewerItem(
    item: MediaItem,
    isActive: Boolean
) {
    val context = LocalContext.current
    var errorMessage by remember(item.id, item.url) { mutableStateOf<String?>(null) }
    var isVideoReady by remember(item.id, item.url) { mutableStateOf(false) }
    var isBuffering by remember(item.id, item.url) { mutableStateOf(true) }

    val exoPlayer = remember(item.url) {
        val performanceProfile = VideoPerformanceProfiles.detect(context)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                performanceProfile.minBufferMs,
                performanceProfile.maxBufferMs,
                performanceProfile.playBufferMs,
                performanceProfile.rebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(VideoCache.getCacheDataSourceFactory(context))
            )
            .build()
            .apply {
                val path = item.url.substringBefore("?")
                val hasKnownVideoExtension = listOf(".mp4", ".mkv", ".webm", ".3gp", ".m3u8")
                    .any { path.endsWith(it, ignoreCase = true) }
                val mediaItem = UbuntuMedia.mediaItem(item.url)
                    .buildUpon()
                    .apply {
                        if (!hasKnownVideoExtension) setMimeType(MimeTypes.VIDEO_MP4)
                    }
                    .build()
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = isActive

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                isVideoReady = true
                                isBuffering = false
                            }

                            Player.STATE_BUFFERING -> isBuffering = true
                            Player.STATE_ENDED -> isBuffering = false
                            else -> Unit
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(
                            "FullScreenImageDialog",
                            "Video playback failed: ${error.errorCodeName}",
                            error
                        )
                        errorMessage = "No se pudo reproducir este video."
                        isBuffering = false
                    }
                })
            }
    }

    LaunchedEffect(isActive, exoPlayer) {
        exoPlayer.playWhenReady = isActive
        if (isActive) exoPlayer.play() else exoPlayer.pause()
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { playerContext ->
                PlayerView(playerContext).apply {
                    player = exoPlayer
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { playerView -> playerView.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        if ((isBuffering || !isVideoReady) && errorMessage == null) {
            CircularProgressIndicator(color = Color.White)
        }

        errorMessage?.let { message ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.76f))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp)
                )
                Text(
                    text = message,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Button(
                    onClick = {
                        errorMessage = null
                        isBuffering = true
                        exoPlayer.prepare()
                        if (isActive) exoPlayer.play()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Reintentar", color = Color.White)
                }
            }
        }
    }
}
