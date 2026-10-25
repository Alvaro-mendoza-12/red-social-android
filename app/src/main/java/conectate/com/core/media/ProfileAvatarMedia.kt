package conectate.com.core.media

import android.content.Context
import coil.request.ImageRequest
import java.util.Locale

internal fun isVideoProfileAvatar(source: String?, mediaType: String?): Boolean {
    val type = mediaType?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val path = source.avatarPath()
    return type == "video" || type.startsWith("video/") ||
        listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".3gp").any(path::endsWith)
}

internal fun profileAvatarImageRequest(
    context: Context,
    source: String?,
    mediaType: String?,
): ImageRequest? {
    if (isVideoProfileAvatar(source, mediaType)) return null
    val type = mediaType?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val path = source.avatarPath()
    // GIF/WebP may contain multiple frames. The thumbnail endpoint deliberately
    // returns a static image, so use original bytes even without a MIME hint.
    val animated = type in setOf("gif", "animated", "image/gif", "image/webp", "image/apng") ||
        listOf(".gif", ".webp", ".apng").any(path::endsWith)
    return if (animated) {
        MediaImageRequestFactory.animated(context, source)?.newBuilder()
            ?.size(MediaImageRequestFactory.AVATAR_PREVIEW_WIDTH, MediaImageRequestFactory.AVATAR_PREVIEW_WIDTH)
            ?.build()
    } else {
        MediaImageRequestFactory.avatar(context, source)
    }
}

private fun String?.avatarPath(): String = this
    ?.substringBefore('?')?.substringBefore('#')?.lowercase(Locale.ROOT).orEmpty()
