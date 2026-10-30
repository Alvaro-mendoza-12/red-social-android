package conectate.com.presentation.videos

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun EditorOverlayPreview(
    timeline: EditorTimeline,
    timeMs: Long,
    selectedClipId: Long?,
    aspectRatio: Float,
    onMove: (Long, Float, Float) -> Unit,
    onSelect: (Long) -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frame = fittedEditorVideoCanvas(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), aspectRatio)
        if (frame.width <= 0f || frame.height <= 0f) return@BoxWithConstraints
        Box(Modifier.offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
            .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() }).clipToBounds()
            .testTag("editor_overlay_canvas")) {
            timeline.tracks.flatMap { it.clips }
                .filter { it.type in setOf(ClipType.TEXT, ClipType.STICKER) && timeMs >= it.startMs && timeMs < it.endMs }
                .forEach { clip -> key(clip.id) {
                    val current by rememberUpdatedState(clip)
                    val move by rememberUpdatedState(onMove)
                    val modifier = Modifier
                        .testTag("editor_overlay_${clip.id}")
                        .offset { IntOffset((editorOverlayCoordinate(clip.x) / EDITOR_OVERLAY_SPACE * frame.width).roundToInt(),
                            (editorOverlayCoordinate(clip.y) / EDITOR_OVERLAY_SPACE * frame.height).roundToInt()) }
                        .pointerInput(clip.id, frame.width, frame.height) {
                            var x = 0f
                            var y = 0f
                            detectDragGestures(onDragStart = { x = current.x; y = current.y }) { change, delta ->
                                change.consume()
                                x = editorOverlayCoordinate(x + delta.x / frame.width * EDITOR_OVERLAY_SPACE)
                                y = editorOverlayCoordinate(y + delta.y / frame.height * EDITOR_OVERLAY_SPACE)
                                move(clip.id, x, y)
                            }
                        }
                        .border(if (selectedClipId == clip.id) 1.dp else 0.dp, MaterialTheme.colorScheme.primary)
                        .clickable { onSelect(clip.id) }
                    if (clip.type == ClipType.TEXT) {
                        Text(clip.text.orEmpty(), modifier = modifier, color = Color(clip.color),
                            maxLines = 1, softWrap = false,
                            fontSize = with(density) { editorOverlayFontPixels(clip.fontSize, frame.width).toSp() })
                    } else {
                        Image(painterResource(clip.resId ?: android.R.drawable.star_on), "Sticker del video",
                            modifier.size(with(density) { (frame.width / 6f).toDp() }), contentScale = ContentScale.FillBounds)
                    }
                } }
        }
    }
}
