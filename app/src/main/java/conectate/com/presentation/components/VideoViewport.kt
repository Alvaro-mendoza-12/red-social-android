package conectate.com.presentation.components

/** Size a frame inside its viewport without cropping or stretching its pixels. */
internal fun containedVideoFraction(viewportRatio: Float, frameRatio: Float): Pair<Float, Float> {
    if (!viewportRatio.isFinite() || !frameRatio.isFinite() || viewportRatio <= 0f || frameRatio <= 0f) {
        return 1f to 1f
    }
    return if (frameRatio > viewportRatio) 1f to viewportRatio / frameRatio
    else frameRatio / viewportRatio to 1f
}
