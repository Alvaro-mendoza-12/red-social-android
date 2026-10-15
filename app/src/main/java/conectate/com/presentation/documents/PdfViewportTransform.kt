package conectate.com.presentation.documents

internal data class PdfViewportTransform(val zoom: Float, val panX: Float, val panY: Float)

/** Ignore incomplete pointer samples; an unspecified centroid must never enter graphicsLayer. */
internal fun pdfViewportTransform(
    zoom: Float, panX: Float, panY: Float, width: Int, height: Int,
    centroidX: Float, centroidY: Float, zoomChange: Float, deltaX: Float, deltaY: Float
): PdfViewportTransform? {
    if (width <= 0 || height <= 0 || zoom <= 0f || zoomChange <= 0f ||
        !listOf(zoom, panX, panY, centroidX, centroidY, zoomChange, deltaX, deltaY).all { it.isFinite() }
    ) return null
    val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
    val shift = 1f - nextZoom / zoom
    val maxX = (nextZoom - 1f) * width / 2f
    val maxY = (nextZoom - 1f) * height / 2f
    return PdfViewportTransform(nextZoom,
        (panX + deltaX + (centroidX - width / 2f) * shift).coerceIn(-maxX, maxX),
        (panY + deltaY + (centroidY - height / 2f) * shift).coerceIn(-maxY, maxY))
}
