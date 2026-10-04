package conectate.com.presentation.social

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import conectate.com.data.model.MomentMusicPresentation
import kotlin.math.roundToInt

/** Shared editor/viewer canvas. A null change handler makes published music read only. */
@Composable
internal fun MomentMusicCanvasOverlay(
    presentation: MomentMusicPresentation,
    title: String,
    artist: String?,
    artworkUrl: String?,
    mediaAspectRatio: Float,
    overlayX: Float = .5f,
    overlayY: Float = .5f,
    overlayScale: Float = 1f,
    overlayRotation: Float = 0f,
    rawLyrics: String? = null,
    playbackPositionMs: Long = 0L,
    durationMs: Long? = null,
    modifier: Modifier = Modifier,
    onTransform: ((MomentMusicTransform) -> Unit)? = null,
) {
    if (presentation == MomentMusicPresentation.AUDIO_ONLY) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rect = momentFittedMediaRect(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() }, mediaAspectRatio)
        val designWidth = when (presentation) {
            MomentMusicPresentation.CARD -> 240.dp
            MomentMusicPresentation.LABEL -> 300.dp
            MomentMusicPresentation.LYRICS -> 340.dp
            MomentMusicPresentation.AUDIO_ONLY -> return@BoxWithConstraints
        }
        val designWidthPx = with(density) { designWidth.roundToPx() }.coerceAtLeast(1)
        val baseScale = rect.width * .78f / designWidthPx
        var size by remember(presentation) { mutableStateOf(IntSize.Zero) }
        val requested = normalizeMomentMusicTransform(overlayX, overlayY, overlayScale, overlayRotation)
        val fitted = fitMomentMusicTransform(requested, rect, size.width * baseScale, size.height * baseScale)
        val current by rememberUpdatedState(fitted)
        val change by rememberUpdatedState(onTransform)
        val interaction = if (onTransform == null) Modifier else Modifier
            .semantics {
                contentDescription = "Música movible. Arrastra, pellizca para cambiar el tamaño y gira con dos dedos."
                customActions = listOf(
                    CustomAccessibilityAction("Centrar música") { change?.invoke(requested.copy(x = .5f, y = .5f)); true },
                    CustomAccessibilityAction("Mover arriba") { change?.invoke(current.copy(y = current.y - .08f)); true },
                    CustomAccessibilityAction("Mover abajo") { change?.invoke(current.copy(y = current.y + .08f)); true },
                    CustomAccessibilityAction("Mover izquierda") { change?.invoke(current.copy(x = current.x - .08f)); true },
                    CustomAccessibilityAction("Mover derecha") { change?.invoke(current.copy(x = current.x + .08f)); true },
                    CustomAccessibilityAction("Aumentar tamaño") { change?.invoke(current.copy(scale = current.scale * 1.1f)); true },
                    CustomAccessibilityAction("Reducir tamaño") { change?.invoke(current.copy(scale = current.scale / 1.1f)); true },
                    CustomAccessibilityAction("Girar 15 grados") { change?.invoke(current.copy(rotation = current.rotation + 15f)); true },
                    CustomAccessibilityAction("Restablecer tamaño y giro") { change?.invoke(MomentMusicTransform(.5f, .5f, 1f, 0f)); true },
                )
            }
            .pointerInput(rect, presentation) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    val old = current
                    val radians = Math.toRadians(old.rotation.toDouble())
                    val panX = (pan.x * kotlin.math.cos(radians) - pan.y * kotlin.math.sin(radians)).toFloat() * old.scale * baseScale
                    val panY = (pan.x * kotlin.math.sin(radians) + pan.y * kotlin.math.cos(radians)).toFloat() * old.scale * baseScale
                    val next = normalizeMomentMusicTransform(old.x + panX / rect.width, old.y + panY / rect.height, old.scale * zoom, old.rotation + rotation)
                    change?.invoke(fitMomentMusicTransform(next, rect, size.width * baseScale, size.height * baseScale))
                }
            }
        Layout(modifier = Modifier.fillMaxSize(), content = {
          Box(Modifier.onSizeChanged { size = it }.then(interaction).testTag("moment_music_canvas_sticker")) {
            MomentMusicPresentationPreview(presentation, title, artist, artworkUrl, modifier = Modifier.fillMaxWidth(),
                rawLyrics = rawLyrics, playbackPositionMs = playbackPositionMs, durationMs = durationMs)
          }
        }) { measurables, constraints ->
            val sticker = measurables.single().measure(Constraints(minWidth = designWidthPx, maxWidth = designWidthPx))
            val placed = fitMomentMusicTransform(requested, rect, sticker.width * baseScale, sticker.height * baseScale)
            layout(constraints.maxWidth, constraints.maxHeight) {
                sticker.placeWithLayer(
                    (rect.left + rect.width * placed.x - sticker.width / 2f).roundToInt(),
                    (rect.top + rect.height * placed.y - sticker.height / 2f).roundToInt(),
                ) {
                    scaleX = baseScale * placed.scale; scaleY = baseScale * placed.scale
                    rotationZ = placed.rotation
                }
            }
        }
    }
}
