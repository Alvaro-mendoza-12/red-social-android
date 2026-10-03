package conectate.com.presentation.social

import conectate.com.data.localfallback.UbuntuStorageUrl

internal enum class MomentImageLoadState { LOADING, READY, FAILED }

/** Ask our storage server for screen-sized bytes, not a multi-megabyte camera original. */
internal fun momentImagePreviewUrl(url: String, viewportWidth: Int): String =
    UbuntuStorageUrl.thumbnail(url, width = viewportWidth.coerceIn(320, 1440), quality = 86) ?: url

/** Bound decoded pixels to the screen and 2160 on the longest edge. */
internal fun momentImageDecodeSize(width: Int, height: Int): Pair<Int, Int> {
    val w = width.coerceIn(1, 10_000)
    val h = height.coerceIn(1, 10_000)
    val factor = (2160.0 / maxOf(w, h)).coerceAtMost(1.0)
    return (w * factor).toInt().coerceAtLeast(1) to (h * factor).toInt().coerceAtLeast(1)
}
