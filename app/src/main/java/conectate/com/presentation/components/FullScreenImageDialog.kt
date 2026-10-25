package conectate.com.presentation.components

import android.util.Log
import android.view.WindowManager
import androidx.core.graphics.drawable.toBitmap
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.core.media.ViewOnceMediaStore
import conectate.com.core.media.StorageReadSession
import conectate.com.core.media.ProfileIdentityImageStore
import conectate.com.util.UbuntuMedia
import conectate.com.util.VideoCache
import conectate.com.util.VideoPerformanceProfiles
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.abs

data class MediaItem(
    val id: String,
    val url: String,
    val type: String,
    val thumbnailUrl: String? = null,
)

internal const val MIN_MEDIA_ZOOM = 1f
internal const val MAX_MEDIA_ZOOM = 3f
internal const val DOUBLE_TAP_MEDIA_ZOOM = 2f

internal fun canZoomLoadedImage(fittedUsable: Boolean, originalReady: Boolean, previewReady: Boolean, durableReady: Boolean): Boolean =
    fittedUsable && (originalReady || previewReady || durableReady)

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
        normalized.startsWith("image/") ||
        isVideoMediaType(normalized)
}

internal fun shouldShowBlockingImageProgress(
    isOriginalLoading: Boolean,
    isPreviewReady: Boolean
): Boolean = isOriginalLoading && !isPreviewReady

internal fun shouldShowBlockingImageError(
    hasOriginalError: Boolean,
    isPreviewReady: Boolean
): Boolean = hasOriginalError && !isPreviewReady

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullScreenImageDialog(
    imageUrl: String? = null,
    mediaItems: List<MediaItem> = emptyList(),
    initialIndex: Int = 0,
    onResolveUrl: (suspend (String) -> String?)? = null,
    onDownload: ((MediaItem) -> Unit)? = null,
    onShare: ((MediaItem) -> Unit)? = null,
    secureContent: Boolean = false,
    bottomOverlay: (@Composable (page: Int) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    // El contenido informativo debe terminar por encima del indicador de gestos,
    // no simplemente en el borde del inset. El margen adicional es visual y el
    // resto procede del inset real del dispositivo.
    val reliableBottomPadding = rememberReliableBottomSafePadding(contentClearance = 12.dp)
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

    val closeSecure by rememberUpdatedState(onDismiss)
    val secureLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val secureIdentity = remember(secureContent, items) { StorageReadSession.snapshot()?.identity }
    DisposableEffect(secureContent, items, secureLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (secureContent && event == androidx.lifecycle.Lifecycle.Event.ON_STOP) closeSecure()
        }
        if (secureContent) {
            secureLifecycle.addObserver(observer)
            if (!secureLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) closeSecure()
        }
        onDispose {
            secureLifecycle.removeObserver(observer)
            if (secureContent) items.forEach { ViewOnceMediaStore.release(it.url) }
        }
    }
    LaunchedEffect(secureContent, items, secureIdentity) {
        if (secureContent) while (true) {
            if (secureIdentity == null || StorageReadSession.snapshot()?.identity != secureIdentity || items.any { !ViewOnceMediaStore.owns(it.url) }) {
                closeSecure(); break
            }
            kotlinx.coroutines.delay(250)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView, secureContent) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            val wasAlreadySecure = window?.attributes?.flags
                ?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
            if (secureContent && !wasAlreadySecure) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            onDispose {
                if (secureContent && !wasAlreadySecure) {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
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
                    val requiresResolution = shouldResolveMediaViewerSource(item.url, resolver != null)
                    var resolutionAttempt by remember(item.id, item.url) { mutableIntStateOf(0) }
                    var resolvedUrl by remember(item.id, item.url, requiresResolution) {
                        mutableStateOf(item.url.takeUnless { requiresResolution })
                    }
                    var isResolving by remember(item.id, item.url, requiresResolution) {
                        mutableStateOf(requiresResolution)
                    }

                    LaunchedEffect(item.id, item.url, requiresResolution, resolutionAttempt) {
                        if (requiresResolution) {
                            isResolving = true
                            resolvedUrl = resolveMediaViewerSource(item.url, resolver)
                            isResolving = false
                        } else {
                            resolvedUrl = item.url
                            isResolving = false
                        }
                    }

                    if (!isVideoMediaType(item.type) && !secureContent) {
                        // Mount the saved preview immediately. Keep its request and
                        // zoom state stable when the resolver finds an offline original.
                        MediaViewerItem(
                            item = item,
                            isActive = pagerState.currentPage == page,
                            imageSource = if (isResolving) null else resolvedUrl ?: item.url
                        )
                    } else if (isResolving) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    } else if (resolvedUrl.isNullOrBlank()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No se pudo abrir este archivo", color = Color.White)
                            Button(onClick = { resolutionAttempt++ }) { Text("Reintentar") }
                        }
                    } else {
                        resolvedUrl?.takeIf(String::isNotBlank)?.let { playableUrl ->
                            MediaViewerItem(
                                item = item.copy(url = playableUrl),
                                isActive = pagerState.currentPage == page,
                                secureContent = secureContent
                            )
                        }
                    }
                }

                if (bottomOverlay != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // El diálogo usa edge-to-edge; sin reservar ambos lados el
                            // contenido de publicaciones puede quedar debajo del recorte
                            // de pantalla o fuera del viewport en landscape. El inset debe
                            // ir antes de fillMaxWidth: de lo contrario el ancho ya queda
                            // fijado y el padding horizontal se agrega por fuera del viewport.
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                            )
                            .fillMaxWidth()
                            .clipToBounds()
                    ) {
                        bottomOverlay(pagerState.currentPage)
                        // Un espaciador explícito conserva la reserva incluso en
                        // fabricantes cuyos Dialog edge-to-edge ignoran el padding
                        // inferior durante la medición del contenido.
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(reliableBottomPadding)
                        )
                    }
                } else if (items.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${items.size}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = -reliableBottomPadding)
                            .padding(bottom = reliableBottomPadding + 16.dp)
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
                if (!secureContent && onShare != null && currentMediaItem != null) {
                    IconButton(onClick = { currentMediaItem?.let(onShare) },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.58f), CircleShape)) {
                        Icon(Icons.Default.Share, "Compartir con otra aplicación", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                if (!secureContent && onDownload != null && currentMediaItem != null) {
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
    isActive: Boolean,
    secureContent: Boolean = false,
    imageSource: String? = item.url
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isVideoMediaType(item.type)) {
            VideoViewerItem(item = item, isActive = isActive, secureContent = secureContent)
        } else {
            ZoomableImageViewer(item = item, secureContent = secureContent, imageSource = imageSource)
        }
    }
}

