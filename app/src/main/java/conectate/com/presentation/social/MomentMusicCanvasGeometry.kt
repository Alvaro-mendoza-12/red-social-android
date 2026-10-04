package conectate.com.presentation.social

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Coordinates belong to the fitted media, independent of editor chrome or device size. */
internal data class MomentMediaRect(val left: Float, val top: Float, val width: Float, val height: Float)

internal fun momentFittedMediaRect(width: Float, height: Float, aspectRatio: Float): MomentMediaRect {
    val w = width.takeIf { it.isFinite() && it > 0 } ?: 1f
    val h = height.takeIf { it.isFinite() && it > 0 } ?: 1f
    val aspect = aspectRatio.takeIf { it.isFinite() && it > 0 } ?: (w / h)
    val mediaWidth = min(w, h * aspect)
    val mediaHeight = mediaWidth / aspect
    return MomentMediaRect((w - mediaWidth) / 2, (h - mediaHeight) / 2, mediaWidth, mediaHeight)
}

internal data class MomentMusicTransform(val x: Float, val y: Float, val scale: Float, val rotation: Float)

internal fun normalizeMomentMusicTransform(x: Float, y: Float, scale: Float, rotation: Float): MomentMusicTransform =
    MomentMusicTransform(
        x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .5f,
        y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .5f,
        scale.takeIf(Float::isFinite)?.coerceIn(.5f, 2f) ?: 1f,
        rotation.takeIf(Float::isFinite)?.let { ((it + 180f) % 360f + 360f) % 360f - 180f } ?: 0f,
    )

/** The rotated bounds remain inside the media, including a narrow portrait video. */
internal fun fitMomentMusicTransform(
    transform: MomentMusicTransform, rect: MomentMediaRect, stickerWidth: Float, stickerHeight: Float,
): MomentMusicTransform {
    val value = normalizeMomentMusicTransform(transform.x, transform.y, transform.scale, transform.rotation)
    val radians = Math.toRadians(value.rotation.toDouble())
    val boundWidth = (abs(cos(radians)) * stickerWidth + abs(sin(radians)) * stickerHeight).toFloat().coerceAtLeast(1f)
    val boundHeight = (abs(sin(radians)) * stickerWidth + abs(cos(radians)) * stickerHeight).toFloat().coerceAtLeast(1f)
    val fittedScale = min(value.scale, min(rect.width / boundWidth, rect.height / boundHeight)).coerceAtLeast(.01f)
    val insetX = (boundWidth * fittedScale / (2f * rect.width)).coerceIn(0f, .5f)
    val insetY = (boundHeight * fittedScale / (2f * rect.height)).coerceIn(0f, .5f)
    return value.copy(x = value.x.coerceIn(insetX, 1f - insetX), y = value.y.coerceIn(insetY, 1f - insetY), scale = fittedScale)
}
