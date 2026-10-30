package conectate.com.presentation.videos

import kotlin.math.min

/** Positions in saved drafts use the same 0..1000 space as the exporter. */
internal const val EDITOR_OVERLAY_SPACE = 1000f
internal data class EditorVideoCanvas(val left: Float, val top: Float, val width: Float, val height: Float)

internal fun fittedEditorVideoCanvas(width: Float, height: Float, aspectRatio: Float): EditorVideoCanvas {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f || !aspectRatio.isFinite() || aspectRatio <= 0f) {
        return EditorVideoCanvas(0f, 0f, 0f, 0f)
    }
    val fittedWidth = min(width, height * aspectRatio)
    val fittedHeight = fittedWidth / aspectRatio
    return EditorVideoCanvas((width - fittedWidth) / 2f, (height - fittedHeight) / 2f, fittedWidth, fittedHeight)
}

internal fun editorOverlayCoordinate(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, EDITOR_OVERLAY_SPACE) else 0f

internal fun editorOverlayAnchor(x: Float, y: Float): Pair<Float, Float> =
    (editorOverlayCoordinate(x) / 500f - 1f) to (1f - editorOverlayCoordinate(y) / 500f)

internal fun editorOverlayFontPixels(fontSize: Float, videoWidth: Float): Float =
    (fontSize.takeIf { it.isFinite() } ?: 32f).coerceIn(16f, 56f) * videoWidth / 360f
