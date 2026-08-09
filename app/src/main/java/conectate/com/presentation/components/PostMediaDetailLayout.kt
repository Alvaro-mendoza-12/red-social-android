package conectate.com.presentation.components

internal data class PostMediaDetailLayout(
    val mediaHeightDp: Float,
    val mediaUsesScrollableHeight: Boolean,
)

/**
 * Preserves the media aspect ratio while keeping pathological images bounded.
 * The detail itself scrolls, so portrait media may be taller than the viewport
 * without placing metadata over the image.
 */
internal fun calculatePostMediaDetailLayout(
    viewportWidthDp: Float,
    viewportHeightDp: Float,
    mediaWidth: Float,
    mediaHeight: Float,
): PostMediaDetailLayout {
    if (viewportWidthDp <= 0f || viewportHeightDp <= 0f) {
        return PostMediaDetailLayout(mediaHeightDp = 240f, mediaUsesScrollableHeight = false)
    }
    val safeAspectRatio = if (mediaWidth > 0f && mediaHeight > 0f) {
        (mediaWidth / mediaHeight).coerceIn(0.25f, 4f)
    } else {
        0.8f
    }
    val naturalHeight = viewportWidthDp / safeAspectRatio
    val minimumHeight = minOf(220f, viewportHeightDp * 0.42f)
    val maximumHeight = maxOf(minimumHeight, viewportHeightDp * 1.35f)
    val boundedHeight = naturalHeight.coerceIn(minimumHeight, maximumHeight)
    return PostMediaDetailLayout(
        mediaHeightDp = boundedHeight,
        mediaUsesScrollableHeight = boundedHeight > viewportHeightDp * 0.72f,
    )
}