@Composable
internal fun ZoomableImageViewer(
    item: MediaItem,
    allowDurableProfileFallback: Boolean = false,
    secureContent: Boolean = false,
    imageSource: String? = item.url
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var requestRevision by remember(item.id, item.url) { mutableIntStateOf(0) }
    val imageRequest = remember(context, item.url, imageSource, requestRevision, secureContent) {
        if (secureContent) coil.request.ImageRequest.Builder(context).data(item.url)
            .size(MediaImageRequestFactory.MAX_FULLSCREEN_IMAGE_DIMENSION_PX, MediaImageRequestFactory.MAX_FULLSCREEN_IMAGE_DIMENSION_PX)
            .memoryCachePolicy(coil.request.CachePolicy.DISABLED).diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .networkCachePolicy(coil.request.CachePolicy.DISABLED).crossfade(false).build()
        else imageSource?.let { MediaImageRequestFactory.fullScreen(context, it) }?.newBuilder()
            ?.setParameter("fullscreen_retry", requestRevision)?.build()
    }
    val previewRequest = remember(context, item.url, secureContent) {
        if (secureContent) null else MediaImageRequestFactory.preview(context, item.url)
    }
    val identityAccountId = LocalProfileIdentityAccount.current
    val identityGeneration = remember(item.url, identityAccountId) { ProfileIdentityImageStore.generation(identityAccountId) }
    var durableProfileImage by remember(context, item.url, allowDurableProfileFallback, identityAccountId) {
        mutableStateOf<java.io.File?>(null)
    }
    LaunchedEffect(context, item.url, allowDurableProfileFallback, identityAccountId) {
        if (allowDurableProfileFallback) durableProfileImage = ProfileIdentityImageStore.find(context, item.url, identityAccountId, identityGeneration)
    }
    var transform by remember(item.id, item.url) { mutableStateOf(MediaTransform()) }
    var viewportSize by remember(item.id, item.url) { mutableStateOf(IntSize.Zero) }

    var intrinsicSize by remember(item.id, item.url) { mutableStateOf(MediaDimensions(0f, 0f)) }
    var isImageLoading by remember(item.id, item.url, durableProfileImage) {
        mutableStateOf(durableProfileImage == null)
    }
    var hasImageError by remember(item.id, item.url) { mutableStateOf(false) }
    var isPreviewReady by remember(item.id, item.url, durableProfileImage) {
        mutableStateOf(durableProfileImage != null)
    }
    val density = LocalDensity.current
    val viewportDimensions = MediaDimensions(viewportSize.width.toFloat(), viewportSize.height.toFloat())
    val fittedDimensions = fitMediaInViewport(viewportDimensions, intrinsicSize)
    val canZoom = canZoomLoadedImage(fittedDimensions.isUsable, !isImageLoading && !hasImageError, isPreviewReady, durableProfileImage != null)

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
            .testTag("media_zoom_viewer")
            .semantics {
                stateDescription = "Ampliación ${(transform.scale * 100).toInt()} %"
                if (canZoom) customActions = listOf(
                    CustomAccessibilityAction("Ampliar imagen") {
                        transform = applyMediaTransformGesture(transform, MediaTranslation(viewportDimensions.width / 2, viewportDimensions.height / 2), MediaTranslation(0f, 0f), 1.5f, viewportDimensions, fittedDimensions); true
                    },
                    CustomAccessibilityAction("Reducir imagen") {
                        transform = applyMediaTransformGesture(transform, MediaTranslation(viewportDimensions.width / 2, viewportDimensions.height / 2), MediaTranslation(0f, 0f), 1 / 1.5f, viewportDimensions, fittedDimensions); true
                    },
                    CustomAccessibilityAction("Restablecer imagen") { transform = MediaTransform(); true }
                )
            }
            .pointerInput(item.id, item.url, canZoom, viewportSize, fittedDimensions) {
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
            .pointerInput(item.id, item.url, canZoom, viewportSize, fittedDimensions) {
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

        // This file survives Coil eviction and process restarts. Keep it below
        // the normal requests so the last known profile image remains visible
        // while a higher-resolution copy is being resolved offline.
        durableProfileImage?.let { cachedFile ->
            AsyncImage(
                model = cachedFile,
                contentDescription = "Imagen de perfil guardada",
                modifier = imageSizeModifier
                    .graphicsLayer(
                        scaleX = transform.scale,
                        scaleY = transform.scale,
                        translationX = transform.translation.x,
                        translationY = transform.translation.y
                    ),
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val drawable = state.result.drawable
                    if (!intrinsicSize.isUsable &&
                        drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0
                    ) {
                        intrinsicSize = MediaDimensions(
                            drawable.intrinsicWidth.toFloat(),
                            drawable.intrinsicHeight.toFloat()
                        )
                    }
                }
            )
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
            contentScale = ContentScale.Fit,
            onLoading = {
                isPreviewReady = durableProfileImage != null
            },
            onSuccess = { state ->
                isPreviewReady = true
                val drawable = state.result.drawable
                if (!intrinsicSize.isUsable && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    intrinsicSize = MediaDimensions(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
                }
            },
            onError = {
                isPreviewReady = durableProfileImage != null
            }
        )

        if (imageRequest != null) AsyncImage(
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
                if (allowDurableProfileFallback) {
                    runCatching { drawable.toBitmap() }
                        .getOrNull()
                        ?.let { bitmap ->
                            coroutineScope.launch {
                                ProfileIdentityImageStore.save(context, item.url, bitmap, identityAccountId, identityGeneration)
                            }
                        }
                }
            },
            onError = {
                isImageLoading = false
                hasImageError = true
            }
        )

        if (shouldShowBlockingImageProgress(isImageLoading, isPreviewReady)) {
            CircularProgressIndicator(color = Color.White)
        } else if (shouldShowBlockingImageError(hasImageError, isPreviewReady)) {
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
                Button(
                    onClick = {
                        hasImageError = false
                        isImageLoading = true
                        requestRevision += 1
                    },
                    modifier = Modifier.padding(top = 14.dp)
                ) {
                    Text("Reintentar")
                }
            }
        } else if (hasImageError && isPreviewReady) {
            Button(
                onClick = {
                    hasImageError = false
                    isImageLoading = true
                    requestRevision += 1
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.68f),
                    contentColor = Color.White
                ),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
            ) {
                Text("Cargar calidad original")
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoViewerItem(
    item: MediaItem,
    isActive: Boolean,
    secureContent: Boolean = false
) {
    val context = LocalContext.current
    var errorMessage by remember(item.id, item.url) { mutableStateOf<String?>(null) }
    var isVideoReady by remember(item.id, item.url) { mutableStateOf(false) }
    var isBuffering by remember(item.id, item.url) { mutableStateOf(true) }
    var requestRevision by remember(item.id, item.url) { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
    }

    val exoPlayer = remember(item.url, requestRevision, secureContent) {
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
                DefaultMediaSourceFactory(if (secureContent) conectate.com.util.MediaPlaybackDataSources.streamingFactory(context) else VideoCache.getCacheDataSourceFactory(context))
            )
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                isBuffering = false
                            }

                            Player.STATE_BUFFERING -> isBuffering = true
                            Player.STATE_ENDED -> isBuffering = false
                            else -> Unit
                        }
                    }

                    override fun onRenderedFirstFrame() { isVideoReady = true }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(
                            "FullScreenImageDialog",
                            "Video playback failed: ${error.errorCodeName}"
                        )
                        errorMessage = "No se pudo reproducir este video."
                        isBuffering = false
                    }
                })
                // Let Media3 sniff .media/content sources; they may contain MP4, WebM or other supported containers.
                setMediaItem(UbuntuMedia.mediaItem(item.url))
                prepare()
                playWhenReady = isActive && foreground
            }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> foreground = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isActive, foreground, exoPlayer) {
        exoPlayer.playWhenReady = isActive && foreground
        if (isActive && foreground) exoPlayer.play() else exoPlayer.pause()
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

        if (!secureContent && !isVideoReady) {
            val poster = remember(context, item.url, item.thumbnailUrl) {
                MediaImageRequestFactory.videoPoster(context, item.url, item.thumbnailUrl)
            }
            if (poster != null) {
                AsyncImage(model = poster, contentDescription = "Portada del video",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag("video_loading_poster"))
            }
        }

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
                        isVideoReady = false
                        exoPlayer.stop()
                        VideoCache.removeResource(context, item.url)
                        requestRevision++
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
